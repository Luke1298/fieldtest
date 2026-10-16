(ns fieldtest.records-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [fieldtest.records :as records]))

(def ok-feedback {:exposure-ids ["e1"] :metric "page-load" :value 1840 :value-unit :ms :outcome :ok})

(deftest exposure-shape
  (let [t (records/now)
        e (records/exposure {:exposure-id "e1" :experiment "x" :revision 2 :arm :b
                             :sticky-key "k" :context {:runtime "jvm"} :created-at t})]
    (is (= {:exposure-id "e1" :experiment "x" :revision 2 :arm :b
            :sticky-key "k" :context {:runtime "jvm"} :created-at t}
           e)))
  (testing "defaults"
    (let [e (records/exposure {:exposure-id "e1" :experiment "x" :revision 2 :arm :a})]
      (is (= {} (:context e)))
      (is (inst? (:created-at e)))
      (is (nil? (:sticky-key e))))))

(deftest feedback-shape
  (let [f (records/feedback ok-feedback)]
    (is (= #{:exposure-ids :metric :value :value-unit :outcome :attrs :recorded-at} (set (keys f))))
    (is (inst? (:recorded-at f)))
    (is (= {} (:attrs f))))
  (testing "a single exposure id is accepted"
    (is (= ["e1"] (:exposure-ids (records/feedback (-> ok-feedback (dissoc :exposure-ids) (assoc :exposure-id "e1")))))))
  (testing "JSON-shaped input"
    (is (= {:value-unit :bytes :outcome :timeout}
           (select-keys (records/feedback {"exposure-ids" ["e1"] "metric" "m" "value" 1
                                           "value-unit" "bytes" "outcome" "timeout"})
                        [:value-unit :outcome]))))
  (testing "feedback never carries the arm, revision or sticky key"
    (is (= #{} (set (filter #{:arm :revision :sticky-key}
                            (keys (records/feedback (assoc ok-feedback :arm :b :revision 1 :sticky-key "k"))))))))
  (testing "every outcome and unit is accepted"
    (doseq [o records/outcomes u records/value-units]
      (is (nil? (records/feedback-problem (assoc ok-feedback :outcome o :value-unit u)))))))

(deftest feedback-validation
  (doseq [[desc m] [["no ids" (dissoc ok-feedback :exposure-ids)]
                    ["empty ids" (assoc ok-feedback :exposure-ids [])]
                    ["blank id" (assoc ok-feedback :exposure-ids [""])]
                    ["no metric" (dissoc ok-feedback :metric)]
                    ["string value" (assoc ok-feedback :value "1")]
                    ["bad unit" (assoc ok-feedback :value-unit :seconds)]
                    ["bad outcome" (assoc ok-feedback :outcome :meh)]
                    ["attrs not a map" (assoc ok-feedback :attrs [1])]
                    ["not a map" "x"]]]
    (is (string? (records/feedback-problem m)) desc)
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (records/feedback m)) desc)))

(deftest attribute-filtering
  (let [long-str (apply str (repeat 250 "x"))
        attrs (:attrs (records/feedback (assoc ok-feedback :attrs {:route "login" :n 3 :cached true :kind :img
                                                                   :long long-str :nested {:a 1} :list [1]
                                                                   :none nil})))]
    (is (= #{:route :n :cached :kind :long} (set (keys attrs))))
    (is (= 200 (count (:long attrs))))
    (is (str/starts-with? long-str (:long attrs)))
    (is (nil? (records/feedback-problem (assoc ok-feedback :attrs nil))) "nil attrs are fine")))
