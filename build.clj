(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.Luke1298/fieldtest)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :pom-data [[:licenses
                              [:license
                               [:name "Eclipse Public License 2.0"]
                               [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
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
