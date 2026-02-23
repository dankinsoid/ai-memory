(ns ai-memory.web.mcp
  "MCP SSE transport — exposes MCP protocol over HTTP Server-Sent Events.
   GET  /mcp/sse     → SSE stream, sends `endpoint` event
   POST /mcp/message → receives JSON-RPC, responds via SSE stream"
  (:require [ai-memory.mcp.protocol :as protocol]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.io OutputStream PipedInputStream PipedOutputStream]
           [java.util UUID]
           [java.util.concurrent ConcurrentHashMap]))

(defonce ^:private sessions (ConcurrentHashMap.))

(defn- sse-write!
  "Writes one SSE event to output stream."
  [^OutputStream os event-type data]
  (let [payload (str "event: " event-type "\ndata: " data "\n\n")]
    (.write os (.getBytes payload "UTF-8"))
    (.flush os)))

(defn sse-handler
  "Returns Ring handler for GET /mcp/sse.
   `mcp-cfg` is {:base-url ... :api-token ...} passed to protocol handler."
  [mcp-cfg]
  (fn [_request]
    (let [session-id (str (UUID/randomUUID))
          pipe-in    (PipedInputStream. 65536)
          pipe-out   (PipedOutputStream. pipe-in)
          handler    (protocol/make-handler mcp-cfg)]
      (.put sessions session-id {:output pipe-out :handler handler})
      (log/info "MCP SSE session created:" session-id)
      (sse-write! pipe-out "endpoint" (str "/mcp/message?sessionId=" session-id))
      {:status  200
       :headers {"Content-Type"  "text/event-stream"
                 "Cache-Control" "no-cache"
                 "Connection"    "keep-alive"}
       :body    pipe-in})))

(defn message-handler
  "Returns Ring handler for POST /mcp/message."
  []
  (fn [request]
    (let [session-id (get-in request [:query-params "sessionId"])
          session    (when session-id (.get sessions session-id))]
      (if-not session
        {:status 404 :body {:error "Session not found"}}
        (let [body    (:body-params request)
              handler (:handler session)
              output  (:output session)]
          (try
            (when-let [response (handler body)]
              (sse-write! output "message" (json/generate-string response)))
            (catch Exception e
              (log/error e "MCP SSE handler error")
              (sse-write! output "message"
                (json/generate-string
                  {:jsonrpc "2.0"
                   :id      (:id body)
                   :error   {:code    -32603
                             :message (str "Internal error: " (.getMessage e))}}))))
          {:status 202 :body {:status "accepted"}})))))
