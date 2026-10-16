(ns build
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.luke1298/fieldtest)
(def version "0.1.1")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def repo-url "https://github.com/Luke1298/fieldtest")

(defn clean [_]
  (b/delete {:path "target"}))

(defn- pom-data []
  [[:description "A/B testing for code paths in production, for Clojure, ClojureScript and nbb."]
   [:url repo-url]
   [:licenses
    [:license
     [:name "Eclipse Public License 2.0"]
     [:url "https://www.eclipse.org/legal/epl-2.0/"]]]
   [:scm
    [:url repo-url]
    [:connection "scm:git:https://github.com/Luke1298/fieldtest.git"]
    [:developerConnection "scm:git:ssh://git@github.com/Luke1298/fieldtest.git"]
    [:tag (str "v" version)]]])

(defn jar [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :pom-data (pom-data)})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/LICENSE")})
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (println "Wrote" jar-file)))

(defn install [_]
  (jar nil)
  (b/install {:basis (b/create-basis {:project "deps.edn"})
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy
  "Builds the jar and pushes it to Clojars. Reads CLOJARS_USERNAME and
  CLOJARS_PASSWORD (a deploy token) from the environment."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
