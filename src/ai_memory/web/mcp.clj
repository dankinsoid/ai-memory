(ns ai-memory.web.mcp
  "MCP Streamable HTTP transport (spec 2025-03-26).
   Single endpoint: POST /mcp for JSON-RPC, GET/DELETE return 405.
   Session tracking via Mcp-Session-Id header."
  (:require [ai-memory.mcp.protocol :as protocol]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.util.concurrent ConcurrentHashMap]))

(defonce ^:private sessions (ConcurrentHashMap.))

(defn- initialize-response? [body response]
  (and (= "initialize" (:method body))
       (:result response)))

(defn- post-handler [mcp-cfg request]
  (let [body    (:body-params request)
        sess-id (get-in request [:headers "mcp-session-id"])
        handler (when sess-id (.get sessions sess-id))]
    ;; Reject only when sess-id is completely absent for non-initialize methods.
    ;; If sess-id is present but session not found (e.g. server restarted), auto-recover below.
    (if (and (nil? sess-id)
             (not= "initialize" (:method body))
             (not= "notifications/initialized" (:method body)))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
               {:jsonrpc "2.0"
                :error {:code -32600
                        :message "Bad Request: missing Mcp-Session-Id"}})}
      ;; Dispatch — create handler on demand if session was lost (server restart)
      (let [handler  (or handler (protocol/make-handler mcp-cfg))
            _        (when (and sess-id (nil? (.get sessions sess-id)))
                       (log/info "MCP session auto-recovered:" sess-id)
                       (.put sessions sess-id handler))
            response (try
                       (handler body)
                       (catch Exception e
                         (log/error e "MCP handler error")
                         {:jsonrpc "2.0"
                          :id      (:id body)
                          :error   {:code    -32603
                                    :message (str "Internal error: " (.getMessage e))}}))]
        (if (nil? response)
          ;; Notification — no response needed
          {:status 202}
          ;; Check if this is initialize — create session
          (if (initialize-response? body response)
            (let [new-sess-id (str (UUID/randomUUID))]
              (.put sessions new-sess-id handler)
              (log/info "MCP session created:" new-sess-id)
              {:status  200
               :headers {"Content-Type"    "application/json"
                         "Mcp-Session-Id"  new-sess-id}
               :body    (json/generate-string response)})
            ;; Regular response
            {:status  200
             :headers {"Content-Type" "application/json"}
             :body    (json/generate-string response)}))))))

(defn streamable-handler
  "Returns Ring handler for the /mcp Streamable HTTP endpoint.
   `mcp-cfg` is {:base-url ... :api-token ...}."
  [mcp-cfg]
  (fn [request]
    (case (:request-method request)
      :post   (post-handler mcp-cfg request)
      :get    {:status 405
               :headers {"Content-Type" "application/json"}
               :body (json/generate-string {:error "Method not allowed. Use POST for JSON-RPC."})}
      :delete {:status 405
               :headers {"Content-Type" "application/json"}
               :body (json/generate-string {:error "Session termination not supported."})}
      {:status 405
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Method not allowed."})})))
