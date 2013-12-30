(ns tree.block-file
  (:require [clojure.java.io :as io]
            [tree.block :as block]
            [tree.voodoo :as voodoo])
  (:import [java.io RandomAccessFile]
           [java.nio ByteBuffer IntBuffer LongBuffer MappedByteBuffer]
           [java.nio.channels FileChannel FileChannel$MapMode]))

(def region-size (* 8 1024 1024))

(def retries 3)

;; Each mapping is called a region, and will contain multiple blocks.
;; Blocks are expected to evenly divide into a region, though slack
;; space at the end of a region is permissible. The slack space will
;; be (mod region-size block-size).
;; - nr-blocks is the total number of blocks in a file
;; - block-size is the number of bytes in a block
;; - nr-mapped-regions is a cached value for the count of mapped-byte-buffers.
;; - mapped-byte-buffers is a seq of all regions.
;; - stride is the size of a region
;; - file is the File being mapped
;; - raf is the RandomAccessFile for the file
;; - fc is the FileChannel of the raf

(defrecord BlockFile [nr-blocks block-size nr-mapped-regions
                      mapped-byte-buffers stride
                      file raf fc])

(declare set-nr-blocks!)

(defn open-block-file
  "Opens a file for storing blocks. Returns a structure with the block file
   and the RandomAccessFile that the block file uses. The file will need to be
   closed when block files based on this initial block file are no longer needed."
  [file block-size]
  (let [file (io/file file)
        raf (RandomAccessFile. file "rw")
        fc (.getChannel raf)
        nr-blocks (long (/ (.size fc) block-size))]
    {:block-file (set-nr-blocks! (->BlockFile 0 block-size 0 [] region-size file raf fc) nr-blocks)
     :file raf}))

(defn- system-cleanup
  "Prompt the system to clean up outstanding objects, thereby releasing unique resources
   for re-use. This is required for MappedByteBuffers as the Java NIO cannot release the
   resources explicitly without putting a guard on every access (thereby compromising the
   speed advantages of memory mapping) or allowing continuing access to memory that is
   no longer accessible. Therefore, the resources must be released implicitly (by setting
   all references null) and then calling this code to prompt the system to clean the
   resources up. Depending on the host OS, this method may need to be called several times.
   Linux typically only requires 1 or 2 invocations, while Windows regularly needs more than
   2 and can require >6"
  []
  (System/gc)
  (try (Thread/sleep 100) (catch InterruptedException _))
  (System/runFinalization))

(defn- retry-loop
  "Retries a thunk, using a countdown and a cleanup thunk."
  [action cleanup retries]
  (loop [r retries]
    (let [[response ex] (try [(action) nil] (catch Exception e [nil e]))]
      (or response
          (if (zero? r)
            (throw ex)
            (do
              (cleanup)
              (recur (dec r))))))))

(defn- file-size
  "Gets the size of a block-file. Returns a size."
  [{fc :fc}]
  (.size fc))

(defn- set-length!
  "Sets the length of a block-file.
   Returns the open block-file."
  [{raf :raf :as block-file} len]
  (.setLength raf len)
  block-file)

(defn- map-buffer
  "Maps a buffer in a block-file. Returns a new block-file."
  [{:keys [fc stride] :as block-file} region-nr]
  (retry-loop
   (fn []
     (let [mbb (.map fc FileChannel$MapMode/READ_WRITE (* region-nr stride) region-size)]
       (-> block-file
           (update-in [:mapped-byte-buffers] conj mbb)
           (assoc :nr-mapped-regions (inc region-nr)))))
   system-cleanup
   retries))

(defn map-file!
  "Expands a block-file to one that is mapped to the required number of regions.
   Returns a new block-file with the required mappings."
  [{:keys [nr-mapped-regions stride mapped-byte-buffers fc] :as block-file} regions]
  (let [mapped-size (if (> nr-mapped-regions 0) (+ (* (dec nr-mapped-regions) stride) region-size) 0)
        current-file-size (file-size block-file)
        new-file-size (+ (* (dec regions) stride) region-size)
        _ (when (< current-file-size mapped-size)
            (throw (ex-info (str "File has shrunk: " (:file block-file)))))
        block-file (if (> current-file-size new-file-size)
                     (set-length! block-file new-file-size)
                     block-file)]
    
    (loop [bf block-file region-nr nr-mapped-regions]
      (if (>= region-nr regions)
        bf
        (recur (map-buffer bf region-nr) (inc region-nr))))))

(defn set-nr-blocks!
  "Updates the number of blocks mapped in a block file. Returns the new block-file."
  [{:keys [nr-blocks block-size nr-mapped-regions stride] :as block-file} new-nr]
  (if (= new-nr nr-blocks)
    block-file
    (let [block-file (assoc block-file :nr-blocks new-nr)]
      (if (< new-nr nr-blocks)
        block-file
        (let [regions (if (<= new-nr 0) 0 (inc (/ (* (dec new-nr) block-size) stride)))]
          (if (> regions nr-mapped-regions)
            (map-file! block-file regions)
            block-file))))))

(defn get-nr-blocks
  "Returns the number of blocks"
  [{:keys [nr-blocks]}]
  nr-blocks)

(defn force-file
  "Ensures all cached data is written to disk. This returns synchronously after all data is written."
  [{:keys [mapped-byte-buffers] :as block-file}]
  (doseq [^MappedByteBuffer b mapped-byte-buffers] (.force b))
  block-file)

(defn block-for
  "Returns the byte buffer that references the given block."
  [{:keys [nr-blocks block-size stride mapped-byte-buffers] :as block-file} block-id]
  (when (< block-id 0) (throw (ex-info "Bad block ID" {:id block-id})))
  (when (>= block-id nr-blocks)
    (throw (ex-info "Block ID out of range" {:id block-id :max-id (dec nr-blocks)})))
  (let [file-offset (* block-id block-size)
        region-nr (int (/ file-offset stride))
        offset (mod file-offset stride)]
    (block/create-block block-size offset (nth mapped-byte-buffers region-nr))))

(defn copy-block
  "Allocates a new block with a copy of the original block."
  [{:keys [mapped-byte-buffers block-size stride] :as block-file} {:keys [byte-offset ro] :as block} new-block-id]
  (let [new-file-offset (* new-block-id block-size)
        new-region-nr (int (/ new-file-offset stride))
        new-byte-offset (mod new-file-offset stride)
        new-buffer (nth mapped-byte-buffers new-region-nr)]
    (.limit ro (+ byte-offset block-size))
    (.position ro byte-offset)
    (.position new-buffer new-byte-offset)
    (.put new-buffer ro)
    (block/create-block block-size new-byte-offset new-buffer)))

(defn unmap
  "Throw away mappings. This is dangerous, as it invalidates all instances.
  Only to be used when closing the file for good."
  [{:keys [mapped-byte-buffers block-size nr-blocks] :as block-file}]
  (set-length! block-file (* block-size nr-blocks))
  (voodoo/release mapped-byte-buffers))

(defn clear!
  [{:keys [block-size stride mapped-byte-buffers file raf fc] :as block-file}]
  (voodoo/release mapped-byte-buffers)
  (set-length! block-file 0)
  (->BlockFile 0 block-size 0 [] stride file raf fc))
