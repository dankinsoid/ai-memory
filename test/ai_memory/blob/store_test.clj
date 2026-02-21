(ns ai-memory.blob.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai-memory.blob.store :as store]))

(def ^:dynamic *base-path* nil)

(defn with-temp-dir [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "blob-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (binding [*base-path* (.getPath dir)]
      (try
        (f)
        (finally
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(use-fixtures :each with-temp-dir)

;; --- slugify / naming ---

(deftest make-blob-dir-name-test
  (testing "creates date_slug directory name"
    (let [name (store/make-blob-dir-name *base-path* "Blob Storage Design"
                                          :date "2026-02-17")]
      (is (= "2026-02-17_blob-storage-design" name))))

  (testing "handles collision by appending -2"
    (let [name1 (store/make-blob-dir-name *base-path* "Test" :date "2026-01-01")]
      (.mkdirs (io/file *base-path* name1))
      (let [name2 (store/make-blob-dir-name *base-path* "Test" :date "2026-01-01")]
        (is (= "2026-01-01_test-2" name2))))))

(deftest make-section-filename-test
  (testing "creates numbered slug filename"
    (is (= "0000-initial-requirements.md"
           (store/make-section-filename 0 "Initial requirements")))
    (is (= "0003-schema-changes.md"
           (store/make-section-filename 3 "Schema changes")))))

;; --- write / read ---

(deftest write-and-read-meta-test
  (testing "writes and reads meta.edn"
    (let [dir-name "2026-02-17_test-blob"
          meta {:id "test" :type :conversation :title "Test"}]
      (store/write-meta! *base-path* dir-name meta)
      (is (.exists (io/file *base-path* dir-name "meta.edn")))
      (is (= meta (store/read-meta *base-path* dir-name))))))

(deftest write-and-read-section-test
  (testing "writes and reads a section file"
    (let [dir-name "2026-02-17_test-blob"]
      (store/write-section! *base-path* dir-name "01-intro.md" "Hello world")
      (is (= "Hello world"
             (store/read-section *base-path* dir-name "01-intro.md"))))))

(deftest read-section-by-index-test
  (testing "reads section by index with sidecar meta"
    (let [dir-name "2026-02-17_test-blob"
          content  (apply str (repeat 25 "line\n"))] ;; 25 lines → triggers sidecar
      (store/write-section! *base-path* dir-name "0001-second.md" content)
      (store/write-section-meta! *base-path* dir-name "0001-second.md"
        {:file "0001-second.md" :summary "Second" :lines 25})
      (let [result (store/read-section-by-index *base-path* dir-name 1)]
        (is (= "Second" (get-in result [:meta :summary])))
        (is (= content (:content result))))))

  (testing "reads section by index without sidecar — derives meta from file"
    (let [dir-name "2026-02-17_test-blob2"]
      (store/write-section! *base-path* dir-name "0000-intro.md" "Hello\nWorld")
      (let [result (store/read-section-by-index *base-path* dir-name 0)]
        (is (= "0000-intro.md" (get-in result [:meta :file])))
        (is (= 2 (get-in result [:meta :lines])))
        (is (= "Hello\nWorld" (:content result)))))))

(deftest list-blob-dirs-test
  (testing "lists directories sorted newest first"
    (store/write-meta! *base-path* "2026-02-15_older" {:id 1})
    (store/write-meta! *base-path* "2026-02-17_newer" {:id 2})
    (store/write-meta! *base-path* "2026-02-16_middle" {:id 3})
    (is (= ["2026-02-17_newer" "2026-02-16_middle" "2026-02-15_older"]
           (store/list-blob-dirs *base-path*)))))

(deftest blob-exists-test
  (testing "returns true only when meta.edn exists"
    (is (not (store/blob-exists? *base-path* "nonexistent")))
    (store/write-meta! *base-path* "2026-02-17_exists" {:id 1})
    (is (store/blob-exists? *base-path* "2026-02-17_exists"))))

;; --- Session chunks ---

(deftest append-current-chunk-test
  (testing "creates _current.md and appends content"
    (let [dir "2026-02-20_session-test"
          r1  (store/append-current-chunk! *base-path* dir "## Turn 1\nHello\n\n")]
      (is (pos? (:line-count r1)))
      (is (pos? (:byte-count r1)))
      (is (.exists (io/file *base-path* dir "_current.md")))))

  (testing "appends to existing _current.md"
    (let [dir "2026-02-20_session-test"
          r2  (store/append-current-chunk! *base-path* dir "## Turn 2\nWorld\n\n")]
      (is (> (:line-count r2) 3))
      (let [content (slurp (io/file *base-path* dir "_current.md"))]
        (is (str/includes? content "Turn 1"))
        (is (str/includes? content "Turn 2"))))))

(deftest list-chunks-empty-test
  (testing "returns nil for nonexistent dir"
    (is (nil? (store/list-chunks *base-path* "nonexistent")))))

(deftest list-chunks-test
  (testing "lists numbered chunks sorted, _current.md last"
    (let [dir "2026-02-20_session-chunks"]
      (store/write-section! *base-path* dir "0001-first-topic.md" "Content 1\n")
      (store/write-section! *base-path* dir "0002-second-topic.md" "Content 2\n")
      (store/append-current-chunk! *base-path* dir "Current content\n")
      ;; Also write meta.edn and compact.md — should be excluded
      (store/write-meta! *base-path* dir {:id "test"})
      (store/write-section! *base-path* dir "compact.md" "Summary")
      (let [chunks (store/list-chunks *base-path* dir)]
        (is (= 3 (count chunks)))
        (is (= "0001-first-topic.md" (:file (first chunks))))
        (is (= "0002-second-topic.md" (:file (second chunks))))
        (is (= "_current.md" (:file (last chunks))))
        (is (= 0 (:index (first chunks))))
        (is (= 2 (:index (last chunks))))))))

(deftest rename-current-chunk-test
  (testing "renames _current.md to numbered slug file"
    (let [dir "2026-02-20_session-rename"]
      (store/append-current-chunk! *base-path* dir "Some content\n")
      (let [filename (store/rename-current-chunk! *base-path* dir "designed-auth-system")]
        (is (= "0001-designed-auth-system.md" filename))
        (is (.exists (io/file *base-path* dir filename)))
        (is (not (.exists (io/file *base-path* dir "_current.md")))))))

  (testing "increments chunk number correctly"
    (let [dir "2026-02-20_session-rename"]
      (store/append-current-chunk! *base-path* dir "More content\n")
      (let [filename (store/rename-current-chunk! *base-path* dir "fixed-tests")]
        (is (= "0002-fixed-tests.md" filename)))))

  (testing "returns nil when no _current.md exists"
    (is (nil? (store/rename-current-chunk! *base-path* "2026-02-20_session-rename" "nothing")))))

(deftest find-session-blob-dir-test
  (testing "finds blob dir by session-id in meta.edn"
    (let [dir "2026-02-20_session-find"]
      (store/write-meta! *base-path* dir {:session-id "abc-123" :type :session})
      (is (= dir (store/find-session-blob-dir *base-path* "abc-123")))))

  (testing "returns nil for unknown session-id"
    (is (nil? (store/find-session-blob-dir *base-path* "unknown")))))
