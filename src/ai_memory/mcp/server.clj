(ns ai-memory.mcp.server
  "Thin HTTP client — proxies MCP tool calls to the persistent ai-memory server."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn- api-get
  "GET request, returns parsed JSON body."
  [base-url path query-params]
  (let [resp (http/get (str base-url path)
               {:query-params  query-params
                :as            :json
                :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (do (log/warn "API error" path (:status resp) (:body resp))
          {:error (str "HTTP " (:status resp))}))))

(defn- api-post
  "POST request with JSON body, returns parsed JSON body."
  [base-url path body]
  (let [resp (http/post (str base-url path)
               {:content-type    :json
                :body            (json/generate-string body)
                :as              :json
                :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (do (log/warn "API error" path (:status resp) (:body resp))
          {:error (str "HTTP " (:status resp))}))))

;; --- Tool handlers (each proxies to one HTTP endpoint) ---

(defn handle-browse-tags [base-url {:keys [limit offset] :or {limit 50 offset 0}}]
  (api-get base-url "/api/tags" {:limit limit :offset offset}))

(defn handle-count-facts [base-url {:keys [tag-sets]}]
  (:counts (api-post base-url "/api/tags/count" {:tag-sets tag-sets})))

(defn handle-get-facts [base-url {:keys [tag-sets limit since until] :or {limit 50}}]
  (:results (api-post base-url "/api/tags/facts"
              (cond-> {:limit limit}
                tag-sets (assoc :tag-sets tag-sets)
                since    (assoc :since since)
                until    (assoc :until until)))))

(defn handle-search-facts [base-url {:keys [query top-k] :or {top-k 10}}]
  (:results (api-post base-url "/api/search" {:query query :top-k top-k})))

(defn handle-create-tag [base-url {:keys [name]}]
  (api-post base-url "/api/tags" {:name name}))

(defn handle-remember [base-url params]
  (api-post base-url "/api/remember" params))

(defn handle-list-blobs [base-url {:keys [limit] :or {limit 20}}]
  (:blobs (api-get base-url "/api/blobs" {:limit limit})))

(defn handle-read-blob [base-url {:keys [blob-dir section]}]
  (api-post base-url "/api/blobs/read" {:blob-dir blob-dir :section section}))

(defn handle-store-file [base-url params]
  (:body (http/post (str base-url "/api/blobs/file")
           {:content-type    :json
            :body            (json/generate-string params)
            :as              :json
            :throw-exceptions false})))

(defn handle-session [base-url params]
  (api-post base-url "/api/session" params))
