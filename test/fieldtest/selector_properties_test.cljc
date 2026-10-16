(ns fieldtest.selector-properties-test
  (:require
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [fieldtest.selector :as selector]))

(def gen-weights
  (gen/let [arms (gen/set (gen/elements [:b :c :d :e]) {:min-elements 1})
            ws (gen/vector (gen/double* {:min 0 :max 100 :NaN? false :infinite? false}) (count arms))]
    (zipmap arms ws)))

(defn- arm-of [weights sticky-key]
  (selector/weighted-hash {:experiment "prop" :config {:revision 1 :weights weights :sticky :hashed}
                           :sticky-key sticky-key
                           :supported-arms (conj (set (keys weights)) :a)}))

(defspec raising-a-weight-never-moves-a-key-out-of-a-candidate 200
  (prop/for-all [weights gen-weights
                 sticky-key gen/string
                 pick gen/nat
                 delta (gen/double* {:min 0 :max 100 :NaN? false :infinite? false})]
                (let [arm (nth (sort (keys weights)) (mod pick (count weights)))
                      raised (update weights arm + delta)
                      before (arm-of weights sticky-key)
                      after (arm-of raised sticky-key)]
                  (if (= :a before)
                    (contains? #{:a arm} after)
                    (= before after)))))

(defn- expected-share [weights arm]
  (let [[_ start end] (first (filter #(= arm (first %)) (selector/slices weights)))]
    (/ (- end start) selector/buckets)))

(defspec none-mode-converges-to-the-weights 10
  (prop/for-all [weights gen-weights]
                (let [n 20000
                      config {:revision 1 :weights weights :sticky :none}
                      counts (frequencies (repeatedly n #(selector/weighted-hash
                                                          {:experiment "prop" :config config
                                                           :supported-arms (conj (set (keys weights)) :a)})))]
                  (every? (fn [arm]
                            (< (abs (- (/ (get counts arm 0) n) (expected-share weights arm))) 0.015))
                          (keys weights)))))
