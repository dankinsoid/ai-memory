;; @ai-generated(guided)
(ns ai-memory.web.api
  "Thin HTTP adapter layer. Parses requests, delegates to service layer,
   formats responses. No direct protocol calls."
  (:require [ai-memory.service.facts :as facts]
            [ai-memory.service.tags :as tags]
            [ai-memory.service.blobs :as blobs]
            [ai-memory.service.sessions :as sessions]
            [ai-memory.service.admin :as admin]
            [ai-memory.service.migration :as migration]
            [ai-memory.store.protocols :as p]
            [ai-memory.graph.write :as write]
            [ai-memory.web.visualization :as viz]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; --- D3 visualization (presentation layer) ---

(defn get-graph [ctx _req]
  {:status 200
   :body   (viz/get-graph ctx)})

(defn get-stats [ctx _req]
  {:status 200
   :body   (admin/stats ctx)})

(defn get-health [ctx _req]
  {:status 200
   :body   (admin/health ctx)})

(defn get-diagnostics [ctx req]
  {:status 200
   :body   (admin/diagnostics ctx (get-in req [:query-params "test-query"]))})

(defn get-top-nodes [ctx req]
  {:status 200
   :body   (viz/get-top-nodes ctx
             {:limit (some-> (get-in req [:query-params "limit"]) parse-long)
              :tag   (get-in req [:query-params "tag"])})})

(defn get-graph-neighborhood [ctx req]
  (let [node-id (some-> (get-in req [:query-params "node_id"]) parse-long)]
    (if-not node-id
      {:status 400 :body {:error "node_id required"}}
      (if-let [result (viz/get-graph-neighborhood ctx
                        {:node-id node-id
                         :depth   (some-> (get-in req [:query-params "depth"]) parse-long)
                         :limit   (some-> (get-in req [:query-params "limit"]) parse-long)})]
        {:status 200 :body result}
        {:status 404 :body {:error "Node not found"}}))))

;; --- Facts ---

(defn get-fact-detail [ctx req]
  (let [id (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not id
      {:status 400 :body {:error "id required"}}
      (if-let [result (facts/get-by-id ctx id)]
        {:status 200 :body result}
        {:status 404 :body {:error "Not found"}}))))

(defn update-fact [ctx req]
  (let [id (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not id
      {:status 400 :body {:error "id required"}}
      {:status 200 :body (facts/patch! ctx id (:body-params req))})))

(defn delete-fact [ctx req]
  (let [eid (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not eid
      {:status 400 :body {:error "Invalid id"}}
      (try
        {:status 200 :body (facts/delete! ctx eid)}
        (catch Exception e
          {:status 404 :body {:error (ex-message e)}})))))

;; --- Nodes ---

(defn list-nodes [_req]
  {:status 200
   :body   []})

(defn create-node [ctx req]
  {:status 201
   :body   (facts/create! ctx (:body-params req))})

;; --- Tags ---

(defn browse-tags [ctx req]
  (let [limit  (some-> (get-in req [:query-params "limit"]) parse-long)
        offset (some-> (get-in req [:query-params "offset"]) parse-long)]
    {:status 200
     :body   (tags/browse ctx {:limit (or limit 50) :offset (or offset 0)})}))

(defn count-facts [ctx req]
  {:status 200
   :body   {:counts (tags/count-by-sets ctx (get-in req [:body-params :tag-sets]))}})

(defn get-facts [ctx req]
  {:status 200
   :body   {:results (facts/search ctx (get-in req [:body-params :filters]))}})

(defn resolve-tags [ctx req]
  (let [{:keys [candidates threshold top-k]} (:body-params req)]
    {:status 200
     :body   {:results (tags/resolve-tags ctx candidates
                                          (cond-> {}
                                            threshold (assoc :threshold threshold)
                                            top-k     (assoc :top-k top-k)))}}))

(defn recall [ctx req]
  {:status 200
   :body   {:results (facts/recall ctx (get-in req [:body-params :tags]))}})

;; --- Reinforce ---

(defn reinforce [ctx req]
  {:status 200
   :body   (facts/reinforce! ctx (get-in req [:body-params :reinforcements]))})

(defn promote-eternal [ctx req]
  (let [id (get-in req [:body-params :id])]
    (facts/promote-eternal! ctx id)
    {:status 200
     :body   {:promoted id}}))

;; --- Remember ---

(defn remember [ctx req]
  (let [body   (:body-params req)
        nodes  (:nodes body)
        result (when (seq nodes)
                 (write/remember ctx (assoc body :metrics (:metrics ctx))))]
    {:status 201
     :body   (or result {:nodes [] :edges-created 0})}))

;; --- Blobs ---

(defn list-blobs [ctx req]
  (let [limit (some-> (get-in req [:query-params "limit"]) parse-long)]
    {:status 200
     :body   {:blobs (p/find-blob-nodes (:fact-store ctx) {:limit (or limit 20)})}}))

(defn read-blob [ctx req]
  (let [{:keys [blob-dir section]} (:body-params req)
        blob-path (:blob-path ctx)]
    (if section
      (if-let [result (blobs/read-blob blob-path blob-dir section)]
        {:status 200 :body result}
        {:status 404 :body {:error (str "Section " section " not found in " blob-dir)}})
      (if-let [meta (blobs/read-blob blob-path blob-dir nil)]
        {:status 200 :body meta}
        {:status 404 :body {:error (str "Blob not found: " blob-dir)}}))))

(defn exec-blob [ctx req]
  (let [{:keys [blob-dir command]} (:body-params req)]
    (if-not (and blob-dir command)
      {:status 400 :body {:error "blob_dir and command required"}}
      (try
        {:status 200 :body (blobs/exec-blob ctx blob-dir command)}
        (catch Exception e
          {:status 400 :body {:error (.getMessage e)}})))))

;; --- Sessions ---

(defn session-sync [ctx req]
  (let [{:keys [session-id]} (:body-params req)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      {:status 200
       :body   (sessions/sync! ctx (:body-params req))})))

(defn session-update [ctx req]
  (let [{:keys [session-id]} (:body-params req)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      {:status 200
       :body   (sessions/update! ctx (:body-params req))})))

(defn session-continue [ctx req]
  (let [{:keys [prev-session-id session-id project]} (:body-params req)]
    (if-not (and prev-session-id session-id)
      {:status 400 :body {:error "prev-session-id and session-id required"}}
      (try
        {:status 200
         :body   (sessions/continue! ctx prev-session-id session-id project)}
        (catch Exception e
          {:status 500 :body {:error (ex-message e)}})))))

(defn session-chain [ctx req]
  (let [{:keys [session-id strengthen]} (:body-params req)]
    (if-not session-id
      {:status 400 :body {:error "session-id required"}}
      {:status 200
       :body   {:chain (sessions/chain ctx session-id strengthen)}})))

;; --- Project ---

(defn project-update [ctx req]
  (let [{:keys [project summary tags]} (:body-params req)]
    (if (or (str/blank? project) (str/blank? summary))
      {:status 400 :body {:error "project and summary are required"}}
      (do (sessions/project-update! ctx project summary tags)
          {:status 200 :body {:project project}}))))

;; --- Admin ---

(defn reset-db [ctx _req]
  {:status 200
   :body   (admin/reset-all! ctx)})

(defn reindex-vectors [ctx _req]
  {:status 200
   :body   (admin/reindex! ctx)})

(defn export-snapshot
  "Returns ZIP archive as a downloadable binary response."
  [ctx req]
  (let [include-vectors (not= "false" (get-in req [:query-params "vectors"]))]
    (try
      (let [zip-bytes (migration/export-snapshot-zip ctx {:include-vectors include-vectors})
            filename  (str "ai-memory-snapshot-"
                           (.format (java.time.LocalDate/now)
                                    (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
                           ".zip")]
        {:status  200
         :headers {"Content-Type"        "application/zip"
                   "Content-Disposition" (str "attachment; filename=\"" filename "\"")
                   "Content-Length"       (str (alength zip-bytes))}
         :body    (java.io.ByteArrayInputStream. zip-bytes)})
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body    (str "{\"error\":\"" (ex-message e) "\"}")}))))

(defn import-snapshot
  "Accepts ZIP archive as raw request body, imports into database."
  [ctx req]
  (try
    (let [body      (:body req)
          zip-bytes (if (bytes? body)
                      body
                      (.readAllBytes body))
          result    (migration/import-snapshot-zip! ctx zip-bytes)]
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string result)})
    (catch Exception e
      {:status  500
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string {:error (ex-message e)})})))
