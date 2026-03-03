(ns ai-memory.db.migrate
  "One-time migrations. Each migration is idempotent — safe to run multiple times."
  (:require [datomic.api :as d]
            [clojure.tools.logging :as log]))

;; --- Migration: project tags → :node/project attribute ---
;; Nodes created before this change stored the project name as a tag.
;; This migration moves project names to :node/project and removes the tag.

(defn- project-names-from-db
  "Returns set of all project names found in project-summary nodes.
   Project summary nodes have tag 'project' plus the project-name tag."
  [db]
  (let [;; All nodes with the 'project' tag
        project-eids (d/q '[:find [?e ...]
                             :where [?t :tag/name "project"]
                                    [?e :node/tag-refs ?t]]
                           db)
        ;; For each, collect all their tag names except "project" and "session"
        skip-tags    #{"project" "session"}]
    (->> project-eids
         (mapcat (fn [eid]
                   (d/q '[:find [?name ...]
                          :in $ ?e
                          :where [?e :node/tag-refs ?t]
                                 [?t :tag/name ?name]]
                        db eid)))
         (remove skip-tags)
         set)))

(defn- nodes-with-project-tag
  "Returns [{:eid e :project-name p}] for nodes that have a project-name tag.
   Skips nodes that already have :node/project set."
  [db known-projects]
  (mapcat
    (fn [project-name]
      (let [eids (d/q '[:find [?e ...]
                        :in $ ?pname
                        :where [?t :tag/name ?pname]
                               [?e :node/tag-refs ?t]
                               (not [?e :node/project])]
                      db project-name)]
        (map (fn [eid] {:eid eid :project-name project-name}) eids)))
    known-projects))

(defn migrate-project-tags!
  "Moves project name from tags to :node/project for all existing nodes.
   Idempotent: skips nodes that already have :node/project set.
   Returns {:migrated N :project-names [...]}."
  [conn]
  (let [db            (d/db conn)
        project-names (project-names-from-db db)
        to-migrate    (nodes-with-project-tag db project-names)]
    (log/info "migrate-project-tags!" {:project-names project-names
                                       :nodes-to-migrate (count to-migrate)})
    (doseq [{:keys [eid project-name]} to-migrate]
      (log/debug "migrating node" eid "project" project-name)
      @(d/transact conn
         [[:db/add    eid :node/project project-name]
          [:db/retract eid :node/tag-refs [:tag/name project-name]]
          [:fn/inc-tag-count project-name -1]]))
    {:migrated      (count to-migrate)
     :project-names (vec project-names)}))
