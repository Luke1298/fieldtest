(ns fieldtest.http
  "Carries decide requests and feedback between clients and a server over HTTP.

  Every wire document is JSON with the same fields as its in-process form plus
  a format version, `\"v\": 1`. New versions only add fields, so readers ignore
  fields they don't know; a server rejects a version it doesn't know with 400.

  The handlers take `{:body ...}` and return a promise of `{:status :headers
  :body}`, so they mount in any web framework. Authentication, rate limiting
  and routing are left to the host."
  (:require
   [fieldtest.json :as json]
   [fieldtest.server :as server]
   [promesa.core :as p]))

(def version
  "The wire format version this library writes and reads."
  1)

(def default-max-body-bytes
  "Largest request body the handlers accept, in bytes."
  65536)

(def ^:private json-headers {"content-type" "application/json"})

(defn- respond [status body]
  {:status status
   :headers json-headers
   :body (json/encode (assoc body :v version))})

(defn- fail [status message]
  (respond status {:error message}))

;; Reading bodies

(defn- utf8-length [^String s]
  #?(:clj (alength (.getBytes s java.nio.charset.StandardCharsets/UTF_8))
     :cljs (.-length (.encode (js/TextEncoder.) s))))

(defn- read-body
  "The body as a string, or `::too-large` when it is over `limit` bytes.
  Accepts a string, and on the JVM also a byte array or InputStream (as Ring
  provides); in ClojureScript also a Uint8Array."
  [body limit]
  #?(:clj (cond
            (nil? body) ""
            (string? body) (if (> (utf8-length body) limit) ::too-large body)
            (bytes? body) (if (> (alength ^bytes body) limit)
                            ::too-large
                            (String. ^bytes body java.nio.charset.StandardCharsets/UTF_8))
            (instance? java.io.InputStream body)
            (let [bs (.readNBytes ^java.io.InputStream body (int (inc limit)))]
              (if (> (alength bs) limit)
                ::too-large
                (String. bs java.nio.charset.StandardCharsets/UTF_8)))
            :else (throw (ex-info "Unsupported body type" {:type ::bad-body})))
     :cljs (cond
             (nil? body) ""
             (string? body) (if (> (utf8-length body) limit) ::too-large body)
             (instance? js/Uint8Array body) (if (> (.-length body) limit)
                                              ::too-large
                                              (.decode (js/TextDecoder.) body))
             :else (throw (ex-info "Unsupported body type" {:type ::bad-body})))))

(defn- parse-document
  "`[:ok doc]` or `[:error status message]` for a request body."
  [body limit]
  (let [text (try (read-body body limit) (catch #?(:clj Exception :cljs :default) _ ::unreadable))]
    (cond
      (= ::too-large text) [:error 413 (str "Body is over " limit " bytes")]
      (= ::unreadable text) [:error 400 "Body could not be read"]
      :else
      (let [doc (try (json/decode text) (catch #?(:clj Exception :cljs :default) _ ::invalid))]
        (cond
          (= ::invalid doc) [:error 400 "Body is not valid JSON"]
          (not (map? doc)) [:error 400 "Body must be a JSON object"]
          (not= version (:v doc)) [:error 400 (str "Unknown format version: " (pr-str (:v doc)))]
          :else [:ok doc])))))

(defn- invalid-request? [e]
  (= ::server/invalid-request (:type (ex-data e))))

(defn- handle [server-call]
  (-> (try (p/promise (server-call)) (catch #?(:clj Throwable :cljs :default) e (p/rejected e)))
      (p/then (fn [body] (respond 200 body)))
      (p/catch (fn [e]
                 (if (invalid-request? e)
                   (fail 400 (:problem (ex-data e)))
                   (fail 500 "Internal error"))))))

;; Server handlers

(defn decide-handler
  "A handler for decide requests. Takes `{:body ...}` and returns a promise of
  `{:status :headers :body}` with a JSON decide response.

  Options: `:max-body-bytes` (default 65,536)."
  ([server] (decide-handler server {}))
  ([server {:keys [max-body-bytes] :or {max-body-bytes default-max-body-bytes}}]
   (fn [{:keys [body]}]
     (let [[status doc message] (parse-document body max-body-bytes)]
       (if (= :ok status)
         (handle #(server/decide server (select-keys doc [:sticky-key :context :experiments])))
         (p/resolved (fail doc message)))))))

(defn- parse-inst [s]
  (try
    #?(:clj (java.util.Date/from (java.time.Instant/parse s))
       :cljs (let [d (js/Date. s)] (when-not (js/isNaN (.getTime d)) d)))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- read-feedback
  "A wire feedback record with unknown fields dropped and `recorded-at` read
  as an inst. An unreadable time is dropped, so the server stamps its own."
  [m]
  (if (map? m)
    (let [m (select-keys m [:exposure-ids :metric :value :value-unit :outcome :attrs :recorded-at])
          t (:recorded-at m)
          t (if (string? t) (parse-inst t) t)]
      (if (inst? t) (assoc m :recorded-at t) (dissoc m :recorded-at)))
    m))

(defn feedback-handler
  "A handler for feedback batches, `{\"v\": 1, \"feedback\": [...]}`. Takes
  `{:body ...}` and returns a promise of `{:status :headers :body}`.

  Options: `:max-body-bytes` (default 65,536)."
  ([server] (feedback-handler server {}))
  ([server {:keys [max-body-bytes] :or {max-body-bytes default-max-body-bytes}}]
   (fn [{:keys [body]}]
     (let [[status doc message] (parse-document body max-body-bytes)]
       (if (= :ok status)
         (let [batch (:feedback doc)]
           (handle #(server/feedback! server (if (sequential? batch) (mapv read-feedback batch) batch))))
         (p/resolved (fail doc message)))))))

;; Client side

#?(:clj
   (defonce ^:private http-client
     (delay (java.net.http.HttpClient/newHttpClient))))

(defn- default-post
  "POSTs `body` to `url` and returns a promise of `{:status :body}`."
  [url body headers]
  #?(:clj (let [builder (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                            (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body)))]
            (doseq [[k v] (merge json-headers headers)]
              (.header builder (name k) (str v)))
            (-> (.sendAsync ^java.net.http.HttpClient @http-client (.build builder)
                            (java.net.http.HttpResponse$BodyHandlers/ofString))
                (p/then (fn [^java.net.http.HttpResponse resp]
                          {:status (.statusCode resp) :body (.body resp)}))))
     ;; Looked up on each call, so a fetch installed after load (a polyfill
     ;; or a test stub) is used.
     :cljs (p/let [resp (.fetch js/globalThis url (clj->js {:method "POST"
                                                            :headers (merge json-headers headers)
                                                            :body body}))
                   text (.text resp)]
             {:status (.-status resp) :body text})))

(defn- post-json [{:keys [post headers]} url doc]
  (p/let [{:keys [status body]} ((or post default-post) url (json/encode (assoc doc :v version)) (or headers {}))]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "HTTP " status " from " url) {:type ::http-error :status status :body body})))
    (let [doc (json/decode body)]
      (when-not (and (map? doc) (= version (:v doc)))
        (throw (ex-info (str "Unexpected response from " url) {:type ::bad-response :body body})))
      doc)))

(defn decider
  "A decider that POSTs decide requests to `url`. Pass it to
  `fieldtest.client/decide`, which adds the timeout and fallback.

  Options: `:headers` to add to every request, and `:post`, a function
  `(fn [url body headers])` returning a promise of `{:status :body}`, to
  replace the built-in HTTP client (`js/fetch` in ClojureScript and nbb,
  `java.net.http.HttpClient` on the JVM)."
  ([url] (decider url {}))
  ([url opts]
   (fn [request]
     (p/let [doc (post-json opts url (select-keys request [:sticky-key :context :experiments]))]
       {:decisions (vec (:decisions doc))}))))

(defn reporter
  "A reporter that POSTs feedback batches to `url`. Takes the same options as
  `decider`."
  ([url] (reporter url {}))
  ([url opts]
   (fn [batch]
     (post-json opts url {:feedback (vec batch)}))))
