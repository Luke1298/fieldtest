(ns fieldtest.http-test
  (:require
   [clojure.test :refer [is testing]]
   [fieldtest.client :as client]
   [fieldtest.http :as http]
   [fieldtest.json :as json]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server]
   [fieldtest.test-macros :refer [defasynctest]]
   [promesa.core :as p]))

(defn- setup []
  (let [store (atom [])]
    {:store store
     :server (server/server {:config-source (memory/config-source {"asset-host" {:revision 3 :weights {:b 100} :sticky :hashed}})
                             :recorder (memory/recorder store)})}))

(def decide-body
  {:v 1 :sticky-key "u1" :context {:runtime "test"}
   :experiments [{:experiment "asset-host" :revision 3 :arms ["a" "b"]}]})

(def feedback-body
  {:v 1 :feedback [{:exposure-ids ["e1"] :metric "load" :value 12.5 :value-unit "ms" :outcome "ok"
                    :attrs {:route "x"} :recorded-at "2026-10-16T12:00:00.000Z"}]})

(defn- call [handler doc]
  (p/let [resp (handler {:body (if (string? doc) doc (json/encode doc))})]
    (assoc resp :doc (json/decode (:body resp)))))

;; 6.2 / 6.3

(defasynctest decide-handler-decides
  (let [{:keys [server store]} (setup)]
    (p/let [{:keys [status headers doc]} (call (http/decide-handler server) decide-body)]
      (is (= 200 status))
      (is (= "application/json" (get headers "content-type")))
      (is (= 1 (:v doc)))
      (is (= [{:experiment "asset-host" :revision 3 :arm "b"}]
             (map #(dissoc % :exposure-id) (:decisions doc))))
      (is (= {:runtime "test"} (:context (first (memory/exposures @store))))))))

(defasynctest feedback-handler-records
  (let [{:keys [server store]} (setup)]
    (p/let [{:keys [status doc]} (call (http/feedback-handler server) feedback-body)
            [f] (memory/feedback @store)]
      (is (= 200 status))
      (is (= {:v 1 :accepted 1} doc))
      (is (= {:exposure-ids ["e1"] :metric "load" :value 12.5 :value-unit :ms :outcome :ok :attrs {:route "x"}}
             (dissoc f :recorded-at)))
      (is (= #inst "2026-10-16T12:00:00.000Z" (:recorded-at f))))))

(defasynctest unknown-fields-are-ignored
  (let [{:keys [server store]} (setup)]
    (p/let [a (call (http/decide-handler server) (assoc decide-body :future-field {:x 1}))
            b (call (http/feedback-handler server)
                    (-> feedback-body
                        (assoc :future-field 1)
                        (assoc-in [:feedback 0 :arm] "b")
                        (assoc-in [:feedback 0 :recorded-at] "not a time")))
            [f] (memory/feedback @store)]
      (is (= [200 200] [(:status a) (:status b)]))
      (is (not (contains? f :arm)) "feedback never carries the arm")
      (is (inst? (:recorded-at f)) "an unreadable time is replaced by the server's"))))

(defasynctest versions-are-checked
  (let [{:keys [server]} (setup)]
    (p/let [results (p/all (for [handler [(http/decide-handler server) (http/feedback-handler server)]
                                 v [nil 0 2 "1"]]
                             (call handler (-> decide-body (merge feedback-body) (assoc :v v)))))]
      (is (= (repeat 8 400) (map :status results)))
      (is (every? #(re-find #"version" (:error (:doc %))) results)))))

(defasynctest bad-shapes-are-400
  (let [{:keys [server store]} (setup)]
    (p/let [results (p/all (concat
                            (for [body ["not json" "[1,2]" "\"str\"" ""]]
                              (call (http/decide-handler server) body))
                            [(call (http/decide-handler server) {:v 1 :experiments "x"})
                             (call (http/decide-handler server) {:v 1 :experiments [{:experiment "x"}]})
                             (call (http/feedback-handler server) {:v 1})
                             (call (http/feedback-handler server) {:v 1 :feedback [{:metric "m"}]})]))]
      (is (= (repeat 8 400) (map :status results)))
      (is (every? (comp string? :error :doc) results))
      (is (empty? @store)))))

(defasynctest oversized-bodies-are-413
  (let [{:keys [server]} (setup)
        big (json/encode (assoc decide-body :padding (apply str (repeat 2000 "é"))))]
    (p/let [a (call (http/decide-handler server {:max-body-bytes 1000}) big)
            b (call (http/feedback-handler server {:max-body-bytes 1000}) big)
            c (call (http/decide-handler server {:max-body-bytes 100000}) big)]
      (is (= [413 413 200] (map :status [a b c]))))))

(defasynctest byte-bodies
  (let [{:keys [server]} (setup)
        text (json/encode decide-body)
        bytes #?(:clj (.getBytes ^String text "UTF-8") :cljs (.encode (js/TextEncoder.) text))]
    (p/let [{:keys [status]} ((http/decide-handler server) {:body bytes})]
      (is (= 200 status)))))

;; 6.4 with a stubbed transport

(defn- loopback
  "A `:post` function that serves requests with the handlers directly."
  [server calls]
  (let [handlers {"http://fieldtest/decide" (http/decide-handler server)
                  "http://fieldtest/feedback" (http/feedback-handler server)}]
    (fn [url body headers]
      (swap! calls conj {:url url :headers headers :doc (json/decode body)})
      ((get handlers url) {:body body}))))

(defasynctest http-decider-and-reporter
  (let [{:keys [server store]} (setup)
        calls (atom [])
        opts {:post (loopback server calls) :headers {"authorization" "Bearer t"}}
        exp {:experiment "asset-host" :revision 3 :arms [:a :b]}]
    (p/let [decisions (client/decide (http/decider "http://fieldtest/decide" opts) [exp] {:sticky-key "u1"})
            c {:decisions decisions :reporter (http/reporter "http://fieldtest/feedback" opts)}
            {:keys [result exposure-id]} (client/branch c exp {:a (constantly :a) :b (constantly :b)})
            _ ((:reporter c) [{:exposure-ids [exposure-id] :metric "load" :value 1 :value-unit :ms :outcome :ok
                               :attrs {} :recorded-at #inst "2026-10-16T12:00:00.000Z"}])]
      (is (= :b result))
      (is (= [exposure-id] (map :exposure-id (memory/exposures @store))))
      (is (= [[exposure-id]] (map :exposure-ids (memory/feedback @store))))
      (is (= #inst "2026-10-16T12:00:00.000Z" (:recorded-at (first (memory/feedback @store)))) "insts survive the wire")
      (is (every? #(= 1 (:v (:doc %))) @calls) "every document carries the version")
      (is (every? #(= "Bearer t" (get (:headers %) "authorization")) @calls)))))

(defasynctest http-errors-reject
  (p/let [results (p/all (for [resp [{:status 500 :body "{\"v\":1,\"error\":\"x\"}"}
                                     {:status 200 :body "{\"v\":2,\"decisions\":[]}"}
                                     {:status 200 :body "not json"}]]
                           (-> ((http/decider "http://x" {:post (fn [_ _ _] (p/resolved resp))}) {:experiments []})
                               (p/then (fn [_] :resolved))
                               (p/catch (fn [_] :rejected)))))]
    (is (= [:rejected :rejected :rejected] results))
    (testing "client/decide turns them into no decisions"
      (p/let [d (client/decide (http/decider "http://x" {:post (fn [_ _ _] (p/resolved {:status 503 :body ""}))}) [])]
        (is (= {} d))))))

#?(:cljs
   (defasynctest default-cljs-transport-uses-fetch
     (let [{:keys [server]} (setup)
           handler (http/decide-handler server)
           original (.-fetch js/globalThis)
           seen (atom nil)]
       (set! (.-fetch js/globalThis)
             (fn [url init]
               (reset! seen {:url url :method (.-method init) :headers (js->clj (.-headers init))})
               (p/let [{:keys [status body]} (handler {:body (.-body init)})]
                 #js {:status status :text (fn [] (p/resolved body))})))
       (-> (p/let [decisions (client/decide (http/decider "http://fieldtest/decide")
                                            [{:experiment "asset-host" :revision 3 :arms [:a :b]}]
                                            {:sticky-key "u1"})]
             (is (= :b (get-in decisions ["asset-host" :arm])))
             (is (= {:url "http://fieldtest/decide" :method "POST" :headers {"content-type" "application/json"}}
                    @seen)))
           (p/finally (fn [_ _] (set! (.-fetch js/globalThis) original)))))))
