(ns commando.build
  (:require
   [clojure.string          :as str]
   [clojure.tools.build.api :as b]))

(def lib 'funkcjonariusze/commando)

(def version
  (->> (slurp "CHANGELOG.md")
       (str/split-lines)
       first
       (re-find #"\d+\.\d+\.\d+")))

(def class-dir "target/classes")

(def jar-file (format "target/%s.jar" (name lib)))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn jar
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))
