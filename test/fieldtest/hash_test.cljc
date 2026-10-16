(ns fieldtest.hash-test
  (:require
   [clojure.test :refer [deftest is]]
   [fieldtest.hash :as hash]))

(deftest fnv1a-32-reference-values
  (doseq [[s expected] [["" 2166136261]
                        ["a" 3826002220]
                        ["foobar" 3214735720]
                        ["asset-host|3|user:8f3a" 2600499888]]]
    (is (= expected (hash/fnv1a-32 s)) (pr-str s))))

(deftest fnv1a-32-non-ascii
  (doseq [[s expected] [["héllo" 1252296000]
                        ["日本語" 2153733351]
                        ["🙂 emoji" 1936825913]
                        ["x|0|ключ" 3579272153]]]
    (is (= expected (hash/fnv1a-32 s)) (pr-str s))))

(deftest fnv1a-32-range
  (doseq [i (range 1000)]
    (let [h (hash/fnv1a-32 (str "key-" i))]
      (is (and (integer? h) (<= 0 h) (< h 4294967296))))))
