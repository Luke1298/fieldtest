(ns fieldtest.records
  "Constructors and validation for the two kinds of record a recorder gets.

  An exposure is written by the server each time it makes a decision, and
  holds the arm, revision, sticky key and context. Feedback is a measurement
  the client reports later, and it refers to one or more exposures by id. To
  find out which arm a measurement came from, join it to its exposure."
  (:require
   [clojure.string :as str]))

(def value-units
  "Units a feedback value can be in."
  #{:ms :bytes :count})

(def outcomes
  "Possible outcomes for a measured operation. Errors and timeouts are reported
  the same way as successes."
  #{:ok :error :timeout :abandoned :fallback :unsupported-arm})

(def max-attr-length
  "Longest string attribute value kept; longer ones are truncated."
  200)

(defn now
  "The current time as an inst."
  []
  #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- ->kw [x]
  (cond
    (keyword? x) x
    (and (string? x) (not (str/blank? x))) (keyword x)
    :else nil))

(defn- field [m k]
  (if (contains? m k) (get m k) (get m (name k))))

(defn- attr-value [v]
  (cond
    (string? v) (if (> (count v) max-attr-length) (subs v 0 max-attr-length) v)
    (or (number? v) (boolean? v) (keyword? v)) v
    :else ::drop))

(defn clean-attrs
  "Keeps attribute values that are strings, numbers, booleans or keywords, and
  truncates long strings. Anything else is dropped."
  [attrs]
  (if (map? attrs)
    (into {}
          (keep (fn [[k v]]
                  (let [v (attr-value v)]
                    (when-not (= ::drop v) [k v]))))
          attrs)
    {}))

(defn exposure
  "An exposure record for one decision."
  [{:keys [exposure-id experiment revision arm sticky-key context created-at]}]
  {:exposure-id exposure-id
   :experiment experiment
   :revision revision
   :arm arm
   :sticky-key sticky-key
   :context (or context {})
   :created-at (or created-at (now))})

(defn- exposure-ids-problem [ids]
  (cond
    (not (sequential? ids)) "exposure-ids must be a list"
    (empty? ids) "exposure-ids must not be empty"
    (not (every? #(and (string? %) (not (str/blank? %))) ids))
    "every exposure id must be a non-blank string"))

(defn feedback-problem
  "Why `m` is not valid feedback, or nil when it is. Accepts keyword or
  JSON-shaped (string) keys and enum values."
  [m]
  (if-not (map? m)
    "feedback must be a map"
    (let [metric (field m :metric)
          value (field m :value)
          unit (->kw (field m :value-unit))
          outcome (->kw (field m :outcome))]
      (or (exposure-ids-problem (field m :exposure-ids))
          (cond
            (not (and (string? metric) (not (str/blank? metric)))) "metric must be a non-blank string"
            (not (number? value)) "value must be a number"
            (not (contains? value-units unit))
            (str "value-unit must be one of " (str/join ", " (map name (sort value-units))))
            (not (contains? outcomes outcome))
            (str "outcome must be one of " (str/join ", " (map name (sort outcomes))))
            (and (some? (field m :attrs)) (not (map? (field m :attrs))))
            "attrs must be a map")))))

(defn feedback
  "A feedback record. Throws ex-info when `m` is not valid feedback.

  Takes `:exposure-ids` (or a single `:exposure-id`), `:metric`, `:value`,
  `:value-unit`, `:outcome` and optional `:attrs` and `:recorded-at`."
  [m]
  (let [m (cond-> m
            (and (map? m) (contains? m :exposure-id) (not (contains? m :exposure-ids)))
            (-> (assoc :exposure-ids [(:exposure-id m)]) (dissoc :exposure-id)))]
    (when-let [problem (feedback-problem m)]
      (throw (ex-info (str "Invalid feedback: " problem) {:type ::invalid-feedback :feedback m})))
    {:exposure-ids (vec (field m :exposure-ids))
     :metric (field m :metric)
     :value (field m :value)
     :value-unit (->kw (field m :value-unit))
     :outcome (->kw (field m :outcome))
     :attrs (clean-attrs (field m :attrs))
     :recorded-at (or (field m :recorded-at) (now))}))
