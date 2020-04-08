(ns discussion-gems.utils.misc
  (:import (java.util Properties)))

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
