(ns discussion-gems.config.fressian
  (:require [clojure.data.fressian :as fressian])
  (:import
    (java.util List)
    (clojure.lang IPersistentSet IPersistentVector)
    (org.fressian StreamingWriter Writer)
    (org.fressian.handlers WriteHandler ReadHandler)))


(def custom-write-handlers
  {IPersistentSet
   {"clj/set"
    (reify WriteHandler
      (write [_ w coll]
        (.writeTag w "clj/set" 1)
        (.beginClosedList ^StreamingWriter w)
        (reduce
          (fn [^Writer w v]
            (.writeObject w v))
          w
          coll)
        (.endList ^StreamingWriter w)))}
   IPersistentVector
   {"clj/vec"
    (reify WriteHandler
      (write [_ w coll]
        (.writeTag w "clj/vec" 1)
        (.beginClosedList ^StreamingWriter w)
        (reduce
          (fn [^Writer w v]
            (.writeObject w v))
          w
          coll)
        (.endList ^StreamingWriter w)))}})

(def custom-read-handlers
  {"clj/set"
   (reify ReadHandler
     (read [_ rdr _tag _component-count]
       (let [coll ^List (.readObject rdr)]
         (set coll))))
   "clj/vec"
   (reify ReadHandler
     (read [_ rdr _tag _component-count]
       (let [coll ^List (.readObject rdr)]
         (vec coll))))})

(def write-handlers
  (->
    (merge
      fressian/clojure-write-handlers
      custom-write-handlers)
    fressian/associative-lookup
    fressian/inheritance-lookup))

(def read-handlers
  (fressian/associative-lookup
    (merge
      fressian/clojure-read-handlers
      custom-read-handlers)))

