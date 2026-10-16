(ns fieldtest.json
  "JSON encoding for the HTTP transport and for decision handoff.

  Uses cheshire on the JVM and `js/JSON` in ClojureScript and nbb. cheshire is
  required only inside the `:clj` branch so that ClojureScript builds and nbb
  don't load it, and `bb lint:no-infra` checks this.

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
