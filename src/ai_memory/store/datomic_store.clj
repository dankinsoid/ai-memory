(ns ai-memory.store.datomic-store
  (:require [datomic.api :as d]
            [ai-memory.store.protocols :as p]
            [ai-memory.db.core :as db-core])
  (:import [java.util Date]))

(def ^:private node-pull-spec
  [:db/id :node/content :node/weight :node/cycle :node/sources
   :node/blob-dir :node/updated-at :node/tags])

(defn- now [] (Date.))

(defn- apply-score
  "Asymptotic approach to 1.0 for positive score; linear decrease for negative.
   current ∈ [0.0, 1.0). Result never reaches 1.0 via regular reinforce."
  [current score factor]
  (if (pos? score)
    (+ current (* score factor (- 1.0 current)))
    (max 0.0 (+ current (* score factor)))))

(defrecord DatomicStore [conn]
  p/FactStore

  ;; --- Nodes ---

  (create-node! [_ {:keys [content tags blob-dir sources session-id]}]
    (let [db     (d/db conn)
          tick   (db-core/next-tick db)
          tempid (d/tempid :db.part/user)
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
          result (db-core/transact! conn [tx] tick)
          eid    (d/resolve-tempid (:db-after result) (:tempids result) tempid)]
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
    (db-core/transact! conn [[:db/add eid :node/content new-content]
                              [:db/add eid :node/updated-at (now)]]))

  (update-node-tags! [_ eid tags]
    (when (seq tags)
      (db-core/transact! conn [{:db/id           eid
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
      (db-core/transact! conn (conj (into retract-txs add-txs)
                                     [:db/add eid :node/updated-at (now)]))))

  (set-node-weight! [_ eid weight]
    (let [w (max 0.0 (min 1.0 (double weight)))]
      (db-core/transact! conn [[:db/add eid :node/weight w]
                                [:db/add eid :node/updated-at (now)]])))

  (set-node-blob-dir! [_ eid blob-dir]
    (db-core/transact! conn [[:db/add eid :node/blob-dir blob-dir]]))

  (reinforce-node! [_ eid content score factor]
    (let [db             (d/db conn)
          tick           (db-core/next-tick db)
          current-weight (or (:node/weight (d/pull db [:node/weight] eid)) 0.0)
          new-weight     (apply-score current-weight score factor)]
      (db-core/transact! conn [[:db/add eid :node/content    content]
                                [:db/add eid :node/weight     new-weight]
                                [:db/add eid :node/cycle      tick]
                                [:db/add eid :node/updated-at (now)]]
                          tick)))

  (reinforce-weight! [_ eid score factor]
    (let [db             (d/db conn)
          tick           (db-core/next-tick db)
          current-weight (or (:node/weight (d/pull db [:node/weight] eid)) 0.0)
          new-weight     (apply-score current-weight score factor)]
      (db-core/transact! conn [[:db/add eid :node/weight     new-weight]
                                [:db/add eid :node/cycle      tick]
                                [:db/add eid :node/updated-at (now)]]
                          tick)))

  (delete-node! [_ eid]
    (let [db         (d/db conn)
          edges-from (d/q '[:find [?e ...] :in $ ?n :where [?e :edge/from ?n]] db eid)
          edges-to   (d/q '[:find [?e ...] :in $ ?n :where [?e :edge/to   ?n]] db eid)
          edge-eids  (distinct (concat edges-from edges-to))
          tx-data    (-> (mapv #(vector :db/retractEntity %) edge-eids)
                         (conj [:db/retractEntity eid]))]
      (db-core/transact! conn tx-data)))

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
                        (into (map #(vector :db/add [:tag/name %] :tag/node-count 0) tag-names)))]
      (when (seq tx-data)
        (db-core/transact! conn tx-data))))

  ;; --- Tags ---

  (ensure-tag! [_ tag-name]
    (if (d/entid (d/db conn) [:tag/name tag-name])
      false
      (do (db-core/transact! conn [{:tag/name tag-name}])
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
    (db-core/recompute-tag-counts! conn))

  ;; --- Edges ---

  (create-edge! [_ {:keys [from to weight type]}]
    (let [tick (db-core/next-tick (d/db conn))]
      (db-core/transact! conn
        [(cond-> {:db/id       (d/tempid :db.part/user)
                  :edge/id     (d/squuid)
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
    (d/q '[:find ?eid .
           :in $ ?from-eid ?to-eid
           :where
           [?e :edge/from ?from-eid]
           [?e :edge/to ?to-eid]
           [?e :edge/id ?eid]]
         (d/db conn) from-eid to-eid))

  (find-typed-edge-from [_ from-eid edge-type]
    (d/q '[:find (pull ?e [:edge/id :edge/weight {:edge/to [:db/id :node/content :node/blob-dir :node/session-id]}]) .
           :in $ ?from-eid ?etype
           :where [?e :edge/from ?from-eid] [?e :edge/type ?etype]]
         (d/db conn) from-eid edge-type))

  (find-or-create-edge! [this from-eid to-eid initial-weight opts]
    (let [existing (p/find-edge-between this from-eid to-eid)]
      (if existing
        (p/strengthen-edge! this existing 1.0 initial-weight)
        (p/create-edge! this (cond-> {:from from-eid :to to-eid :weight initial-weight}
                               (:type opts) (assoc :type (:type opts)))))))

  (strengthen-edge! [_ edge-id score factor]
    (let [db      (d/db conn)
          tick    (db-core/next-tick db)
          current (or (:edge/weight (d/pull db [:edge/weight] [:edge/id edge-id])) 0.0)
          new-w   (apply-score current score factor)]
      (db-core/transact! conn [{:edge/id edge-id :edge/weight new-w :edge/cycle tick}] tick)))

  (promote-edge-eternal! [_ edge-id]
    (let [db   (d/db conn)
          tick (db-core/next-tick db)]
      (db-core/transact! conn [{:edge/id edge-id :edge/weight 1.0 :edge/cycle tick}] tick)))

  (all-edges [_]
    (d/q '[:find [(pull ?e [:edge/id :edge/weight :edge/cycle
                             {:edge/from [:db/id]}
                             {:edge/to   [:db/id]}]) ...]
           :where [?e :edge/id]]
         (d/db conn)))

  ;; --- System ---

  (current-tick [_]
    (db-core/current-tick (d/db conn)))

  (next-tick! [_]
    (let [db   (d/db conn)
          tick (db-core/next-tick db)]
      (db-core/transact! conn [] tick)
      tick)))

(defn create [conn]
  (->DatomicStore conn))
