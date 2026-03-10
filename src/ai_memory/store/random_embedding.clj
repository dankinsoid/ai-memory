;; @ai-generated(solo)
(ns ai-memory.store.random-embedding
  "Fake EmbeddingProvider for local dev without OpenAI API key.
   Generates deterministic pseudo-random vectors from text hash so that
   identical inputs always produce identical embeddings. Different inputs
   produce uncorrelated vectors — semantic similarity won't work, but
   the full pipeline (store, search, retrieve) remains exercisable."
  (:require [ai-memory.store.protocols :as p]
            [clojure.tools.logging :as log])
  (:import [java.util Random]))

(defn- text->vector
  "Deterministic pseudo-random unit vector seeded by text hash.
   Same text always yields the same embedding.
   `dim` — vector dimensionality (from config :embedding-dim)."
  [dim text]
  (let [rng (Random. (long (hash text)))
        raw (vec (repeatedly dim #(.nextGaussian rng)))
        mag (Math/sqrt (reduce + (map #(* % %) raw)))]
    (mapv #(/ % mag) raw)))

(defrecord RandomEmbedding [dim]
  p/EmbeddingProvider
  (embed-query    [_ text]  (text->vector dim text))
  (embed-document [_ text]  (text->vector dim text))
  (embed-batch    [_ texts] (mapv (partial text->vector dim) texts)))

(defn create
  "Creates a fake embedding provider.
   `dim` — vector dimensionality (from config :embedding-dim)."
  [dim]
  (log/warn "Using random embeddings — semantic search will not work. Set OPENAI_API_KEY for real embeddings.")
  (->RandomEmbedding dim))
