(ns ai-memory.web.mcp
  "MCP SSE transport — exposes MCP protocol over HTTP Server-Sent Events.
   GET  /mcp/sse     → SSE stream via StreamableResponseBody (explicit flush per event)
   POST /mcp/message → receives JSON-RPC, queues response to SSE stream"
  (:require [ai-memory.mcp.protocol :as protocol]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [ring.core.protocols :as ring-protocols])
  (:import [java.util UUID]
           [java.util.concurrent ConcurrentHashMap LinkedBlockingQueue]))

(defonce ^:private sessions (ConcurrentHashMap.))

(def ^:private sentinel ::close)

(defn- sse-bytes
  "Returns UTF-8 bytes for one SSE event."
  [event-type data]
  (.getBytes (str "event: " event-type "\ndata: " data "\n\n") "UTF-8"))

(defn- sse-stream-body
  "Returns a StreamableResponseBody that writes SSE events to the output stream.
   Sends the initial endpoint event immediately (with flush), then blocks on
   the queue for subsequent events. Each event is flushed individually so the
   client receives it without waiting for the buffer to fill."
  [session-id ^LinkedBlockingQueue queue]
  (reify ring-protocols/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (try
        ;; Send endpoint event immediately
        (.write out (sse-bytes "endpoint" (str "/mcp/message?sessionId=" session-id)))
        (.flush out)
        ;; Block until each subsequent message arrives
        (loop []
          (let [item (.take queue)]
            (when (not= item sentinel)
              (.write out ^bytes item)
              (.flush out)
              (recur))))
        (catch Exception e
          (log/info "MCP SSE session closed:" session-id (.getMessage e)))
        (finally
          (.remove sessions session-id))))))

(defn sse-handler
  "Returns Ring handler for GET /mcp/sse."
  [mcp-cfg]
  (fn [_request]
    (let [session-id (str (UUID/randomUUID))
          queue      (LinkedBlockingQueue.)
          handler    (protocol/make-handler mcp-cfg)]
      (.put sessions session-id {:queue queue :handler handler})
      (log/info "MCP SSE session created:" session-id)
      {:status  200
       :headers {"Content-Type"  "text/event-stream"
                 "Cache-Control" "no-cache"
                 "Connection"    "keep-alive"}
       :body    (sse-stream-body session-id queue)})))

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
              ^LinkedBlockingQueue queue (:queue session)]
          (try
            (when-let [response (handler body)]
              (.put queue (sse-bytes "message" (json/generate-string response))))
            (catch Exception e
              (log/error e "MCP SSE handler error")
              (.put queue (sse-bytes "message"
                            (json/generate-string
                              {:jsonrpc "2.0"
                               :id      (:id body)
                               :error   {:code    -32603
                                         :message (str "Internal error: " (.getMessage e))}})))))
          {:status 202 :body {:status "accepted"}})))))
