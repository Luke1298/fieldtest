(ns fieldtest.test-kit-test
  (:require
   [clojure.test :refer [is testing]]
   [fieldtest.config :as config]
   [fieldtest.memory :as memory]
   [fieldtest.selector :as selector]
   [fieldtest.test-kit :as kit]
   [fieldtest.test-macros :refer [defasynctest]]
   [promesa.core :as p]))

(defn- reasons [{:keys [failures]}]
  (set (map :reason failures)))

(def configs {"asset-host" {:revision 3 :weights {:b 20} :sticky :hashed}})

;; Built-ins pass

(defasynctest built-ins-pass
  (p/let [store (atom [])
          source (kit/check-config-source (memory/config-source configs))
          cached (kit/check-config-source (config/cached (memory/config-source configs)))
          sel (kit/check-selector selector/weighted-hash)
          rec (kit/check-recorder (memory/recorder store) {:read-back #(mapv :record @store)})]
    (doseq [[desc r] [["memory config source" source] ["cached config source" cached]
                      ["weighted-hash selector" sel] ["memory recorder" rec]]]
      (is (:pass? r) (str desc ": " (pr-str (:failures r)))))))

;; Broken config sources

(defasynctest broken-config-sources-fail
  (p/let [not-a-map (kit/check-config-source (fn [] [configs]))
          nil-result (kit/check-config-source (fn [] nil))
          invalid (kit/check-config-source (fn [] (assoc configs "bad" {:revision 1 :weights {:b -1} :sticky :none})))
          hangs (kit/check-config-source (fn [] (p/deferred)) {:timeout-ms 20 :calls 1})
          throws (kit/check-config-source (fn [] (throw (ex-info "down" {}))))
          rejects (kit/check-config-source (fn [] (p/rejected (ex-info "down" {}))))]
    (is (= #{:not-a-map} (reasons not-a-map)))
    (is (= #{:not-a-map} (reasons nil-result)))
    (is (= #{:invalid-experiment} (reasons invalid)))
    (is (= "bad" (:experiment (first (:failures invalid)))))
    (is (= #{:hangs} (reasons hangs)))
    (testing "failing loudly is allowed: the server isolates it"
      (is (:pass? throws))
      (is (:pass? rejects)))))

;; Broken selectors

(defasynctest broken-selectors-fail
  (p/let [throws (kit/check-selector (fn [_] (throw (ex-info "boom" {}))))
          rejects (kit/check-selector (fn [{:keys [sticky-key]}] (if (= "user:1" sticky-key) (p/rejected (ex-info "boom" {})) :a)))
          unconfigured (kit/check-selector (fn [_] :z))
          nil-arm (kit/check-selector (fn [_] nil))
          ignores-support (kit/check-selector (fn [{:keys [config]}] (or (first (sort (keys (:weights config)))) :a)))
          unstable (kit/check-selector (fn [{:keys [config]}]
                                         (if (and (seq (:weights config)) (zero? (rand-int 2)))
                                           (first (keys (:weights config)))
                                           :a)))
          hangs (kit/check-selector (fn [_] (p/deferred)) {:timeout-ms 5 :repeats 1})]
    (is (= #{:throws} (reasons throws)))
    (is (= #{:throws} (reasons rejects)))
    (is (= #{:unconfigured-arm} (reasons unconfigured)))
    (is (= #{:unconfigured-arm} (reasons nil-arm)))
    (is (= #{:unsupported-arm} (reasons ignores-support)))
    (is (contains? (reasons unstable) :unstable))
    (is (= #{:hangs} (reasons hangs)))))

(defasynctest selectors-may-return-promises-and-strings
  (p/let [r (kit/check-selector (fn [req] (p/delay 1 (name (selector/weighted-hash req)))))]
    (is (:pass? r) (pr-str (:failures r)))))

;; Broken recorders

(defn- not-reentrant
  "A recorder that fails when called while another call is in flight."
  []
  (let [busy (atom false)]
    (fn [_ _]
      (if (compare-and-set! busy false true)
        (p/then (p/delay 2) (fn [_] (reset! busy false) nil))
        (throw (ex-info "concurrent call" {}))))))

(defasynctest broken-recorders-fail
  (p/let [throws-on-feedback (kit/check-recorder (fn [kind _] (when (= :feedback kind) (throw (ex-info "no" {})))))
          rejects (kit/check-recorder (fn [_ _] (p/rejected (ex-info "no" {}))))
          hangs (kit/check-recorder (fn [_ _] (p/deferred)) {:timeout-ms 5 :concurrency 2})
          racy (kit/check-recorder (not-reentrant))
          lossy (let [store (atom [])]
                  (kit/check-recorder (fn [kind r] (when (= :exposure kind) (swap! store conj r)))
                                      {:read-back #(deref store)}))]
    (is (= #{:throws :concurrent-failure} (reasons throws-on-feedback)))
    (is (= :feedback (:kind (first (:failures throws-on-feedback)))))
    (is (= #{:throws :concurrent-failure} (reasons rejects)))
    (is (= #{:hangs :concurrent-failure} (reasons hangs)))
    (is (= #{:concurrent-failure} (reasons racy)))
    (is (= #{:missing-records} (reasons lossy)))))
