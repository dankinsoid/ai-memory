(ns ai-memory.mcp.transport
  "MCP stdio transport: newline-delimited JSON-RPC over stdin/stdout."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.io BufferedReader InputStreamReader
            BufferedWriter OutputStreamWriter]))

(defn read-message
  "Reads one JSON line from reader. Returns parsed map or nil on EOF."
  [^BufferedReader rdr]
  (when-let [line (.readLine rdr)]
    (json/parse-string line true)))

(defn write-message!
  "Writes JSON + newline to writer, flushes. Thread-safe via locking."
  [^BufferedWriter wtr data]
  (locking wtr
    (.write wtr (json/generate-string data))
    (.write wtr "\n")
    (.flush wtr)))

(defn run-loop
  "Main stdio loop. Calls `(handler request)` for each JSON-RPC message.
   Handler returns a response map or nil (for notifications).
   Blocks until stdin EOF."
  [handler]
  (let [rdr (BufferedReader. (InputStreamReader. System/in))
        wtr (BufferedWriter. (OutputStreamWriter. System/out))]
    (log/info "MCP transport: stdio loop started")
    (loop []
      (when-let [request (read-message rdr)]
        (try
          (when-let [response (handler request)]
            (write-message! wtr response))
          (catch Exception e
            (log/error e "Error processing MCP request")
            (write-message! wtr
              {:jsonrpc "2.0"
               :id      (:id request)
               :error   {:code    -32603
                         :message (str "Internal error: " (.getMessage e))}})))
        (recur)))
    (log/info "MCP transport: stdin closed, exiting")))
