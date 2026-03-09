;; @ai-generated(guided)
(ns ai-memory.web.api
  "Thin HTTP adapter layer. Parses requests, delegates to service layer,
   formats responses. No direct protocol calls."
  (:require [ai-memory.service.facts :as facts]
            [ai-memory.service.tags :as tags]
            [ai-memory.service.blobs :as blobs]
            [ai-memory.service.sessions :as sessions]
            [ai-memory.service.admin :as admin]
            [ai-memory.graph.write :as write]
            [ai-memory.web.visualization :as viz]
            [clojure.string :as str]))

;; --- D3 visualization (presentation layer) ---

(defn get-graph [_conn stores _req]
  {:status 200
   :body   (viz/get-graph stores)})

(defn get-stats [_conn stores _req]
  {:status 200
   :body   (admin/stats stores)})

(defn get-health [stores _req]
  {:status 200
   :body   (admin/health stores)})

(defn get-diagnostics [stores req]
  {:status 200
   :body   (admin/diagnostics stores (get-in req [:query-params "test-query"]))})

(defn get-top-nodes [stores req]
  {:status 200
   :body   (viz/get-top-nodes stores
             {:limit (some-> (get-in req [:query-params "limit"]) parse-long)
              :tag   (get-in req [:query-params "tag"])})})

(defn get-graph-neighborhood [_conn stores req]
  (let [node-id (some-> (get-in req [:query-params "node_id"]) parse-long)]
    (if-not node-id
      {:status 400 :body {:error "node_id required"}}
      (if-let [result (viz/get-graph-neighborhood stores
                        {:node-id node-id
                         :depth   (some-> (get-in req [:query-params "depth"]) parse-long)
                         :limit   (some-> (get-in req [:query-params "limit"]) parse-long)})]
        {:status 200 :body result}
        {:status 404 :body {:error "Node not found"}}))))

;; --- Facts ---

(defn get-fact-detail [stores req]
  (let [id (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not id
      {:status 400 :body {:error "id required"}}
      (if-let [result (facts/get-by-id stores id)]
        {:status 200 :body result}
        {:status 404 :body {:error "Not found"}}))))

(defn update-fact [stores _cfg req]
  (let [id (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not id
      {:status 400 :body {:error "id required"}}
      {:status 200 :body (facts/patch! stores id (:body-params req))})))

(defn delete-fact [stores cfg req]
  (let [eid (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not eid
      {:status 400 :body {:error "Invalid id"}}
      (try
        {:status 200 :body (facts/delete! stores (:blob-path cfg) eid)}
        (catch Exception e
          {:status 404 :body {:error (ex-message e)}})))))

;; --- Nodes ---

(defn list-nodes [_req]
  {:status 200
   :body   []})

(defn create-node [stores req]
  {:status 201
   :body   (facts/create! stores (:body-params req))})

;; --- Tags ---

(defn browse-tags [stores req]
  (let [limit  (some-> (get-in req [:query-params "limit"]) parse-long)
        offset (some-> (get-in req [:query-params "offset"]) parse-long)]
    {:status 200
     :body   (tags/browse stores {:limit (or limit 50) :offset (or offset 0)})}))

(defn count-facts [stores cfg req]
  {:status 200
   :body   {:counts (tags/count-by-sets stores (:metrics cfg)
                                        (get-in req [:body-params :tag-sets]))}})

(defn get-facts [stores cfg req]
  {:status 200
   :body   {:results (facts/search stores (:metrics cfg)
                                   (get-in req [:body-params :filters]))}})

(defn resolve-tags [stores req]
  (let [{:keys [candidates threshold top-k]} (:body-params req)]
    {:status 200
     :body   {:results (tags/resolve-tags stores candidates
                                          (cond-> {}
                                            threshold (assoc :threshold threshold)
                                            top-k     (assoc :top-k top-k)))}}))

(defn recall [stores req]
  {:status 200
   :body   {:results (facts/recall stores (get-in req [:body-params :tags]))}})

;; --- Reinforce ---

(defn reinforce [stores cfg req]
  {:status 200
   :body   (facts/reinforce! stores cfg (get-in req [:body-params :reinforcements]))})

(defn promote-eternal [stores req]
  (let [id (get-in req [:body-params :id])]
    (facts/promote-eternal! stores id)
    {:status 200
     :body   {:promoted id}}))

;; --- Remember ---

(defn remember [stores cfg req]
  (let [body   (:body-params req)
        nodes  (:nodes body)
        result (when (seq nodes)
                 (write/remember stores (assoc body :metrics (:metrics cfg))))]
    {:status 201
     :body   (or result {:nodes [] :edges-created 0})}))

;; --- Blobs ---

(defn list-blobs [stores req]
  (let [limit (some-> (get-in req [:query-params "limit"]) parse-long)]
    {:status 200
     :body   {:blobs (blobs/list-blobs stores {:limit (or limit 20)})}}))

(defn read-blob [cfg req]
  (let [{:keys [blob-dir section]} (:body-params req)]
    (if section
      (if-let [result (blobs/read-blob (:blob-path cfg) blob-dir section)]
        {:status 200 :body result}
        {:status 404 :body {:error (str "Section " section " not found in " blob-dir)}})
      (if-let [meta (blobs/read-blob (:blob-path cfg) blob-dir nil)]
        {:status 200 :body meta}
        {:status 404 :body {:error (str "Blob not found: " blob-dir)}}))))

(defn exec-blob [cfg req]
  (let [{:keys [blob-dir command]} (:body-params req)]
    (if-not (and blob-dir command)
      {:status 400 :body {:error "blob_dir and command required"}}
      (try
        {:status 200 :body (blobs/exec-blob cfg blob-dir command)}
        (catch Exception e
          {:status 400 :body {:error (.getMessage e)}})))))

(defn store-file [stores cfg req]
  {:status 201
   :body   (blobs/store! stores (:blob-path cfg) (:body-params req))})

(defn update-blob [stores cfg req]
  (let [{:keys [blob-dir summary content tags]} (:body-params req)]
    (if (str/blank? blob-dir)
      {:status 400 :body {:error "blob-dir required"}}
      (try
        {:status 200
         :body   (blobs/update! stores (:blob-path cfg) cfg blob-dir
                                {:summary summary :content content :tags tags})}
        (catch Exception e
          (if (= "No node found for blob-dir" (ex-message e))
            {:status 404 :body {:error (str "No node found for blob-dir: " blob-dir)}}
            (throw e)))))))

;; --- Sessions ---

(defn session-sync [_conn stores cfg req]
  (let [{:keys [session-id]} (:body-params req)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      {:status 200
       :body   (sessions/sync! stores (:blob-path cfg) (:body-params req))})))

(defn session-update [_conn stores cfg req]
  (let [{:keys [session-id]} (:body-params req)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      {:status 200
       :body   (sessions/update! stores (:blob-path cfg) (:body-params req))})))

(defn session-continue [_conn stores cfg req]
  (let [{:keys [prev-session-id session-id project]} (:body-params req)]
    (if-not (and prev-session-id session-id)
      {:status 400 :body {:error "prev-session-id and session-id required"}}
      (try
        {:status 200
         :body   (sessions/continue! stores prev-session-id session-id project)}
        (catch Exception e
          {:status 500 :body {:error (ex-message e)}})))))

(defn session-chain [_conn stores req]
  (let [{:keys [session-id strengthen]} (:body-params req)]
    (if-not session-id
      {:status 400 :body {:error "session-id required"}}
      {:status 200
       :body   {:chain (sessions/chain stores session-id strengthen)}})))

;; --- Project ---

(defn project-update [stores cfg req]
  (let [{:keys [project summary tags]} (:body-params req)]
    (if (or (str/blank? project) (str/blank? summary))
      {:status 400 :body {:error "project and summary are required"}}
      (do (sessions/project-update! stores cfg project summary tags)
          {:status 200 :body {:project project}}))))

;; --- Admin ---

(defn reset-db [stores cfg _req]
  {:status 200
   :body   (admin/reset-all! stores (:blob-path cfg))})

(defn reindex-vectors [stores cfg _req]
  {:status 200
   :body   (admin/reindex! stores (:blob-path cfg))})
