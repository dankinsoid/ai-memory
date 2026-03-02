(ns ai-memory.export.core
  "EDN export/import for Datalevin. Canonical format for backup and conflict resolution.
   Usage: clj -M:export [output-path]"
  (:require [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [datalevin.core :as d]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:gen-class))

(def ^:private node-pull-spec
  [:db/id :node/content :node/weight :node/cycle
   :node/created-at :node/updated-at
   :node/blob-dir :node/sources :node/session-id
   {:node/tag-refs [:tag/name]}])

(defn export-to-edn!
  "Exports all data to EDN file. Returns path written."
  [conn path]
  (let [db     (d/db conn)
        nodes  (d/q '[:find [(pull ?e pull-spec) ...]
                      :in $ pull-spec
                      :where [?e :node/content]]
                    db node-pull-spec)
        tags   (d/q '[:find [(pull ?t [:tag/name :tag/node-count :tag/tier]) ...]
                      :where [?t :tag/name]]
                    db)
        edges  (d/q '[:find [(pull ?e [:edge/id :edge/weight :edge/cycle :edge/type
                                       {:edge/from [:db/id]}
                                       {:edge/to   [:db/id]}]) ...]
                      :where [?e :edge/id]]
                    db)
        tick   (db/current-tick db)
        data   {:nodes nodes :tags tags :edges edges :tick tick}]
    (io/make-parents path)
    (spit path (pr-str data))
    (log/info "Exported" (count nodes) "nodes," (count tags) "tags," (count edges) "edges to" path)
    path))

(defn import-from-edn!
  "Imports data from EDN file into Datalevin. Used for conflict resolution / rebuild."
  [conn path]
  (let [{:keys [nodes tags edges tick]} (edn/read-string (slurp path))
        db (d/db conn)]
    ;; Tags first (nodes reference them)
    (doseq [tag tags]
      (when-not (d/entid db [:tag/name (:tag/name tag)])
        (d/transact! conn [{:tag/name      (:tag/name tag)
                            :tag/node-count (or (:tag/node-count tag) 0)
                            :tag/tier      (:tag/tier tag)}])))
    ;; Nodes (use content as stable identity — skip if exists)
    (doseq [node nodes]
      (let [existing (d/q '[:find ?e .
                             :in $ ?content
                             :where [?e :node/content ?content]]
                           (d/db conn) (:node/content node))]
        (when-not existing
          (d/transact! conn [(-> (dissoc node :db/id)
                                 (update :node/tag-refs
                                         #(mapv (fn [t] [:tag/name (:tag/name t)]) %)))]))))
    ;; Edges (use edge/id as stable identity)
    (doseq [edge edges]
      (let [existing (d/entid (d/db conn) [:edge/id (:edge/id edge)])]
        (when-not existing
          (let [from-content (get-in edge [:edge/from])
                to-content   (get-in edge [:edge/to])]
            ;; Edges reference nodes by :db/id which changes on import
            ;; Skip edge import for now — regenerate via normal usage
            (log/debug "Skipping edge import (IDs differ)" (:edge/id edge))))))
    ;; Update tick
    (when tick
      (d/transact! conn [{:db/id :tick/singleton :tick/value tick}]))
    (log/info "Import complete:" (count nodes) "nodes," (count tags) "tags")
    {:nodes-imported (count nodes) :tags-imported (count tags)}))

(defn -main [& args]
  (let [cfg  (config/load-config)
        conn (db/connect (:db-path cfg))
        _    (db/ensure-schema conn)
        path (or (first args)
                 (str (System/getProperty "user.home") "/.claude/ai-memory/export.edn"))]
    (export-to-edn! conn path)
    (println "Export written to:" path)
    (d/close conn)))
