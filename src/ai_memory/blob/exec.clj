(ns ai-memory.blob.exec
  "Executes bash commands inside blob directories.
   Uses ProcessBuilder with cwd set to the blob dir. When the server runs
   inside Docker, the container itself provides sandboxing."
  (:require [ai-memory.blob.store :as blob-store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent TimeUnit]
           [java.io BufferedReader InputStreamReader]))

(def ^:private default-timeout-ms 30000)
(def ^:private max-output-bytes 102400) ;; 100 KB

(defn validate-blob-dir
  "Validates blob-dir path and returns absolute path. Throws on invalid input.
   Accepts both short names and resolved paths (e.g. projects/ai-memory/...)."
  [base-path blob-dir]
  (when (or (str/blank? blob-dir)
            (str/includes? blob-dir "..")
            (str/starts-with? blob-dir "/"))
    (throw (ex-info "Invalid blob-dir" {:blob-dir blob-dir})))
  (let [dir      (io/file base-path blob-dir)
        abs-dir  (.getCanonicalPath dir)
        abs-base (.getCanonicalPath (io/file base-path))]
    ;; Ensure resolved path stays under base-path
    (when-not (str/starts-with? abs-dir abs-base)
      (throw (ex-info "Invalid blob-dir: escapes base path" {:blob-dir blob-dir})))
    (when-not (.isDirectory dir)
      (throw (ex-info "Blob directory not found" {:blob-dir blob-dir})))
    abs-dir))

(defn- read-stream
  "Reads an InputStream to string in a future (avoids pipe deadlock)."
  [input-stream]
  (future
    (with-open [rdr (BufferedReader. (InputStreamReader. input-stream))]
      (let [sb (StringBuilder.)]
        (loop []
          (let [line (.readLine rdr)]
            (when line
              (when (pos? (.length sb))
                (.append sb "\n"))
              (.append sb line)
              (recur))))
        (.toString sb)))))

(defn truncate-output
  "Truncates string to max-bytes, appending suffix if truncated."
  [s max-bytes]
  (if (or (nil? s) (<= (count s) max-bytes))
    s
    (str (subs s 0 max-bytes) "\n... (truncated)")))

(defn exec-in-dir
  "Executes a shell command in the given directory. Returns {:exit-code :stdout :stderr}."
  [abs-dir command timeout-ms]
  (let [pb (doto (ProcessBuilder. ["sh" "-c" command])
             (.directory (io/file abs-dir)))
        env (.environment pb)
        path (get env "PATH")]
    ;; Minimal env: only PATH
    (.clear env)
    (when path (.put env "PATH" path))
    (let [proc  (.start pb)
          out-f (read-stream (.getInputStream proc))
          err-f (read-stream (.getErrorStream proc))
          done? (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)]
      (when-not done?
        (.destroyForcibly proc)
        (.waitFor proc 5 TimeUnit/SECONDS))
      {:exit-code (if done? (.exitValue proc) -1)
       :stdout    (deref out-f 5000 "")
       :stderr    (if done?
                    (deref err-f 5000 "")
                    "Process timed out")})))

(defn exec-blob
  "Executes a bash command inside a blob directory. Returns {:exit-code :stdout :stderr}.
   Resolves short blob-dir names to their actual filesystem path."
  [config blob-dir command]
  (let [base-path    (:blob-path config)
        resolved-dir (or (blob-store/resolve-blob-dir base-path blob-dir)
                         blob-dir)
        abs-dir      (validate-blob-dir base-path resolved-dir)
        timeout      (or (:blob-exec-timeout config) default-timeout-ms)]
    (log/info "exec-blob" blob-dir command)
    (let [{:keys [exit-code stdout stderr]} (exec-in-dir abs-dir command timeout)]
      {:exit-code exit-code
       :stdout    (truncate-output stdout max-output-bytes)
       :stderr    (truncate-output stderr max-output-bytes)})))
