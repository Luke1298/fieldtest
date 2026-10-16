(ns fieldtest.client
  "Runs the decided arm of an operation and reports how it went.

  A client is a map:

      {:decisions {\"asset-host\" {:experiment \"asset-host\" :revision 3
                                  :arm :b :exposure-id \"6f1c...\"}}
       :reporter  (in-process-reporter server)   ; or fieldtest.http/reporter
       :on-error  (fn [e] ...)}                  ; optional

  Get `:decisions` once per scope with `decide`, then reuse them for every
  `branch` in that scope. An experiment is described by the same data the
  decide request lists:

      (def asset-host {:experiment \"asset-host\" :revision 3 :arms [:a :b]})"
  (:require
   [fieldtest.json :as json]
   [fieldtest.records :as records]
   [fieldtest.server :as server]
   [promesa.core :as p]))

(defn- report-error! [{:keys [on-error]} e]
  (when on-error
    (try (on-error e) (catch #?(:clj Throwable :cljs :default) _ nil))))

;; Deciders

(defn in-process-decider
  "A decider that calls `server` in this process. A decider takes a decide
  request and returns a promise of `{:decisions [...]}`."
  [server]
  (fn [request]
    (server/decide server request)))

(defn decisions-by-experiment
  "Turns a decide response into a map of experiment name to decision."
  [response]
  (into {}
        (keep (fn [{:keys [experiment] :as d}]
                (when (string? experiment)
                  [experiment (update d :arm keyword)])))
        (:decisions response)))

(defn decide
  "Asks `decider` for decisions on `experiments` and returns a promise of a map
  of experiment name to decision. Never rejects: when the decider fails, or
  takes longer than `:timeout-ms` (default 1,000), the promise resolves to
  `{}` and every branch runs `:a` untracked.

  Options: `:sticky-key`, `:context`, `:timeout-ms` and `:on-error`."
  ([decider experiments] (decide decider experiments {}))
  ([decider experiments {:keys [sticky-key context timeout-ms] :or {timeout-ms 1000} :as opts}]
   (let [request (cond-> {:experiments (mapv #(select-keys % [:experiment :revision :arms]) experiments)}
                   sticky-key (assoc :sticky-key sticky-key)
                   context (assoc :context context))]
     (-> (try
           (p/promise (decider request))
           (catch #?(:clj Throwable :cljs :default) e
             (p/rejected e)))
         (p/timeout timeout-ms)
         (p/then decisions-by-experiment)
         (p/catch (fn [e]
                    (report-error! opts e)
                    {}))))))

;; Reporters

(defn in-process-reporter
  "A reporter that hands feedback to `server` in this process. A reporter takes
  a batch (a vector) of feedback records and returns a promise."
  [server]
  (fn [batch]
    (server/feedback! server batch)))

(defn report!
  "Sends one piece of feedback through the client's reporter, fire-and-forget.
  Returns nil.

  `feedback` takes `:exposure-id` (or `:exposure-ids`), `:metric`, `:value`,
  `:value-unit`, `:outcome` and optional `:attrs`; see
  `fieldtest.records/feedback`. Does nothing when there is no exposure id.
  Invalid feedback and reporter failures go to `:on-error` and never throw."
  [{:keys [reporter] :as client} {:keys [exposure-id exposure-ids] :as feedback}]
  (when (and reporter (or (some? exposure-id) (seq exposure-ids)))
    (try
      (-> (p/promise (reporter [(records/feedback feedback)]))
          (p/catch (fn [e] (report-error! client e))))
      (catch #?(:clj Throwable :cljs :default) e
        (report-error! client e))))
  nil)

;; Branching

(defn decision
  "The client's decision for `experiment`, or nil when it has none for that
  experiment and revision."
  [{:keys [decisions]} {:keys [experiment revision]}]
  (let [d (get decisions experiment)]
    (when (and d (= revision (:revision d)))
      d)))

(defn branch
  "Runs exactly one of `arms`, a map of arm keyword to function, with `args`,
  and returns `{:result r :exposure-id id}`.

  The decided arm runs when the client has a decision for this experiment and
  revision. Without one, `:a` runs and `:exposure-id` is nil. A decision for an
  arm that `arms` doesn't declare runs `:a`, keeps the exposure id and reports
  `unsupported-arm` feedback.

  A promise result is returned as the same promise, and an error thrown or
  rejected by the arm reaches the caller unchanged. No other arm is ever tried.
  Throws ex-info before running anything when `arms` has no `:a`."
  [client experiment arms & args]
  (when-not (ifn? (get arms :a))
    (throw (ex-info (str "A branch must declare an :a arm: " (:experiment experiment))
                    {:type ::missing-baseline :experiment (:experiment experiment)})))
  (let [{:keys [arm exposure-id]} (decision client experiment)
        declared? (ifn? (get arms arm))]
    (when (and exposure-id (not declared?))
      (report! client {:exposure-id exposure-id
                       :metric "fieldtest.branch"
                       :value 1
                       :value-unit :count
                       :outcome :unsupported-arm}))
    {:result (apply (get arms (if declared? arm :a)) args)
     :exposure-id exposure-id}))

;; Timing

(defn- now-ms []
  #?(:clj (/ (System/nanoTime) 1e6)
     :cljs (if (exists? js/performance) (js/performance.now) (js/Date.now))))

#?(:clj
   (defonce ^:private ^java.util.concurrent.ScheduledExecutorService scheduler
     (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
      (reify java.util.concurrent.ThreadFactory
        (newThread [_ r]
          (doto (Thread. ^Runnable r "fieldtest-timer")
            (.setDaemon true)))))))

(defn- schedule
  "Runs `f` after `ms` and returns a function that cancels it."
  [ms f]
  #?(:clj (let [fut (.schedule scheduler ^Runnable f (long ms) java.util.concurrent.TimeUnit/MILLISECONDS)]
            (fn [] (.cancel fut false)))
     :cljs (let [id (js/setTimeout f ms)]
             (fn [] (js/clearTimeout id)))))

(defn- promise? [x]
  #?(:clj (instance? java.util.concurrent.CompletionStage x)
     :cljs (p/promise? x)))

(defn timed
  "Calls `(apply f args)`, reports its duration in ms as feedback, and returns
  its result (a value or the same promise) or rethrows its error unchanged.

  Reports outcome `ok` on success and `error` when `f` throws or rejects. With
  `:timeout-ms`, reports `timeout` once that time passes instead, without
  cancelling the operation. `opts` holds `:exposure-id` (nothing is reported
  without one), `:metric`, and optional `:attrs` and `:timeout-ms`."
  [client {:keys [exposure-id metric attrs timeout-ms]} f & args]
  (let [start (now-ms)
        reported? (atom false)
        report (fn [outcome value]
                 (when (compare-and-set! reported? false true)
                   (report! client {:exposure-id exposure-id
                                    :metric metric
                                    :value value
                                    :value-unit :ms
                                    :outcome outcome
                                    :attrs attrs})))
        elapsed #(- (now-ms) start)
        result (try
                 (apply f args)
                 (catch #?(:clj Throwable :cljs :default) e
                   (report :error (elapsed))
                   (throw e)))]
    (if (promise? result)
      (let [cancel (if timeout-ms
                     (schedule timeout-ms #(report :timeout timeout-ms))
                     (fn []))]
        (p/handle result (fn [_ err]
                           (cancel)
                           (report (if err :error :ok) (elapsed))
                           nil))
        result)
      (do
        (report :ok (elapsed))
        result))))

;; Handoff

(def ^:private handoff-version 1)

(defn decisions->json
  "Serializes decisions so another runtime can branch on them without asking
  the server again."
  [decisions]
  (json/encode {:v handoff-version :decisions (vec (vals decisions))}))

(defn json->decisions
  "Reads decisions written by `decisions->json`. Unknown fields are ignored."
  [s]
  (decisions-by-experiment (json/decode s)))
