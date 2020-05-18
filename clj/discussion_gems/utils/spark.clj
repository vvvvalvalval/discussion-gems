(ns discussion-gems.utils.spark
  (:require [sparkling.core :as spark]
            [sparkling.serialization]
            [sparkling.destructuring :as s-de]
            [manifold.deferred :as mfd]
            [sparkling.conf :as conf]
            [sparkling.function]
            [clojure.data.fressian :as fressian]
            [discussion-gems.utils.encoding :as uenc]
            [discussion-gems.utils.misc :as u])
  (:import [org.apache.spark.api.java JavaSparkContext JavaRDD JavaRDDLike JavaPairRDD JavaDoubleRDD]
           [org.apache.spark.broadcast Broadcast]
           (scala Tuple2)
           (org.apache.hadoop.io SequenceFile$Writer$Option SequenceFile SequenceFile$Writer Text BytesWritable)
           (org.apache.hadoop.conf Configuration)
           (org.apache.hadoop.fs Path)
           (org.apache.hadoop.mapred SequenceFileOutputFormat)
           (org.apache.hadoop.io.compress DefaultCodec)
           (java.io ByteArrayOutputStream ByteArrayInputStream)
           (org.apache.spark.sql Dataset RowFactory SparkSession)
           (org.apache.spark.sql.types StructType DataType StructField DataTypes)
           (org.apache.spark.sql.catalyst.expressions GenericRow)))



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
  ([prep-cnf-or-n-threads f]
   (mfd/future
     (spark/with-context
       sc (-> (conf/spark-conf)
            (conf/master "local[*]")
            (conf/app-name "discussion-gems-local")
            (conf/set {"spark.driver.allowMultipleContexts" "true"})
            (as-> cnf
              (cond
                (ifn? prep-cnf-or-n-threads)
                (prep-cnf-or-n-threads cnf)

                (integer? prep-cnf-or-n-threads)
                (conf/master cnf
                  (format "local[%d]" f))))
            prep-cnf-or-n-threads)
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


(defn save-to-hadoop-text+bytes-seqfile
  [^String fpath, ^JavaPairRDD rdd]
  (.saveAsHadoopFile
    ^JavaPairRDD
    (spark/map-to-pair
      (fn [p]
        (let [k (s-de/key p)
              v (s-de/value p)]
          (spark/tuple
            (Text. ^String k)
            (BytesWritable. ^bytes v))))
      rdd)
    fpath
    Text BytesWritable
    SequenceFileOutputFormat
    DefaultCodec))


(defn save-to-hadoop-text+fressian-seqfile
  [^String fpath, ^JavaPairRDD rdd]
  (save-to-hadoop-text+bytes-seqfile
    fpath
    (spark/map-values uenc/fressian-encode
      rdd)))

(defn save-to-hadoop-fressian-seqfile
  [^String fpath, ^JavaRDD rdd]
  (save-to-hadoop-text+fressian-seqfile
    fpath
    (spark/key-by (constantly "")
      rdd)))



(defn from-hadoop-text-sequence-file
  "Reads an String RDD from a Hadoop SequenceFile (discards the keys)."
  ^JavaRDD
  [^JavaSparkContext sc, ^String fpath]
  (->> (.sequenceFile sc fpath Text Text)
    (spark/map
      (fn [k+v]
        (let [^Text v (s-de/value k+v)]
          (.toString v))))))


(defn from-hadoop-text+bytes-sequence-file
  "Reads an String RDD from a Hadoop SequenceFile (discards the keys)."
  ^JavaRDD
  [^JavaSparkContext sc, ^String fpath]
  (->> (.sequenceFile sc fpath Text Text)
    (spark/map-to-pair
      (fn [k+v]
        (let [^Text k (s-de/key k+v)
              ^BytesWritable v (s-de/value k+v)]
          (spark/tuple
            (.toString k)
            (.copyBytes v)))))))

(defn from-hadoop-fressian-sequence-file
  "Reads an RDD from a fressian-encoded Hadoop SequenceFile (discards the keys)."
  ^JavaRDD
  [^JavaSparkContext sc, ^String fpath]
  (->> (from-hadoop-text+bytes-sequence-file sc fpath)
    (spark/map-values uenc/fressian-decode)
    (spark/values)))



(defn sample-rdd-by-key
  [key-fn p rdd]
  (spark/filter
    (fn [x]
      (when-some [k (key-fn x)]
        (let [r (u/draw-random-from-string k)]
          (< r p))))
    rdd))


(defn rdd-java-context
  ^JavaSparkContext
  [^JavaRDDLike rdd]
  (JavaSparkContext.
    (.context rdd)))


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

;; ------------------------------------------------------------------------------
;; Dataframes

(defn add-columns-to-dataframe
  "Transforms a Spark Dataframe by computing and adding new columns to it.

  Given a seq of [^StructField new-field, input-col-names, f] tuples and an input Dataframe,
  creates a new DataFrame with as many new columns, with values computed by applying the function `f`
  to the values corresponding to `input-col-names`, packed in a seq."
  [new-fields+input-col-names+compute-fns
   ^Dataset input-df]
  (let [old-sch (.schema input-df)
        new-sch (StructType.
                  (into-array StructField
                    (into (vec (.fields old-sch))
                      (mapv
                        (fn [[^StructField new-field _ _]]
                          new-field)
                        new-fields+input-col-names+compute-fns))))
        old-row-length (int (count (.fields old-sch)))
        row-length (int (count (.fields new-sch)))]
    (.createDataFrame (.sparkSession input-df)
      ^JavaRDD
      (spark/map
        (let [setter-fns
              (->> new-fields+input-col-names+compute-fns
                (map-indexed
                  (fn [j [^StructField _new-field, input-col-names, f]]
                    (let [deps-pos (int-array
                                     (->> input-col-names
                                       (mapv
                                         (fn [input-col-name]
                                           (.fieldIndex old-sch input-col-name)))))
                          deps-length (int (count deps-pos))
                          dest-idx (int (+ j old-row-length))
                          setter-fn
                          (fn [^objects old-values, ^objects dest-arr]
                            (let [deps-arr (object-array deps-length)]
                              (loop [i (int 0)]
                                (when (< i deps-length)
                                  (aset deps-arr i
                                    (aget old-values
                                      (aget deps-pos i)))
                                  (recur (unchecked-inc-int i))))
                              (let [deps-seq deps-arr ;; NOTE native arrays support destructuring, that's why I'm not converting to another sequence type (Val, 23 Apr 2020)
                                    result (f deps-seq)]
                                (aset dest-arr dest-idx result))))]
                      setter-fn)))
                object-array)]
          (fn [^GenericRow old-row]
            (let [dest-arr (object-array row-length)
                  old-values (.values old-row)]
              (System/arraycopy
                old-values 0
                dest-arr 0
                old-row-length)
              (areduce setter-fns j ret nil
                (let [setter-fn (aget setter-fns j)]
                  (setter-fn old-values dest-arr)))
              (RowFactory/create dest-arr))))
        (.toJavaRDD input-df))
      new-sch)))

(comment "examples of" add-columns-to-dataframe

  [(def sc
     (spark/spark-context
       (-> (conf/spark-conf)
         (conf/master "local[4]")
         (conf/app-name "discussion-gems-local")
         (conf/set {"spark.driver.allowMultipleContexts" "true"}))))

   (def ^SparkSession sprk
     (-> (SparkSession/builder)
       (.sparkContext (JavaSparkContext/toSparkContext sc))
       (.getOrCreate)))]

  (def input-df
    (.createDataFrame sprk
      ^JavaRDD
      (spark/map
        (fn [vs]
          (RowFactory/create
            (object-array vs)))
        (spark/parallelize sc
          [[0 "coucou"]
           [1 "Comment ça va?"]
           [2 "Mythe"]]))
      (StructType.
        (into-array StructField
          [(DataTypes/createStructField "id" DataTypes/LongType false)
           (DataTypes/createStructField "text" DataTypes/StringType true)]))))

  (.show input-df)
  ;+---+--------------+
  ;| id|          text|
  ;+---+--------------+
  ;|  0|        coucou|
  ;|  1|Comment ça va?|
  ;|  2|         Mythe|
  ;+---+--------------+

  (def dest-df
    (add-columns-to-dataframe
      [[(DataTypes/createStructField "id_text" DataTypes/StringType true)
        ["text" "id"]
        (fn [[text id]]
          (str id "_" text))]]
      input-df))

  (.show dest-df)
  ;+---+--------------+----------------+
  ;| id|          text|         id_text|
  ;+---+--------------+----------------+
  ;|  0|        coucou|        0_coucou|
  ;|  1|Comment ça va?|1_Comment ça va?|
  ;|  2|         Mythe|         2_Mythe|
  ;+---+--------------+----------------+


  (.stop sc)

  *e)


(defn extract-column-from-dataframe
  "Convenience function from transforming a column of Dataframe into an RDD."
  ^JavaRDD [^String col-name, ^Dataset input-df]
  (spark/map
    (let [col-idx (.fieldIndex
                    (.schema input-df)
                    col-name)]
      (fn extract-col [^GenericRow row]
        (.get row col-idx)))
    (.toJavaRDD
      input-df)))

(defn col-reader-fn
  [df col-name]
  (let [col-idx (int (.fieldIndex ^StructType (.schema df) ^String col-name))]
    (fn read-col-in-row [^GenericRow row]
      (.get row col-idx))))



;; ------------------------------------------------------------------------------
;; Relational

(defn flow-parent-value-to-children
  ;; TODO restructure to get rid of is-node? predicate, error-prone, (Val, 27 Apr 2020)
  [is-node? get-id get-parent-id get-value conj-value-to-child nodes-rdd]
  (->> nodes-rdd
    (spark/flat-map-to-pair
      (fn [node]
        (into [(spark/tuple
                 (get-id node)
                 node)]
          (when-some [pid (get-parent-id node)]
            (let [child-id (get-id node)]
              [(spark/tuple pid child-id)])))))
    (spark/group-by-key)
    (spark/flat-map-to-pair
      (fn [pid->vs]
        (let [pid (s-de/key pid->vs)
              vs (s-de/value pid->vs)
              parent (->> vs
                       (filter is-node?)
                       first)]
          (vec
            (when (some? parent)
              (let [v (get-value parent)]
                (into [(spark/tuple pid parent)]
                  (comp
                    (remove is-node?)
                    (distinct)
                    (map (fn [child-id]
                           (spark/tuple child-id v))))
                  vs)))))))
    (spark/reduce-by-key
      (fn [v1 v2]
        (if (is-node? v1)
          (conj-value-to-child v1 v2)
          (conj-value-to-child v2 v1))))
    (spark/values)))


(comment

  @(run-local
     (fn [sc]
       (->>
         (spark/parallelize sc
           [{:id "a"
             :contents #{"A"}}
            {:id "b"
             :contents #{"B"}}
            {:id "ca"
             :parent_id "a"
             :contents #{"C"}}
            {:id "db"
             :parent_id "b"
             :contents #{"D"}}
            {:id "ec"
             :parent_id "ca"
             :contents #{"E"}}
            {:id "fz"
             :parent_id "z"
             :contents #{"F"}}])
         (flow-parent-value-to-children
           map? :id :parent_id :contents
           (fn [node parent-contents]
             (update node :contents into parent-contents)))
         (spark/collect) (sort-by :id) vec)))
  =>
  [{:id "a", :contents #{"A"}}
   {:id "b", :contents #{"B"}}
   {:id "ca", :parent_id "a", :contents #{"C" "A"}}
   {:id "db", :parent_id "b", :contents #{"B" "D"}}
   {:id "ec", :parent_id "ca", :contents #{"E" "C"}}
   {:id "fz", :parent_id "z", :contents #{"F"}}]

  *e)