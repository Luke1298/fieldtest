(ns fieldtest.selector-test
  (:require
   #?(:cljs ["fs" :as fs])
   [clojure.test :refer [deftest is testing]]
   [fieldtest.json :as json]
   [fieldtest.selector :as selector]
   [fieldtest.selector-vectors :refer [vectors]]))

(defn- choose [config sticky-key & [arms]]
  (selector/weighted-hash {:experiment "x" :config config :sticky-key sticky-key
                           :supported-arms (or arms (conj (set (keys (:weights config))) :default))
                           :context {}}))

(deftest slices-are-fixed-per-candidate
  (is (= [[:b 0 2000]] (selector/slices {:b 20})))
  (testing "sorted by arm name, each 100/n percent wide"
    (is (= [[:b 0 1000] [:c 5000 7500]] (selector/slices {:c 25 :b 10}))))
  (testing "weights above the slice width are clamped"
    (is (= [[:b 0 5000] [:c 5000 10000]] (selector/slices {:b 90 :c 70}))))
  (testing "uneven division covers the whole space"
    (is (= [[:b 0 3333] [:c 3333 6666] [:d 6666 10000]] (selector/slices {:b 50 :c 50 :d 50}))))
  (testing "fractional weights draw whole buckets"
    (is (= [[:b 0 1250]] (selector/slices {:b 12.5})))
    (is (= [[:b 0 0]] (selector/slices {:b 0.009})))))

(deftest arm-for-bucket
  (is (= :b (selector/arm-for-bucket {:b 20} 0)))
  (is (= :b (selector/arm-for-bucket {:b 20} 1999)))
  (is (= :default (selector/arm-for-bucket {:b 20} 2000)))
  (is (= :default (selector/arm-for-bucket {} 0)))
  (is (= :c (selector/arm-for-bucket {:b 10 :c 10} 5000))))

(deftest hashed-mode-is-sticky
  (let [config {:revision 1 :weights {:b 50} :sticky :hashed}]
    (doseq [i (range 200)]
      (let [k (str "user:" i)]
        (is (apply = (repeatedly 5 #(choose config k))))))))

(deftest hashed-mode-without-key-is-random
  (let [config {:revision 1 :weights {:b 50} :sticky :hashed}
        arms (frequencies (repeatedly 2000 #(choose config nil)))]
    (is (< 800 (:b arms 0) 1200))))

(deftest none-mode-ignores-the-key
  (let [config {:revision 1 :weights {:b 50} :sticky :none}
        arms (frequencies (repeatedly 2000 #(choose config "same-key")))]
    (is (< 800 (:b arms 0) 1200))))

(deftest unsupported-arms-get-default
  (let [config {:revision 1 :weights {:b 100} :sticky :hashed}]
    (is (= :b (choose config "k" #{:default :b})))
    (is (= :default (choose config "k" #{:default})))
    (is (= :default (choose config "k" [])))))

(deftest revision-reshuffles
  (let [arms (fn [rev] (mapv #(choose {:revision rev :weights {:b 50} :sticky :hashed} (str "user:" %)) (range 400)))
        moved (count (remove true? (map = (arms 1) (arms 2))))]
    (is (< 120 moved 280) "about half of keys change arm")))

(deftest test-vectors
  (is (<= 45 (count vectors)))
  (doseq [{:keys [note experiment revision sticky-key weights supported-arms bucket arm]} vectors]
    (is (= bucket (selector/hash-bucket experiment revision sticky-key)) note)
    (is (= arm (selector/weighted-hash {:experiment experiment
                                        :config {:revision revision :weights weights :sticky :hashed}
                                        :sticky-key sticky-key
                                        :supported-arms supported-arms}))
        note)))

(defn- read-file [path]
  #?(:clj (slurp path) :cljs (str (fs/readFileSync path "utf8"))))

(deftest published-vectors-match
  (let [published (:vectors (json/decode (read-file "doc/selector-vectors.json")))
        ->kw (fn [m] (-> m
                         (update :weights #(into {} (map (fn [[k v]] [(keyword (name k)) v])) %))
                         (update :supported-arms #(set (map keyword %)))
                         (update :arm keyword)))]
    (is (= vectors (mapv ->kw published)))))
