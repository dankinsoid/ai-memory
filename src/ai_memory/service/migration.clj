;; @ai-generated(guided)
(ns ai-memory.service.migration
  "Export/import snapshots for migrating data between backends.

   Export produces a ZIP archive:
     snapshot.json  — facts, edges, vectors (optional), tick, tags
     blobs/         — blob directory tree (files as-is)

   snapshot.json format:
   {:version 1
    :tick    N
    :tags    [{:name \"foo\" :tier :aspect} ...]
    :nodes   [{:snapshot-id 0 :content \"...\" :weight 0.5 :cycle 30
               :tags [\"t1\" \"t2\"] :blob-dir \"...\" ...} ...]
    :edges   [{:from-snapshot-id 0 :to-snapshot-id 1
               :weight 0.3 :cycle 10 :type :co-occurrence} ...]
    :vectors [{:snapshot-id 0 :vector [...] :payload {...}} ...]}

   On import, nodes are matched by content for merge (upsert semantics).
   Tick cycles are adjusted to preserve fact ages across both existing
   and imported data."
  (:require [ai-memory.store.protocols :as p]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util.zip ZipOutputStream ZipInputStream ZipEntry]
           [java.util Date]
           [java.text SimpleDateFormat]))

;; ---------------------------------------------------------------------------
;; Date helpers
;; ---------------------------------------------------------------------------

(defn- parse-date
  "Coerces a value to java.util.Date. Accepts Date, ISO-8601 string, or nil."
  [v]
  (cond
    (instance? Date v) v
    (string? v)        (.parse (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss") v)
    :else              nil))

;; ---------------------------------------------------------------------------
;; Blob I/O helpers
;; ---------------------------------------------------------------------------

(defn- collect-blob-files
  "Recursively collects all files under blob-path/blob-dir.
   Returns seq of {:relative-path \"blobs/dir/file\" :file java.io.File}."
  [blob-path blob-dir]
  (let [dir (io/file blob-path blob-dir)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (mapv (fn [f]
                   (let [rel (.relativize (.toPath (io/file blob-path))
                                          (.toPath f))]
                     {:relative-path (str "blobs/" rel)
                      :file          f})))))))

(defn- write-blob-files!
  "Writes blob files from a map of {relative-path → byte-array} to disk.
   Paths are relative to blob-path (e.g. \"projects/ai-memory/2026-03-03_foo/meta.edn\")."
  [blob-path blob-files]
  (doseq [[rel-path content-bytes] blob-files]
    (let [f (io/file blob-path rel-path)]
      (.mkdirs (.getParentFile f))
      (io/copy (ByteArrayInputStream. content-bytes) f)))
  (count blob-files))

;; ---------------------------------------------------------------------------
;; ZIP packaging
;; ---------------------------------------------------------------------------

(defn- snapshot->zip-bytes
  "Packages snapshot map + blob files into a ZIP byte array.
   `snapshot`  — the snapshot map (will be serialized as snapshot.json)
   `blob-path` — filesystem base path for blobs
   `blob-dirs` — set of blob-dir strings referenced by nodes"
  [snapshot blob-path blob-dirs]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [zos (ZipOutputStream. baos)]
      ;; 1. Write snapshot.json
      (.putNextEntry zos (ZipEntry. "snapshot.json"))
      (let [json-bytes (.getBytes (json/generate-string snapshot) "UTF-8")]
        (.write zos json-bytes 0 (count json-bytes)))
      (.closeEntry zos)
      ;; 2. Write blob files
      (doseq [blob-dir blob-dirs
              :when blob-dir
              {:keys [relative-path file]} (collect-blob-files blob-path blob-dir)]
        (.putNextEntry zos (ZipEntry. relative-path))
        (io/copy file zos)
        (.closeEntry zos)))
    (.toByteArray baos)))

(defn- zip-bytes->parts
  "Reads a ZIP byte array and returns {:snapshot <map> :blob-files {rel-path → bytes}}.
   blob-files paths are relative to blob-path (without 'blobs/' prefix)."
  [^bytes zip-bytes]
  (with-open [zis (ZipInputStream. (ByteArrayInputStream. zip-bytes))]
    (loop [snapshot nil
           blob-files {}]
      (if-let [entry (.getNextEntry zis)]
        (let [name (.getName entry)
              content (.readAllBytes zis)]
          (.closeEntry zis)
          (cond
            (= name "snapshot.json")
            (recur (json/parse-string (String. content "UTF-8") true) blob-files)

            (.startsWith name "blobs/")
            ;; Strip "blobs/" prefix → path relative to blob-path
            (recur snapshot (assoc blob-files (subs name 6) content))

            :else
            (recur snapshot blob-files)))
        {:snapshot snapshot :blob-files blob-files}))))

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(defn- build-snapshot-map
  "Builds the snapshot data map (without blobs — those go in the ZIP separately).
   Returns the snapshot map + set of blob-dirs for packaging."
  [ctx {:keys [include-vectors] :or {include-vectors true}}]
  (let [fs   (:fact-store ctx)
        vs   (:vector-store ctx)
        tick (p/current-tick fs)

        node-eids (p/all-nodes fs)
        eid->sid  (zipmap node-eids (range))

        nodes (mapv (fn [eid]
                      (let [node (p/find-node fs eid)
                            tags (p/node-tags fs eid)]
                        {:snapshot-id (eid->sid eid)
                         :content     (:node/content node)
                         :weight      (:node/weight node)
                         :cycle       (:node/cycle node)
                         :tags        (vec tags)
                         :blob-dir    (:node/blob-dir node)
                         :sources     (:node/sources node)
                         :session-id  (:node/session-id node)
                         :created-at  (:node/created-at node)
                         :updated-at  (:node/updated-at node)}))
                    node-eids)

        all-tags (p/all-tags fs)
        tags-export (filterv :tag/tier
                             (mapv (fn [t]
                                     (cond-> {:name (:tag/name t)}
                                       (:tag/tier t) (assoc :tier (:tag/tier t))))
                                   all-tags))

        all-edges (p/all-edges fs)
        edges (filterv
                #(and (:from-snapshot-id %) (:to-snapshot-id %))
                (mapv (fn [e]
                        (let [from-eid (get-in e [:edge/from :db/id])
                              to-eid   (get-in e [:edge/to :db/id])]
                          {:from-snapshot-id (eid->sid from-eid)
                           :to-snapshot-id   (eid->sid to-eid)
                           :weight           (:edge/weight e)
                           :cycle            (:edge/cycle e)
                           :type             (:edge/type e)}))
                      all-edges))

        vectors (when include-vectors
                  (let [all-vectors (p/scroll-all vs)]
                    (filterv
                      some?
                      (mapv (fn [pt]
                              (when-let [sid (eid->sid (:id pt))]
                                {:snapshot-id sid
                                 :vector      (:vector pt)
                                 :payload     (:payload pt)}))
                            all-vectors))))

        blob-dirs (into #{} (keep :blob-dir) nodes)

        snapshot (cond-> {:version 1
                          :tick    tick
                          :tags    tags-export
                          :nodes   nodes
                          :edges   edges}
                   include-vectors (assoc :vectors vectors))]

    (log/info "Exported snapshot:" (count nodes) "nodes,"
              (count edges) "edges,"
              (if include-vectors (str (count vectors) " vectors,") "no vectors,")
              (count blob-dirs) "blob dirs, tick=" tick)

    {:snapshot snapshot :blob-dirs blob-dirs}))

(defn export-snapshot-zip
  "Exports database state as a ZIP byte array containing snapshot.json + blobs/.
   `ctx`  — service context with :fact-store, :vector-store, :blob-path.
   `opts` — {:include-vectors true/false} (default true).
   Returns byte array of the ZIP archive."
  ([ctx] (export-snapshot-zip ctx {}))
  ([ctx opts]
   (let [{:keys [snapshot blob-dirs]} (build-snapshot-map ctx opts)
         blob-path (:blob-path ctx)]
     (snapshot->zip-bytes snapshot blob-path blob-dirs))))

;; ---------------------------------------------------------------------------
;; Import
;; ---------------------------------------------------------------------------

(defn- build-content-index
  "Builds a content → {:eid E :updated-at T} map of all existing nodes.
   Used for merge: match by content, then compare updated-at to pick winner."
  [fs]
  (reduce (fn [m eid]
            (if-let [node (p/find-node fs eid)]
              (assoc m (:node/content node)
                     {:eid        eid
                      :updated-at (:node/updated-at node)})
              m))
          {}
          (p/all-nodes fs)))

(defn- adjust-existing-cycles!
  "Shifts cycles of all existing nodes and edges forward by `delta` ticks.
   Preserves their relative ages when the imported snapshot has a higher tick."
  [fs delta]
  (when (pos? delta)
    (log/info "Adjusting existing fact cycles by +" delta)
    ;; Adjust node cycles
    (doseq [eid (p/all-nodes fs)]
      (when-let [node (p/find-node fs eid)]
        (let [old-cycle (or (:node/cycle node) 0)]
          (p/update-node-cycle! fs eid (+ old-cycle delta)))))
    ;; Adjust edge cycles
    (doseq [e (p/all-edges fs)]
      (let [old-cycle (or (:edge/cycle e) 0)]
        (p/update-edge-cycle! fs (:edge/id e) (+ old-cycle delta))))))

(defn- import-snapshot-map!
  "Imports a snapshot map into the current database with merge semantics.
   Nodes matched by content are updated; new nodes are created.
   Tick cycles are adjusted to preserve fact ages for both existing
   and imported data.

   `ctx`      — service context with :fact-store, :vector-store.
   `snapshot` — parsed snapshot map.
   Returns {:imported-nodes N :imported-edges N :imported-vectors N :new-tick N}."
  [ctx snapshot]
  (let [fs   (:fact-store ctx)
        vs   (:vector-store ctx)
        {:keys [tick nodes edges vectors tags]} snapshot

        target-tick    (p/current-tick fs)
        new-tick       (max target-tick tick)
        import-delta   (- new-tick tick)          ;; shift for imported facts
        existing-delta (- new-tick target-tick)    ;; shift for existing facts

        _ (log/info "Import: snapshot-tick=" tick "target-tick=" target-tick
                    "new-tick=" new-tick "import-delta=" import-delta
                    "existing-delta=" existing-delta)

        ;; Step 1: Adjust existing facts' cycles (if target had data)
        _ (adjust-existing-cycles! fs existing-delta)

        ;; Step 2: Ensure all tags exist, set tiers
        _ (doseq [{:keys [name tier]} tags]
            (p/ensure-tag! fs name)
            ;; Tier is set during DB seed for aspect tags; custom tiers
            ;; would need a separate protocol method. For now, ensure-tag
            ;; is sufficient since aspect tags are seeded on startup.
            )
        _ (doseq [n nodes
                  tag-name (:tags n)]
            (p/ensure-tag! fs tag-name))

        ;; Step 3: Build content index for merge matching
        content->eid (build-content-index fs)

        ;; Step 4: Import nodes, build snapshot-id → new-eid mapping
        sid->eid (reduce
                   (fn [m {:keys [snapshot-id content weight cycle tags
                                  blob-dir sources session-id created-at updated-at]}]
                     (let [adjusted-cycle (+ (or cycle 0) import-delta)
                           existing       (get content->eid content)
                           existing-eid   (:eid existing)]
                       (if existing-eid
                         ;; Merge: keep the more recently updated version
                         (let [imp-date      (parse-date updated-at)
                               ext-date      (:updated-at existing)
                               import-newer? (or (nil? ext-date)
                                                 (and imp-date
                                                      (.after imp-date ext-date)))]
                           ;; Always map snapshot-id and update cycle
                           (p/update-node-cycle! fs existing-eid adjusted-cycle)
                           ;; Only overwrite weight/tags/blob when import is newer
                           (when import-newer?
                             (p/update-node-weight! fs existing-eid (or weight 0.0))
                             (when (seq tags)
                               (p/replace-node-tags! fs existing-eid tags))
                             (when blob-dir
                               (p/set-node-blob-dir! fs existing-eid blob-dir)))
                           (assoc m snapshot-id existing-eid))
                         ;; New: create via import-node!
                         (let [result (p/import-node! fs
                                        {:content    content
                                         :weight     (or weight 0.0)
                                         :cycle      adjusted-cycle
                                         :tags       tags
                                         :blob-dir   blob-dir
                                         :sources    sources
                                         :session-id session-id
                                         :created-at created-at
                                         :updated-at updated-at})]
                           (assoc m snapshot-id (:id result))))))
                   {}
                   nodes)

        ;; Step 5: Import edges
        edges-imported
        (reduce (fn [cnt {:keys [from-snapshot-id to-snapshot-id weight cycle type]}]
                  (let [from-eid (sid->eid from-snapshot-id)
                        to-eid   (sid->eid to-snapshot-id)]
                    (if (and from-eid to-eid)
                      (let [adjusted-cycle (+ (or cycle 0) import-delta)]
                        ;; Skip if edge already exists between these nodes
                        (when-not (p/find-edge-between fs from-eid to-eid)
                          (p/import-edge! fs {:from   from-eid
                                              :to     to-eid
                                              :weight (or weight 0.0)
                                              :cycle  adjusted-cycle
                                              :type   type}))
                        (inc cnt))
                      cnt)))
                0
                edges)

        ;; Step 6: Import vectors with new entity IDs (skip if snapshot has none)
        vectors-imported
        (if (seq vectors)
          (reduce (fn [cnt {:keys [snapshot-id vector payload]}]
                    (if-let [new-eid (sid->eid snapshot-id)]
                      (do (p/upsert! vs new-eid vector payload)
                          (inc cnt))
                      cnt))
                  0
                  vectors)
          0)

        ;; Step 7: Set tick and reconcile tag counts
        _ (p/set-tick! fs new-tick)
        _ (p/reconcile-tag-counts! fs)]

    (log/info "Import complete:" (count nodes) "nodes processed,"
              edges-imported "edges," vectors-imported "vectors, tick=" new-tick)

    {:imported-nodes   (count nodes)
     :imported-edges   edges-imported
     :imported-vectors vectors-imported
     :new-tick         new-tick}))

(defn import-snapshot-zip!
  "Imports a ZIP archive (as byte array) into the current database.
   Unpacks snapshot.json + blob files, then delegates to import logic.
   `ctx`       — service context with :fact-store, :vector-store, :blob-path.
   `zip-bytes` — byte array of the ZIP archive.
   Returns {:imported-nodes N :imported-edges N :imported-vectors N :imported-blobs N :new-tick N}."
  [ctx zip-bytes]
  (let [{:keys [snapshot blob-files]} (zip-bytes->parts zip-bytes)
        blob-path (:blob-path ctx)
        blobs-written (when (and blob-path (seq blob-files))
                        (write-blob-files! blob-path blob-files))
        result (import-snapshot-map! ctx snapshot)]
    (assoc result :imported-blobs (or blobs-written 0))))
