(ns ai-memory.embedding.core
  "Client for the Text Embeddings Inference (TEI) service.
   Uses nomic-ai/modernbert-embed-base which requires task prefixes."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn- add-prefix [prefix text]
  (str prefix text))

(defn- raw-embed
  "Calls TEI /embed endpoint. Returns seq of vectors."
  [base-url inputs]
  (let [resp (http/post (str base-url "/embed")
                        {:content-type :json
                         :body         (json/generate-string {:inputs inputs})
                         :as           :json})]
    (:body resp)))

(defn embed-query
  "Embeds a search query (adds 'search_query: ' prefix)."
  [base-url text]
  (first (raw-embed base-url (add-prefix "search_query: " text))))

(defn embed-document
  "Embeds a document for storage (adds 'search_document: ' prefix)."
  [base-url text]
  (first (raw-embed base-url (add-prefix "search_document: " text))))

(defn embed-batch
  "Embeds multiple documents for storage in one call."
  [base-url texts]
  (raw-embed base-url (mapv #(add-prefix "search_document: " %) texts)))
