(ns example.postgres-recorder
  "A fieldtest recorder that writes exposures and feedback to Postgres with
  next.jdbc."
  (:require
   [clojure.java.io :as io]
   [fieldtest.json :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(defn- ts [inst]
  (java.sql.Timestamp. (inst-ms inst)))

(defn migrate!
  "Creates the tables if they don't exist."
  [ds]
  (jdbc/execute! ds [(slurp (io/resource "schema.sql"))]))

(defn recorder
  "A recorder over the datasource `ds`. Each call is one transaction."
  [ds]
  (fn [kind record]
    (case kind
      :exposure
      (let [{:keys [exposure-id experiment revision arm sticky-key context created-at]} record]
        (jdbc/execute-one! ds ["insert into exposures (exposure_id, experiment, revision, arm, sticky_key, context, created_at)
                                values (?, ?, ?, ?, ?, ?::jsonb, ?) on conflict (exposure_id) do nothing"
                               exposure-id experiment revision (name arm) sticky-key
                               (json/encode context) (ts created-at)]))
      :feedback
      (let [{:keys [exposure-ids metric value value-unit outcome attrs recorded-at]} record]
        (jdbc/with-transaction [tx ds]
          (let [{:keys [id]} (jdbc/execute-one! tx ["insert into feedback (metric, value, value_unit, outcome, attrs, recorded_at)
                                                     values (?, ?, ?, ?, ?::jsonb, ?) returning id"
                                                    metric (double value) (name value-unit) (name outcome)
                                                    (json/encode attrs) (ts recorded-at)]
                                                {:builder-fn rs/as-unqualified-lower-maps})]
            (doseq [exposure-id (distinct exposure-ids)]
              (jdbc/execute-one! tx ["insert into feedback_exposures (feedback_id, exposure_id) values (?, ?)"
                                     id exposure-id]))))))
    nil))

(defn record-count
  "Exposures plus feedback rows, for checking that records arrived."
  [ds]
  (:n (jdbc/execute-one! ds ["select (select count(*) from exposures) + (select count(*) from feedback) as n"]
                         {:builder-fn rs/as-unqualified-lower-maps})))

(defn analysis
  "Runs resources/analysis.sql for one experiment revision and metric."
  [ds experiment revision metric]
  (jdbc/execute! ds [(slurp (io/resource "analysis.sql")) metric experiment revision]
                 {:builder-fn rs/as-unqualified-lower-maps}))
