(ns discussion-gems.utils.spark
  (:require [sparkling.core :as spark]
            [sparkling.serialization]
            [sparkling.destructuring :as s-de]
            [manifold.deferred :as mfd]
            [sparkling.conf :as conf]
            [sparkling.function]
            [clojure.data.fressian :as fressian]
            [discussion-gems.utils.encoding :as uenc])
  (:import [org.apache.spark.api.java JavaSparkContext JavaRDD JavaRDDLike JavaPairRDD JavaDoubleRDD]
           [org.apache.spark.broadcast Broadcast]
           (scala Tuple2)
           (org.apache.hadoop.io SequenceFile$Writer$Option SequenceFile SequenceFile$Writer Text BytesWritable)
           (org.apache.hadoop.conf Configuration)
           (org.apache.hadoop.fs Path)
           (org.apache.hadoop.mapred SequenceFileOutputFormat)
           (org.apache.hadoop.io.compress DefaultCodec)
           (java.io ByteArrayOutputStream)))



(defn broadcast-var
  "Wrapper for creating a Spark Broadcast Variable."
  [^JavaSparkContext sc, v]
  (.broadcast sc v))

(defn broadcast-value
  "Wrapper for reading a Spark Broadcast Variable."
  [^Broadcast bv]
  (.value bv))


(defn run-local
  "Utility for concisely running Spark jobs locally.
  Given a callback function f accepting a Spark Context,
  returns the result of f wrapped in a Manifold Deferred."
  ([f] (run-local identity f))
  ([prepare-conf f]
   (mfd/future
     (spark/with-context
       sc (-> (conf/spark-conf)
            (conf/master "local[*]")
            (conf/app-name "discussion-gems-local")
            (conf/set {"spark.driver.allowMultipleContexts" "true"})
            prepare-conf)
       (f sc)))))


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

(defn save-to-hadoop-text+fressian-seqfile
  [^String fpath, ^JavaPairRDD rdd]
  (.saveAsHadoopFile
    ^JavaPairRDD
    (spark/map-to-pair
      (fn [p]
        (let [k (s-de/key p)
              v (s-de/value p)]
          (spark/tuple
            (Text. ^String k)
            (BytesWritable.
              (with-open [baos (ByteArrayOutputStream.)]
                (let [wtr (uenc/fressian-writer baos)]
                  (fressian/write-object wtr v)
                  (.flush baos)
                  (.toByteArray baos)))))))
      rdd)
    fpath
    Text BytesWritable
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





(defn diversified-sample
  "See https://vvvvalvalval.github.io/posts/2019-09-13-diversified-sampling-mining-large-datasets-for-special-cases.html"
  ^JavaRDD
  [sc K draw-random get-features, ^JavaRDD rdd]
  (let [
        counting-feature ::counting-feature
        ftr-counts (->> rdd
                     (spark/flat-map
                       (fn [e]
                         (into [counting-feature]
                           (get-features e))))
                     (spark/map-to-pair
                       (fn [k]
                         (spark/tuple k 1)))
                     (spark/reduce-by-key +)
                     (spark/collect-map))
        n-elems (get ftr-counts counting-feature)
        ftr-counts--BV (broadcast-var sc
                         (dissoc ftr-counts counting-feature))]
    (->> rdd
      (spark/filter
        (fn [e]
          (let [present-ftrs (set (get-features e))
                ftr-counts (broadcast-value ftr-counts--BV)
                prob-threshold
                (Math/min
                  1.0
                  (double
                    (reduce-kv
                      (fn [pt ftr M]
                        (Math/max
                          (double pt)
                          (double
                            (/ K
                              (if (contains? present-ftrs ftr)
                                M
                                (- n-elems M))))))
                      0.0
                      ftr-counts)))]
            (> prob-threshold (draw-random e))))))))
