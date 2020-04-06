(ns discussion-gems.utils.spark
  (:require [sparkling.core :as spark]
            [sparkling.serialization]
            [sparkling.destructuring :as s-de]
            [manifold.deferred :as mfd]
            [sparkling.conf :as conf]
            [sparkling.function])
  (:import [org.apache.spark.api.java JavaSparkContext JavaRDD JavaRDDLike JavaPairRDD JavaDoubleRDD]
           [org.apache.spark.broadcast Broadcast]
           (scala Tuple2)))


(defn run-local
  "Utility for concisely running Spark jobs locally.
  Given a callback function f accepting a Spark Context,
  returns the result of f wrapped in a Manifold Deferred."
  [f]
  (mfd/future
    (spark/with-context
      sc (-> (conf/spark-conf)
           (conf/master "local[*]")
           (conf/app-name "discussion-gems-local")
           (conf/set {"spark.driver.allowMultipleContexts" "true"}))
      (f sc))))
