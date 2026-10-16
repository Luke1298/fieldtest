(ns fieldtest.smoke-test
  (:require
   [clojure.test :refer [is]]
   [fieldtest.test-macros :refer [defasynctest]]
   [promesa.core :as p]))

(defn- slow-inc [x]
  (p/delay 5 (inc x)))

(defasynctest async-body-resolves
  (p/let [v (slow-inc 1)]
    (is (= 2 v))))

(defasynctest redefs-apply-across-await
  (p/with-redefs [slow-inc (fn [x] (p/resolved (* 10 x)))]
    (p/let [v (slow-inc 2)]
      (is (= 20 v)))))
