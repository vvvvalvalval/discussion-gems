(ns discussion-gems.utils.spark
  (:require [sparkling.core :as spark]
            [sparkling.serialization]
            [sparkling.destructuring :as s-de]
            [manifold.deferred :as mfd]
            [sparkling.conf :as conf]
            [sparkling.function])
  (:import [org.apache.spark.api.java JavaSparkContext JavaRDD JavaRDDLike JavaPairRDD JavaDoubleRDD]
           [org.apache.spark.broadcast Broadcast]
           (scala Tuple2)
           (org.apache.hadoop.io SequenceFile$Writer$Option SequenceFile SequenceFile$Writer Text)
           (org.apache.hadoop.conf Configuration)
           (org.apache.hadoop.fs Path)
           (org.apache.hadoop.mapred SequenceFileOutputFormat)
           (org.apache.hadoop.io.compress DefaultCodec)))


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


(defn save-to-hadoop-text+text-seqfile
  [^String fpath, ^JavaPairRDD rdd]
  (.saveAsHadoopFile
    ^JavaPairRDD
    (->> rdd
      (spark/map-to-pair
        (fn [k+v]
          (let [k (s-de/key k+v)
                v (s-de/value k+v)]
            (spark/tuple
              (Text. ^String k)
              (Text. ^String v))))))
    fpath
    Text Text
    SequenceFileOutputFormat
    DefaultCodec))

(defn from-hadoop-text-sequence-file
  "Reads an String RDD from a Hadoop SequenceFile (discards the keys)."
  ^JavaRDD
  [^JavaSparkContext sc, ^String fpath]
  (->> (.sequenceFile sc fpath Text Text)
    (spark/map
      (fn [k+v]
        (let [^Text v (s-de/value k+v)]
          (.toString v))))))
