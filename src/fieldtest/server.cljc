(ns fieldtest.server
  "The experiment server: decides arms for clients and receives their feedback.

  A server is a value built from plug-ins, not a process. Host it in-process,
  behind HTTP (see `fieldtest.http`), or both. Each plug-in is a plain function
  that may return a value or a promise:

  - `:config-source` `(fn [] configs)` returns the current experiment configs
    (see `fieldtest.config`).
  - `:selector` `(fn [{:keys [experiment config sticky-key supported-arms context]}] arm)`
    chooses an arm for one experiment. Defaults to the built-in
    `fieldtest.selector/weighted-hash`.
  - `:recorder` `(fn [kind record])` receives every record, where `kind` is
    `:exposure` or `:feedback` (see `fieldtest.records`).
  - `:error-hook` `(fn [{:keys [plugin error experiment]}])` receives every
    plug-in failure. Optional.

  A failing plug-in never makes a decide request fail: a bad config source
  leaves the last good configs in place, a failing selector gives `:default`, and a
  failing recorder is ignored. Every failure goes to the error hook."
  (:require
   [clojure.string :as str]
   [fieldtest.config :as config]
   [fieldtest.ids :as ids]
   [fieldtest.records :as records]
   [fieldtest.selector :as selector]
   [promesa.core :as p]))

(defn- attempt
  "Calls `f` with `args` and returns a promise of its result. A synchronous
  throw becomes a rejection."
  [f & args]
  (try
    (p/promise (apply f args))
    (catch #?(:clj Throwable :cljs :default) e
      (p/rejected e))))

(defn server
  "Builds a server from plug-ins. See the namespace docstring.

  Also takes `:clock`, a function returning the current inst, for exposure
  times."
  [{:keys [config-source selector recorder error-hook clock]}]
  (when-not (fn? config-source)
    (throw (ex-info "A server needs a :config-source function" {:type ::invalid-server})))
  (when-not (fn? recorder)
    (throw (ex-info "A server needs a :recorder function" {:type ::invalid-server})))
  (when (and (some? selector) (not (fn? selector)))
    (throw (ex-info ":selector must be a function" {:type ::invalid-server})))
  {::config-source config-source
   ::selector (or selector selector/weighted-hash)
   ::recorder recorder
   ::error-hook (or error-hook (fn [_]))
   ::clock (or clock records/now)
   ::last-good (atom {})})

(defn- report-error! [server event]
  (try
    ((::error-hook server) event)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- record! [server kind record]
  (-> (attempt (::recorder server) kind record)
      (p/catch (fn [e]
                 (report-error! server {:plugin :recorder :error e :kind kind :record record})
                 nil))))

;; Configs

(defn- read-configs!
  "The current normalized configs, or the last good ones when the config
  source fails or returns bad data."
  [server]
  (let [last-good (::last-good server)]
    (-> (attempt (::config-source server))
        (p/then (fn [raw]
                  (if-let [normalized (config/normalize raw)]
                    (do
                      (when-let [problems (not-empty (config/problems raw))]
                        (report-error! server {:plugin :config-source
                                               :error (ex-info "Invalid experiment configs were ignored"
                                                               {:type ::invalid-experiments :problems problems})}))
                      (reset! last-good normalized))
                    (do
                      (report-error! server {:plugin :config-source
                                             :error (ex-info "Config source returned something other than a map"
                                                             {:type ::invalid-configs :value raw})})
                      @last-good))))
        (p/catch (fn [e]
                   (report-error! server {:plugin :config-source :error e})
                   @last-good)))))

;; Requests

(defn- ->kw [x]
  (cond
    (keyword? x) x
    (and (string? x) (not (str/blank? x))) (keyword x)
    :else nil))

(defn- experiment-problem [{:keys [experiment revision arms] :as e}]
  (cond
    (not (map? e)) "each experiment must be a map"
    (not (and (string? experiment) (not (str/blank? experiment)))) "experiment must be a non-blank string"
    (not (and (integer? revision) (not (neg? revision)))) (str "revision for " experiment " must be a non-negative integer")
    (not (sequential? arms)) (str "arms for " experiment " must be a list")
    (not (every? ->kw arms)) (str "every arm for " experiment " must be a name")))

(defn request-problem
  "Why `request` is not a valid decide request, or nil when it is."
  [{:keys [sticky-key context experiments] :as request}]
  (cond
    (not (map? request)) "request must be a map"
    (not (or (nil? sticky-key) (string? sticky-key))) "sticky-key must be a string"
    (not (or (nil? context) (map? context))) "context must be a map"
    (not (sequential? experiments)) "experiments must be a list"
    :else (some experiment-problem experiments)))

(defn- normalize-request [request]
  {:sticky-key (:sticky-key request)
   :context (or (:context request) {})
   :experiments (->> (:experiments request)
                     (map #(update % :arms (fn [arms] (set (map ->kw arms)))))
                     (reduce (fn [[seen acc] e]
                               (if (seen (:experiment e))
                                 [seen acc]
                                 [(conj seen (:experiment e)) (conj acc e)]))
                             [#{} []])
                     second)})

;; Deciding

(defn- valid-arm
  "`arm` if the server may decide it, else nil. The server only decides `:default`
  or a configured candidate the client supports."
  [arm config supported-arms]
  (let [arm (->kw arm)]
    (cond
      (= :default arm) :default
      (and (contains? supported-arms arm) (contains? (:weights config) arm)) arm)))

(defn- choose-arm
  "A promise of the arm for one requested experiment."
  [server configs {:keys [sticky-key context]} {:keys [experiment revision arms]}]
  (let [cfg (get configs experiment)]
    (if (or (nil? cfg) (not= revision (:revision cfg)))
      (p/resolved :default)
      (let [cfg (cond-> cfg (nil? sticky-key) (assoc :sticky :none))]
        (-> (attempt (::selector server) {:experiment experiment
                                          :config cfg
                                          :sticky-key sticky-key
                                          :supported-arms arms
                                          :context context})
            (p/then (fn [arm]
                      (or (valid-arm arm cfg arms)
                          (do (report-error! server {:plugin :selector
                                                     :experiment experiment
                                                     :error (ex-info "Selector chose an arm that is not a supported, configured candidate"
                                                                     {:type ::invalid-arm :arm arm})})
                              :default))))
            (p/catch (fn [e]
                       (report-error! server {:plugin :selector :experiment experiment :error e})
                       :default)))))))

(defn- decide-one [server configs request e]
  (p/let [arm (choose-arm server configs request e)
          decision {:experiment (:experiment e)
                    :revision (:revision e)
                    :arm arm
                    :exposure-id (ids/exposure-id)}
          _ (record! server :exposure (records/exposure
                                       (assoc decision
                                              :sticky-key (:sticky-key request)
                                              :context (:context request)
                                              :created-at ((::clock server)))))]
    decision))

(defn decide
  "Decides an arm for each experiment in `request` and returns a promise of
  `{:decisions [{:experiment :revision :arm :exposure-id} ...]}`.

  `request` is `{:sticky-key \"...\" :context {...} :experiments [{:experiment
  \"name\" :revision 3 :arms [:default :cdn]} ...]}`; the sticky key and context are
  optional. Every decision, including `:default`, is recorded as an exposure before
  the promise resolves.

  Rejects with an ex-info of type `:fieldtest.server/invalid-request` when the
  request itself is malformed. Plug-in failures never reject."
  [server request]
  (if-let [problem (request-problem request)]
    (p/rejected (ex-info (str "Invalid decide request: " problem)
                         {:type ::invalid-request :problem problem}))
    (let [request (normalize-request request)]
      (p/let [configs (read-configs! server)
              decisions (p/all (map #(decide-one server configs request %) (:experiments request)))]
        {:decisions (vec decisions)}))))

;; Feedback

(defn feedback!
  "Validates a batch of feedback and passes each record to the recorder.
  Returns a promise of `{:accepted n}`.

  Rejects with an ex-info of type `:fieldtest.server/invalid-request`, and
  records nothing, when the batch is not a list or any record is invalid.
  Recorder failures go to the error hook and never reject."
  [server batch]
  (let [problem (if (sequential? batch)
                  (some (fn [[i m]]
                          (when-let [problem (records/feedback-problem m)]
                            (str "feedback " i ": " problem)))
                        (map-indexed vector batch))
                  "feedback batch must be a list")]
    (if problem
      (p/rejected (ex-info (str "Invalid feedback: " problem) {:type ::invalid-request :problem problem}))
      (p/let [stamp (fn [m]
                      (cond-> m
                        (nil? (or (:recorded-at m) (get m "recorded-at")))
                        (assoc :recorded-at ((::clock server)))))
              records (mapv (comp records/feedback stamp) batch)
              _ (p/all (map #(record! server :feedback %) records))]
        {:accepted (count records)}))))
