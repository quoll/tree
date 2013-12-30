(ns tree.block-file-test
  (:require [tree.voodoo :as voodoo]
            [tree.util :as util])
  (:use [clojure.test]
        [tree.block-file]
        [tree.block :only [put-byte! put-bytes! put-int! get-byte get-int get-bytes]])
  (:import [java.nio.charset Charset]))

(def bf (atom nil))
(def af (atom nil))

(def test-block-size 256)

(def str0 "String in block 0.")

(def str1 "String in block 1.")

(def str2 "String in block 2.")

(def str3 "String in block 3.")

(defn cleanup [f]
  (let [filename (util/temp-file "mbftest")
        {:keys [block-file file]} (open-block-file filename test-block-size)]
    (try
      (reset! af file)
      (reset! bf block-file)
      (f)
      (finally
        (clear! @bf)
        (.close @af)
        (when voodoo/windows?
         (System/gc)
         (System/runFinalization))))))

(use-fixtures :each cleanup)

(def utf8 (Charset/forName "UTF-8"))

(defn put-string! [b s]
  (let [^bytes bytes (.getBytes s utf8)]
    (put-byte! b 0 (count bytes))
    (put-bytes! b 1 bytes)))

(defn get-string [b]
  (let [l (get-byte b 0)
        d (get-bytes b 1 l)]
    (String. d utf8)))



(deftest test-allocate
  (reset! bf (set-nr-blocks! @bf 1))
  (let [blk (block-for @bf 0)]
    (is (not (nil? blk)))))

(deftest test-write
  (reset! bf (set-nr-blocks! @bf 4))
  (let [b (block-for @bf 0)]
    (put-string! b str0))
  (let [b (block-for @bf 3)]
    (put-string! b str3))
  (let [b (block-for @bf 2)]
    (put-string! b str2))
  (let [b (block-for @bf 1)]
    (put-string! b str1))

  (let [b (block-for @bf 2)]
    (is (= str2 (get-string b))))
  (let [b (block-for @bf 0)]
    (is (= str0 (get-string b))))
  (let [b (block-for @bf 1)]
    (is (= str1 (get-string b))))
  (let [b (block-for @bf 3)]
    (is (= str3 (get-string b))))

  ;; close all, and start again
  (unmap @bf)
  (.close @af)
  (when voodoo/windows? (System/gc) (System/runFinalization))
  (let [filename (util/temp-file "mbftest")
        {:keys [block-file file]} (open-block-file filename test-block-size)]
    (reset! af file)
    (reset! bf block-file))

  ;; does it persist

  (is (= 4 (get-nr-blocks @bf)))

  (let [b (block-for @bf 2)]
    (is (= str2 (get-string b))))
  (let [b (block-for @bf 0)]
    (is (= str0 (get-string b))))
  (let [b (block-for @bf 1)]
    (is (= str1 (get-string b))))
  (let [b (block-for @bf 3)]
    (is (= str3 (get-string b)))))

(deftest test-performance
  (reset! bf (clear! @bf))
  (let [nr-blocks 100000]
    (reset! bf (set-nr-blocks! @bf nr-blocks))

    (doseq [i (range nr-blocks)]
      (let [b (block-for @bf i)]
        (put-int! b 0 (+ i 5))))

    (doseq [i (range nr-blocks)]
      (let [b (block-for @bf i)]
        (is (= (+ i 5) (get-int b 0)))))

    (doseq [pass (range 10)]
      (doseq [i (range nr-blocks)]
        (let [b (block-for @bf i)]
          (put-int! b 0 (bit-xor i pass))))
      (doseq [i (range nr-blocks)]
        (let [b (block-for @bf i)]
          (is (= (bit-xor i pass) (get-int b 0))))))))
