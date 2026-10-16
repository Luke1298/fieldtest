(ns fieldtest.client-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [fieldtest.client :as client]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server]
   [fieldtest.test-macros :refer [defasynctest]]
   [promesa.core :as p]))

(def asset-host {:experiment "asset-host" :revision 3 :arms [:default :b]})

(defn- decision [arm]
  {"asset-host" {:experiment "asset-host" :revision 3 :arm arm :exposure-id "e1"}})

(defn- recording-client
  "A client whose reporter collects batches in `sent`."
  [decisions]
  (let [sent (atom [])]
    {:sent sent
     :client {:decisions decisions
              :reporter (fn [batch] (swap! sent into batch) (p/resolved nil))}}))

(def arms {:default (fn [x] [:default x]) :b (fn [x] [:b x])})

(defn- settle [pr]
  (-> pr (p/then (fn [v] {:ok v})) (p/catch (fn [e] {:err e}))))

;; Branching

(deftest branch-runs-the-decided-arm
  (let [{:keys [client sent]} (recording-client (decision :b))]
    (is (= {:result [:b 1] :exposure-id "e1"} (client/branch client asset-host arms 1)))
    (is (empty? @sent))))

(deftest branch-runs-exactly-one-arm
  (let [calls (atom [])
        arms {:default (fn [] (swap! calls conj :default)) :b (fn [] (swap! calls conj :b))}]
    (client/branch {:decisions (decision :b)} asset-host arms)
    (is (= [:b] @calls))))

(deftest missing-decisions-run-default-untracked
  (testing "no decisions at all"
    (is (= {:result [:default 1] :exposure-id nil} (client/branch {:decisions {}} asset-host arms 1))))
  (testing "decision for another revision"
    (is (= {:result [:default 1] :exposure-id nil}
           (client/branch {:decisions (decision :b)} (assoc asset-host :revision 4) arms 1))))
  (testing "no client decisions key"
    (is (= [:default 1] (:result (client/branch {} asset-host arms 1))))))

(deftest undeclared-arms-run-default-and-report
  (let [{:keys [client sent]} (recording-client (decision :c))]
    (is (= {:result [:default 1] :exposure-id "e1"} (client/branch client asset-host arms 1)))
    (is (= [{:exposure-ids ["e1"] :outcome :unsupported-arm :value-unit :count}]
           (map #(select-keys % [:exposure-ids :outcome :value-unit]) @sent)))))

(deftest default-is-required
  (let [ran (atom false)]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"must declare a :default arm"
                          (client/branch {:decisions (decision :b)} asset-host {:b (fn [] (reset! ran true))})))
    (is (false? @ran))))

(deftest arm-errors-reach-the-caller
  (let [boom (ex-info "arm failed" {})
        b-ran (atom 0)
        a-ran (atom 0)]
    (is (= boom (try
                  (client/branch {:decisions (decision :b)} asset-host
                                 {:default (fn [] (swap! a-ran inc)) :b (fn [] (swap! b-ran inc) (throw boom))})
                  nil
                  (catch #?(:clj Exception :cljs :default) e e))))
    (is (= [1 0] [@b-ran @a-ran]) "never retried on :default")))

(defasynctest promise-results-pass-through
  (let [pr (p/delay 5 :done)
        {:keys [result exposure-id]} (client/branch {:decisions (decision :b)} asset-host {:default (fn [] :default) :b (fn [] pr)})]
    (is (identical? pr result))
    (is (= "e1" exposure-id))
    (p/let [v result]
      (is (= :done v)))))

(defasynctest rejected-arms-reach-the-caller
  (let [boom (ex-info "async failure" {})
        {:keys [result]} (client/branch {:decisions (decision :b)} asset-host
                                        {:default (fn [] :default) :b (fn [] (p/rejected boom))})]
    (p/let [{:keys [err]} (settle result)]
      (is (= boom err)))))

;; Deciders

(defn- test-server []
  (let [store (atom [])]
    {:store store
     :server (server/server {:config-source (memory/config-source {"asset-host" {:revision 3 :weights {:b 100} :sticky :hashed}})
                             :recorder (memory/recorder store)})}))

(defasynctest in-process-decider
  (let [{:keys [server store]} (test-server)]
    (p/let [decisions (client/decide (client/in-process-decider server) [asset-host]
                                     {:sticky-key "u1" :context {:page "home"}})]
      (is (= :b (get-in decisions ["asset-host" :arm])))
      (is (string? (get-in decisions ["asset-host" :exposure-id])))
      (is (= {:page "home"} (:context (first (memory/exposures @store))))))))

(defasynctest failing-deciders-give-no-decisions
  (let [errors (atom [])]
    (p/let [results (p/all [(client/decide (fn [_] (throw (ex-info "sync" {}))) [asset-host] {:on-error #(swap! errors conj %)})
                            (client/decide (fn [_] (p/rejected (ex-info "async" {}))) [asset-host] {:on-error #(swap! errors conj %)})
                            (client/decide (fn [_] (p/delay 200 {:decisions [{:experiment "asset-host" :revision 3 :arm :b :exposure-id "x"}]}))
                                           [asset-host] {:timeout-ms 20 :on-error #(swap! errors conj %)})])]
      (is (= [{} {} {}] results))
      (is (= 3 (count @errors))))))

;; Reporters

(defasynctest in-process-reporter
  (let [{:keys [server store]} (test-server)
        c {:reporter (client/in-process-reporter server)}]
    (client/report! c {:exposure-id "e1" :metric "m" :value 3 :value-unit :ms :outcome :ok})
    (p/let [_ (p/delay 10)]
      (is (= [["e1"]] (map :exposure-ids (memory/feedback @store)))))))

(defasynctest reporting-never-surfaces-failures
  (let [errors (atom [])
        on-error #(swap! errors conj %)
        fb {:exposure-id "e1" :metric "m" :value 3 :value-unit :ms :outcome :ok}]
    (is (nil? (client/report! {:reporter (fn [_] (throw (ex-info "sync" {}))) :on-error on-error} fb)))
    (is (nil? (client/report! {:reporter (fn [_] (p/rejected (ex-info "async" {}))) :on-error on-error} fb)))
    (is (nil? (client/report! {:reporter (fn [_] nil) :on-error on-error} (dissoc fb :metric))) "invalid feedback")
    (is (nil? (client/report! {:reporter (fn [_] (throw (ex-info "x" {})))
                               :on-error (fn [_] (throw (ex-info "hook too" {})))} fb)))
    (p/let [_ (p/delay 10)]
      (is (= 3 (count @errors))))))

(deftest reporting-without-an-exposure-id-does-nothing
  (let [{:keys [client sent]} (recording-client {})]
    (client/report! client {:exposure-id nil :metric "m" :value 1 :value-unit :ms :outcome :ok})
    (client/report! client {:exposure-ids [] :metric "m" :value 1 :value-unit :ms :outcome :ok})
    (is (empty? @sent))))

;; Timing

(defn- outcome [sent] (map #(select-keys % [:exposure-ids :metric :outcome :value-unit]) sent))

(deftest timed-sync-ok
  (let [{:keys [client sent]} (recording-client {})]
    (is (= 3 (client/timed client {:exposure-id "e1" :metric "work"} + 1 2)))
    (is (= [{:exposure-ids ["e1"] :metric "work" :outcome :ok :value-unit :ms}] (outcome @sent)))
    (is (<= 0 (:value (first @sent))))))

(deftest timed-sync-error
  (let [{:keys [client sent]} (recording-client {})
        boom (ex-info "nope" {})]
    (is (= boom (try (client/timed client {:exposure-id "e1" :metric "work"} (fn [] (throw boom)))
                     (catch #?(:clj Exception :cljs :default) e e))))
    (is (= [:error] (map :outcome @sent)))))

(defasynctest timed-async-ok
  (let [{:keys [client sent]} (recording-client {})
        pr (p/delay 20 :v)
        result (client/timed client {:exposure-id "e1" :metric "work" :attrs {:route "x"}} (fn [] pr))]
    (is (identical? pr result))
    (p/let [v result
            _ (p/delay 1)]
      (is (= :v v))
      (is (= [:ok] (map :outcome @sent)))
      (is (= {:route "x"} (:attrs (first @sent))))
      (is (<= 15 (:value (first @sent)))))))

(defasynctest timed-async-error
  (let [{:keys [client sent]} (recording-client {})
        boom (ex-info "async nope" {})]
    (p/let [{:keys [err]} (settle (client/timed client {:exposure-id "e1" :metric "work"} (fn [] (p/delay 5 nil) (p/rejected boom))))
            _ (p/delay 1)]
      (is (= boom err))
      (is (= [:error] (map :outcome @sent))))))

(defasynctest timed-timeout-does-not-cancel
  (let [{:keys [client sent]} (recording-client {})
        finished (atom false)
        result (client/timed client {:exposure-id "e1" :metric "work" :timeout-ms 10}
                             (fn [] (p/then (p/delay 60) (fn [_] (reset! finished true) :late))))]
    (p/let [_ (p/delay 30)
            _ (is (= [{:outcome :timeout :value 10}] (map #(select-keys % [:outcome :value]) @sent)))
            v result
            _ (p/delay 1)]
      (is (= :late v) "the operation still completes")
      (is (true? @finished))
      (is (= 1 (count @sent)) "nothing more is reported after the timeout"))))

(deftest timed-without-exposure-id-reports-nothing
  (let [{:keys [client sent]} (recording-client {})]
    (is (= 2 (client/timed client {:metric "work"} inc 1)))
    (is (empty? @sent))))

;; Handoff

(deftest decisions-round-trip-through-json
  (let [decisions {"asset-host" {:experiment "asset-host" :revision 3 :arm :b :exposure-id "e1"}
                   "team/upload" {:experiment "team/upload" :revision 1 :arm :default :exposure-id "e2"}}]
    (is (= decisions (client/json->decisions (client/decisions->json decisions))))
    (is (= {} (client/json->decisions (client/decisions->json {}))))
    (testing "unknown fields are ignored"
      (let [decisions (client/json->decisions "{\"v\":1,\"later\":true,\"decisions\":[{\"experiment\":\"x\",\"revision\":1,\"arm\":\"b\",\"exposure-id\":\"e\",\"new-field\":2}]}")]
        (is (= ["x"] (keys decisions)))
        (is (= {:result :b :exposure-id "e"}
               (client/branch {:decisions decisions} {:experiment "x" :revision 1}
                              {:default (constantly :default) :b (constantly :b)})))))))
