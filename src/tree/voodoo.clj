(ns tree.voodoo
  (:require [clojure.string :as s])
  (:import [java.security AccessController PrivilegedAction]))

(def windows? (>= (.indexOf (s/lower-case (System/getProperty "os.name" "")) "win") 0))

(defn clean [obj]
  (when obj
    (AccessController/doPrivileged
     (proxy [PrivilegedAction] []
       (run [_]
         (try
           (let [get-cleaner-method (.getMethod (class obj) "cleaner" (make-array Class 0))
                 _ (.setAccessible get-cleaner-method true)
                 cleaner (.invoke get-cleaner-method obj (make-array Object 0))]
             (.clean cleaner))
           (catch Exception e (println "non-fatal buffer cleanup error"))))))))

(defn release [mapped]
  (when windows?
    (doseq [b mapped] (clean b))))
