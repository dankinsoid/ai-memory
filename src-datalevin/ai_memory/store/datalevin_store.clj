;; @ai-generated(guided)
(ns ai-memory.store.datalevin-store
  "FactStore protocol implementation backed by Datalevin.
   Mirrors datomic_store.clj but uses Datalevin's Datalog API.

   Key differences from Datomic implementation:
   - d/transact! is synchronous (no deref needed)
   - Tempids are negative integers (no d/tempid)
   - No d/squuid — uses java.util.UUID/randomUUID
   - d/pull and d/q are largely compatible but some forms differ
   - Entity retraction uses :db/retractEntity (same syntax)"
  (:require [datalevin.core :as d]
            [ai-memory.store.protocols :as p]
            [ai-memory.db.datalevin :as db])
  (:import [java.util Date UUID]))

(def ^:private node-pull-spec
  [:db/id :node/content :node/weight :node/cycle :node/sources
   :node/blob-dir :node/created-at :node/updated-at :node/session-id :node/tags])

(defn- now [] (Date.))

(defrecord DatalevinStore [conn]
  p/FactStore

  ;; --- Nodes ---

  (create-node! [_ {:keys [content tags blob-dir sources session-id]}]
    (let [db     (d/db conn)
          tick   (db/next-tick db)
          tempid -1
          ts     (now)
          tx     (cond-> {:db/id           tempid
                          :node/content    content
                          :node/weight     0.0
                          :node/cycle      tick
                          :node/created-at ts
                          :node/updated-at ts}
                   (seq tags)    (assoc :node/tags (vec tags))
                   blob-dir      (assoc :node/blob-dir blob-dir)
                   (seq sources) (assoc :node/sources sources)
                   session-id    (assoc :node/session-id session-id))
          result (db/transact! conn [tx] tick)
          eid    (get (:tempids result) tempid)]
      {:id eid}))

  (find-node [_ eid]
    (let [node (d/pull (d/db conn) node-pull-spec eid)]
      (when (:node/content node) node)))

  (find-node-by-content [_ content]
    (let [db  (d/db conn)
          eid (d/q '[:find ?e .
                     :in $ ?content
                     :where
                     [?e :node/content ?content]
                     [?e :node/tags "entity"]]
                   db content)]
      (when eid (d/pull db '[*] eid))))

  (update-node-content! [_ eid new-content]
    (db/transact! conn [{:db/id eid :node/content new-content :node/updated-at (now)}]))

  (update-node-tags! [_ eid tags]
    (when (seq tags)
      (db/transact! conn [{:db/id           eid
                           :node/tags       (vec tags)
                           :node/updated-at (now)}])))

  (replace-node-tags! [_ eid new-tag-names]
    (let [db            (d/db conn)
          current-names (set (d/q '[:find [?name ...]
                                    :in $ ?eid
                                    :where [?eid :node/tags ?name]]
                                  db eid))
          new-names-set (set new-tag-names)
          to-add        (remove current-names new-names-set)
          to-remove     (remove new-names-set current-names)
          retract-txs   (mapv #(vector :db/retract eid :node/tags %) to-remove)
          add-txs       (mapv #(vector :db/add eid :node/tags %) to-add)]
      (db/transact! conn (conj (into retract-txs add-txs)
                                {:db/id eid :node/updated-at (now)}))))

  (update-node-weight! [_ eid weight]
    (let [db   (d/db conn)
          tick (db/next-tick db)]
      (db/transact! conn [{:db/id           eid
                           :node/weight     (double weight)
                           :node/cycle      tick
                           :node/updated-at (now)}]
                    tick)))

  (set-node-blob-dir! [_ eid blob-dir]
    (db/transact! conn [{:db/id eid :node/blob-dir blob-dir}]))

  (delete-node! [_ eid]
    (let [db         (d/db conn)
          edges-from (d/q '[:find [?e ...] :in $ ?n :where [?e :edge/from ?n]] db eid)
          edges-to   (d/q '[:find [?e ...] :in $ ?n :where [?e :edge/to   ?n]] db eid)
          edge-eids  (distinct (concat edges-from edges-to))
          tx-data    (-> (mapv #(vector :db/retractEntity %) edge-eids)
                         (conj [:db/retractEntity eid]))]
      (db/transact! conn tx-data)))

  (find-recent-nodes [_ min-tick]
    (d/q '[:find [?e ...]
           :in $ ?min-tick
           :where
           [?e :node/cycle ?c]
           [(>= ?c ?min-tick)]
           [?e :node/content]]
         (d/db conn) min-tick))

  (find-blob-nodes [_ {:keys [limit] :or {limit 20}}]
    (let [db      (d/db conn)
          results (d/q '[:find [(pull ?n pull-expr) ...]
                         :in $ pull-expr
                         :where [?n :node/blob-dir _]]
                       db node-pull-spec)]
      (->> results
           (sort-by :node/created-at #(compare %2 %1))
           (take limit)
           vec)))

  (all-nodes [_]
    (d/q '[:find [?n ...] :where [?n :node/updated-at]] (d/db conn)))

  (reset-nodes! [_]
    (let [db        (d/db conn)
          node-eids (d/q '[:find [?e ...] :where [?e :node/content]] db)
          edge-eids (d/q '[:find [?e ...] :where [?e :edge/id]] db)
          tag-names (d/q '[:find [?name ...] :where [?t :tag/name ?name]] db)
          tx-data   (-> []
                        (into (map #(vector :db/retractEntity %) edge-eids))
                        (into (map #(vector :db/retractEntity %) node-eids))
                        (into (map #(hash-map :tag/name % :tag/node-count 0) tag-names)))]
      (when (seq tx-data)
        (db/transact! conn tx-data))))

  ;; --- Tags ---

  (ensure-tag! [_ tag-name]
    (if (d/entid (d/db conn) [:tag/name tag-name])
      false
      (do (db/transact! conn [{:tag/name tag-name}])
          true)))

  (all-tags [_]
    (d/q '[:find [(pull ?t [:tag/name :tag/node-count :tag/tier]) ...]
           :where [?t :tag/name]]
         (d/db conn)))

  (find-nodes-by-tag [_ tag-name {:keys [since until]}]
    (let [db   (d/db conn)
          eids (if (or since until)
                 (let [where-cls (cond-> [['?n :node/tags '?name]
                                          ['?n :node/updated-at '?updated]]
                                   since (conj '[(>= ?updated ?since)])
                                   until (conj '[(< ?updated ?until)]))
                       in-cls    (cond-> '($ ?name)
                                   since (conj '?since)
                                   until (conj '?until))
                       query     {:find '[[?n ...]] :in (vec in-cls) :where where-cls}
                       args      (cond-> [db tag-name] since (conj since) until (conj until))]
                   (apply d/q query args))
                 (d/q '[:find [?n ...] :in $ ?name :where [?n :node/tags ?name]]
                      db tag-name))]
      (mapv #(d/pull db node-pull-spec %) eids)))

  (find-nodes-by-tags [_ tags {:keys [since until]}]
    (let [db        (d/db conn)
          where-cls (cond-> (into [] (mapcat (fn [name] [['?n :node/tags name]]) tags))
                      (or since until) (conj ['?n :node/updated-at '?updated])
                      since            (conj '[(>= ?updated ?since)])
                      until            (conj '[(< ?updated ?until)]))
          in-cls    (cond-> '[$] since (conj '?since) until (conj '?until))
          args      (cond-> [db] since (conj since) until (conj until))
          query     {:find '[[?n ...]] :in (vec in-cls) :where where-cls}
          eids      (apply d/q query args)]
      (mapv #(d/pull db node-pull-spec %) eids)))

  (find-nodes-by-any-tags [_ tags]
    (let [db   (d/db conn)
          eids (d/q '[:find [?n ...] :in $ [?name ...] :where [?n :node/tags ?name]]
                    db tags)]
      (mapv #(d/pull db node-pull-spec %) eids)))

  (find-nodes-by-session [_ session-id]
    (let [db  (d/db conn)
          eid (d/q '[:find ?e . :in $ ?sid :where [?e :node/session-id ?sid]]
                   db session-id)]
      (when eid (d/pull db node-pull-spec eid))))

  (find-node-by-eid [_ id]
    (let [eid  (cond (number? id) (long id) (string? id) (parse-long id))
          node (when eid (d/pull (d/db conn) node-pull-spec eid))]
      (when (:node/content node) node)))

  (find-node-by-blob-dir [_ blob-dir]
    (let [db  (d/db conn)
          eid (d/q '[:find ?e . :in $ ?bd :where [?e :node/blob-dir ?bd]]
                   db blob-dir)]
      (when eid (d/pull db node-pull-spec eid))))

  (find-nodes-by-date [_ since until]
    (let [db        (d/db conn)
          where-cls (cond-> [['?n :node/updated-at '?updated]]
                      since (conj '[(>= ?updated ?since)])
                      until (conj '[(< ?updated ?until)]))
          in-cls    (cond-> '[$] since (conj '?since) until (conj '?until))
          args      (cond-> [db] since (conj since) until (conj until))
          query     {:find '[[?n ...]] :in (vec in-cls) :where where-cls}
          eids      (apply d/q query args)]
      (mapv #(d/pull db node-pull-spec %) eids)))

  (count-nodes-by-tag-sets [_ tag-sets]
    (let [db (d/db conn)]
      (mapv (fn [tags]
              {:tags  tags
               :count (if (= 1 (count tags))
                        (or (d/q '[:find (count ?n) .
                                   :in $ ?name
                                   :where [?n :node/tags ?name]]
                                 db (first tags))
                            0)
                        (let [where-cls (into [] (mapcat #(vector ['?n :node/tags %]) tags))
                              query {:find '[(count ?n) .] :in '[$] :where where-cls}]
                          (or (d/q query db) 0)))})
            tag-sets)))

  (node-tags [_ eid]
    (d/q '[:find [?name ...] :in $ ?eid :where [?eid :node/tags ?name]]
         (d/db conn) eid))

  (reconcile-tag-counts! [_]
    (db/recompute-tag-counts! conn))

  ;; --- Edges ---

  (create-edge! [_ {:keys [from to weight type]}]
    (let [tick (db/next-tick (d/db conn))]
      (db/transact! conn
        [(cond-> {:db/id       -1
                  :edge/id     (UUID/randomUUID)
                  :edge/from   from
                  :edge/to     to
                  :edge/weight (or weight 0.0)
                  :edge/cycle  tick}
           type (assoc :edge/type type))]
        tick)))

  (find-edges-from [_ from-eid]
    (d/q '[:find [(pull ?e [* {:edge/to [:db/id :node/content]}]) ...]
           :in $ ?from-eid
           :where [?e :edge/from ?from-eid]]
         (d/db conn) from-eid))

  (find-edge-between [_ from-eid to-eid]
    (d/q '[:find [?eid ?w]
           :in $ ?from-eid ?to-eid
           :where
           [?e :edge/from ?from-eid]
           [?e :edge/to ?to-eid]
           [?e :edge/id ?eid]
           [?e :edge/weight ?w]]
         (d/db conn) from-eid to-eid))

  (find-typed-edge-from [_ from-eid edge-type]
    (d/q '[:find (pull ?e [:edge/id :edge/weight {:edge/to [:db/id :node/content :node/blob-dir :node/session-id]}]) .
           :in $ ?from-eid ?etype
           :where [?e :edge/from ?from-eid] [?e :edge/type ?etype]]
         (d/db conn) from-eid edge-type))

  (update-edge-weight! [_ edge-id weight]
    (let [db   (d/db conn)
          tick (db/next-tick db)]
      (db/transact! conn [{:edge/id edge-id :edge/weight (double weight) :edge/cycle tick}] tick)))

  (promote-edge-eternal! [_ edge-id]
    (let [db   (d/db conn)
          tick (db/next-tick db)]
      (db/transact! conn [{:edge/id edge-id :edge/weight 1.0 :edge/cycle tick}] tick)))

  (all-edges [_]
    (d/q '[:find [(pull ?e [:edge/id :edge/weight :edge/cycle
                             {:edge/from [:db/id]}
                             {:edge/to   [:db/id]}]) ...]
           :where [?e :edge/id]]
         (d/db conn)))

  ;; --- System ---

  (current-tick [_]
    (db/current-tick (d/db conn)))

  (next-tick! [_]
    (let [db   (d/db conn)
          tick (db/next-tick db)]
      (db/transact! conn [] tick)
      tick))

  ;; --- Migration ---

  (set-tick! [_ tick]
    (let [db (d/db conn)]
      (if-let [eid (d/entid db :tick/singleton)]
        (d/transact! conn [{:db/id eid :tick/value tick}])
        (d/transact! conn [{:db/ident :tick/singleton :tick/value tick}]))))

  (import-node! [_ {:keys [content weight cycle tags blob-dir sources session-id
                            created-at updated-at]}]
    (let [tempid -1
          ts     (now)
          tx     (cond-> {:db/id           tempid
                          :node/content    content
                          :node/weight     (or weight 0.0)
                          :node/cycle      (or cycle 0)
                          :node/created-at (or created-at ts)
                          :node/updated-at (or updated-at ts)}
                   (seq tags)    (assoc :node/tags (vec tags))
                   blob-dir      (assoc :node/blob-dir blob-dir)
                   (seq sources) (assoc :node/sources sources)
                   session-id    (assoc :node/session-id session-id))
          ;; Raw transact — no tick auto-increment
          result (d/transact! conn [tx])]
      {:id (get (:tempids result) tempid)}))

  (import-edge! [_ {:keys [from to weight cycle type]}]
    (let [tx (cond-> {:db/id       -1
                      :edge/id     (UUID/randomUUID)
                      :edge/from   from
                      :edge/to     to
                      :edge/weight (or weight 0.0)
                      :edge/cycle  (or cycle 0)}
               type (assoc :edge/type type))]
      (d/transact! conn [tx])))

  (update-node-cycle! [_ eid cycle]
    (d/transact! conn [{:db/id eid :node/cycle cycle}]))

  (update-edge-cycle! [_ edge-id cycle]
    (d/transact! conn [{:edge/id edge-id :edge/cycle cycle}])))

(defn create
  "Creates a DatalevinStore wrapping the given Datalevin connection."
  [conn]
  (->DatalevinStore conn))
