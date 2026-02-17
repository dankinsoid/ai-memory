(ns ai-memory.blob.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
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
    (is (= "00-initial-requirements.md"
           (store/make-section-filename 0 "Initial requirements")))
    (is (= "03-schema-changes.md"
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
  (testing "reads section by index from meta.edn"
    (let [dir-name "2026-02-17_test-blob"
          meta {:sections [{:file "00-first.md" :summary "First"}
                           {:file "01-second.md" :summary "Second"}]}]
      (store/write-meta! *base-path* dir-name meta)
      (store/write-section! *base-path* dir-name "01-second.md" "Content of second")
      (let [result (store/read-section-by-index *base-path* dir-name 1)]
        (is (= "Second" (get-in result [:meta :summary])))
        (is (= "Content of second" (:content result)))))))

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
