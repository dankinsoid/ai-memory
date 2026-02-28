(ns ai-memory.embedding.core
  "Client for OpenAI Embeddings API (text-embedding-3-small, 1536d)."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(def ^:private model "text-embedding-3-small")

(defn- raw-embed
  "Calls OpenAI /v1/embeddings endpoint. Returns seq of vectors."
  [api-key input]
  (let [resp (http/post "https://api.openai.com/v1/embeddings"
                        {:headers      {"Authorization" (str "Bearer " api-key)}
                         :content-type :json
                         :body         (json/generate-string
                                        {:model model
                                         :input input})
                         :as           :json})]
    (->> (get-in resp [:body :data])
         (sort-by :index)
         (mapv :embedding))))

(defn embed-query
  "Embeds a search query. Returns a single vector."
  [api-key text]
  (first (raw-embed api-key text)))

(defn embed-document
  "Embeds a document for storage. Returns a single vector."
  [api-key text]
  (first (raw-embed api-key text)))

(defn embed-batch
  "Embeds multiple documents for storage in one call."
  [api-key texts]
  (raw-embed api-key texts))
