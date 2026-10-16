(ns fieldtest.json
  "JSON encoding for the wire and for decision handoff.

  cheshire on the JVM and `js/JSON` in ClojureScript and nbb. cheshire is only
  ever required inside the `:clj` branch, so ClojureScript builds and nbb never
  load it; `bb lint:no-infra` enforces that.

  Decoding keywordizes object keys. Keywords encode as their full name
  (`:a/b` -> \"a/b\") and insts as ISO-8601 strings with milliseconds."
  #?(:clj (:require [cheshire.core :as cheshire])))

#?(:clj (def ^:private encode-opts {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"}))

#?(:cljs
   (defn- kw-name [k]
     (subs (str k) 1)))

(defn encode
  "Encodes Clojure data as a JSON string."
  [x]
  #?(:clj (cheshire/generate-string x encode-opts)
     :cljs (js/JSON.stringify (clj->js x :keyword-fn kw-name))))

(defn decode
  "Decodes a JSON string, keywordizing object keys. Throws on invalid JSON."
  [s]
  #?(:clj (cheshire/parse-string-strict s true)
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))
