(ns fieldtest.nbb-test-runner
  "Finds every `*_test.clj[cs]` namespace under test/, runs them
  with cljs.test and exits non-zero on any failure or error."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [cljs.test :as t]
   [clojure.string :as str]
   [nbb.core :as nbb]
   [promesa.core :as p]))

(def ^:private skipped
  "Namespaces nbb can't load, and why."
  {'fieldtest.selector-properties-test "test.check needs goog.math.Long, which nbb lacks"})

(defn- test-files [dir]
  (when (fs/existsSync dir)
    (->> (fs/readdirSync dir #js {:recursive true})
         (map str)
         (filter #(re-find #"_test\.clj[cs]$" %))
         (map #(path/join dir %)))))

(defn- file->ns [root file]
  (-> (path/relative root file)
      (str/replace #"\.clj[cs]$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn -main [& _]
  (let [nss (->> ["test"]
                 (mapcat (fn [root] (map #(file->ns root %) (test-files root))))
                 (remove skipped)
                 sort)]
    (doseq [[n why] skipped]
      (println "Skipping" n "-" why))
    (p/do
      (p/doseq [n nss]
        (nbb/await (nbb/load-string (str "(require '" n ")"))))
      (let [done (p/deferred)]
        (defmethod t/report [::t/default :end-run-tests] [m]
          (p/resolve! done m))
        (apply t/run-tests nss)
        (p/let [{:keys [fail error]} done]
          (when (pos? (+ fail error))
            (set! (.-exitCode js/process) 1)))))))
