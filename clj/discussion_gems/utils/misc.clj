(ns discussion-gems.utils.misc
  (:require [buddy.core.codecs]
            [buddy.core.hash])
  (:import (java.util Properties Arrays)))


(defn remove-nil-vals
  "Transforms a map by removing entries with a nil value."
  [m]
  (when (some? m)
    (persistent!
      (reduce-kv
        (fn [tm k v]
          (if (nil? v)
            (dissoc! tm k)
            tm))
        (transient m)
        m))))

(defn index-and-map-by
  [kf vf coll]
  (persistent!
    (reduce
      (fn [tm e]
        (let [k (kf e)
              v (vf e)]
          (assoc! tm k v)))
      (transient {})
      coll)))

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


(defn take-first-and-last
  "Returns the first `n-first` and the last `n-last` elements of `coll` in a single list,
  making sure there's no overlap."
  [n-first n-last coll]
  (let [n (count coll)
        m (+ n-first n-last)]
    (if (< m n)
      (concat
        (take n-first coll)
        (take-last n-last coll))
      coll)))


(defn rename-keys
  [m oldk->newk]
  (reduce
    (fn [ret [oldk newk]]
      (if (contains? ret oldk)
        (-> ret
          (assoc newk (get ret oldk))
          (dissoc oldk))
        ret))
    m
    oldk->newk))



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


;; TODO use MurMur3 or some stable hash implementation for reproducibility (Val, 29 Apr 2020)
(defn draw-random-from-string
  "Pseudo-randomly draws a number in uniformly [0;1) from a String input, typically an id."
  [^String id-str]
  (double
    (/
      (-'
        (long Integer/MAX_VALUE)
        (-> id-str hash long))
      (-' (long Integer/MAX_VALUE) (long Integer/MIN_VALUE)))))

(defn draw-n-by
  [rand-fn n coll]
  (->> coll
    (mapv
      (fn [e]
        [(rand-fn e) e]))
    (sort-by first)
    (into []
      (comp
        (take n)
        (map second)))))

(comment

  (draw-n-by
    draw-random-from-string
    2
    ["bonjour" "comment" "allez" "vous" "ce" "soir"])
  => ["allez" "comment"]

  *e)


(defn decreasing
  [x y]
  (compare y x))



(defn arr-doubles-sum
  [^doubles arr]
  (areduce arr idx s 0.
    (+ (double s) (aget arr idx))))


(defn throw-stop!
  "Handy for killing loops."
  []
  (throw (ex-info "stop!" {})))
