;; @ai-generated(guided)
(ns ai-memory.store.schema
  "Canonical schemas for all store protocol data types.
   Single source of truth for node, edge, tag, and vector-store shapes.

   Schemas are written in Malli syntax but used as documentation only.
   To enable runtime validation, add metosin/malli dependency and
   use (malli.core/validate Schema value).")

;; ── Primitives ──────────────────────────────────────────────────

(def Eid
  "Entity ID — long integer, always positive in practice."
  :int)

(def Tick
  "Monotonic counter for decay tracking."
  :int)

(def Weight
  "Importance weight, 0.0–1.0."
  :double)

;; ── Node ────────────────────────────────────────────────────────

(def Node
  "Canonical node shape returned by all find-* FactStore methods."
  [:map
   [:db/id Eid]
   [:node/content :string]
   [:node/weight Weight]
   [:node/cycle Tick]
   [:node/created-at inst?]
   [:node/updated-at inst?]
   [:node/blob-dir {:optional true} :string]
   [:node/sources {:optional true} [:sequential :string]]
   [:node/session-id {:optional true} :string]
   [:node/tags {:optional true} [:seqable :string]]])

(def CreateNodeInput
  "Input for FactStore/create-node!"
  [:map
   [:content :string]
   [:tags {:optional true} [:sequential :string]]
   [:blob-dir {:optional true} :string]
   [:sources {:optional true} [:sequential :string]]
   [:session-id {:optional true} :string]])

(def CreateNodeOutput
  "Return value of FactStore/create-node!"
  [:map [:id Eid]])

;; ── Edge ────────────────────────────────────────────────────────

(def Edge
  "Canonical edge shape returned by all find-* and all-edges FactStore methods.
   :edge/from and :edge/to are plain long EIDs — no nested maps."
  [:map
   [:db/id Eid]
   [:edge/id :uuid]
   [:edge/from Eid]
   [:edge/to Eid]
   [:edge/weight Weight]
   [:edge/cycle Tick]
   [:edge/type {:optional true} :keyword]])

(def CreateEdgeInput
  "Input for FactStore/create-edge!"
  [:map
   [:from Eid]
   [:to Eid]
   [:weight {:optional true} Weight]
   [:type {:optional true} :keyword]])

(def ImportEdgeInput
  "Input for FactStore/import-edge! (migration)."
  [:map
   [:from Eid]
   [:to Eid]
   [:weight Weight]
   [:cycle Tick]
   [:type {:optional true} :keyword]])

;; ── Tag ─────────────────────────────────────────────────────────

(def Tag
  "Tag shape returned by FactStore/all-tags."
  [:map
   [:tag/name :string]
   [:tag/node-count :int]
   [:tag/tier {:optional true} :keyword]])

;; ── VectorStore ─────────────────────────────────────────────────

(def VectorPayload
  "Payload stored alongside a vector point."
  [:map-of :keyword :any])

(def VectorPoint
  "A point returned by VectorStore/scroll-all."
  [:map
   [:id [:or :int :string]]
   [:vector [:sequential :double]]
   [:payload VectorPayload]])

(def SearchResult
  "A single search hit returned by VectorStore/search."
  [:map
   [:id [:or :int :string]]
   [:score :double]
   [:payload VectorPayload]])

(def StoreInfo
  "VectorStore/store-info return value."
  [:map
   [:reachable? :boolean]
   [:status {:optional true} :string]
   [:vector-count {:optional true} :int]
   [:points-count {:optional true} :int]
   [:error {:optional true} :string]])

;; ── EmbeddingProvider ───────────────────────────────────────────

(def EmbeddingVector
  "Dense float vector (typically 1536-dim for text-embedding-3-small)."
  [:sequential :double])
