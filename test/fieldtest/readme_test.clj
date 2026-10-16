(ns fieldtest.readme-test
  "Runs the README's first Clojure example so it can't drift from the code."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- first-clojure-block [markdown]
  (second (re-find #"(?s)```clojure\n(.*?)```" markdown)))

(deftest readme-example-runs
  (let [code (first-clojure-block (slurp "README.md"))
        ns-name (gensym "readme-example")]
    (is (str/includes? code "client/branch"))
    (binding [*ns* (create-ns ns-name)]
      (refer-clojure)
      (load-string code))
    (let [result @(ns-resolve ns-name 'result)
          store @(ns-resolve ns-name 'store)
          exposures ((ns-resolve ns-name 'memory/exposures) @store)]
      (is (contains? #{"Hello, Ada" "Hi, Ada"} result))
      (is (= 1 (count exposures)))
      ;; Feedback is fire-and-forget; give it a moment to land.
      (loop [tries 50]
        (when (and (pos? tries) (empty? ((ns-resolve ns-name 'memory/feedback) @store)))
          (Thread/sleep 10)
          (recur (dec tries))))
      (is (= [[(:exposure-id (first exposures))]]
             (map :exposure-ids ((ns-resolve ns-name 'memory/feedback) @store)))))))
