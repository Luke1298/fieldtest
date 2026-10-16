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

(defn- half-to-new-flow
  "A context evaluator that sends half of the targeting keys to \"new-flow\",
  deterministically, and everyone without a key to \"default\"."
  []
  (reify ContextEvaluator
    (evaluate [_ _flag ctx]
      (let [k (.getTargetingKey ctx)]
        (if (and k (even? (hash/fnv1a-32 k))) "new-flow" "default")))))

(defn- flag []
  (-> (Flag/builder)
      (.variant "default" "default")
      (.variant "new-flow" "new-flow")
      (.defaultVariant "default")
      (.contextEvaluator (half-to-new-flow))
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
        srv (server/server {:config-source (memory/config-source {"checkout" {:revision 2 :weights {:new-flow 50} :sticky :hashed}})
                            :selector (of/selector (openfeature-client ["checkout"]))
                            :recorder (memory/recorder store)})
        exp {:experiment "checkout" :revision 2 :arms [:default :new-flow]}
        arms (for [i (range 200)]
               (get-in @(client/decide (client/in-process-decider srv) [exp] {:sticky-key (str "user:" i)})
                       ["checkout" :arm]))]
    (is (= #{:default :new-flow} (set arms)))
    (is (= (map #(if (even? (hash/fnv1a-32 (str "user:" %))) :new-flow :default) (range 200)) arms)
        "the flag's variant is the arm")
    (is (= 200 (count (memory/exposures @store))))))

(deftest unsupported-or-unconfigured-variants-give-a
  (let [sel (of/selector (openfeature-client ["checkout"]))
        k (first (filter #(even? (hash/fnv1a-32 %)) (map #(str "user:" %) (range))))
        req {:experiment "checkout" :config {:revision 1 :weights {:new-flow 50} :sticky :hashed}
             :sticky-key k :supported-arms #{:default :new-flow} :context {:runtime "jvm"}}]
    (is (= :new-flow (sel req)))
    (is (= :default (sel (assoc req :supported-arms #{:default}))))
    (is (= :default (sel (assoc-in req [:config :weights] {:c 50}))))
    (is (= :default (sel (assoc req :experiment "no-such-flag"))))))
