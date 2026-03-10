;; @ai-generated(solo)
(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'ai-memory/ai-memory)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(def ^:private valid-backends #{:datomic :datalevin})

(defn- resolve-backend
  "Returns the backend keyword from opts, defaulting to :datomic.
   Accepts string or keyword via :backend key."
  [opts]
  (let [backend (some-> (:backend opts) name keyword)]
    (cond
      (nil? backend)                      :datomic
      (contains? valid-backends backend)  backend
      :else (throw (ex-info (str "Unknown backend: " backend
                                 ". Valid: " valid-backends)
                            {:backend backend})))))

(defn- make-basis
  "Creates a tools.build basis including the given backend alias.
   backend — :datomic or :datalevin"
  [backend]
  (b/create-basis {:project "deps.edn"
                   :aliases [backend]}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Build uberjar. Pass :backend \"datomic\" or \"datalevin\" (default: datomic).
   Example: clj -T:build uber :backend datomic"
  [opts]
  (let [backend (resolve-backend opts)
        basis   (make-basis backend)
        src-dir (str "src-" (name backend))]
    (clean nil)
    (b/copy-dir {:src-dirs ["src" src-dir "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis     basis
                    :ns-compile '[ai-memory.core]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      'ai-memory.core})))
