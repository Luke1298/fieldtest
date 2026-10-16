(ns fieldtest.config
  "Experiment configs: the data a config source returns.

  A config source returns a map of experiment name to config:

      {\"asset-host\" {:revision 3 :weights {:b 20} :sticky :hashed}}

  Input may be JSON-shaped (string keys, string arms and modes). `normalize`
  turns it into the shape above, with keyword arms and modes, and drops every
  experiment that is invalid. `:a` is the baseline: it is never weighted and
  receives every unit not drawn into a candidate."
  (:require
   [clojure.string :as str]
   [promesa.core :as p]))

(def sticky-modes
  "How the server treats a request's sticky key."
  #{:none :hashed})

(defn- ->kw [x]
  (cond
    (keyword? x) x
    (and (string? x) (not (str/blank? x))) (keyword x)
    :else nil))

(defn- field [m k]
  (if (contains? m k) (get m k) (get m (name k))))

(defn- number-ok? [x]
  (and (number? x)
       #?(:clj (not (and (double? x) (or (Double/isNaN x) (Double/isInfinite x))))
          :cljs (js/isFinite x))))

(defn- revision-ok? [x]
  (and (integer? x) (not (neg? x))))

(defn- weights-problem [weights]
  (cond
    (not (map? weights)) "weights must be a map of arm to percentage"
    :else (some (fn [[arm w]]
                  (let [k (->kw arm)]
                    (cond
                      (nil? k) (str "arm " (pr-str arm) " is not a name")
                      (= :a k) ":a is the baseline and is never weighted"
                      (not (number-ok? w)) (str "weight for " (name k) " is not a number")
                      (neg? w) (str "weight for " (name k) " is negative"))))
                weights)))

(defn experiment-problem
  "Why `config` is not a valid experiment config, or nil when it is."
  [experiment config]
  (let [revision (when (map? config) (field config :revision))
        weights (when (map? config) (field config :weights))
        sticky (when (map? config) (->kw (field config :sticky)))]
    (cond
      (not (and (string? experiment) (not (str/blank? experiment))))
      "experiment name must be a non-blank string"

      (not (map? config)) "config must be a map"
      (nil? revision) "revision is missing"
      (not (revision-ok? revision)) "revision must be a non-negative integer"
      (nil? weights) "weights are missing"
      :else (or (weights-problem weights)
                (cond
                  (nil? (field config :sticky)) "sticky mode is missing"
                  (not (contains? sticky-modes sticky))
                  (str "sticky mode must be one of " (str/join ", " (map name (sort sticky-modes)))))))))

(defn normalize-experiment
  "The normalized form of one experiment's config, or nil when it is invalid."
  [experiment config]
  (when-not (experiment-problem experiment config)
    {:revision (field config :revision)
     :weights (into {} (map (fn [[arm w]] [(->kw arm) w])) (field config :weights))
     :sticky (->kw (field config :sticky))}))

(defn- experiment-name [k]
  (if (keyword? k) (subs (str k) 1) k))

(defn problems
  "Map of experiment name to the reason it is invalid, for every invalid
  experiment in `configs`."
  [configs]
  (into {}
        (keep (fn [[k config]]
                (let [experiment (experiment-name k)]
                  (when-let [problem (experiment-problem experiment config)]
                    [experiment problem]))))
        configs))

(defn normalize
  "Normalizes the configs a config source returned, dropping invalid
  experiments. Returns nil when `configs` is not a map at all, so the caller
  can tell bad data from an empty config."
  [configs]
  (when (map? configs)
    (into {}
          (keep (fn [[k config]]
                  (let [experiment (experiment-name k)]
                    (when-let [normalized (normalize-experiment experiment config)]
                      [experiment normalized]))))
          configs)))

(defn- now-ms []
  #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))

(defn cached
  "Wraps a config source so it is called at most once per `:ttl-ms`
  (default 30,000). Concurrent calls share one fetch.

  A failed fetch is not cached as a value: the failure is passed on, so the
  server keeps its last good configs and reports it, and the source is not
  called again for `:retry-ms` (default 5,000), during which calls fail with
  the same error. `:clock` returns the current time in milliseconds."
  ([source] (cached source {}))
  ([source {:keys [ttl-ms retry-ms clock] :or {ttl-ms 30000 retry-ms 5000 clock now-ms}}]
   (let [state (atom {:expires-at 0 :result nil})
         fetch! (fn [d]
                  (-> (try
                        (p/promise (source))
                        (catch #?(:clj Throwable :cljs :default) e
                          (p/rejected e)))
                      (p/then (fn [configs]
                                (swap! state assoc :expires-at (+ (clock) ttl-ms))
                                (p/resolve! d configs)))
                      (p/catch (fn [e]
                                 (swap! state assoc :expires-at (+ (clock) retry-ms))
                                 (p/reject! d e)))))]
     (fn []
       (let [mine (p/deferred)
             [_ {:keys [result]}]
             (swap-vals! state (fn [{:keys [expires-at result] :as s}]
                                 (if (and result (< (clock) expires-at))
                                   s
                                   ;; Claim the fetch; until it settles every caller shares it.
                                   {:expires-at ##Inf :result mine})))]
         (when (identical? result mine)
           (fetch! mine))
         result)))))
