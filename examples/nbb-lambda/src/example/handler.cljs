(ns example.handler
  "A Lambda-shaped nbb handler. It branches a sort on fieldtest decisions
  taken from its payload (handed off by whoever invoked it) or, when the
  payload has none, asks an HTTP decider. It reports the sort's duration
  through the HTTP reporter.

      nbb -m example.handler event-handoff.json"
  (:require
   ["fs" :as fs]
   [fieldtest.client :as client]
   [fieldtest.http :as http]
   [promesa.core :as p]))

(def sort-impl {:experiment "sort-impl" :revision 1 :arms [:a :b]})

(defn- sort-a [items] (sort items))
(defn- sort-b [items] (vec (sort-by identity items)))

(defn- decisions-for
  "Decisions handed off in the payload, else a promise of decisions from the
  HTTP decider, else none."
  [{:keys [decisions decideUrl]} job-id]
  (cond
    decisions (client/json->decisions decisions)
    decideUrl (client/decide (http/decider decideUrl) [sort-impl]
                             {:sticky-key (str "job:" job-id) :context {:runtime "nbb-lambda"} :timeout-ms 300})
    :else {}))

(defn handler
  "The Lambda entry point: takes the event and returns a promise of the
  response."
  [event _context]
  (let [{:keys [jobId items fieldtest]} (js->clj event :keywordize-keys true)]
    (p/let [decisions (decisions-for fieldtest jobId)
            c {:decisions decisions
               :reporter (when-let [url (:feedbackUrl fieldtest)] (http/reporter url))
               :on-error #(js/console.warn "fieldtest:" (ex-message %))}
            {:keys [arm exposure-id]} (client/decision c sort-impl)
            sorted (client/timed c {:exposure-id exposure-id :metric "job.sort"}
                                 #(:result (client/branch c sort-impl {:a sort-a :b sort-b} items)))
            ;; Reporting is fire-and-forget. A real Lambda should keep the
            ;; event loop alive (or flush) before returning; here we wait a beat.
            _ (p/delay 100)]
      (clj->js {:jobId jobId :sorted sorted :arm (some-> arm name) :exposureId exposure-id}))))

(defn -main [& [event-file]]
  (p/let [event (js/JSON.parse (fs/readFileSync (or event-file "event-handoff.json") "utf8"))
          result (handler event #js {})]
    (println (js/JSON.stringify result))))
