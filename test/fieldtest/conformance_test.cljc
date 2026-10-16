(ns fieldtest.conformance-test
  "A/A conformance: identical code in :default and :b, run from a client through a
  server for many simulated sticky keys, in-process and over the HTTP
  handlers. The arm split must match the weights and every exposure must join
  to its feedback."
  (:require
   [clojure.test :refer [is]]
   [fieldtest.client :as client]
   [fieldtest.http :as http]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server]
   [fieldtest.test-macros :refer [defasynctest]]
   [promesa.core :as p]))

(def experiment {:experiment "aa" :revision 1 :arms [:default :b]})

(defn- operation
  "The operation under test. Both arms run exactly this."
  [x]
  (p/resolved (* 2 x)))

(defn- transport
  "A decider and reporter for `srv`, either calling it directly or going
  through the HTTP handlers and JSON."
  [srv mode]
  (case mode
    :in-process {:decider (client/in-process-decider srv)
                 :reporter (client/in-process-reporter srv)}
    :http (let [handlers {"decide" (http/decide-handler srv)
                          "feedback" (http/feedback-handler srv)}
                post (fn [url body _] ((get handlers url) {:body body}))]
            {:decider (http/decider "decide" {:post post})
             :reporter (http/reporter "feedback" {:post post})})))

(defn- run-aa
  "Runs `n` sticky keys through a client and server with `:b` at `weight`.
  Returns a promise of the arms chosen and the recorded store."
  [mode weight n]
  (let [store (atom [])
        srv (server/server {:config-source (memory/config-source {"aa" {:revision 1 :weights {:b weight} :sticky :hashed}})
                            :recorder (memory/recorder store)})
        {:keys [decider reporter]} (transport srv mode)
        pending (atom [])
        tracked-reporter (fn [batch]
                           (let [pr (reporter batch)]
                             (swap! pending conj pr)
                             pr))]
    (p/let [arms (p/all
                  (for [i (range n)]
                    (p/let [decisions (client/decide decider [experiment] {:sticky-key (str "user:" i) :timeout-ms 30000})
                            c {:decisions decisions :reporter tracked-reporter}
                            {:keys [exposure-id]} (client/branch c experiment {:default (constantly :default) :b (constantly :b)})
                            arm (get-in decisions ["aa" :arm])
                            v (client/timed c {:exposure-id exposure-id :metric "aa.op"} operation i)]
                      (when-not (= (* 2 i) v)
                        (throw (ex-info "operation result changed" {:i i :v v})))
                      arm)))
            _ (p/all @pending)]
      {:arms arms :store @store})))

(defn- joins? [store]
  (let [exposures (set (map :exposure-id (memory/exposures store)))
        feedback (set (mapcat :exposure-ids (memory/feedback store)))]
    (and (seq exposures) (= exposures feedback))))

(defn- check-split [mode]
  (p/let [n 20000
          {:keys [arms store]} (run-aa mode 50 n)
          share (* 100.0 (/ (count (filter #{:b} arms)) n))]
    (is (= n (count arms)) (str mode ": every key got a decision"))
    (is (<= 48.5 share 51.5) (str mode ": :b share " share " is within 1.5 points of 50"))
    (is (= n (count (memory/exposures store))) (str mode ": one exposure per decision"))
    (is (= n (count (memory/feedback store))) (str mode ": one feedback per operation"))
    (is (joins? store) (str mode ": every exposure joins to its feedback"))))

(defn- check-zero [mode]
  (p/let [{:keys [arms store]} (run-aa mode 0 2000)]
    (is (= #{:default} (set arms)) (str mode ": 0% gives no :b"))
    (is (joins? store) (str mode ": :default exposures join to their feedback too"))))

(defasynctest aa-in-process
  (p/do (check-split :in-process)
        (check-zero :in-process)))

(defasynctest aa-over-http-handlers
  (p/do (check-split :http)
        (check-zero :http)))
