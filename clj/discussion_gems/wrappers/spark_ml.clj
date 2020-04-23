(ns discussion-gems.wrappers.spark-ml
  (:require [discussion-gems.wrappers.spark-mllib])
  (:import (org.apache.spark.ml.linalg SparseVector)))

(defn mllib-sparse-vector
  "Converts a Spark-ML SparseVector to a Spark-MLLib SparseVector."
  [^SparseVector ml-sparse-vector]
  (discussion-gems.wrappers.spark-mllib/sparse-vector
    (.size ml-sparse-vector)
    (.indices ml-sparse-vector)
    (.values ml-sparse-vector)))
