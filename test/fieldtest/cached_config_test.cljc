(ns fieldtest.cached-config-test
  (:require
   [clojure.test :refer [is]]
   [fieldtest.config :as config]
   [fieldtest.test-macros :refer [defasynctest]]
   [promesa.core :as p]))

(defn- counting-source [results]
  (let [calls (atom 0)
        results (atom results)]
    {:calls calls
     :source (fn []
               (swap! calls inc)
               (let [r (first @results)]
                 (swap! results rest)
                 (if (instance? #?(:clj Throwable :cljs js/Error) r) (throw r) r)))}))

(defasynctest caches-until-the-ttl-passes
  (let [now (atom 0)
        {:keys [calls source]} (counting-source [{:v 1} {:v 2}])
        cached (config/cached source {:ttl-ms 100 :clock #(deref now)})]
    (p/let [a (cached)
            b (cached)
            _ (reset! now 99)
            c (cached)
            _ (reset! now 100)
            d (cached)]
      (is (= [{:v 1} {:v 1} {:v 1} {:v 2}] [a b c d]))
      (is (= 2 @calls)))))

(defasynctest concurrent-calls-share-a-fetch
  (let [calls (atom 0)
        cached (config/cached (fn [] (swap! calls inc) (p/delay 10 {:v 1})) {:ttl-ms 1000})]
    (p/let [results (p/all (repeatedly 5 cached))]
      (is (= (repeat 5 {:v 1}) results))
      (is (= 1 @calls)))))

(defasynctest failures-pass-through-and-back-off
  (let [now (atom 0)
        boom (ex-info "down" {})
        {:keys [calls source]} (counting-source [boom {:v 2}])
        cached (config/cached source {:ttl-ms 100 :retry-ms 10 :clock #(deref now)})
        settle #(-> (cached) (p/catch (fn [e] [:failed (ex-message e)])))]
    (p/let [a (settle)
            _ (reset! now 5)
            b (settle)
            _ (reset! now 10)
            c (settle)]
      (is (= [:failed "down"] a))
      (is (= [:failed "down"] b) "within retry-ms the source is not called again")
      (is (= {:v 2} c))
      (is (= 2 @calls)))))

(defasynctest async-failures-pass-through
  (let [cached (config/cached (fn [] (p/rejected (ex-info "async down" {}))))]
    (p/let [r (-> (cached) (p/catch ex-message))]
      (is (= "async down" r)))))
