(ns ai-memory.web.mcp
  "MCP SSE transport — exposes MCP protocol over HTTP Server-Sent Events.
   GET  /mcp/sse     → SSE stream (ISeq body, ring-jetty flushes after each chunk)
   POST /mcp/message → receives JSON-RPC, queues response to SSE stream"
  (:require [ai-memory.mcp.protocol :as protocol]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.util.concurrent ConcurrentHashMap LinkedBlockingQueue TimeUnit]))

(defonce ^:private sessions (ConcurrentHashMap.))

(def ^:private sentinel ::close)

(defn- sse-event
  "Returns SSE event string."
  [event-type data]
  (str "event: " event-type "\ndata: " data "\n\n"))

(defn- queue-seq
  "Lazy seq that reads from a BlockingQueue, blocking between elements.
   Terminates when sentinel value is dequeued."
  [^LinkedBlockingQueue q]
  (lazy-seq
    (let [item (.take q)]
      (when (not= item sentinel)
        (cons item (queue-seq q))))))

(defn sse-handler
  "Returns Ring handler for GET /mcp/sse.
   Body is a lazy ISeq — ring-jetty-adapter flushes after each element,
   so SSE events reach the client immediately without buffering issues."
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
       :body    (cons (sse-event "endpoint" (str "/mcp/message?sessionId=" session-id))
                      (queue-seq queue))})))

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
              (.put queue (sse-event "message" (json/generate-string response))))
            (catch Exception e
              (log/error e "MCP SSE handler error")
              (.put queue (sse-event "message"
                            (json/generate-string
                              {:jsonrpc "2.0"
                               :id      (:id body)
                               :error   {:code    -32603
                                         :message (str "Internal error: " (.getMessage e))}})))))
          {:status 202 :body {:status "accepted"}})))))
