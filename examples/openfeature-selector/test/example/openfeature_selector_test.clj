(ns example.openfeature-selector-test
  (:require
   [clojure.test :refer [deftest is]]
   [example.openfeature-selector :as of]
   [fieldtest.client :as client]
   [fieldtest.hash :as hash]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server]
   [fieldtest.test-kit :as test-kit])
  (:import
   (dev.openfeature.sdk OpenFeatureAPI)
   (dev.openfeature.sdk.providers.memory ContextEvaluator Flag InMemoryProvider)))

(defn- half-to-b
  "A context evaluator that sends half of the targeting keys to \"b\",
  deterministically, and everyone without a key to \"a\"."
  []
  (reify ContextEvaluator
    (evaluate [_ _flag ctx]
      (let [k (.getTargetingKey ctx)]
        (if (and k (even? (hash/fnv1a-32 k))) "b" "a")))))

(defn- flag []
  (-> (Flag/builder)
      (.variant "a" "a")
      (.variant "b" "b")
      (.defaultVariant "a")
      (.contextEvaluator (half-to-b))
      (.build)))

(defn- openfeature-client [flag-keys]
  (let [api (OpenFeatureAPI/getInstance)]
    (.setProviderAndWait api (InMemoryProvider. (into {} (map (fn [k] [k (flag)])) flag-keys)))
    (.getClient api)))

(deftest passes-the-test-kit
  (let [sel (of/selector (openfeature-client ["test-kit"]))
        {:keys [pass? failures]} @(test-kit/check-selector sel)]
    (is pass? (pr-str failures))))

(deftest maps-variants-to-arms-through-a-server
  (let [store (atom [])
        srv (server/server {:config-source (memory/config-source {"checkout" {:revision 2 :weights {:b 50} :sticky :hashed}})
                            :selector (of/selector (openfeature-client ["checkout"]))
                            :recorder (memory/recorder store)})
        exp {:experiment "checkout" :revision 2 :arms [:a :b]}
        arms (for [i (range 200)]
               (get-in @(client/decide (client/in-process-decider srv) [exp] {:sticky-key (str "user:" i)})
                       ["checkout" :arm]))]
    (is (= #{:a :b} (set arms)))
    (is (= (map #(if (even? (hash/fnv1a-32 (str "user:" %))) :b :a) (range 200)) arms)
        "the flag's variant is the arm")
    (is (= 200 (count (memory/exposures @store))))))

(deftest unsupported-or-unconfigured-variants-give-a
  (let [sel (of/selector (openfeature-client ["checkout"]))
        k (first (filter #(even? (hash/fnv1a-32 %)) (map #(str "user:" %) (range))))
        req {:experiment "checkout" :config {:revision 1 :weights {:b 50} :sticky :hashed}
             :sticky-key k :supported-arms #{:a :b} :context {:runtime "jvm"}}]
    (is (= :b (sel req)))
    (is (= :a (sel (assoc req :supported-arms #{:a}))))
    (is (= :a (sel (assoc-in req [:config :weights] {:c 50}))))
    (is (= :a (sel (assoc req :experiment "no-such-flag"))))))
