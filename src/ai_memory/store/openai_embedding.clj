(ns ai-memory.store.openai-embedding
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.embedding.core :as emb]))

(defrecord OpenAIEmbedding [api-key]
  p/EmbeddingProvider
  (embed-query    [_ text]  (emb/embed-query api-key text))
  (embed-document [_ text]  (emb/embed-document api-key text))
  (embed-batch    [_ texts] (emb/embed-batch api-key texts))
  (embedding-dim  [_]       1536))

(defn create [cfg]
  (->OpenAIEmbedding (:openai-api-key cfg)))
