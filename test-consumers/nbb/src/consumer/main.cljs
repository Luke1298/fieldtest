(ns consumer.main
  "A downstream nbb project that depends on fieldtest through :local/root, so
  the JVM-only cheshire jar and a promesa jar are on its classpath. It checks
  that JSON goes through js/JSON, that nbb's own promesa still works, and that
  decide and branch run. Exits non-zero on any failure."
  (:require
   [fieldtest.client :as client]
   [fieldtest.json :as json]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server]
   [promesa.core :as p]))

(defn- check [desc ok?]
  (println (if ok? "ok  " "FAIL") desc)
  (when-not ok? (set! (.-exitCode js/process) 1)))

(defn -main [& _]
  (let [store (atom [])
        srv (server/server {:config-source (memory/config-source {"asset-host" {:revision 3 :weights {:b 100} :sticky :hashed}})
                            :recorder (memory/recorder store)})
        exp {:experiment "asset-host" :revision 3 :arms [:default :b]}
        doc {:v 1 :decisions [{:experiment "x" :arm :b :at #inst "2026-10-16T12:00:00.000Z"}]}]
    (check "json round-trips through js/JSON"
           (= {:v 1 :decisions [{:experiment "x" :arm "b" :at "2026-10-16T12:00:00.000Z"}]}
              (json/decode (json/encode doc))))
    (check "cheshire is not loaded" (not (find-ns 'cheshire.core)))
    (check "nbb's built-in promesa is used, not the jar's source"
           (nil? (:file (meta (resolve 'promesa.core/let)))))
    (-> (p/let [decisions (client/decide (client/in-process-decider srv) [exp] {:sticky-key "u1"})
                {:keys [result exposure-id]} (client/branch {:decisions decisions} exp {:default (constantly :default) :b (constantly :b)})
                handed-off (client/json->decisions (client/decisions->json decisions))]
          (check "decide and branch run the decided arm" (= :b result))
          (check "the exposure was recorded" (= [exposure-id] (map :exposure-id (memory/exposures @store))))
          (check "decisions hand off through JSON" (= decisions handed-off)))
        (p/catch (fn [e]
                   (check (str "no errors: " (ex-message e)) false))))))
