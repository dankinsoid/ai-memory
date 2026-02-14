(ns ai-memory.embedding.core
  "Client for the Text Embeddings Inference (TEI) service."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn embed
  "Returns a vector (seq of doubles) for the given text.
   `base-url` — TEI service URL, e.g. \"http://localhost:8090\"."
  [base-url text]
  (let [resp (http/post (str base-url "/embed")
                        {:content-type :json
                         :body         (json/generate-string {:inputs text})
                         :as           :json})]
    (first (:body resp))))

(defn embed-batch
  "Returns a seq of vectors for multiple texts in one call."
  [base-url texts]
  (let [resp (http/post (str base-url "/embed")
                        {:content-type :json
                         :body         (json/generate-string {:inputs texts})
                         :as           :json})]
    (:body resp)))
