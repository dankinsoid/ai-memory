(ns ai-memory.store.protocols)

;; Malli schemas: see ai-memory.store.schema for canonical type definitions.
;; Docstrings below use shorthand; schema ns is the source of truth.

(defprotocol EmbeddingProvider
  "Converts text into dense float vectors for similarity search.
   See schema/EmbeddingVector."

  (embed-query [this text]
    "Embed a search query.
     text: str => [double ...] (1536-dim)")

  (embed-document [this text]
    "Embed a document for storage.
     text: str => [double ...] (1536-dim)")

  (embed-batch [this texts]
    "Embed multiple texts in one call.
     texts: [str] => [[double ...] ...]"))

(defprotocol VectorStore
  "Dense-vector similarity index (Qdrant or in-memory).
   See schema/SearchResult, schema/VectorPoint, schema/StoreInfo."

  (ensure-store! [this dim]
    "Initialize/verify the vector collection.
     dim: long (typically 1536) => nil")

  (upsert! [this id vector payload]
    "Insert or update a vector point.
     id:      long | str (UUID)
     vector:  [double ...]
     payload: schema/VectorPayload
     => nil")

  (search [this query-vector top-k opts]
    "Find nearest neighbors by cosine similarity.
     query-vector: [double ...], top-k: long, opts: map (unused, pass {})
     => [schema/SearchResult ...] sorted by score desc")

  (delete! [this id]
    "Remove a vector point. id: long | str => nil")

  (delete-all! [this]
    "Remove all points from the collection. => nil")

  (store-info [this]
    "Collection status and stats. => schema/StoreInfo")

  (scroll-all [this]
    "Iterate all stored points. => [schema/VectorPoint ...]"))

(defprotocol FactStore
  "Persistent storage for nodes, edges, tags, and global tick counter.
   Canonical shapes: schema/Node, schema/Edge, schema/Tag.
   All edge methods return :edge/from and :edge/to as plain long EIDs."

  ;; ── Nodes ──

  (create-node! [this node-data]
    "Create a new fact/blob node.
     node-data: schema/CreateNodeInput => schema/CreateNodeOutput")

  (find-node [this eid]
    "Look up a node by entity ID.
     eid: long => schema/Node | nil")

  (find-node-by-content [this content]
    "Find an \"entity\"-tagged node with exact content match.
     content: str => schema/Node | nil")

  (update-node-content! [this eid content]
    "Replace node content; bumps updated-at.
     eid: long, content: str => nil")

  (update-node-tags! [this eid tags]
    "Add tags to a node (additive, does not remove existing).
     eid: long, tags: [str] => nil")

  (replace-node-tags! [this eid tag-names]
    "Replace the entire tag set on a node.
     eid: long, tag-names: [str] => nil")

  (update-node-weight! [this eid weight]
    "Set node weight and bump cycle to current tick.
     eid: long, weight: double => nil")

  (set-node-blob-dir! [this eid blob-dir]
    "Attach a blob directory reference to a node.
     eid: long, blob-dir: str => nil")

  (delete-node! [this eid]
    "Delete a node and cascade-delete its edges.
     eid: long => nil")

  (find-recent-nodes [this min-tick]
    "Find node EIDs accessed since a given tick.
     min-tick: long => [long ...]")

  (find-blob-nodes [this opts]
    "Find nodes that have a blob-dir, sorted by created-at desc.
     opts: {:limit long?} (default 20) => [schema/Node ...]")

  (all-nodes [this]
    "List all node entity IDs. => [long ...]")

  (reset-nodes! [this]
    "Delete ALL nodes and edges. Destructive. => nil")

  ;; ── Tags ──

  (ensure-tag! [this tag-name]
    "Create a tag if it doesn't exist.
     tag-name: str => bool (true if created)")

  (all-tags [this]
    "List all tags. => [schema/Tag ...]")

  (find-nodes-by-tag [this tag opts]
    "Find nodes matching a single tag, optionally filtered by date.
     tag: str, opts: {:since inst? :until inst?} => [schema/Node ...]")

  (find-nodes-by-tags [this tags opts]
    "Find nodes matching ALL given tags (intersection).
     tags: [str], opts: {:since inst? :until inst?} => [schema/Node ...]")

  (find-nodes-by-any-tags [this tags]
    "Find nodes matching ANY of the given tags (union).
     tags: [str] => [schema/Node ...]")

  (find-nodes-by-session [this session-id]
    "Find the node for a session.
     session-id: str => schema/Node | nil")

  (find-node-by-eid [this id]
    "Find a node by entity ID (accepts str or long, coerces).
     id: long | str => schema/Node | nil")

  (find-node-by-blob-dir [this blob-dir]
    "Find a node by its blob-dir value.
     blob-dir: str => schema/Node | nil")

  (find-nodes-by-date [this since until]
    "Find nodes within a date range on :node/updated-at.
     since: inst, until: inst => [schema/Node ...]")

  (count-nodes-by-tag-sets [this tag-sets]
    "Count nodes matching each tag combination.
     tag-sets: [[str ...] ...] => [{:tags [str], :count long} ...]")

  (node-tags [this eid]
    "Get tag names attached to a node.
     eid: long => [str ...]")

  (reconcile-tag-counts! [this]
    "Recompute all :tag/node-count values from actual data. => nil")

  ;; ── Edges ──
  ;; All query methods return schema/Edge with plain long EIDs for :edge/from, :edge/to.

  (create-edge! [this edge-data]
    "Create a directed edge between two nodes.
     edge-data: schema/CreateEdgeInput => nil")

  (find-edges-from [this from-eid]
    "Find all edges originating from a node.
     from-eid: long => [schema/Edge ...]")

  (find-edge-between [this from-eid to-eid]
    "Find an edge between two specific nodes.
     from-eid: long, to-eid: long => schema/Edge | nil")

  (find-typed-edge-from [this from-eid edge-type]
    "Find a typed edge from a node.
     from-eid: long, edge-type: keyword => schema/Edge | nil")

  (update-edge-weight! [this edge-id weight]
    "Set edge weight and bump cycle to current tick.
     edge-id: uuid, weight: double => nil")

  (promote-edge-eternal! [this edge-id]
    "Set edge weight to 1.0 (immune to decay).
     edge-id: uuid => nil")

  (all-edges [this]
    "List all edges. => [schema/Edge ...]")

  ;; ── System (tick) ──

  (current-tick [this]
    "Read the global monotonic tick counter. => long")

  (next-tick! [this]
    "Increment and return the global tick. => long")

  ;; ── Migration — import with explicit field values, no auto-tick ──

  (set-tick! [this tick]
    "Force-set the global tick counter (migration only).
     tick: long => nil")

  (import-node! [this node-data]
    "Import a node with all fields explicitly set (migration only).
     node-data: see schema/Node fields + :created-at, :updated-at
     => schema/CreateNodeOutput")

  (import-edge! [this edge-data]
    "Import an edge with explicit field values (migration only).
     edge-data: schema/ImportEdgeInput => nil")

  (update-node-cycle! [this eid cycle]
    "Force-set a node's cycle value (migration only).
     eid: long, cycle: long => nil")

  (update-edge-cycle! [this edge-id cycle]
    "Force-set an edge's cycle value (migration only).
     edge-id: uuid, cycle: long => nil"))
