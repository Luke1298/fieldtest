(ns fieldtest.http-jvm-test
  "The HTTP decider and reporter against a real HTTP server serving the
  handlers."
  (:require
   [clojure.test :refer [deftest is]]
   [fieldtest.client :as client]
   [fieldtest.http :as http]
   [fieldtest.memory :as memory]
   [fieldtest.server :as server])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.net InetSocketAddress)))

(defn- mount [handler]
  (reify HttpHandler
    (handle [_ exchange]
      (let [^HttpExchange exchange exchange
            {:keys [status headers body]} @(handler {:body (.getRequestBody exchange)})
            bs (.getBytes ^String body "UTF-8")]
        (doseq [[k v] headers]
          (.add (.getResponseHeaders exchange) k v))
        (.sendResponseHeaders exchange status (alength bs))
        (with-open [out (.getResponseBody exchange)]
          (.write out bs))))))

(deftest over-real-http
  (let [store (atom [])
        srv (server/server {:config-source (memory/config-source {"asset-host" {:revision 3 :weights {:b 100} :sticky :hashed}})
                            :recorder (memory/recorder store)})
        http-server (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                      (.createContext "/decide" (mount (http/decide-handler srv)))
                      (.createContext "/feedback" (mount (http/feedback-handler srv)))
                      (.start))
        base (str "http://127.0.0.1:" (.getPort (.getAddress http-server)))
        exp {:experiment "asset-host" :revision 3 :arms [:a :b]}]
    (try
      (let [decisions @(client/decide (http/decider (str base "/decide")) [exp] {:sticky-key "u1" :timeout-ms 5000})
            c {:decisions decisions :reporter (http/reporter (str base "/feedback"))}
            {:keys [result exposure-id]} (client/branch c exp {:a (constantly :a) :b (constantly :b)})]
        (is (= :b result))
        @((:reporter c) [{:exposure-ids [exposure-id] :metric "load" :value 7 :value-unit :ms :outcome :ok
                          :attrs {} :recorded-at (java.util.Date.)}])
        (is (= [exposure-id] (map :exposure-id (memory/exposures @store))))
        (is (= [[exposure-id]] (map :exposure-ids (memory/feedback @store)))))
      (finally
        (.stop http-server 0)))))
