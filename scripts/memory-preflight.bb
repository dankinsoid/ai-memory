#!/usr/bin/env bb
;; PreToolUse hook: ensures remote ai-memory blob storage is accessible via SSHFS.
;; Only acts on memory_* tool calls. Never blocks — warns on stderr if setup fails.
;;
;; MCP env vars (in ~/.claude/settings.json → mcpServers.ai-memory.env):
;;   AI_MEMORY_SSH        — SSH host, e.g. "user@server.com" (absent = local, skip)
;;   AI_MEMORY_BLOB_PATH  — remote blob dir (default "~/.ai-memory/blobs")
;;   AI_MEMORY_BLOB_MOUNT — local mount point (default "~/ai-memory-blobs")

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as p])

;; --- Read hook input ---

(def input (json/parse-string (slurp *in*) true))
(def tool-name (or (:tool_name input) ""))

;; Only act on memory_* tools
(when-not (str/starts-with? tool-name "memory_")
  (System/exit 0))

;; --- Read MCP config ---

(def home (System/getenv "HOME"))

(defn read-settings []
  (let [path (str home "/.claude/settings.json")]
    (when (.exists (java.io.File. path))
      (json/parse-string (slurp path) true))))

(def settings (read-settings))
(def mcp-env (get-in settings [:mcpServers :ai-memory :env]))

(def ssh-host (or (:AI_MEMORY_SSH mcp-env)
                   (System/getenv "AI_MEMORY_SSH")))
(def blob-remote-path (or (:AI_MEMORY_BLOB_PATH mcp-env)
                           (System/getenv "AI_MEMORY_BLOB_PATH")
                           "~/.ai-memory/blobs"))
(def blob-mount (or (:AI_MEMORY_BLOB_MOUNT mcp-env)
                     (System/getenv "AI_MEMORY_BLOB_MOUNT")
                     (str home "/ai-memory-blobs")))

;; --- Local mode: nothing to do ---

(when (str/blank? ssh-host)
  (System/exit 0))

;; --- Remote mode ---

(defn warn [msg]
  (binding [*out* *err*]
    (println (str "[memory-preflight] " msg))))

(defn mounted? []
  (let [{:keys [out]} (p/shell {:out :string :err :string :continue true} "mount")]
    (str/includes? out blob-mount)))

;; Already mounted — fast path
(when (mounted?)
  (System/exit 0))

;; Check SSH access
(let [{:keys [exit]} (p/shell {:continue true :out :string :err :string}
                       "ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=5" ssh-host "true")]
  (when-not (zero? exit)
    (warn (str "Cannot SSH to " ssh-host ". Configure SSH keys for remote blob access."))
    (System/exit 0)))

;; Mount SSHFS
(p/shell "mkdir" "-p" blob-mount)
(let [{:keys [exit err]} (p/shell {:continue true :out :string :err :string}
                           "sshfs"
                           (str ssh-host ":" blob-remote-path)
                           blob-mount
                           "-o" "ro,reconnect,ServerAliveInterval=15")]
  (if (zero? exit)
    (warn (str "Mounted " ssh-host ":" blob-remote-path " → " blob-mount))
    (warn (str "SSHFS mount failed: " err))))

(System/exit 0)
