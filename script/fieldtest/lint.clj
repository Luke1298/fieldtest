(ns fieldtest.lint
  "Repository checks that keep the library free of infrastructure."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [edamame.core :as e]))

(def ^:private banned-text
  "Strings that aren't allowed anywhere under src/, with the reason for each."
  [["System/getenv" "reads the environment"]
   ["System/getProperty" "reads system properties"]
   ["process.env" "reads the environment"]
   ["js/process" "reads the Node process"]
   ["amazonaws" "depends on AWS"]
   ["cognitect.aws" "depends on AWS"]
   ["next.jdbc" "depends on a database"]
   ["clojure.java.jdbc" "depends on a database"]
   ["java.sql" "depends on a database"]])

(def ^:private non-jvm-features
  "Reader features of the runtimes that must never load cheshire."
  [#{:cljs} #{:org.babashka/nbb :cljs}])

(defn- symbols [forms]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when (symbol? x) (vswap! acc conj x)) x) forms)
    @acc))

(defn- cheshire-sym? [sym]
  (or (some-> (namespace sym) (str/starts-with? "cheshire"))
      (str/starts-with? (name sym) "cheshire")))

(defn- parse [text features]
  (e/parse-string-all text {:all true
                            :read-cond :allow
                            :features features
                            :auto-resolve (fn [k] (if (= :current k) 'user k))}))

(defn file-problems
  "Problems in one source file, as strings."
  [path]
  (let [text (slurp (str path))
        textual (for [[needle why] banned-text
                      :when (str/includes? text needle)]
                  (format "%s: `%s` %s" path needle why))
        cheshire (for [features non-jvm-features
                       :when (some cheshire-sym? (symbols (parse text features)))]
                   (format "%s: cheshire is reachable with reader features %s; keep it inside #?(:clj ...)"
                           path features))]
    (concat textual (distinct cheshire))))

(defn no-infra!
  "Checks every source file under `dir` and exits non-zero on any problem."
  [dir]
  (let [problems (->> (fs/glob dir "**.{clj,cljc,cljs}")
                      (sort-by str)
                      (mapcat file-problems))]
    (doseq [p problems] (println p))
    (when (seq problems)
      (System/exit 1))
    (println "lint:no-infra: ok")))
