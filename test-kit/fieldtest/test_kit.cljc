(ns fieldtest.test-kit
  "Contract checks for plug-ins. Run them from any test runner to confirm a
  config source, selector or recorder behaves the way the server expects.

  Each check takes a plug-in and returns a promise of

      {:pass? true|false
       :failures [{:reason :keyword :message \"...\"} ...]}

  rather than calling `is`, so it fits clojure.test, cljs.test, nbb or
  anything else:

      (deftest my-recorder-meets-the-contract
        (let [{:keys [pass? failures]} @(test-kit/check-recorder my-recorder)]
          (is pass? (pr-str failures))))

  On ClojureScript, wait on the promise with your runner's async support
  instead of deref."
  (:require
   [clojure.string :as str]
   [fieldtest.config :as config]
   [fieldtest.ids :as ids]
   [fieldtest.records :as records]
   [promesa.core :as p]))

(defn- attempt [f & args]
  (try
    (p/promise (apply f args))
    (catch #?(:clj Throwable :cljs :default) e
      (p/rejected e))))

(defn- settle
  "A promise of `{:value v}`, `{:error e}` or `{:timeout true}`."
  [pr timeout-ms]
  (-> pr
      (p/timeout timeout-ms ::timeout)
      (p/then (fn [v] (if (= ::timeout v) {:timeout true} {:value v})))
      (p/catch (fn [e] {:error e}))))

(defn- failure [reason message & {:as data}]
  (merge {:reason reason :message message} data))

(defn- result [failures]
  (let [failures (vec (distinct failures))]
    {:pass? (empty? failures) :failures failures}))

(defn- error-message [e]
  (or (ex-message e) (str e)))

;; Config sources

(defn check-config-source
  "Checks a config source: every call settles within `:timeout-ms` (default
  5,000) and either resolves to a map of valid experiment configs or fails by
  throwing or rejecting, which the server can isolate. Calls it `:calls`
  times (default 3).

  Reasons: `:hangs`, `:not-a-map`, `:invalid-experiment`."
  ([source] (check-config-source source {}))
  ([source {:keys [timeout-ms calls] :or {timeout-ms 5000 calls 3}}]
   (p/let [outcomes (p/loop [i 0 acc []]
                      (if (< i calls)
                        (p/let [o (settle (attempt source) timeout-ms)]
                          (p/recur (inc i) (conj acc o)))
                        acc))]
     (result
      (mapcat (fn [{:keys [value timeout error] :as o}]
                (cond
                  timeout [(failure :hangs (str "config source did not settle within " timeout-ms " ms"))]
                  error []
                  (not (map? value)) [(failure :not-a-map (str "config source returned " (pr-str value)
                                                               "; return a map, or throw when the configs can't be read"))]
                  :else (for [[experiment problem] (config/problems (:value o))]
                          (failure :invalid-experiment (str experiment ": " problem) :experiment experiment))))
              outcomes)))))

;; Selectors

(def ^:private selector-configs
  [{:revision 1 :weights {} :sticky :hashed}
   {:revision 1 :weights {:b 0} :sticky :hashed}
   {:revision 2 :weights {:b 50} :sticky :hashed}
   {:revision 3 :weights {:b 100} :sticky :hashed}
   {:revision 1 :weights {:b 30 :c 30} :sticky :hashed}
   {:revision 1 :weights {:b 50} :sticky :none}
   {:revision 4 :weights {:b 100 :c 100 :d 100} :sticky :none}])

(def ^:private sticky-keys
  [nil "" "user:1" "user:2" "session:abc" "héllo-日本" (apply str (repeat 300 "k"))])

(defn- supported-sets [weights]
  (let [candidates (sort (keys weights))]
    (distinct [(conj (set candidates) :a)
               #{:a}
               (set (take 1 candidates))
               (conj (set (drop 1 candidates)) :a)])))

(defn- selector-requests []
  (for [cfg selector-configs
        sticky-key sticky-keys
        supported (supported-sets (:weights cfg))]
    {:experiment "test-kit"
     :config cfg
     :sticky-key sticky-key
     :supported-arms supported
     :context {:runtime "test-kit"}}))

(defn- ->arm [x]
  (cond
    (keyword? x) x
    (and (string? x) (not (str/blank? x))) (keyword x)
    :else x))

(defn- selector-failures [request {:keys [value timeout error]} timeout-ms]
  (let [arm (->arm value)
        {:keys [config supported-arms]} request]
    (cond
      timeout [(failure :hangs (str "selector did not settle within " timeout-ms " ms") :request request)]
      error [(failure :throws (str "selector threw on valid input: " (error-message error)) :request request)]
      (= :a arm) []
      (not (contains? (:weights config) arm))
      [(failure :unconfigured-arm (str "selector chose " (pr-str value) ", which is neither :a nor a configured candidate")
                :request request)]
      (not (contains? supported-arms arm))
      [(failure :unsupported-arm (str "selector chose " (pr-str value) ", which the client does not support")
                :request request)]
      :else [])))

(defn check-selector
  "Checks a selector over a matrix of valid requests: it never throws or
  hangs, it only chooses `:a` or a configured candidate, it respects the
  client's supported arms, and in `hashed` mode it chooses the same arm every
  time for the same sticky key.

  Reasons: `:throws`, `:hangs`, `:unconfigured-arm`, `:unsupported-arm`,
  `:unstable`."
  ([selector] (check-selector selector {}))
  ([selector {:keys [timeout-ms repeats] :or {timeout-ms 5000 repeats 5}}]
   (p/let [checked (p/all
                    (for [request (selector-requests)]
                      (p/let [outcomes (p/all (repeatedly repeats #(settle (attempt selector request) timeout-ms)))]
                        (let [own (mapcat #(selector-failures request % timeout-ms) outcomes)
                              arms (set (map (comp ->arm :value) outcomes))
                              hashed? (and (= :hashed (get-in request [:config :sticky]))
                                           (some? (:sticky-key request)))]
                          (cond-> own
                            (and hashed? (empty? own) (< 1 (count arms)))
                            (conj (failure :unstable (str "hashed mode chose " (pr-str arms) " for one sticky key")
                                           :request request)))))))]
     ;; One failure per reason is enough to act on.
     (result (vals (into {} (map (juxt :reason identity)) (apply concat checked)))))))

;; Recorders

(defn- sample-records []
  (let [t (records/now)
        exposure (fn [m] (records/exposure (merge {:exposure-id (ids/exposure-id) :experiment "test-kit"
                                                   :revision 1 :arm :a :created-at t}
                                                  m)))]
    (concat
     [[:exposure (exposure {})]
      [:exposure (exposure {:arm :b :sticky-key "user:1" :context {:runtime "test-kit" :release "abc"}})]
      [:exposure (exposure {:arm :c :sticky-key "héllo-日本" :context {}})]]
     (for [outcome (sort records/outcomes)
           unit (sort records/value-units)]
       [:feedback (records/feedback {:exposure-ids [(ids/exposure-id)] :metric "test-kit" :value 12.5
                                     :value-unit unit :outcome outcome :recorded-at t})])
     [[:feedback (records/feedback {:exposure-ids [(ids/exposure-id) (ids/exposure-id)] :metric "test-kit.multi"
                                    :value 0 :value-unit :count :outcome :ok
                                    :attrs {:route "x" :n 3 :cached true :kind :img}
                                    :recorded-at t})]])))

(defn- run-concurrently
  "Calls each thunk at once, on separate threads on the JVM."
  [thunks]
  #?(:clj (p/all (map (fn [thunk]
                        (-> (java.util.concurrent.CompletableFuture/supplyAsync
                             (reify java.util.function.Supplier (get [_] (thunk))))
                            (p/then identity)))
                      thunks))
     :cljs (p/all (map (fn [thunk] (thunk)) thunks))))

(defn check-recorder
  "Checks a recorder: it accepts every exposure and feedback record shape
  without throwing, rejecting or hanging, and tolerates `:concurrency`
  (default 50) calls at once.

  Optionally takes `:read-back`, a function returning (a promise of) every
  record the recorder has stored, to check each record arrived.

  Reasons: `:throws`, `:hangs`, `:concurrent-failure`, `:missing-records`."
  ([recorder] (check-recorder recorder {}))
  ([recorder {:keys [timeout-ms concurrency read-back] :or {timeout-ms 5000 concurrency 50}}]
   (let [samples (vec (sample-records))
         ;; Fresh records (new ids), so an idempotent recorder stores every one.
         concurrent (take concurrency (apply concat (repeatedly sample-records)))
         call (fn [[kind record]] (settle (attempt recorder kind record) timeout-ms))]
     (p/let [sequential (p/loop [i 0 acc []]
                          (if (< i (count samples))
                            (p/let [o (call (nth samples i))]
                              (p/recur (inc i) (conj acc [(nth samples i) o])))
                            acc))
             parallel (run-concurrently (map (fn [s] #(call s)) concurrent))
             stored (when read-back (settle (attempt read-back) timeout-ms))]
       (let [shape-failures (for [[[kind record] {:keys [timeout error]}] sequential
                                  :when (or timeout error)]
                              (failure (if timeout :hangs :throws)
                                       (str "recorder " (if timeout "did not settle" (str "failed: " (error-message error)))
                                            " for a " (name kind) " record")
                                       :kind kind :record record))
             concurrent-failures (when (some #(or (:timeout %) (:error %)) parallel)
                                   [(failure :concurrent-failure
                                             (str (count (filter #(or (:timeout %) (:error %)) parallel))
                                                  " of " (count parallel) " concurrent calls failed"))])
             expected (count (concat samples concurrent))
             read-back-failures (when read-back
                                  (let [{:keys [value error timeout]} stored
                                        n (when (sequential? value) (count value))]
                                    (cond
                                      (or error timeout) [(failure :missing-records "read-back failed")]
                                      (or (nil? n) (< n expected))
                                      [(failure :missing-records (str "read-back returned " (pr-str n)
                                                                      " records, expected at least " expected))])))]
         (result (concat shape-failures concurrent-failures read-back-failures)))))))
