(ns fieldtest.config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [fieldtest.config :as config]
   [fieldtest.json :as json]))

(def valid {:revision 3 :weights {:b 20 :c 5} :sticky :hashed})

(deftest experiment-shape
  (testing "a valid experiment normalizes to keyword arms and mode"
    (is (= {"asset-host" valid} (config/normalize {"asset-host" valid}))))
  (testing "JSON-shaped input normalizes to the same thing"
    (is (= {"asset-host" valid}
           (config/normalize {"asset-host" {"revision" 3 "weights" {"b" 20 "c" 5} "sticky" "hashed"}}))))
  (testing "keyword experiment names keep their full name"
    (is (= ["asset-host" "team/upload"]
           (sort (keys (config/normalize {:asset-host valid :team/upload valid}))))))
  (testing "empty weights are valid: everyone gets :default"
    (is (= {"x" {:revision 0 :weights {} :sticky :none}}
           (config/normalize {"x" {:revision 0 :weights {} :sticky :none}})))))

(deftest baseline-is-never-weighted
  (is (= {} (config/normalize {"x" (assoc-in valid [:weights :default] 50)})))
  (is (= {} (config/normalize {"x" {"revision" 1 "weights" {"default" 50} "sticky" "none"}})))
  (is (re-find #":default can.t be weighted" (get (config/problems {"x" (assoc-in valid [:weights :default] 1)}) "x"))))

(deftest round-trips-through-json
  (let [configs {"asset-host" valid "upload-path" {:revision 1 :weights {:b 25.5} :sticky :none}}]
    (is (= configs (config/normalize (json/decode (json/encode configs)))))))

(deftest stickiness-modes
  (is (= :none (get-in (config/normalize {"x" (assoc valid :sticky :none)}) ["x" :sticky])))
  (is (= :hashed (get-in (config/normalize {"x" (assoc valid :sticky "hashed")}) ["x" :sticky])))
  (testing "any other mode is invalid"
    (doseq [mode [:remembered "random" "" nil 1]]
      (is (= {} (config/normalize {"x" (assoc valid :sticky mode)})) (pr-str mode)))))

(deftest invalid-configs-are-absent
  (testing "missing fields"
    (doseq [k [:revision :weights :sticky]]
      (is (= {} (config/normalize {"x" (dissoc valid k)})) (str "missing " k))))
  (testing "bad weights"
    (doseq [w [-1 "20" nil ##NaN ##Inf]]
      (is (= {} (config/normalize {"x" (assoc-in valid [:weights :b] w)})) (pr-str w))))
  (testing "bad revisions"
    (doseq [r [-1 1.5 "3"]]
      (is (= {} (config/normalize {"x" (assoc valid :revision r)})) (pr-str r))))
  (testing "bad names and shapes"
    (is (= {} (config/normalize {"" valid})))
    (is (= {} (config/normalize {"x" [1 2]})))
    (is (= {} (config/normalize {"x" (assoc valid :weights [[:b 1]])}))))
  (testing "one bad experiment does not drop the others"
    (is (= {"good" valid} (config/normalize {"good" valid "bad" (dissoc valid :sticky)}))))
  (testing "problems names every invalid experiment"
    (is (= #{"bad" "worse"}
           (set (keys (config/problems {"good" valid "bad" (dissoc valid :sticky) "worse" 7})))))))

(deftest non-map-configs-are-bad-data
  (is (nil? (config/normalize nil)))
  (is (nil? (config/normalize [valid])))
  (is (= {} (config/normalize {}))))
