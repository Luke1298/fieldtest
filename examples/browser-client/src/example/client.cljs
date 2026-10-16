(ns example.client
  "Fetches decisions from the ring-server example, branches an image load on
  them, and reports how long the chosen image took to fetch."
  (:require
   [fieldtest.client :as client]
   [fieldtest.http :as http]
   [promesa.core :as p]))

(def server "http://localhost:8723")

(def image-format {:experiment "image-format" :revision 1 :arms [:default :svg]})

(defn- sticky-key
  "A random id kept in localStorage, so this browser keeps its arm."
  []
  (let [k "fieldtest-example-key"]
    (or (try (.getItem js/localStorage k) (catch :default _ nil))
        (let [id (str "browser:" (random-uuid))]
          (try (.setItem js/localStorage k id) (catch :default _ nil))
          id))))

(defn- fetch-image
  "Fetches `url` and shows it. Rejects on a non-2xx status."
  [url]
  (p/let [resp (js/fetch url)
          _ (when-not (.-ok resp) (throw (ex-info (str "HTTP " (.-status resp)) {:url url})))
          blob (.blob resp)]
    (set! (.-src (js/document.getElementById "asset")) (js/URL.createObjectURL blob))
    url))

(defn- status! [text]
  (set! (.-textContent (js/document.getElementById "status")) text))

(defn init []
  (p/let [decisions (client/decide (http/decider (str server "/decide")) [image-format]
                                   {:sticky-key (sticky-key)
                                    :context {:runtime "browser"}
                                    :timeout-ms 500})
          c {:decisions decisions
             :reporter (http/reporter (str server "/feedback"))
             :on-error #(js/console.warn "fieldtest:" %)}
          {:keys [arm exposure-id]} (client/decision c image-format)
          url (client/timed c {:exposure-id exposure-id :metric "image.fetch" :timeout-ms 5000
                               :attrs {:page "example"}}
                            #(:result (client/branch c image-format
                                                     {:default (fn [] (fetch-image "/asset.png"))
                                                      :svg (fn [] (fetch-image "/asset.svg"))})))]
    (status! (str "Arm " (or arm ":default (no decision)") " loaded " url
                  (when exposure-id (str ", exposure " exposure-id))))))
