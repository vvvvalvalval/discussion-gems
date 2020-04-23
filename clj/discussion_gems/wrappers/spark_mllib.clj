(ns discussion-gems.wrappers.spark-mllib
  (:import (org.apache.spark.mllib.linalg SparseVector)))

(defn sparse-vector
  ^SparseVector [size indices-arr values-arr]
  (SparseVector.
    (int size)
    ^ints indices-arr
    ^doubles values-arr))
