(ns ai-memory.embedding.vector-store
  "Client for Qdrant vector database."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(def ^:private collection-name "nodes")
(def ^:private vector-size 768) ;; nomic-ai/modernbert-embed-base

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
  "Upserts a single node vector. `point-id` — Datomic entity ID (long) or UUID string.
   `vector` — seq of doubles, `payload` — map with metadata."
  [base-url point-id vector payload]
  (http/put (str base-url "/collections/" collection-name "/points")
            {:content-type :json
             :body (json/generate-string
                    {:points [{:id      point-id
                               :vector  vector
                               :payload payload}]})}))

(defn search
  "Returns top-k nearest point IDs with scores.
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
  "Removes a node vector by point ID."
  [base-url point-id]
  (http/post (str base-url "/collections/" collection-name "/points/delete")
             {:content-type :json
              :body (json/generate-string
                     {:points [point-id]})}))

(defn delete-all-points!
  "Drops and recreates the collection — wipes all vectors."
  [base-url]
  (try
    (http/delete (str base-url "/collections/" collection-name)
                 {:content-type :json})
    (catch Exception _))
  (ensure-collection! base-url))

(defn collection-info
  "Returns Qdrant collection stats: reachable?, status, vector-count, points-count.
   On any error returns {:reachable? false :error <message>}."
  [base-url]
  (try
    (let [resp   (http/get (str base-url "/collections/" collection-name) {:as :json})
          result (get-in resp [:body :result])]
      {:reachable?   true
       :status       (:status result)
       :vector-count (get-in result [:vectors_count])
       :points-count (get-in result [:points_count])})
    (catch Exception e
      {:reachable? false :error (.getMessage e)})))
