(ns fieldtest.ids-test
  (:require
   [clojure.test :refer [deftest is]]
   [fieldtest.ids :as ids]))

(deftest exposure-id-format
  (dotimes [_ 100]
    (is (re-matches #"[0-9a-f]{32}" (ids/exposure-id)))))

(deftest exposure-ids-are-unique
  (let [n 5000]
    (is (= n (count (into #{} (repeatedly n ids/exposure-id)))))))
