(ns example.server
  "Mounts fieldtest's decide and feedback handlers in http-kit, with an
  EDN-file config source and a recorder that appends JSON lines to a file."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [fieldtest.config :as config]
   [fieldtest.http :as http]
   [fieldtest.json :as json]
   [fieldtest.server :as server]
   [org.httpkit.server :as http-kit]))

(defn edn-file-config-source
  "A config source that reads experiment configs from an EDN file."
  [path]
  (fn [] (edn/read-string (slurp path))))

(defn json-lines-recorder
  "A recorder that appends one JSON object per record to `path`."
  [path]
  (let [lock (Object.)]
    (fn [kind record]
      (let [line (str (json/encode {:kind kind :record record}) "\n")]
        (locking lock
          (spit path line :append true))))))

(def ^:private cors
  ;; The browser-client example is served from another port. A real host
  ;; would restrict this to its own origins.
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST, OPTIONS"
   "access-control-allow-headers" "content-type"})

(defn app [srv]
  (let [routes {"/decide" (http/decide-handler srv)
                "/feedback" (http/feedback-handler srv)}]
    (fn [{:keys [request-method uri body]}]
      (let [handler (get routes uri)]
        (cond
          (nil? handler) {:status 404 :headers cors :body "not found"}
          (= :options request-method) {:status 204 :headers cors}
          (not= :post request-method) {:status 405 :headers cors :body "POST only"}
          :else (update @(handler {:body body}) :headers merge cors))))))

(defn -main [& [port]]
  (let [port (Long/parseLong (or port "8723"))
        records "records.jsonl"
        srv (server/server {:config-source (config/cached (edn-file-config-source "experiments.edn") {:ttl-ms 2000})
                            :recorder (json-lines-recorder records)
                            :error-hook (fn [{:keys [plugin error]}]
                                          (println "plug-in failure:" plugin (ex-message error)))})]
    (http-kit/run-server (app srv) {:port port})
    (println (format "fieldtest example server on http://localhost:%d (records -> %s)"
                     port (.getAbsolutePath (io/file records))))))
