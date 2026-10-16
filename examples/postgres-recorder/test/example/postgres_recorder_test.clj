(ns example.postgres-recorder-test
  "Runs the fieldtest test kit and an A/A experiment against a real Postgres.
  Set FIELDTEST_PG_URL to a JDBC URL for a scratch database, for example
  jdbc:postgresql://localhost:55432/postgres?user=postgres&password=postgres"
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [example.postgres-recorder :as pg]
   [fieldtest.client :as client]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server]
   [fieldtest.test-kit :as test-kit]
   [next.jdbc :as jdbc]))

(def ds (delay (jdbc/get-datasource {:jdbcUrl (or (System/getenv "FIELDTEST_PG_URL")
                                                  "jdbc:postgresql://localhost:55432/postgres?user=postgres&password=postgres")})))

(use-fixtures :each
  (fn [t]
    (pg/migrate! @ds)
    (jdbc/execute! @ds ["truncate exposures, feedback, feedback_exposures"])
    (t)))

(deftest passes-the-test-kit
  (let [{:keys [pass? failures]} @(test-kit/check-recorder
                                   (pg/recorder @ds)
                                   {:read-back #(repeat (pg/record-count @ds) ::record)})]
    (is pass? (pr-str failures))))

(deftest analysis-query-over-an-aa-run
  (let [srv (server/server {:config-source (memory/config-source {"aa" {:revision 1 :weights {:b 50} :sticky :hashed}})
                            :recorder (pg/recorder @ds)})
        exp {:experiment "aa" :revision 1 :arms [:a :b]}]
    (doseq [i (range 400)]
      (let [decisions @(client/decide (client/in-process-decider srv) [exp] {:sticky-key (str "k" i)})
            c {:decisions decisions :reporter (client/in-process-reporter srv)}
            id (:exposure-id (client/decision c exp))]
        (when (pos? (mod i 10)) ; every tenth unit never reports
          @(server/feedback! srv [{:exposure-ids [id] :metric "op" :value (rand-int 100)
                                   :value-unit :ms :outcome (if (zero? (mod i 7)) :error :ok)}]))))
    (let [rows (pg/analysis @ds "aa" 1 "op")]
      (is (= ["a" "b"] (map :arm rows)))
      (is (= 400 (reduce + (map :exposures rows))))
      (is (= 360 (reduce + (map :feedback rows))))
      (is (= 40 (reduce + (map :missing_feedback rows))))
      (is (every? #(< 0 (:failure_rate %) 1) rows))
      (is (every? #(<= (:p50 %) (:p75 %) (:p95 %)) rows)))))
