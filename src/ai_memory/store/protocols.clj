(ns ai-memory.store.protocols)

(defprotocol EmbeddingProvider
  (embed-query    [this text])
  (embed-document [this text])
  (embed-batch    [this texts]))

(defprotocol VectorStore
  (ensure-store!  [this dim])
  (upsert!        [this id vector payload])
  (search         [this query-vector top-k opts])
  (delete!        [this id])
  (delete-all!    [this])
  (store-info     [this]))

(defprotocol FactStore
  ;; Nodes
  (create-node!          [this node-data])
  (find-node             [this eid])
  (find-node-by-content  [this content])
  (update-node-content!  [this eid content])
  (update-node-tags!     [this eid tags])
  (replace-node-tags!    [this eid tag-names])
  (update-node-weight!   [this eid weight])
  (set-node-blob-dir!    [this eid blob-dir])
  (delete-node!          [this eid])
  (find-recent-nodes     [this min-tick])
  (find-blob-nodes       [this opts])
  (all-nodes             [this])
  (reset-nodes!          [this])
  ;; Tags
  (ensure-tag!           [this tag-name])
  (all-tags              [this])
  (find-nodes-by-tag     [this tag opts])
  (find-nodes-by-tags    [this tags opts])
  (find-nodes-by-any-tags [this tags])
  (find-nodes-by-session [this session-id])
  (find-node-by-eid      [this id])
  (find-node-by-blob-dir [this blob-dir])
  (find-nodes-by-date    [this since until])
  (count-nodes-by-tag-sets [this tag-sets])
  (node-tags             [this eid])
  (reconcile-tag-counts! [this])
  ;; Edges
  (create-edge!          [this edge-data])
  (find-edges-from       [this from-eid])
  (find-edge-between     [this from-eid to-eid])
  (find-typed-edge-from  [this from-eid edge-type])
  (update-edge-weight!   [this edge-id weight])
  (promote-edge-eternal! [this edge-id])
  (all-edges             [this])
  ;; System
  (current-tick          [this])
  (next-tick!            [this]))
