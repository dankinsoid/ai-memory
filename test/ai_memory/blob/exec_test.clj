(ns ai-memory.blob.exec-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [ai-memory.blob.exec :as exec]))

(def ^:dynamic *base-path* nil)

(defn with-temp-dir [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "exec-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (binding [*base-path* (.getPath dir)]
      (try
        (f)
        (finally
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(use-fixtures :each with-temp-dir)

;; --- validate-blob-dir ---

(deftest validate-rejects-path-traversal
  (testing "rejects .."
    (is (thrown? Exception (exec/validate-blob-dir *base-path* "foo/../bar"))))
  (testing "rejects absolute path"
    (is (thrown? Exception (exec/validate-blob-dir *base-path* "/etc/passwd"))))
  (testing "rejects slash in name"
    (is (thrown? Exception (exec/validate-blob-dir *base-path* "foo/bar"))))
  (testing "rejects blank"
    (is (thrown? Exception (exec/validate-blob-dir *base-path* "")))))

(deftest validate-accepts-valid-dir
  (let [dir-name "2026-02-20_test-blob"]
    (.mkdirs (io/file *base-path* dir-name))
    (let [result (exec/validate-blob-dir *base-path* dir-name)]
      (is (string? result))
      (is (.endsWith result dir-name)))))

(deftest validate-rejects-nonexistent-dir
  (is (thrown? Exception (exec/validate-blob-dir *base-path* "nonexistent"))))

;; --- truncate-output ---

(deftest truncate-short-string
  (is (= "hello" (exec/truncate-output "hello" 100))))

(deftest truncate-nil
  (is (nil? (exec/truncate-output nil 100))))

(deftest truncate-long-string
  (let [s (apply str (repeat 200 "x"))
        result (exec/truncate-output s 50)]
    (is (.startsWith result (apply str (repeat 50 "x"))))
    (is (.endsWith result "(truncated)"))))

;; --- exec-in-dir ---

(deftest exec-echo
  (let [dir-name "2026-02-20_test-exec"
        dir (io/file *base-path* dir-name)]
    (.mkdirs dir)
    (let [result (exec/exec-in-dir (.getPath dir) "echo hello" 5000)]
      (is (= 0 (:exit-code result)))
      (is (= "hello" (:stdout result)))
      (is (= "" (:stderr result))))))

(deftest exec-captures-stderr
  (let [dir-name "2026-02-20_test-stderr"
        dir (io/file *base-path* dir-name)]
    (.mkdirs dir)
    (let [result (exec/exec-in-dir (.getPath dir) "echo err >&2" 5000)]
      (is (= 0 (:exit-code result)))
      (is (= "err" (:stderr result))))))

(deftest exec-nonzero-exit
  (let [dir-name "2026-02-20_test-exit"
        dir (io/file *base-path* dir-name)]
    (.mkdirs dir)
    (let [result (exec/exec-in-dir (.getPath dir) "exit 42" 5000)]
      (is (= 42 (:exit-code result))))))

(deftest exec-timeout
  (let [dir-name "2026-02-20_test-timeout"
        dir (io/file *base-path* dir-name)]
    (.mkdirs dir)
    (let [result (exec/exec-in-dir (.getPath dir) "sleep 60" 500)]
      (is (= -1 (:exit-code result)))
      (is (= "Process timed out" (:stderr result))))))

;; --- exec-blob integration ---

(deftest exec-blob-cat-file
  (let [dir-name "2026-02-20_test-cat"
        dir (io/file *base-path* dir-name)]
    (.mkdirs dir)
    (spit (io/file dir "compact.md") "session summary")
    (let [cfg    {:blob-path *base-path*}
          result (exec/exec-blob cfg dir-name "cat compact.md")]
      (is (= 0 (:exit-code result)))
      (is (= "session summary" (:stdout result))))))

(deftest exec-blob-ls
  (let [dir-name "2026-02-20_test-ls"
        dir (io/file *base-path* dir-name)]
    (.mkdirs dir)
    (spit (io/file dir "meta.edn") "{}")
    (spit (io/file dir "compact.md") "")
    (let [cfg    {:blob-path *base-path*}
          result (exec/exec-blob cfg dir-name "ls")]
      (is (= 0 (:exit-code result)))
      (is (.contains (:stdout result) "compact.md"))
      (is (.contains (:stdout result) "meta.edn")))))
