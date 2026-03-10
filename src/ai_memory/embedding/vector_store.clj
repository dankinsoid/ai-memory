(ns ai-memory.embedding.vector-store
  "Client for Qdrant vector database.
   All public functions accept `qdrant-cfg` — a map with:
     :url     — Qdrant base URL (e.g. \"http://localhost:6333\" or \"https://xyz.cloud.qdrant.io:6333\")
     :api-key — optional API key for Qdrant Cloud auth (sent as `api-key` header)"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn- auth-headers
  "Returns headers map with `api-key` if configured, empty map otherwise."
  [{:keys [api-key]}]
  (if api-key
    {"api-key" api-key}
    {}))

(defn- base-opts
  "Base clj-http options with content-type and optional auth headers."
  [cfg]
  {:content-type :json
   :headers      (auth-headers cfg)})

(defn ensure-collection!
  "Creates the collection if it doesn't exist.
   `dim` — embedding dimension (e.g. 1536 for OpenAI text-embedding-3-small)."
  [{:keys [url] :as cfg} collection dim]
  (try
    (http/get (str url "/collections/" collection)
              {:headers (auth-headers cfg)})
    (log/info "Qdrant collection" collection "already exists")
    (catch Exception _
      (http/put (str url "/collections/" collection)
                (assoc (base-opts cfg)
                       :body (json/generate-string
                              {:vectors {:size     dim
                                         :distance "Cosine"}})))
      (log/info "Created Qdrant collection" collection "with dim" dim))))

(defn upsert-point!
  "Upserts a single vector. `point-id` — entity ID (long) or UUID string.
   `vector` — seq of doubles, `payload` — map with metadata."
  [{:keys [url] :as cfg} collection point-id vector payload]
  (http/put (str url "/collections/" collection "/points")
            (assoc (base-opts cfg)
                   :body (json/generate-string
                          {:points [{:id      point-id
                                     :vector  vector
                                     :payload payload}]}))))

(defn search
  "Returns top-k nearest point IDs with scores.
   Optional `filter-map` for Qdrant filtering (by tags, type, etc.)."
  ([cfg collection query-vector top-k]
   (search cfg collection query-vector top-k nil))
  ([{:keys [url] :as cfg} collection query-vector top-k filter-map]
   (let [body (cond-> {:vector query-vector
                        :limit  top-k
                        :with_payload true}
                filter-map (assoc :filter filter-map))
         resp (http/post (str url "/collections/" collection "/points/search")
                         (assoc (base-opts cfg)
                                :body (json/generate-string body)
                                :as   :json))]
     (->> (get-in resp [:body :result])
          (mapv (fn [r] {:id    (:id r)
                         :score (:score r)
                         :payload (:payload r)}))))))

(defn delete-point!
  "Removes a vector by point ID."
  [{:keys [url] :as cfg} collection point-id]
  (http/post (str url "/collections/" collection "/points/delete")
             (assoc (base-opts cfg)
                    :body (json/generate-string
                           {:points [point-id]}))))

(defn delete-all-points!
  "Drops and recreates the collection — wipes all vectors.
   `dim` — embedding dimension used to recreate the collection."
  [{:keys [url] :as cfg} collection dim]
  (try
    (http/delete (str url "/collections/" collection)
                 (base-opts cfg))
    (catch Exception _))
  (ensure-collection! cfg collection dim))

(defn scroll-all-points
  "Returns all points with vectors and payloads using Qdrant scroll API.
   Paginates automatically until all points are retrieved.
   Each point: {:id N :vector [...] :payload {...}}."
  [{:keys [url] :as cfg} collection]
  (loop [acc [] offset nil]
    (let [body (cond-> {:limit 100 :with_vector true :with_payload true}
                 offset (assoc :offset offset))
          resp (http/post (str url "/collections/" collection "/points/scroll")
                          (assoc (base-opts cfg)
                                 :body (json/generate-string body)
                                 :as   :json))
          result     (get-in resp [:body :result])
          points     (:points result)
          next-page  (:next_page_offset result)
          new-acc    (into acc (map (fn [pt]
                                     {:id      (:id pt)
                                      :vector  (:vector pt)
                                      :payload (:payload pt)}))
                           points)]
      (if next-page
        (recur new-acc next-page)
        new-acc))))

(defn collection-info
  "Returns Qdrant collection stats: reachable?, status, vector-count, points-count.
   On any error returns {:reachable? false :error <message>}."
  [{:keys [url] :as cfg} collection]
  (try
    (let [resp   (http/get (str url "/collections/" collection)
                           (assoc (base-opts cfg) :as :json))
          result (get-in resp [:body :result])]
      {:reachable?   true
       :status       (:status result)
       :vector-count (get-in result [:vectors_count])
       :points-count (get-in result [:points_count])})
    (catch Exception e
      {:reachable? false :error (.getMessage e)})))
