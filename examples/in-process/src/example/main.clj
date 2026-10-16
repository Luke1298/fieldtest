(ns example.main
  "One JVM process that is both server and client. It runs an A/A experiment
  (identical code in :default and :candidate, with :candidate at 50%) for 10,000 sticky keys and prints
  per-arm stats from the in-memory recorder."
  (:require
   [fieldtest.client :as client]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server]
   [promesa.core :as p]))

(def experiment {:experiment "checkout-total" :revision 1 :arms [:default :candidate]})

(defn- compute-total
  "The operation under test. Both arms run this same code, so any difference
  between them is noise."
  [items]
  (Thread/sleep (long (rand-int 3)))
  (reduce + items))

(defn- percentile [sorted q]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted)) (int (* q (count sorted)))))))

(defn- stats
  "Per-arm count and latency percentiles, joining feedback to exposures by id."
  [entries]
  (let [arm-of (into {} (map (juxt :exposure-id :arm)) (memory/exposures entries))]
    (->> (memory/feedback entries)
         (group-by #(arm-of (first (:exposure-ids %))))
         (into (sorted-map)
               (map (fn [[arm fs]]
                      (let [ms (sort (map :value fs))]
                        [arm {:count (count fs)
                              :p50 (percentile ms 0.5)
                              :p95 (percentile ms 0.95)
                              :errors (count (remove #(= :ok (:outcome %)) fs))}])))))))

(defn -main [& _]
  (let [store (atom [])
        srv (server/server {:config-source (memory/config-source
                                            {"checkout-total" {:revision 1 :weights {:candidate 50} :sticky :hashed}})
                            :recorder (memory/recorder store)})
        decider (client/in-process-decider srv)
        reporter (client/in-process-reporter srv)
        n 10000]
    @(p/all
      (for [i (range n)]
        (p/let [decisions (client/decide decider [experiment] {:sticky-key (str "customer:" i)})]
          (let [c {:decisions decisions :reporter reporter}]
            (client/timed c {:exposure-id (:exposure-id (client/decision c experiment)) :metric "checkout.total"}
                          #(:result (client/branch c experiment {:default compute-total :candidate compute-total} [1 2 3])))))))
    ;; report! doesn't wait, so give the last records time to arrive.
    (Thread/sleep 200)
    (let [by-arm (stats @store)
          total (reduce + (map :count (vals by-arm)))]
      (println (format "%d keys, %d feedback records" n total))
      (doseq [[arm {:keys [count p50 p95 errors]}] by-arm]
        (println (format "  %-10s %5d (%4.1f%%)  p50 %5.2f ms  p95 %5.2f ms  errors %d"
                         arm count (* 100.0 (/ count total)) p50 p95 errors))))
    (shutdown-agents)))
