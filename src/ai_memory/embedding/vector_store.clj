(ns ai-memory.embedding.vector-store
  "Client for Qdrant vector database."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(def ^:private collection-name "nodes")
(def ^:private vector-size 384) ;; all-MiniLM-L6-v2

(defn ensure-collection!
  "Creates the collection if it doesn't exist."
  [base-url]
  (try
    (http/get (str base-url "/collections/" collection-name))
    (log/info "Qdrant collection" collection-name "already exists")
    (catch Exception _
      (http/put (str base-url "/collections/" collection-name)
                {:content-type :json
                 :body (json/generate-string
                        {:vectors {:size     vector-size
                                   :distance "Cosine"}})})
      (log/info "Created Qdrant collection" collection-name))))

(defn upsert-point!
  "Upserts a single node vector. `node-uuid` — UUID string, `vector` — seq of doubles,
   `payload` — map with metadata (content, type, tags, etc.)."
  [base-url node-uuid vector payload]
  (http/put (str base-url "/collections/" collection-name "/points")
            {:content-type :json
             :body (json/generate-string
                    {:points [{:id      node-uuid
                               :vector  vector
                               :payload payload}]})}))

(defn search
  "Returns top-k nearest node UUIDs with scores.
   Optional `filter-map` for Qdrant filtering (by tags, type, etc.)."
  ([base-url query-vector top-k]
   (search base-url query-vector top-k nil))
  ([base-url query-vector top-k filter-map]
   (let [body (cond-> {:vector query-vector
                        :limit  top-k
                        :with_payload true}
                filter-map (assoc :filter filter-map))
         resp (http/post (str base-url "/collections/" collection-name "/points/search")
                         {:content-type :json
                          :body         (json/generate-string body)
                          :as           :json})]
     (->> (get-in resp [:body :result])
          (mapv (fn [r] {:id    (:id r)
                         :score (:score r)
                         :payload (:payload r)}))))))

(defn delete-point!
  "Removes a node vector by UUID."
  [base-url node-uuid]
  (http/post (str base-url "/collections/" collection-name "/points/delete")
             {:content-type :json
              :body (json/generate-string
                     {:points [node-uuid]})}))
