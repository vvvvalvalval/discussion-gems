(ns discussion-gems.utils.misc
  (:require [buddy.core.codecs]
            [buddy.core.hash])
  (:import (java.util Properties Arrays)))

(defn group-and-map-by
  [kf vf coll]
  (persistent!
    (reduce
      (fn [tm e]
        (let [k (kf e)
              v (vf e)]
          (assoc! tm k
            (conj
              (get tm k [])
              v))))
      (transient {})
      coll)))


(defn as-java-props
  ^Properties [m]
  (reduce-kv
    (fn [props k v]
      (.setProperty ^Properties props
        (name k)
        ^String v)
      props)
    (Properties.)
    m))


(defn draw-random-from-string
  "Pseudo-randomly draws a number in uniformly [0;1) from a String input, typically an id."
  [^String id-str]
  (double
    (/
      (-'
        (long Integer/MAX_VALUE)
        (-> id-str hash long))
      (-' (long Integer/MAX_VALUE) (long Integer/MIN_VALUE)))))
