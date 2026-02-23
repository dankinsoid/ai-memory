(ns ai-memory.mcp.server
  "Thin HTTP client — proxies MCP tool calls to the persistent ai-memory server."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn- auth-headers [api-token]
  (when api-token
    {"Authorization" (str "Bearer " api-token)}))

(defn- api-get
  [base-url path query-params api-token]
  (let [resp (http/get (str base-url path)
               {:query-params    query-params
                :headers         (auth-headers api-token)
                :as              :json
                :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (do (log/warn "API error" path (:status resp) (:body resp))
          {:error (str "HTTP " (:status resp))}))))

(defn- api-post
  [base-url path body api-token]
  (let [resp (http/post (str base-url path)
               {:content-type    :json
                :body            (json/generate-string body)
                :headers         (auth-headers api-token)
                :as              :json
                :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (do (log/warn "API error" path (:status resp) (:body resp))
          {:error (str "HTTP " (:status resp))}))))

;; --- Tool handlers ---
;; Each takes {:base-url ... :api-token ...} config map

(defn handle-explore-tags [{:keys [base-url api-token]} {:keys [tag-sets limit offset] :or {limit 50 offset 0}}]
  (if (seq tag-sets)
    {:mode :count
     :data (:counts (api-post base-url "/api/tags/count" {:tag-sets tag-sets} api-token))}
    {:mode :browse
     :data (api-get base-url "/api/tags" {:limit limit :offset offset} api-token)}))

(defn handle-get-facts [{:keys [base-url api-token]} {:keys [filters]}]
  (:results (api-post base-url "/api/tags/facts" {:filters filters} api-token)))

(defn handle-remember [{:keys [base-url api-token]} params]
  (api-post base-url "/api/remember" params api-token))

(defn handle-store-file [{:keys [base-url api-token]} params]
  (:body (http/post (str base-url "/api/blobs/file")
           {:content-type    :json
            :body            (json/generate-string params)
            :headers         (auth-headers api-token)
            :as              :json
            :throw-exceptions false})))

(defn handle-read-blob [{:keys [base-url api-token]} params]
  (api-post base-url "/api/blobs/exec" params api-token))

(defn handle-reinforce [{:keys [base-url api-token]} params]
  (api-post base-url "/api/reinforce" params api-token))

(defn handle-session [{:keys [base-url api-token]} params]
  (api-post base-url "/api/session" params api-token))
