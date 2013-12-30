(ns tree.block
  (:import [java.nio ByteBuffer IntBuffer LongBuffer]))

(def sizeof-int 4)
(def sizeof-long 8)

(defrecord Block [^ByteBuffer bb ^IntBuffer ib ^LongBuffer lb
                  ^ByteBuffer ro
                  block-size
                  byte-offset int-offset long-offset])

(defn- new-block
  [bb ib lb ro block-size byte-offset]
  (let [ib (or ib (-> bb .rewind .asIntBuffer))
        lb (or lb (-> bb .rewind .asLongBuffer))
        ro (or ro (.asReadOnlyBuffer bb))
        int-offset (bit-shift-right byte-offset 2)
        long-offset (bit-shift-right byte-offset 3)]
    (->Block bb ib lb ro block-size byte-offset int-offset long-offset)))

(defn ^Block create-block
  ([size byte-offset byte-buffer ro-byte-buffer int-buffer long-buffer]
   (new-block byte-buffer int-buffer long-buffer ro-byte-buffer size byte-offset))
  ([size byte-offset byte-buffer]
   (new-block byte-buffer nil nil nil size byte-offset)))

(defn ^ByteBuffer get-source-buffer
  ([^Block b] (or (:ro b) (.asReadOnlyBuffer (:bb b))))
  ([^Block b offset length]
   (let [start (+ (:byte-offset b) offset)]
     (doto (get-source-buffer b)
           (.limit (+ start length))
           (.position start)))))

(defn ^byte get-byte [^Block b offset]
  (.get (:bb b) (+ (:byte-offset b) offset)))

(defn ^int get-int [^Block b offset]
  (.get (:ib b) (+ (:int-offset b) offset)))

(defn ^long get-long [^Block b offset]
  (.get (:lb b) (+ (:long-offset b) offset)))

(defn ^ByteBuffer copy-to-buffer! [^Block b ^ByteBuffer buffer offset]
  (let [pos (+ (:byte-offset b) offset)]
    (.put buffer (doto (.asReadOnlyBuffer (:bb b))
                       (.position pos)
                       (.limit (+ pos (.remaining buffer)))))
    buffer))

(defn ^ByteBuffer slice [^Block b offset size]
  (let [pos (+ (:byte-offset b) offset)]
    (.slice (doto (.asReadOnlyBuffer (:bb b))
                  (.position pos)
                  (.limit (+ pos size))))))

(declare put-block!)

(defn ^Block copy-over! [^Block b ^Block dest offset]
  (put-block! dest 0 b offset (:block-size dest)))

(defn get-bytes [^Block b offset len]
  (let [^ByteBuffer bb (.duplicate (:bb b))
        start (+ (:byte-offset b) offset)
        arr (byte-array len)]
    (doto bb
      (.position start)
      (.limit (+ start len))
      (.get arr))
    arr))

(defn get-ints [^Block b offset len]
  (let [^IntBuffer ib (.duplicate (:ib b))
        start (+ (:int-offset b) offset)
        arr (int-array len)]
    (doto ib
      (.position start)
      (.limit (+ start len))
      (.get arr))
    arr))

(defn get-longs [^Block b offset len]
  (let [^LongBuffer lb (:lb b)
        start (+ (:long-offset b) offset)
        arr (long-array len)]
    (doto lb
      (.position start)
      (.limit (+ start len))
      (.get arr))
    arr))

(defn ^Block put-byte! [^Block b offset v]
  (.put (:bb b) (+ (:byte-offset b) offset) v)
  b)

(defn ^Block put-int! [^Block b offset v]
  (.put (:ib b) (+ (:int-offset b) offset) v)
  b)

(defn ^Block put-long! [^Block b offset v]
  (.put (:lb b) (+ (:long-offset b) offset) v)
  b)

;; a single writer allows for position/put

(defn ^Block put-bytes! [^Block b offset ^bytes the-bytes]
  (doto (:bb b) (.position (+ (:byte-offset b) offset)) (.put the-bytes))
  b)

(defn ^Block put-ints! [^Block b offset ^ints the-ints]
  (doto (:ib b) (.position (+ (:int-offset b) offset)) (.put the-ints))
  b)

(defn ^Block put-longs! [^Block b offset ^longs the-longs]
  (doto (:lb b) (.position (+ (:long-offset b) offset)) (.put the-longs))
  b)

(defn ^Block put-buffer! [^Block b offset ^ByteBuffer buffer]
  (doto (:bb b) (.position (+ (:byte-offset b) offset)) (.put buffer))
  b)

(defn ^Block put-block!
  ([^Block b offset ^Block src] (put-block! b offset src 0 (:block-size src)))
  ([^Block b offset ^Block src src-offset length]
   (doto (:bb b)
         (.position (+ (:byte-offset b) offset))
         (.put (get-source-buffer src src-offset length)))
   b))

