(ns fieldtest.server-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [fieldtest.memory :as memory]
   [fieldtest.selector :as selector]
   [fieldtest.server :as server]
   [fieldtest.test-macros :refer [defasynctest]]
   [promesa.core :as p]))

(def configs
  {"asset-host" {:revision 3 :weights {:b 100} :sticky :hashed}
   "upload-path" {:revision 1 :weights {:b 50 :c 50} :sticky :none}})

(defn- setup
  "A server over in-memory plug-ins. Returns the server plus its store and
  error log."
  [& {:as opts}]
  (let [store (atom [])
        errors (atom [])
        srv (server/server (merge {:config-source (memory/config-source configs)
                                   :recorder (memory/recorder store)
                                   :error-hook #(swap! errors conj %)}
                                  opts))]
    {:server srv :store store :errors errors}))

(defn- request [& experiments]
  {:sticky-key "user:1"
   :context {:runtime "test"}
   :experiments (vec experiments)})

(def asset-host {:experiment "asset-host" :revision 3 :arms [:default :b]})

(defn- settle
  "A promise of `{:err e}` or `{:ok v}`. (A handler that returns a Throwable
  re-rejects on the JVM, so errors are wrapped.)"
  [pr]
  (-> pr (p/then (fn [v] {:ok v})) (p/catch (fn [e] {:err e}))))

(defn- invalid-request? [{:keys [err]}]
  (= ::server/invalid-request (:type (ex-data err))))

;; Construction

(deftest construction
  (testing "defaults to the weighted-hash selector"
    (is (= selector/weighted-hash (::server/selector (:server (setup))))))
  (testing "plug-ins must be functions"
    (is (thrown? #?(:clj Exception :cljs :default) (server/server {:recorder (fn [_ _])})))
    (is (thrown? #?(:clj Exception :cljs :default) (server/server {:config-source (fn [])})))
    (is (thrown? #?(:clj Exception :cljs :default)
                 (server/server {:config-source (fn []) :recorder (fn [_ _]) :selector :nope}))))
  (testing "the error hook is optional"
    (is (map? (server/server {:config-source (fn [] {}) :recorder (fn [_ _])})))))

;; Deciding

(defasynctest one-exposure-per-decision
  (let [{:keys [server store]} (setup)]
    (p/let [{:keys [decisions]} (server/decide server (request asset-host
                                                               {:experiment "upload-path" :revision 1 :arms [:default :b :c]}
                                                               {:experiment "missing" :revision 1 :arms [:default :b]}))
            exposures (memory/exposures @store)]
      (is (= 3 (count decisions)))
      (is (= 3 (count exposures)))
      (is (= ["asset-host" "upload-path" "missing"] (map :experiment decisions)))
      (is (= :b (:arm (first decisions))))
      (is (= :default (:arm (last decisions))))
      (doseq [d decisions
              :let [e (first (filter #(= (:exposure-id d) (:exposure-id %)) exposures))]]
        (is (= (select-keys d [:experiment :revision :arm :exposure-id])
               (select-keys e [:experiment :revision :arm :exposure-id])))
        (is (= "user:1" (:sticky-key e)))
        (is (= {:runtime "test"} (:context e)) "context passes through unchanged")
        (is (inst? (:created-at e))))
      (is (= (count (set (map :exposure-id decisions))) 3)))))

(defasynctest absent-or-mismatched-revisions-get-default
  (let [{:keys [server store]} (setup :selector (fn [_] (throw (ex-info "selector must not be called" {}))))]
    (p/let [{:keys [decisions]} (server/decide server (request (assoc asset-host :revision 2)
                                                               {:experiment "nope" :revision 3 :arms [:default :b]}))]
      (is (= [:default :default] (map :arm decisions)))
      (is (= [2 3] (map :revision decisions)) "decisions echo the requested revision")
      (is (= 2 (count (memory/exposures @store))) ":default decisions are recorded too"))))

(defasynctest no-sticky-key-is-decided-as-none
  (let [seen (atom [])
        {:keys [server]} (setup :selector (fn [{:keys [config sticky-key]}]
                                            (swap! seen conj [(:sticky config) sticky-key])
                                            :b))]
    (p/let [_ (server/decide server {:experiments [asset-host]})]
      (is (= [[:none nil]] @seen)))))

(defasynctest selector-sees-the-request
  (let [seen (atom nil)
        {:keys [server]} (setup :selector (fn [m] (reset! seen m) :default))]
    (p/let [_ (server/decide server (request asset-host))]
      (is (= {:experiment "asset-host"
              :config (get configs "asset-host")
              :sticky-key "user:1"
              :supported-arms #{:default :b}
              :context {:runtime "test"}}
             @seen)))))

(defasynctest only-supported-arms
  (p/do
    (let [{:keys [server errors]} (setup :selector (fn [_] :c))]
      (p/let [{:keys [decisions]} (server/decide server (request {:experiment "upload-path" :revision 1 :arms [:default :b]}))]
        (is (= [:default] (map :arm decisions)) "configured but unsupported")
        (is (= :selector (:plugin (first @errors))))))
    (p/let [{:keys [server]} (setup :selector (fn [_] :z))
            {:keys [decisions]} (server/decide server (request {:experiment "upload-path" :revision 1 :arms [:default :z]}))]
      (is (= [:default] (map :arm decisions)) "supported but not configured"))
    (p/let [{:keys [server]} (setup :selector (fn [_] "c"))
            {:keys [decisions]} (server/decide server (request {:experiment "upload-path" :revision 1 :arms ["default" "b" "c"]}))]
      (is (= [:c] (map :arm decisions)) "string arms from JSON are accepted"))))

(defasynctest async-selectors
  (p/let [{:keys [server]} (setup :selector (fn [_] (p/delay 5 :b)))
          {:keys [decisions]} (server/decide server (request asset-host))]
    (is (= [:b] (map :arm decisions)))))

(defasynctest duplicate-experiments-decide-once
  (p/let [{:keys [server store]} (setup)
          {:keys [decisions]} (server/decide server (request asset-host asset-host))]
    (is (= 1 (count decisions)))
    (is (= 1 (count (memory/exposures @store))))))

(defasynctest invalid-requests-reject
  (p/let [{:keys [server store]} (setup)
          results (p/all (for [r [nil
                                  {:experiments "x"}
                                  {:sticky-key 1 :experiments []}
                                  {:context [] :experiments []}
                                  {:experiments [{:experiment "" :revision 1 :arms []}]}
                                  {:experiments [{:experiment "x" :revision -1 :arms []}]}
                                  {:experiments [{:experiment "x" :revision 1}]}
                                  {:experiments [{:experiment "x" :revision 1 :arms [nil]}]}]]
                           (settle (server/decide server r))))]
    (is (every? invalid-request? results))
    (is (empty? @store))))

(defasynctest empty-request-is-fine
  (p/let [{:keys [server]} (setup)
          result (server/decide server {:experiments []})]
    (is (= {:decisions []} result))))

;; Failure isolation

(defasynctest config-source-failures-keep-last-good
  (p/do
    (p/doseq [[desc failing] [["throws" (fn [] (throw (ex-info "down" {})))]
                              ["rejects" (fn [] (p/rejected (ex-info "down" {})))]
                              ["returns bad data" (fn [] [:not :default :map])]]]
      (let [healthy? (atom true)
            {:keys [server errors]} (setup :config-source (fn [] (if @healthy? configs (failing))))]
        (p/let [first-run (server/decide server (request asset-host))
                _ (reset! healthy? false)
                second-run (server/decide server (request asset-host))]
          (is (= [:b] (map :arm (:decisions first-run))) desc)
          (is (= [:b] (map :arm (:decisions second-run))) (str desc ": last good configs stay"))
          (is (= [:config-source] (map :plugin @errors)) desc))))
    (p/let [{:keys [server errors]} (setup :config-source (fn [] (throw (ex-info "never up" {}))))
            {:keys [decisions]} (server/decide server (request asset-host))]
      (is (= [:default] (map :arm decisions)) "no configs read yet: everything is :default")
      (is (= 1 (count @errors))))))

(defasynctest invalid-experiments-are-reported
  (p/let [{:keys [server errors]} (setup :config-source (fn [] (assoc configs "broken" {:revision 1})))
          {:keys [decisions]} (server/decide server (request asset-host {:experiment "broken" :revision 1 :arms [:default :b]}))]
    (is (= [:b :default] (map :arm decisions)))
    (is (= {"broken" "weights are missing"} (:problems (ex-data (:error (first @errors))))))))

(defasynctest selector-failures-give-default
  (p/doseq [[desc sel] [["throws" (fn [_] (throw (ex-info "boom" {})))]
                        ["rejects" (fn [_] (p/rejected (ex-info "boom" {})))]
                        ["returns nil" (fn [_] nil)]]]
    (p/let [{:keys [server errors store]} (setup :selector sel)
            {:keys [decisions]} (server/decide server (request asset-host))]
      (is (= [:default] (map :arm decisions)) desc)
      (is (= 1 (count (memory/exposures @store))) desc)
      (is (= [{:plugin :selector :experiment "asset-host"}]
             (map #(select-keys % [:plugin :experiment]) @errors))
          desc))))

(defasynctest recorder-failures-are-swallowed
  (p/doseq [[desc rec] [["throws" (fn [_ _] (throw (ex-info "disk full" {})))]
                        ["rejects" (fn [_ _] (p/rejected (ex-info "disk full" {})))]]]
    (p/let [{:keys [server errors]} (setup :recorder rec)
            {:keys [decisions]} (server/decide server (request asset-host))
            fb (server/feedback! server [{:exposure-ids ["e1"] :metric "m" :value 1 :value-unit :ms :outcome :ok}])]
      (is (= [:b] (map :arm decisions)) desc)
      (is (= {:accepted 1} fb) desc)
      (is (= [:recorder :recorder] (map :plugin @errors)) desc)
      (is (= [:exposure :feedback] (map :kind @errors)) desc))))

(defasynctest a-throwing-error-hook-is-ignored
  (p/let [{:keys [server]} (setup :selector (fn [_] (throw (ex-info "boom" {})))
                                  :error-hook (fn [_] (throw (ex-info "hook too" {}))))
          {:keys [decisions]} (server/decide server (request asset-host))]
    (is (= [:default] (map :arm decisions)))))

;; Feedback

(def fb {:exposure-ids ["e1"] :metric "page-load" :value 12 :value-unit :ms :outcome :ok :attrs {:route "x"}})

(defasynctest feedback-reaches-the-recorder
  (p/let [{:keys [server store]} (setup :clock (constantly #inst "2026-01-01"))
          result (server/feedback! server [fb (assoc fb :outcome :error :recorded-at #inst "2025-01-01")])]
    (is (= {:accepted 2} result))
    (is (= [(assoc fb :recorded-at #inst "2026-01-01")
            (assoc fb :outcome :error :recorded-at #inst "2025-01-01")]
           (memory/feedback @store)))))

(defasynctest invalid-feedback-batches-reject
  (p/let [{:keys [server store]} (setup)
          results (p/all (for [batch [nil fb [fb (dissoc fb :metric)]]]
                           (settle (server/feedback! server batch))))]
    (is (every? invalid-request? results))
    (is (empty? @store) "nothing from a bad batch is recorded")))
