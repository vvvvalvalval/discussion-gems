(ns discussion-gems.sandbox.clustering
  (:require [sparkling.conf :as conf]
            [discussion-gems.utils.spark :as uspark]
            [sparkling.core :as spark]
            [discussion-gems.utils.encoding :as uenc]
            [clojure.java.io :as io]
            [discussion-gems.parsing :as parsing]
            [clojure.reflect :as reflect]
            [sparkling.destructuring :as s-de]
            [clojure.string :as str]
            [manifold.deferred :as mfd]
            [clojure.data.fressian :as fressian]
            [discussion-gems.utils.misc :as u]
            [discussion-gems.algs.spark.dimensionality-reduction :as dim-red])
  (:import (org.apache.spark.sql Dataset Row SparkSession RowFactory)
           (org.apache.spark.ml.feature CountVectorizer CountVectorizerModel)
           (org.apache.spark.ml.clustering LDA LDAModel KMeans)
           (org.apache.spark.api.java JavaSparkContext JavaRDD)
           (org.apache.spark.sql.types DataTypes)
           (org.apache.spark.sql.catalyst.expressions GenericRow GenericRowWithSchema)
           (org.apache.spark.sql functions)
           (java.util.zip GZIPOutputStream)
           (scala.collection.mutable WrappedArray$ofRef)
           (org.apache.spark.ml.linalg SparseVector DenseVector Vectors VectorUDT SQLDataTypes)
           (org.apache.lucene.analysis.fr FrenchAnalyzer)))

(require '[sc.api])

(comment
  ;; https://spark.apache.org/docs/2.1.0/sql-programming-guide.html

  (do
    (def sc
      (spark/spark-context
        (-> (conf/spark-conf)
          (conf/master "local[2]")
          (conf/app-name "discussion-gems-local")
          (conf/set {"spark.driver.allowMultipleContexts" "true"}))))

    (def ^SparkSession sprk
      (-> (SparkSession/builder)
        (.sparkContext (JavaSparkContext/toSparkContext sc))
        (.getOrCreate))))




  (def rdd-comments
    (spark/parallelize sc
      (uenc/json-read
        (io/resource "reddit-france-comments-dv-sample.json"))))

  (->> (uenc/json-read
         (io/resource "reddit-france-comments-dv-sample.json")))


  (->> (uenc/json-read
         (io/resource "reddit-france-comments-dv-sample.json"))
    (run!
      (fn [c]
        (try
          (RowFactory/create
            (object-array
              (let [body-md (:body c)
                    body-raw (parsing/trim-markdown body-md)]
                [(:id c)
                 (:score c)
                 (or body-md "")
                 (or body-raw "")
                 (if (some? body-raw)
                   (to-array
                     (parsing/split-words-fr body-raw))
                   (object-array []))])))
          (catch Throwable err
            (prn err)
            (RowFactory/create
              (object-array [""])))))))

  (do
    (def comments-rows
      (->> rdd-comments
        (spark/map
          (fn [c]
            (try
              (RowFactory/create
                (object-array
                  (let [body-md (:body c)
                        body-raw (parsing/trim-markdown body-md)]
                    [(or (:id c) "")
                     (or (:score c) 0)
                     (or body-md "")
                     (or body-raw "")
                     (if (some? body-raw)
                       (to-array
                         (parsing/split-words-fr body-raw))
                       (object-array []))])))
              (catch Throwable err
                (prn err)
                (RowFactory/create
                  (object-array [""]))))))))

    (def comments-schema
      (DataTypes/createStructType
        [(DataTypes/createStructField "id" DataTypes/StringType true)
         (DataTypes/createStructField "score" DataTypes/IntegerType true)
         (DataTypes/createStructField "body_md" DataTypes/StringType true)
         (DataTypes/createStructField "body_raw" DataTypes/StringType true)
         (DataTypes/createStructField "body_words"
           (DataTypes/createArrayType DataTypes/StringType true)
           true)]))


    (def comments-df
      (.createDataFrame sprk comments-rows comments-schema)))


  (spark/count comments-rows)

  (-> comments-df
    (.collectAsList)
    vec)

  (-> comments-df
    (.take 10)
    (->>
      (mapv (fn [^GenericRowWithSchema row]
              (->> row
                (.values)
                (vec))))))

  (.printSchema comments-df)
  ;root
  ; |-- id: string (nullable = false)
  ; |-- score: integer (nullable = true)
  ; |-- body: string (nullable = true)


  (.describe comments-df (into-array String []))


  (do
    (.collectAsList
      (-> comments-df))
    nil)

  ;; Vectorization
  ;; https://spark.apache.org/docs/2.1.0/ml-features.html#countvectorizer
  ;; https://spark.apache.org/docs/2.1.0/api/java/org/apache/spark/ml/feature/CountVectorizer.html

  (def ^CountVectorizerModel cv-model
    (-> (CountVectorizer.)
      (.setInputCol "body_words")
      (.setOutputCol "body_bow")
      (.setVocabSize 10000)
      (.fit
        (-> comments-df
          (.filter
            (.isNotNull (functions/col "body_words")))))))

  (into []
    (take 20)
    (.vocabulary cv-model))
  => ["de" "le" "la" "est" "les" "et" "que" "l'" "un" "a" "pas" "des" "en" "d'" "une" "pour" "qui" "c'" "je" "qu'"]

  (def df1
    (.transform cv-model comments-df))

  (.printSchema df1)
  (.show df1 10)


  (def ^LDAModel lda-model
    (-> (LDA.)
      (.setK 100)
      (.setFeaturesCol "body_bow")
      (.fit df1)))

  (.printSchema (.describeTopics lda-model))
  (.show (.describeTopics lda-model) false)

  (->> (.describeTopics lda-model)
    .collectAsList
    (mapv (fn [^GenericRowWithSchema row]
            (-> row
              (.values)
              (vec)
              (nth 1)
              .array
              (->>
                (mapv
                  (fn [term-i]
                    (aget (.vocabulary cv-model) term-i))))))))

  (def df2 (.transform lda-model df1))
  (.printSchema df2)

  (.show df2 10)


  *e)




(defn x->xlnx
  ^double [x]
  (let [x (double x)]
    (if (or (= 0. x) (= 1. x))
      0.
      (* x
        (Math/log x)))))
(defn h2
  ^double [p]
  (let [p (double p)]
    (/
      (+
        (x->xlnx p)
        (x->xlnx (- 1. p)))
      (Math/log 0.5))))


(defn label-clusters-with-words-PMI
  [{vocab-size ::vocab-size
    n-labels ::n-labels
    :or {vocab-size 10000
         n-labels 20}}
   extract-word-ids extract-cluster-assignment
   sc docs-rdd]
  (let [wid->n-docs
        (->> docs-rdd
          (spark/flat-map-to-pair
            (fn [doc]
              (-> (extract-word-ids doc)
                (->> (map
                       (fn [wid]
                         (spark/tuple wid 1))))
                (conj
                  (spark/tuple -1 1)))))
          (spark/reduce-by-key +)
          (spark/map-to-pair
            (fn [wid+n-docs]
              (spark/tuple
                (s-de/value wid+n-docs)
                (s-de/key wid+n-docs))))
          (spark/sort-by-key compare false)
          (spark/take (inc vocab-size))
          (u/index-and-map-by s-de/value s-de/key))
        n-docs (get wid->n-docs -1)
        wid->n-docs (dissoc wid->n-docs -1)
        wid->n-docs--bv (uspark/broadcast-var sc wid->n-docs)]
    (->> docs-rdd
      (spark/flat-map-to-pair
        (fn [doc]
          (let [wid->n-docs (uspark/broadcast-value wid->n-docs--bv)
                word-ids (set (extract-word-ids doc))
                cla (extract-cluster-assignment doc)]
            (into []
              (comp
                (map-indexed
                  (fn [cluster-id p] [cluster-id p]))
                (mapcat
                  (fn [[cluster-id p]]
                    (into [(spark/tuple
                             (spark/tuple cluster-id -1)
                             p)]
                      (comp
                        (filter #(contains? wid->n-docs %))
                        (map
                          (fn [wid]
                            (spark/tuple
                              (spark/tuple cluster-id wid)
                              p))))
                      (set word-ids)))))
              cla))))
      (spark/reduce-by-key +)
      (spark/map-to-pair
        (fn [cid+wid->n]
          (let [cid+wid (s-de/key cid+wid->n)
                cid (s-de/key cid+wid)
                wid (s-de/value cid+wid)
                n (s-de/value cid+wid->n)]
            (spark/tuple
              cid
              (spark/tuple wid n)))))
      (spark/group-by-key)
      (spark/map-values
        (fn [wid+ns]
          (let [wid->n-docs (uspark/broadcast-value wid->n-docs--bv)
                n-docs-in-c
                (-> wid+ns
                  (->>
                    (keep
                      (fn [wid+n]
                        (let [wid (s-de/key wid+n)]
                          (when (= -1 wid)
                            (s-de/value wid+n))))))
                  (first) (or 0))]
            {:n-docs-in-cluster n-docs-in-c
             :characteristic-words
             (if (zero? n-docs-in-c)
               []
               (->> wid+ns
                 (keep
                   (fn [wid+n]
                     (let [wid (s-de/key wid+n)]
                       (when-not (= -1 wid)
                         (let [n-in-c-with-wid (s-de/value wid+n)
                               n-docs-with-wid (get wid->n-docs wid) ;; seems to be always 1 TODO
                               MI-score
                               (-
                                 (+
                                   (h2 (/ n-docs-with-wid n-docs)) ;; IMPROVEMENT pre-compute (Val, 16 Apr 2020)
                                   (h2 (/ n-docs-in-c n-docs)))
                                 ;; FIXME factor out
                                 (/
                                   (+
                                     (x->xlnx (/ n-in-c-with-wid
                                                n-docs))
                                     (x->xlnx (/ (- n-docs-in-c n-in-c-with-wid)
                                                n-docs))
                                     (x->xlnx (/ (- n-docs-with-wid n-in-c-with-wid)
                                                n-docs))
                                     (x->xlnx (/ (- n-docs
                                                   (+
                                                     n-docs-in-c
                                                     (- n-docs-with-wid n-in-c-with-wid)))

                                                n-docs)))
                                   (Math/log 0.5)))]
                           (when (>
                                   (/ n-in-c-with-wid n-docs-in-c)
                                   (/ n-docs-with-wid n-docs)))
                           [wid MI-score n-in-c-with-wid n-docs-with-wid n-docs])))))
                 (sort-by second u/decreasing)
                 (into []
                   (take n-labels))))})))
      (spark/map
        (s-de/fn [(cluster-id cluster-data)]
          (assoc cluster-data
            :cluster-id cluster-id))))))


(defn add-cluster-samples
  [{:as _opts, n-examples ::n-example-docs
    :or {n-examples 12}}
   id-col-name
   extract-cla-arr
   clusters-label-summary
   ^Dataset df]
  (let [cluster-id->p-threshold
        (double-array
          (->> clusters-label-summary
            (sort-by :cluster-id)
            (mapv (fn [cluster]
                    (* 5.                                   ;; NOTE reduces the probability of sampling too few. (Val, 17 Apr 2020)
                      n-examples
                      (/ 1. (:n-docs-in-cluster cluster)))))))
        min-threshold (double (apply min (seq cluster-id->p-threshold)))
        cluster-id->sampled
        (->> df
          .toJavaRDD
          (spark/flat-map
            (let [schm (.schema df)
                  id-col-i (.fieldIndex schm id-col-name)]
              (fn [^GenericRow row]
                (vec
                  (let [id (.get row (int id-col-i))
                        s (u/draw-random-from-string id)]
                    (when (< s min-threshold)
                      (let [cla-ps (extract-cla-arr row)]
                        (areduce cla-ps cluster-id sel []
                          (let [cluster-threshold (*
                                                    (aget cla-ps cluster-id)
                                                    (aget cluster-id->p-threshold cluster-id))]
                            (cond-> sel
                              (< s cluster-threshold) (conj [cluster-id row])))))))))))
          (spark/filter some?)
          (spark/collect)
          (u/group-and-map-by first second))]
    (->> clusters-label-summary
      (mapv
        (fn [cluster]
          (assoc cluster
            :doc-examples
            (into []
              (take n-examples)
              (shuffle
                (get cluster-id->sampled (:cluster-id cluster) [])))))))))




(comment


  @(uspark/run-local
     (fn [sc]
       (->>
         (spark/parallelize sc
           [{:text "La pie niche haut l'oie niche bas. Mais le hibou niche où? Le hibou ne niche ni haut ni bas. Le hibou ne niche pas."
             :clusters [0.01 0.99]}
            {:text "L'ornythologie se préoccupe de l'étude des oiseaux tels que le hibou."
             :clusters [0.1 0.9]}
            {:text "Les espaces propres d'un endomorphisme symmétrique décomposent l'espace en somme directe orthogonale."
             :clusters [0. 1.]}
            {:text "Les endomorphismes sont les applications linéaires d'un espace vectoriel dans lui-meme."
             :clusters [0.02 0.98]}])
         (label-clusters-with-words-PMI
           {}
           #(-> % :text (str/split #"\W+") set)
           #(-> % :clusters double-array)
           sc)
         (spark/collect-map))))

  *e)






(def min-comment-body-length
  (* 5 20))


(def flairs-of-interest
  #{"Société"
    "Politique"
    "Économie"
    "News"
    ;"Forum Libre"
    ;"Culture"
    "Science"
    "Écologie"})


(def txt-contents-df-schema
  (DataTypes/createStructType
    [(DataTypes/createStructField "id" DataTypes/StringType false)
     (DataTypes/createStructField "txt_contents_raw"
       (DataTypes/createArrayType DataTypes/StringType false)
       true)
     (DataTypes/createStructField "txt_contents_words"
       (DataTypes/createArrayType DataTypes/StringType false)
       true)]))




(defn txt-contents-df
  [sprk subm-rdd comments-rdd]
  (let [data-rdd
        (->>
          (spark/union
            (->> comments-rdd
              (spark/map #(parsing/backfill-reddit-name "t1_" %))
              (spark/map
                (fn [c]
                  (merge
                    (select-keys c [:name :parent_id :link_id])
                    (when-some [body-raw (parsing/trim-markdown
                                           {::parsing/remove-quotes true
                                            ::parsing/remove-code true}
                                           (:body c))]
                      {:dgms_body_raw body-raw}))))
              (spark/filter
                (fn interesting-comment [c]
                  (when-some [body-raw (:dgms_body_raw c)]
                    (-> body-raw count (>= min-comment-body-length)))))
              (spark/map
                (fn [c]
                  (let [body_raw (:dgms_body_raw c)]
                    (assoc
                      (select-keys c [:name :parent_id :link_id])
                      :dgms_txt_contents [body_raw])))))
            (->> subm-rdd
              (spark/map #(parsing/backfill-reddit-name "t3_" %))
              (spark/map
                (fn [s]
                  (assoc (select-keys s [:name :link_flair_text])
                    :dgms_txt_contents
                    (into []
                      (remove nil?)
                      [(some-> s :title
                         (->> (parsing/trim-markdown {::parsing/remove-quotes true ::parsing/remove-code true})))
                       (some-> s :selftext
                         (->> (parsing/trim-markdown {::parsing/remove-quotes true ::parsing/remove-code true})))]))))))
          (uspark/flow-parent-value-to-children
            (fn node? [v]
              (not
                (or (nil? v) (string? v))))
            :name
            :link_id
            (fn get-flair [m]
              (let [ret (:link_flair_text m)]
                (when (and (some? ret) (string? ret))
                  ret)))
            (fn conj-parent-flair [child flair]
              (try
                (assoc child :link_flair_text flair)
                (catch Throwable err
                  (sc.api/spy flair)
                  (throw err)))))
          (spark/filter
            (fn contents-length-above-threshold? [m]
              (-> m :dgms_txt_contents
                (->> (map count) (reduce + 0))
                (>= min-comment-body-length))))
          (spark/filter
            #(contains? flairs-of-interest
               (:link_flair_text %))))

        id->txt-contents ;; maps each id to both its contents and the contents from its parent.
        (->> data-rdd
          (uspark/flow-parent-value-to-children
            map? :name :parent_id :dgms_txt_contents
            (fn conj-parent-contents [child parent-contents]
              (update child :dgms_txt_contents into parent-contents)))
          (spark/map-to-pair
            (fn [m]
              (spark/tuple
                (:name m)
                (:dgms_txt_contents m)))))

        df-rows
        (->> id->txt-contents
          (spark/map
            (fn [id+txt-contents]
              (RowFactory/create
                (object-array
                  (let [id (s-de/key id+txt-contents)
                        txt-contents (s-de/value id+txt-contents)
                        words (into []
                                ;; TODO stop-words removal (Val, 15 Apr 2020)
                                (comp
                                  (mapcat
                                    (let [fr-an (FrenchAnalyzer.)]
                                      #(parsing/lucene-tokenize fr-an %)))
                                  #_(map str/lower-case))
                                txt-contents)]

                    [id
                     (to-array txt-contents)
                     (to-array words)])))))
          (spark/storage-level! (:disk-only spark/STORAGE-LEVELS)))]
    (.createDataFrame sprk df-rows txt-contents-df-schema)))


(comment

  (->> (uenc/json-read
         (io/resource "reddit-france-submissions-dv-sample.json"))
    (map :created_utc)
    (take 10))

  (->> (uenc/json-read
         (io/resource "reddit-france-comments-dv-sample.json"))
    (map :created_utc)
    (take 10))

  (->> (uenc/json-read
         (io/resource "reddit-france-comments-dv-sample.json"))
    (map :name)
    (take 10))

  (->> (uenc/json-read
         (io/resource "reddit-france-submissions-dv-sample.json"))
    (keep :name)
    (take 10))

  (->> (uenc/json-read
         (io/resource "reddit-france-comments-dv-sample.json"))
    (keep :link_id)
    (take 10))

  @(uspark/run-local
     (fn [sc]
       (let [sprk (-> (SparkSession/builder)
                    (.sparkContext (JavaSparkContext/toSparkContext sc))
                    (.getOrCreate))]
         (->
           (txt-contents-df sprk
             (spark/parallelize sc
               (uenc/json-read
                 (io/resource "reddit-france-submissions-dv-sample.json")))
             (spark/parallelize sc
               (uenc/json-read
                 (io/resource "reddit-france-comments-dv-sample.json"))))
           (.show 10)))))

  *e)


(defn extract-first-ngrams
  [stop-at-i, ^WrappedArray$ofRef words-wrapped-arr]
  (set
    (let [words
          (-> words-wrapped-arr
            .array
            vec
            (as-> words
              (cond-> words
                (and
                  (some? stop-at-i)
                  (< stop-at-i (count words)))
                (subvec 0 stop-at-i))))]
      (into #{}
        (comp
          (mapcat
            (fn [n-gram-size]
              (partition n-gram-size 1 words)))
          (map vec))
        [1 2 3]))))

(defn extract-cluster-label-tokens
  [stop-at-i, words-seq-col-index, ^GenericRowWithSchema row]
  (-> row
    (.get (int words-seq-col-index))
    (->> (extract-first-ngrams stop-at-i))))

(comment                                                    ;; actual clustering


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

  (def early-ts
    (quot (.getTime #inst "2018-09-06") 1000))

  (defn created-lately?
    [m]
    (-> m :created_utc
      (as-> ts
        (cond-> ts
          (string? ts) (Long/parseLong 10)))
      (>= early-ts)))


  [(def subm-rdd
     (->>
       (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/submissions/RS.seqfile")
       (spark/repartition 200)
       (spark/map uenc/json-read)
       (spark/filter :created_utc)
       (spark/filter created-lately?)
       (spark/map #(parsing/backfill-reddit-name "t3_" %))
       #_
       (->>
         (spark/filter
           (fn [s]
             (-> s :name u/draw-random-from-string (< 1e-2))))
         (spark/repartition 2))))
   (def comments-rdd
     (->> (uspark/from-hadoop-fressian-sequence-file sc "../derived-data/reddit-france/comments/RC-enriched_v1.seqfile")
       (spark/filter :created_utc)
       (spark/filter created-lately?)
       (spark/map #(parsing/backfill-reddit-name "t1_" %))
       #_
       (->>
         (spark/filter
           (fn [c]
             (-> c :link_id (or "")
               u/draw-random-from-string (< 1e-2))))
         (spark/repartition 10))))]

  (spark/count subm-rdd) => 15135
  (spark/count comments-rdd)

  (def txt-ctnts-df
    (txt-contents-df sprk subm-rdd comments-rdd))

  (def p_n-docs
    (mfd/future
      (spark/count txt-ctnts-df)))

  (def done_count-vec
    (mfd/future
      [(def ^CountVectorizerModel cv-model
         (-> (CountVectorizer.)
           (.setInputCol "txt_contents_words")
           (.setOutputCol "txt_contents_bow")
           (.setVocabSize 10000)
           (.fit txt-ctnts-df)))

       (def txt-ctnts-df-1
         (-> txt-ctnts-df
           (->> (.transform cv-model))
           #_(.persist (:disk-only spark/STORAGE-LEVELS))))]))


  (take 200 (.vocabulary cv-model))

  (def done_lda
    (mfd/chain done_count-vec
      (fn [_]
        (def ^LDAModel lda-model
          (-> (LDA.)
            (.setK 100)
            (.setFeaturesCol "txt_contents_bow")
            (.fit txt-ctnts-df-1)))

        (def txt-ctnts-df-2
          (.transform lda-model
            (-> txt-ctnts-df-1
              #_(.filter
                  (.isNotEmpty (functions/col "txt_contents_words")))))))))

  (spark/count txt-ctnts-df-1) => 3370
  (spark/count txt-ctnts-df-2) => 3370

  (->> txt-ctnts-df-1
    (spark/sample false 1e-2 43255)
    (spark/collect)
    (vec))

  (->> txt-ctnts-df-2
    (spark/take 10) #_#_(spark/sample false 1e-3 43255)
      (spark/collect)
    vec
    (mapv
      #_(let [words-col-i (.fieldIndex (.schema txt-ctnts-df-2)
                            "txt_contents_words")]
          (fn [row]
            (extract-cluster-label-tokens 10 words-col-i row)))
      (let [cla-col-i (.fieldIndex (.schema txt-ctnts-df-2)
                        "topicDistribution")]
        (fn [^GenericRow row]
          (vec
            (.values ^DenseVector (.get row (int cla-col-i))))))))
  ;; TODO remove URLs, figures
  ;; TODO stemming / normalization


  org.apache.spark.ml.linalg.DenseVector
  org.apache.spark.sql.Dataset

  org.apache.spark.sql.types.StructType
  (.printSchema txt-ctnts-df-2)
  (vec (.fieldNames))
  (.fieldIndex (.schema txt-ctnts-df-2) "txt_contents_words")
  => 2

  (def p_clusters-summary
    (mfd/future
      (->> txt-ctnts-df-2
        .toJavaRDD
        (label-clusters-with-words-PMI
          {::vocab-size 20000}
          (let [words-col-i (.fieldIndex (.schema txt-ctnts-df-2)
                              "txt_contents_words")]
            (fn [row]
              ;; TODO see if this 'first 12 tokens' strategy works (Val, 17 Apr 2020)
              (extract-cluster-label-tokens 12 words-col-i row)))
          (let [cla-col-i (.fieldIndex (.schema txt-ctnts-df-2)
                            "topicDistribution")]
            (fn [^GenericRow row]
              (.values ^DenseVector (.get row (int cla-col-i)))))
          sc)
        (spark/collect)
        (sort-by :n-docs-in-cluster u/decreasing)
        vec)))


  (->> @p_clusters-summary (take 10) vec)
  =>
  [{:n-docs-in-cluster 72475.7021465508,
    :characteristic-words [[["http"] 0.00395394147609629 610.1608307201692 6010 245558]
                           [["ça"] 0.003752416092558386 13959.195207000088 37292 245558]
                           [["gilet"] 0.003027469036099073 176.67082418102723 2928 245558]
                           [["jaun"] 0.002829134380836096 171.42104030988773 2778 245558]
                           [["gilet" "jaun"] 0.0027542431453556615 154.87500612720942 2627 245558]
                           [["être"] 0.002701511797831291 5947.007178389018 14514 245558]
                           [["peut"] 0.0024457148566545683 5167.24313418951 12504 245558]
                           [["bonjou"] 0.0024020978250978153 77.79958727768685 1897 245558]
                           [["macron"] 0.0020978223369342297 355.7063256985256 3373 245558]
                           [["pens"] 0.0020308782026039474 3840.2663368198296 9087 245558]
                           [["dire"] 0.002017155528394099 4003.808883466292 9561 245558]
                           [["si"] 0.001863984066903468 10031.916678023248 27777 245558]
                           [["tout"] 0.0017381334666743964 8729.190218811691 23950 245558]
                           [["gen"] 0.0016665327831637544 4557.1456151298735 11486 245558]
                           [["the"] 0.0016292463401146318 92.20278913869953 1561 245558]
                           [["chos"] 0.0015414357474794471 2654.011604958935 6155 245558]
                           [["problem"] 0.0015061291153095624 3058.805659699162 7321 245558]
                           [["dis"] 0.0014638131853901282 1893.7751531075576 4130 245558]
                           [["regl" "r" "franc"] 0.0013578019832949373 2.663027507572441 703 245558]
                           [["regl" "r"] 0.0013552839799672034 3.360658620746668 711 245558]],
    :cluster-id 2}
   {:n-docs-in-cluster 35068.21769930855,
    :characteristic-words [[["macron"] 0.008622102524689534 1811.440596924242 3373 245558]
                           [["election"] 0.006304625737230896 923.7385715087391 1309 245558]
                           [["polit"] 0.005562416342467014 1945.6436753899393 4985 245558]
                           [["parti"] 0.004788631518023112 1437.0809604890671 3360 245558]
                           [["democrat"] 0.004754743692654939 990.37875497549 1821 245558]
                           [["president"] 0.004580201884481494 876.855319930389 1517 245558]
                           [["gauch"] 0.0043694667435035806 1104.875559402222 2317 245558]
                           [["vote"] 0.004369440508831146 1022.0374255098036 2035 245558]
                           [["europen"] 0.004241304509112531 1020.97305879936 2072 245558]
                           [["droit"] 0.0042228904517078725 1949.3921085808581 5752 245558]
                           [["lrem"] 0.0035798783960970804 560.0244366667395 832 245558]
                           [["gouvern"] 0.0034048058520167457 1113.0660930690929 2728 245558]
                           [["vot"] 0.0032711883973044475 600.3233974914061 1005 245558]
                           [["élu"] 0.0030908979391365277 533.7830609199767 854 245558]
                           [["melenchon"] 0.0030765163383128513 519.7670281769166 818 245558]
                           [["presidentiel"] 0.002744426093147845 389.6353893245523 537 245558]
                           [["voté"] 0.0026993385784674917 478.0835554484857 779 245558]
                           [["deput"] 0.002665040533638252 543.7875230586626 982 245558]
                           [["rn"] 0.0026397665335611276 367.54133707068394 499 245558]
                           [["gilet"] 0.002476251351412273 1043.9953117787636 2928 245558]],
    :cluster-id 37}
   {:n-docs-in-cluster 23690.630637551865,
    :characteristic-words [[["impot"] 0.010080255655755432 1213.7648639228773 1873 245558]
                           [["salair"] 0.0059320852004235425 808.6098400695951 1398 245558]
                           [["revenu"] 0.005909660435898634 749.1834322728341 1208 245558]
                           [["argent"] 0.004970300117569715 926.2481288591235 2122 245558]
                           [["entrepris"] 0.0048147576752674 942.1396474120753 2248 245558]
                           [["fiscal"] 0.004414189054304352 597.9531003306744 1025 245558]
                           [["euro"] 0.004357867855901143 648.4382792580903 1214 245558]
                           [["augment"] 0.0038018687894020298 776.0634603926492 1912 245558]
                           [["rich"] 0.0037380228754475553 695.8990821138988 1588 245558]
                           [["tau"] 0.003535421567753272 533.704635522673 1011 245558]
                           [["miliard"] 0.0034078333990527065 503.27970043261877 934 245558]
                           [["retrait"] 0.0032404603251276476 523.7203854991285 1055 245558]
                           [["depens"] 0.0029557088934261677 433.8725139820852 800 245558]
                           [["isf"] 0.002748344664576685 313.7205762582431 455 245558]
                           [["investi"] 0.0026850672628656036 417.17477456480503 810 245558]
                           [["pay"] 0.002534037937790057 1394.608406324912 6381 245558]
                           [["état"] 0.0023966416675078595 941.2656874719244 3614 245558]
                           [["bais"] 0.002382426664081949 511.72848095843403 1308 245558]
                           [["banqu"] 0.002377142556862555 335.2877995765944 595 245558]
                           [["cot"] 0.002349747842104233 248.21429959000207 334 245558]],
    :cluster-id 15}
   {:n-docs-in-cluster 19511.495951440607,
    :characteristic-words [[["homeopath"] 0.0031634293330868313 351.45281945187077 562 245558]
                           [["journalist"] 0.002978906200561182 519.5070176950445 1308 245558]
                           [["media"] 0.002869777513410332 525.1667992570718 1381 245558]
                           [["gilet"] 0.002752275194378151 782.6625286460953 2928 245558]
                           [["articl"] 0.00271950766654927 1230.0978807838828 6136 245558]
                           [["jaun"] 0.00258001638921318 739.2328380297034 2778 245558]
                           [["gilet" "jaun"] 0.002506471049028125 707.0391567680696 2627 245558]
                           [["twit"] 0.0015244033978095706 274.4014613315778 708 245558]
                           [["anti"] 0.0013668518377272787 395.0401981070278 1485 245558]
                           [["medecin"] 0.001326428645042843 312.2677288764448 1009 245558]
                           [["journal"] 0.0012756169576756404 250.04942571889939 695 245558]
                           [["fake"] 0.001230129052251283 168.70131109752145 335 245558]
                           [["chain"] 0.0012124717392300033 222.59288419815815 584 245558]
                           [["new"] 0.0011138217217636637 243.83960325167754 743 245558]
                           [["propo"] 0.0011003351016463658 395.88015105528194 1714 245558]
                           [["homophob"] 0.001084483796733926 171.09583187112779 390 245558]
                           [["vacin"] 0.0010659108799908057 169.6612784806896 390 245558]
                           [["facebok"] 0.0010230696660940564 220.10544011019135 661 245558]
                           [["info"] 0.0010053240213573345 309.427277730518 1212 245558]
                           [["fake" "new"] 9.991771175804631E-4 132.46747698783133 254 245558]],
    :cluster-id 39}
   {:n-docs-in-cluster 18074.500879896525,
    :characteristic-words [[["mec"] 0.002057548511768703 712.2809853378402 3250 245558]
                           [["putain"] 0.0013527628741168973 323.81033058480443 1134 245558]
                           [["quand"] 0.0011233591832522372 1809.7685780788684 15603 245558]
                           [["con"] 0.001101893112688157 510.89642501740013 2745 245558]
                           [["soir"] 9.313159355127665E-4 212.647875479922 716 245558]
                           [["aime"] 9.123912954056013E-4 325.23073508678414 1501 245558]
                           [["vai"] 8.439329821626207E-4 367.7168588564663 1907 245558]
                           [["jour"] 8.350862579240559E-4 527.3080402642504 3283 245558]
                           [["franc"] 8.248508976798075E-4 376.65341175671745 10550 245558]
                           [["fai"] 8.081892972201521E-4 509.3794866384363 3168 245558]
                           [["fair"] 8.028336185661455E-4 1637.1118731696763 14874 245558]
                           [["pote"] 7.596300410555035E-4 156.56543714023834 484 245558]
                           [["conard"] 7.199830008968733E-4 139.0069722068195 406 245558]
                           [["musiqu"] 7.029864007704334E-4 97.19907174704333 206 245558]
                           [["merd"] 6.666780333212108E-4 363.93501065901444 2116 245558]
                           [["http"] 6.236846888432179E-4 182.66933128654497 6010 245558]
                           [["articl"] 5.996457424112256E-4 193.3603837557274 6136 245558]
                           [["gamin"] 5.912464647200855E-4 155.49335981834898 583 245558]
                           [["gueul"] 5.899280524028772E-4 213.81657723211129 995 245558]
                           [["prof"] 5.860368390909199E-4 164.7058557259541 648 245558]],
    :cluster-id 76}
   {:n-docs-in-cluster 13027.423297255305,
    :characteristic-words [[["voitur"] 0.010731949128091722 1117.6776257069398 2249 245558]
                           [["transport"] 0.0047495411192101655 521.2936181579422 1103 245558]
                           [["consom"] 0.004485368434555659 641.2110671331911 1860 245558]
                           [["taxe"] 0.004401818928842227 629.4715602946746 1826 245558]
                           [["carbon"] 0.004119810198024598 402.14606663828505 731 245558]
                           [["elect"] 0.0038566484334326967 472.1128938042165 1140 245558]
                           [["co2"] 0.0034713698861186804 355.4305853822959 686 245558]
                           [["vehicul"] 0.002772253750727649 293.71199686241204 591 245558]
                           [["vites"] 0.0024924669527121024 260.0974610955527 513 245558]
                           [["avion"] 0.0022795580865087595 270.1566530298785 624 245558]
                           [["emision"] 0.002225423115619296 325.2134379433838 958 245558]
                           [["polution"] 0.002182605725098874 253.68065706301363 572 245558]
                           [["taxe" "carbon"] 0.0020531123772937754 193.12849940823492 333 245558]
                           [["voitur" "elect"] 0.0019206368973084809 153.04597234874663 213 245558]
                           [["esenc"] 0.0019027782928139891 216.2673158400315 474 245558]
                           [["bat"] 0.0018702426614367051 197.5456479312815 395 245558]
                           [["carburant"] 0.0018665844639572104 190.12447703990074 363 245558]
                           [["km"] 0.001623757248062574 160.5705578138512 295 245558]
                           [["cher"] 0.0016147727367675224 360.64877624382564 1605 245558]
                           [["produit"] 0.0015929479272444036 380.8672490914428 1795 245558]],
    :cluster-id 21}
   {:n-docs-in-cluster 7595.923675652582,
    :characteristic-words [[["climat"] 0.003435577037452703 388.89604507670094 1334 245558]
                           [["rechauf"] 0.002276672779602751 219.34282089230567 590 245558]
                           [["guer"] 0.002023318991190348 267.08175625503253 1117 245558]
                           [["rechauf" "climat"] 0.0016459051880528541 154.77835428817008 400 245558]
                           [["europ"] 0.0015301195936234246 264.2852569566903 1512 245558]
                           [["pay"] 0.0014147474665893167 559.3894017880083 6381 245558]
                           [["rus"] 0.0011876339725317153 129.52120099058675 416 245558]
                           [["afriqu"] 9.96054120079387E-4 105.95398667554329 328 245558]
                           [["chin"] 9.737058993242176E-4 154.42328101793237 800 245558]
                           [["mondial"] 8.216778896824595E-4 133.07216651295116 705 245558]
                           [["temperatur"] 7.762448031497504E-4 77.87575196287303 221 245558]
                           [["ruse"] 7.232609110431054E-4 107.16108135767824 512 245558]
                           [["ane"] 7.229254103114924E-4 290.7823508440085 3313 245558]
                           [["sud"] 7.117603477480583E-4 91.43673480507181 366 245558]
                           [["nord"] 7.020420626002999E-4 93.08498461236341 388 245558]
                           [["americain"] 6.273794365539709E-4 157.55225071139657 1276 245558]
                           [["migrant"] 6.272078256524816E-4 87.8563761180641 392 245558]
                           [["siecl"] 6.040472137688047E-4 91.1896364467358 445 245558]
                           [["arab"] 5.895096646967302E-4 103.28304194095797 595 245558]
                           [["chang" "climat"] 5.726676845897727E-4 64.74032818910716 218 245558]],
    :cluster-id 32}
   {:n-docs-in-cluster 4577.992636814509,
    :characteristic-words [[["polici"] 0.004624497439149389 385.1303228056472 1267 245558]
                           [["arme"] 0.0039024063483224236 354.224625369699 1352 245558]
                           [["polic"] 0.0031485712610349603 317.72320953665616 1441 245558]
                           [["ordr"] 0.0027454455856291415 271.7041428451447 1189 245558]
                           [["manifestant"] 0.0026026359636738827 207.07953928792497 617 245558]
                           [["violenc"] 0.0022666811830607536 273.1880309224091 1615 245558]
                           [["grenad"] 0.0019234447485237394 126.88408851950408 263 245558]
                           [["flic"] 0.0018764181963738524 190.31655952316748 860 245558]
                           [["manifest"] 0.001789929537313606 216.31712029796077 1277 245558]
                           [["forc" "ordr"] 0.0015159734955709492 111.96683340255163 288 245558]
                           [["forc"] 0.0014667259950435774 227.257689315386 1858 245558]
                           [["crs"] 0.0013430197230720475 104.85042357403364 299 245558]
                           [["bles"] 0.0012861240633914028 103.14556352662032 309 245558]
                           [["tir"] 0.0012857549559741333 124.71105388558267 521 245558]
                           [["militair"] 0.001085914443970215 111.32191878274922 508 245558]
                           [["lbd"] 0.0010624503877717795 73.14830827159871 164 245558]
                           [["manif"] 0.0010311546658111348 125.14332931380139 737 245558]
                           [["gendarm"] 0.0010053701646793034 84.8217616897158 278 245558]
                           [["violenc" "polici"] 0.0010042059150972005 78.26813887339331 222 245558]
                           [["defens"] 8.669847272812503E-4 97.30530506430061 510 245558]],
    :cluster-id 51}
   {:n-docs-in-cluster 4103.9995766307475,
    :characteristic-words [[["nucleair"] 0.010691442837728632 684.1165094543569 1528 245558]
                           [["energ"] 0.004658959717514982 349.6078267574238 1038 245558]
                           [["central"] 0.0040080043434858326 278.62328655536965 707 245558]
                           [["renouvelabl"] 0.002633655658960732 159.290902460905 302 245558]
                           [["eolien"] 0.002124150245788242 130.37526909691834 254 245558]
                           [["dechet"] 0.0018730406918871512 147.44502712954434 470 245558]
                           [["charbon"] 0.0018461429208065094 116.53658949827901 240 245558]
                           [["solair"] 0.0018420169374143425 121.27822572427249 272 245558]
                           [["electricit"] 0.0018352484668512359 146.99321115748893 484 245558]
                           [["react"] 0.0016099642176305717 96.19712120262791 177 245558]
                           [["uranium"] 0.0013952341143406433 81.8569722260147 145 245558]
                           [["production"] 0.0013209183986819528 143.12554386769582 790 245558]
                           [["central" "nucleair"] 0.0012250432652984033 74.88314942554376 144 245558]
                           [["trump"] 0.0012195833963129232 148.82315796446406 981 245558]
                           [["energ" "renouvelabl"] 9.755568962152017E-4 58.306062083179235 107 245558]
                           [["paneau"] 9.22041381091171E-4 79.54266331594633 298 245558]
                           [["eau"] 8.548896226024671E-4 122.43436608474775 999 245558]
                           [["energet"] 8.034121367491431E-4 67.51913214234803 241 245558]
                           [["fosil"] 7.437822591400411E-4 59.30903565382216 192 245558]
                           [["gaz"] 5.890246343071837E-4 69.8987257931097 439 245558]],
    :cluster-id 17}
   {:n-docs-in-cluster 3925.274249111283,
    :characteristic-words [[["the"] 0.021743256091295876 1085.7524656565586 1561 245558]
                           [["to"] 0.011603555020454681 569.8712261071347 760 245558]
                           [["of"] 0.010577570854349072 551.4064732339319 825 245558]
                           [["i"] 0.010240302805410356 502.56623624963277 666 245558]
                           [["is"] 0.010037441666187696 496.36929332572475 667 245558]
                           [["you"] 0.009625994475320088 452.04976608324455 551 245558]
                           [["and"] 0.00784086050252808 395.55265686965134 548 245558]
                           [["in"] 0.00747269796597444 418.09889250606875 714 245558]
                           [["that"] 0.00742657713539599 345.93404685448803 413 245558]
                           [["are"] 0.005495584169975365 266.01589093721225 338 245558]
                           [["for"] 0.005080375597691228 258.17001407368826 359 245558]
                           [["it"] 0.0042871556839866876 219.55543092366565 309 245558]
                           [["have"] 0.0034736329796563326 168.3500341720257 213 245558]
                           [["not"] 0.0031716980305401543 210.21401227690373 499 245558]
                           [["be"] 0.0029997231350824133 149.80897153051302 200 245558]
                           [["french"] 0.002976533474912585 149.82597556030944 203 245558]
                           [["we"] 0.0027435687686198917 142.754563504183 206 245558]
                           [["was"] 0.0026969904970230996 126.56445021937118 151 245558]
                           [["thi"] 0.002683241871148656 209.2330750009165 685 245558]
                           [["peopl"] 0.0026324190245458623 134.1857985576844 186 245558]],
    :cluster-id 68}]


  (def clusters-summary *1)

  (def p_clusters-summary-w-examples
    (mfd/future
      (add-cluster-samples
        {}
        "id"
        (let [cla-col-i (.fieldIndex
                          (.schema ^Dataset txt-ctnts-df-2)
                          "topicDistribution")]
          (fn [^GenericRow row]
            (let [^DenseVector v (.get row (int cla-col-i))]
              (.values v))))
        clusters-summary
        txt-ctnts-df-2)))

  (reverse @p_clusters-summary-w-examples)
  ;; TODO: problem: basically only the top clusters are populated. This suggests that the algorithm is bad at hard cluster assignment, or maybe that effectively clusters don't correspond well to topics. (Val, 17 Apr 2020)
  ;; TODO: trye to turn the topicsDistribution into hard assignment, and try labelling with that method.

  (spark/group-by-key)


  (.printSchema txt-ctnts-df-1)
  (.printSchema txt-ctnts-df-2)

  (.show (.describeTopics lda-model) false)

  (->> (.describeTopics lda-model)
    .collectAsList
    (mapv (fn [^GenericRowWithSchema row]
            (-> row
              (.values)
              (vec)
              (nth 1)
              .array
              (->>
                (mapv
                  (fn [term-i]
                    (aget (.vocabulary cv-model) term-i))))))))

  (->> txt-ctnts-df-1
    (spark/take 10)
    (mapv (fn [^GenericRowWithSchema row]
            (-> row
              (.values)
              (vec)
              (nth 3)))))
  (.show txt-ctnts-df-1 10)


  (.save cv-model "../models/lda_0/cv-model-1")
  org.apache.spark.ml.linalg.SparseVector



  (with-open [os
              (GZIPOutputStream.
                (io/output-stream "../models/lda_0-cv-model"))]
    (let [wtr (uenc/fressian-writer os)]
      (fressian/write-object wtr
        (vec
          (.vocabulary cv-model)))))


  ;; Now let's try K-means clustering using LDA output as features
  (def p_kmeans
    (mfd/future
      (def lda-kmeans-model
        (-> (KMeans.) (.setSeed 6960)
          (.setK 200)
          (.setFeaturesCol "topicDistribution")
          (.setPredictionCol "kmeans_lda_cluster_id")
          (.fit txt-ctnts-df-2)))

      (def txt-ctnts-df-3
        (.transform lda-kmeans-model
          txt-ctnts-df-2))))

  (vec (.clusterCenters lda-kmeans-model))

  (-> (.summary lda-kmeans-model)
    (.clusterSizes)
    vec)

  (def p_kmeans-summary
    (mfd/future
      (mfd/chain p_kmeans
        (fn [_]
          (-> txt-ctnts-df-3
            .toJavaRDD
            (->>
              (label-clusters-with-words-PMI
                {::vocab-size 20000}
                (let [words-col-i (.fieldIndex (.schema txt-ctnts-df-3)
                                    "txt_contents_words")]
                  (fn [row]
                    (extract-cluster-label-tokens 12 words-col-i row)))
                (let [cluster-id-col (.fieldIndex (.schema txt-ctnts-df-3)
                                       "kmeans_lda_cluster_id")
                      K (.getK lda-kmeans-model)
                      zeroes-vec (vec (repeat K 0.))]
                  (fn get-cla-vec [^GenericRow row]
                    (let [cluster-id (int (.get row (int cluster-id-col)))
                          one-hot-vec (assoc zeroes-vec cluster-id 1.)]
                      one-hot-vec)))
                sc)
              (spark/collect) vec)
            (as-> clusters-summary
              (add-cluster-samples
                {}
                "id"
                (let [cluster-id-col (.fieldIndex (.schema txt-ctnts-df-3)
                                       "kmeans_lda_cluster_id")
                      K (.getK lda-kmeans-model)]
                  (fn get-cla-arr [^GenericRow row]
                    (let [cluster-id (int (.get row (int cluster-id-col)))
                          one-hot-arr (doto (double-array K 0.)
                                        (aset cluster-id 1.))]
                      one-hot-arr)))
                clusters-summary
                txt-ctnts-df-3))
            (->>
              (sort-by :n-docs-in-cluster u/decreasing)
              vec))))))

  (->> @p_kmeans-summary deref reverse vec)



  (-> txt-ctnts-df-1
    .schema
    .fields
    vec
    last
    .dataType)

  VectorUDT

  org.apache.spark.sql.types.StructField


  (->> ppmi-rdd
    (spark/sample false 1e-3 96087)
    (spark/take 20)
    (mapv (fn [^SparseVector v]
            (set (.values v)))))

  (->> ppmi-model :log-term-counts
    seq vec
    (shuffle)
    (take 10))

  (Math/exp (:log-n-terms ppmi-model))
  (Math/exp (:log-n-docs ppmi-model))

  ;;;; Clustering with PPMI-svd + K-means
  (def p_ppmi-kmeans
    (mfd/chain done_count-vec
      (fn [_]
        (let [tf-rdd (uspark/extract-column-from-dataframe "txt_contents_bow"
                       txt-ctnts-df-1)]
          [(def ppmi-model
             (dim-red/ppmi-fit tf-rdd))
           (def ppmi-rdd
             (dim-red/ppmi-transform-rdd ppmi-model tf-rdd))
           (def svd-model
             (dim-red/svd-fit {::dim-red/n-dims 300} ppmi-rdd))
           (def txt-ctnts-df-4
             (uspark/add-columns-to-dataframe
               [[(DataTypes/createStructField "txt_contents_ppmi_svd"
                   (SQLDataTypes/VectorType)
                   false)
                 ["txt_contents_bow"]
                 (fn [[tf-vec]]
                   (dim-red/svd-transform-vec svd-model
                     (dim-red/ppmi-transform-vec ppmi-model
                       tf-vec)))]]
               txt-ctnts-df-1))
           (def kmeans-model
             (-> (KMeans.) (.setSeed 6960)
               (.setK 100)
               (.setFeaturesCol "txt_contents_ppmi_svd")
               (.setPredictionCol "kmeans_ppmi_cluster_id")
               (.fit txt-ctnts-df-4)))
           (def txt-ctnts-df-5
             (.transform kmeans-model txt-ctnts-df-4))]))))

  (-> txt-ctnts-df-4
    (.show 10))


  (def p_ppmi-kmeans-summary
    (mfd/future
      (let [K (.getK kmeans-model)]
        (-> txt-ctnts-df-5
          .toJavaRDD
          (->>
            (label-clusters-with-words-PMI
              {::vocab-size 20000}
              (comp
                #(extract-first-ngrams 12 %)
                (uspark/col-reader-fn txt-ctnts-df-5 "txt_contents_words"))
              (comp
                (let [zeroes-vec (vec (repeat K 0.))]
                  (fn get-cla-vec [cluster-id]
                    (assoc zeroes-vec cluster-id 1.)))
                (uspark/col-reader-fn txt-ctnts-df-5 "kmeans_ppmi_cluster_id"))
              sc)
            (spark/collect) vec)
          (as-> clusters-summary
            (add-cluster-samples
              {}
              "id"
              (comp
                (let [zeroes-vec (vec (repeat K 0.))]
                  (fn get-cla-vec [cluster-id]
                    (doto (double-array K 0.)
                      (aset cluster-id 1.))))
                (uspark/col-reader-fn txt-ctnts-df-5 "kmeans_ppmi_cluster_id"))
              clusters-summary
              txt-ctnts-df-5))
          (->>
            (sort-by :n-docs-in-cluster u/decreasing)
            vec)))))

  (->> @p_ppmi-kmeans-summary (take 10) vec)
  => ;; ouch, ugly, but it's a small sample, it may be due to overfitting
  [{:n-docs-in-cluster 1993.0,
    :characteristic-words [[["bagnol"] 0.0062517735442008115 0.0 4 2198]
                           [["atention"] 0.003871246404110684 2.0 6 2198]
                           [["asez"] 0.0035157910167232798 25.0 35 2198]
                           [["sued"] 0.003271408692715916 1.0 4 2198]
                           [["part"] 0.003252159468528748 50.0 50 2198]
                           [["a" "dire"] 0.0031200177802403473 0.0 2 2198]
                           [["droit" "europen"] 0.0031200177802403473 0.0 2 2198]
                           [["tapent"] 0.0031200177802403473 0.0 2 2198]
                           [["ausi" "manichen" "critiqu"] 0.0031200177802403473 0.0 2 2198]
                           [["sauvegard"] 0.0031200177802403473 0.0 2 2198]
                           [["peu" "prè" "ausi"] 0.0031200177802403473 0.0 2 2198]
                           [["trè" "inteligent"] 0.0031200177802403473 0.0 2 2198]
                           [["ilusion"] 0.0031200177802403473 0.0 2 2198]
                           [["danemark"] 0.0031200177802403473 0.0 2 2198]
                           [["manichen" "critiqu"] 0.0031200177802403473 0.0 2 2198]
                           [["envelop"] 0.0031200177802403473 0.0 2 2198]
                           [["prè" "ausi"] 0.0031200177802403473 0.0 2 2198]
                           [["alarmist"] 0.0031200177802403473 0.0 2 2198]
                           [["substantif"] 0.0031200177802403473 0.0 2 2198]
                           [["cataclysm"] 0.0031200177802403473 0.0 2 2198]],
    :cluster-id 0,
    :doc-examples [#object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x2e468b8b
                           "[t1_exh02y1,WrappedArray(Ha, ça y est, la doxa a parlé.

                            Tiens, sur Thinkerview y'a pas longtemps, j'écoutais l'ITW de François Bégaudeau , chantre de l'anarchisme, et personnage plus qu'intéressant. Il citait pourtant l'ITW de Polony sur Thinkerview et notamment sur sa positio,n sur le souverainisme, qui est \"la capacité à prendre ses décisions par soi-même\", qu'il disait approuver.

                            Sérieusement, vous réalisez que l'extrême-gauche a réussi à faire du souverainisme, c'est à dire notre capacité à prendre des décision, un gros mot ?

                            C'est notamment pour ce genre de chose que je ne vous supporte plus.

                            , Depuis que Natasha Polony, folle furieuse d'extrême droite, habituée des dérapages racistes et convaincue de son rôle messianique (d'après ses dires), oui, effectivement, ce n'est plus un magazine de gauche.

                            ),WrappedArray(ha, ça, doxa, a, parl, tien, thinkerview, y'a, longtemp, ecoutai, itw, francoi, begaudeau, chantr, anarchism, personag, plu, interesant, citait, pourtant, itw, polony, thinkerview, notament, positio, souverainism, capacit, prendr, decision, disait, aprouv, serieus, realisez, extrem, gauch, a, reusi, fair, souverainism, dire, capacit, prendr, decision, gro, mot, notament, genr, chos, suport, plu, depui, natasha, polony, fou, furieu, extrem, droit, habitu, derapag, racist, convaincu, rôle, mesian, aprè, dire, oui, efectif, plu, magazin, gauch),(10000,[0,1,2,8,16,24,36,45,51,53,76,78,79,176,207,278,306,391,396,402,418,444,528,532,650,751,764,793,818,1058,1130,1319,1359,1529,1596,1663,1726,2242,2317,2639,2859,3205,3420,3852,3855,4113,4694,4788,5542,8087,8287,8770,9558,9631],[2.0,1.0,3.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,2.0,2.0,1.0,1.0,2.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,2.0,2.0,1.0,1.0,1.0]),[0.0019137343777643583,0.004994980391240165,0.003937374035492734,0.21249881107315194,-0.004102450217461319,-0.005141678515096555,-1.5857542291980282E-4,-5.431422458461237E-4,0.004758739932774694,-9.557045895575982E-4,-0.0020312592562938096,-9.108842773953044E-4,-0.0025149980998156705,-0.011512734948701597,-0.007423365512023063,-5.066565871842924E-4,0.009359858293972468,-0.002376240690170792,7.152115901735809E-4,0.0014214311653965367,-0.0021952075574960535,-0.0015641640671819207,0.008156181855945702,0.007343788909460617,-0.1043724202058637,0.00433088580621654,-0.04329248616408929,0.022212715863035054,0.0019005230806679342,0.013914663635850222,-5.806458802020372E-4,0.037227931544449366,0.03210971527836929,-0.01868385927357953,0.10168592826527066,1.2951723034451958,-0.022024509814745394,0.01006103259870145,0.008197967688318356,0.1095651547028264,0.005161433419719523,0.038363142111133716,-0.6853603430880809,5.449416391034896,1.0563665868068277,-0.4505003453083976,0.9823789846532595,0.04160825953093602,0.05542600450254165,0.005921944866092665,-0.0013900413901640517,0.014227372249091342,0.011842804452519411,0.005189119369545053,-0.2961032619577159,-0.1989693527349409,0.04498007880043764,-0.6811474881281749,-1.1689550732346483,0.010561130863952252,1.7440410523859033,-0.01680863625258462,-0.05351345321856755,4.8699196254869736E-4,-0.02061800046737485,-0.10402331485368681,-0.055318984056767155,-0.024394052150890657,-0.09444350647365485,0.03525317287462166,-0.03439089120168133,-0.10305132801689036,-0.015926829685244934,-0.004104776418844238,0.12005806947390837,-0.15368976445194615,-0.03829320140059229,0.015357134416782093,-0.36836291595063064,-0.10185572240971105,0.25573026364830437,-0.2735908827535838,-0.41881367716612483,0.004311030271027031,0.6200043292904188,1.6229244601883988,0.011596793688184392,0.025769518847083037,-0.3527218332366316,0.05602995935322342,-0.3404057642175046,-0.035465622796259116,-0.02238447047797339,-0.025669555759222862,-0.1279710570188509,-0.15765466284322013,0.16240705018313878,0.024223051755844473,-0.03009758919498122,-0.23340877145731817,0.8865706337856679,-0.6358423750694435,-0.2741067282542202,0.08354293509484255,0.0979039685234899,-0.5131835449305584,1.3290893029440962,-0.8573576478094628,0.36868920123957116,-1.2097812922157751,0.1808232965765921,-0.3805348720412074,2.3530632880797593,-7.515048861632737,-8.576254303618528,-67.73921236172919,0.2089957510517649,-0.20691993166378705,-2.0389314780945496,0.6264399967800811,-1.1020978266987524,0.2034749832796951,-0.3222909257225086,-0.26181975032059657,-0.29766111816488267,0.1321594675073275,0.9155112654151663,0.3715628660516282,0.17646484738187856,-0.071289861141187,-5.451174695279051,-0.9867593366952898,0.12796740896006495,0.08577766134371936,0.4431328627534032,-1.4144377848979515,-0.558128338878527,0.060887296672570264,0.5873923717849024,0.684656157073984,-0.10098256660463505,0.539448868960009,0.6093866043697305,0.02482062625765142,0.14484594146714946,-0.1656081556479067,-1.3709753124115163,0.5229555489444102,-0.11804824066294733,-0.30518506245674926,-0.2531502965994267,-2.513506568161456,-0.21435713150460353,0.013873745766633305,0.5483156126219106,0.34641544898035487,-1.579503603614711,-0.7496662886305465,-0.707820554566302,1.538382719266775,-0.5032904634551926,0.29761474875334387,-0.019170762464563246,-1.1481529367262748,0.20484414204623685,51.805813665251755,-13.065500906964964,0.20158818971244535,-1.0534507728221338,0.4810834815510339,0.3404279953726201,-3.0884386553319993,-0.2153071387085338,-0.8882744285588466,0.07828821153685311,-0.05617678332406144,7.6589124585401285,0.13901205120021837,0.5419743128383987,2.168732345734653,-1.4416924797740271,1.0936358547218963,-1.7047899049957214,0.016536404399759442,0.8883302095473589,1.6016931089124649,-0.3118429862319634,2.6438015556725594,2.517479454772778,-18.023740447090944,-2.448381881802616,-0.7654307950008841,1.2563111252718004,0.3777703890716464,0.7871669496803301,-2.859414455490269,-0.532271129078169,2.9976832122743575,-0.4348638572905899,8.780962956892942,2.9321932267962967,-1.019122764012939,-3.1752541482640253,0.02190135626347619,3.6224971201070106,5.05625508096901,34.77712369869599,-9.337801715649631,26.487869276371367,2.410412143511184,3.6359935539752204,-3.428433007519096,5.319431476137552,6.211387596747491,1.258212287719354,23.68826926046644,-6.305315537768981,6.436852268869809,-1.68186188717531,9.169179374827428,-11.272280594652129,-7.794301117186302,-80.23878654112126,35.085555328701396,245.63369360201978,61.354403230929336,10.58457146551472,28.91283370012411,66.38773024683981,-3.3008562162278907,-2.683272971449986,26.187471293036246,3.0166896304553106,-24.14276216651372,-3.1917211890956465,-0.16582339781160388,-3.9889831636019335,7.948322614223658,-12.112869066534868,1.787775140375511E-11,-3.2811771709953077,5.463849490664873,7.535455240096529,15.588400997879619,-7.484059422554658,-3.1213752470921925,-1.0156267435260884,4.345494743982556,2.427886632134782,0.37976460864278544,-1.4526622866334091,-0.8054798260379973,-0.13509160081772506,0.2282268910982583,-0.7373743775145456,-0.5974041963939043,-1.0673287645313103,-0.5125165677393568,-4.656627792148931,-0.4226485474354774,-3.5707999795358685,-1.883948924186995,-0.471313492935518,-0.7947268745268773,0.5095912923362792,-0.3910135780384158,-0.9354106207549643,-0.05769488115600545,-0.41108742351779987,-0.35483856626771393,-0.022885708722951106,-1.8130629128186087,-0.5784144636187663,1.169514180464739,-3.3298630708062813,-0.6871981056834685,0.3083155064298278,-0.37348989680968026,-5.489997897986589,-4.081508571850987,-0.7102322936326717,-0.12412945713715284,6.687860171329391E-13,-0.1954616795380377,0.9134290829743397,-1.6722254526086404,-2.0844575596444708,0.4602345097188571,6.247331967784542E-4,-0.26725588841907544,-0.22935829882945918,1.891655403080358,-0.2655239064619976,-0.9550192046056408,1.8380240792118532,-0.20808203586133556,-1.381744669877684,0.6193589695234756,-2.461169251824682,0.13713149288847729],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x3d70aef9
                           "[t3_b5q007,WrappedArray(Le policier qui a eu un malaise cardiaque samedi dernier a été opéré mais son pronostic vital est toujours engagé.

                            , ),WrappedArray(polici, a, malais, cardiaqu, samedi, derni, a, oper, pronostic, vital, toujou, engag),(10000,[0,70,276,1172,1903,1915,1965,1980,2272,4974,5322],[2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0]),[6.0726392151658654E-5,9.276040727594563E-5,8.50557180783093E-4,3.142392802838242E-4,-0.002423855830303356,0.022218629975686473,-0.0011449496162102618,6.180614928601709E-4,-0.0011219667296977211,0.00780006158343318,-9.459133214206349E-5,-4.00294348928346E-4,-1.756930117141573E-4,-0.0015386205257477284,0.022959876306201932,0.0013600741107176863,-0.012208987711004544,-0.019862918060683257,-0.01031262667132193,-9.78439881306672E-4,-3.5947275351979095E-4,-0.001344805875036288,0.041465244068707344,-0.006706372271881951,-0.8138515431849837,-0.027400529800599357,-0.0039809411477810565,0.07547857579774665,0.00221687017795333,-7.019101680256538E-5,0.0016374326467685657,0.020559299089468956,0.0038743619096928226,-0.0036360979271507247,-0.008513775482070822,-0.13322683725345374,0.0032192980991533346,-9.841047197255133E-4,-0.006906187197905301,-7.822679833148912E-4,-3.207820835545686E-4,-0.00204543972978002,-0.015732512633332953,-4.952273181014384E-4,-0.00618924521649494,0.00690020710592129,0.007197514465269869,7.770123072338611E-5,1.8850161658288627E-4,8.695025313286316E-4,7.039690481785934E-5,2.0842317549796708E-4,-3.774565457251292E-5,-0.015854482439418494,0.010613305135862321,0.003274262126033209,0.02625321576179884,6.637273217062088E-4,0.0031357506284359717,-0.007615324595821744,-0.005435334154606047,0.3933306667349737,0.22093407157956133,-0.010234162165614489,-0.0026824788932227213,-0.009160439004723242,-0.009406734316929451,-7.844679224447914E-4,-0.029404846635455045,4.9792478706709466E-5,-0.004645420152399892,0.007468233696577285,3.5245126721946676E-4,6.891320187073086E-4,0.0038188867349304534,-0.008419131103074518,-0.0013484265890622606,-1.545995148618056E-4,-2.561059004797687E-4,4.5202147885597293E-4,3.5305384949892453E-4,-7.485113589696026E-5,-2.2324144725586873E-4,7.383913546976323E-4,-0.0027186456391550613,0.004365133767402784,-7.725151549482555E-4,-3.078975531365813E-5,0.0041921892970407295,-5.760570209204076E-4,0.0035691891425831896,-4.954323750630955E-4,-8.870087982323167E-4,0.004785767782522909,0.0032530151294171577,0.0014367355085932883,-0.030190748259203953,0.001912748597849673,-0.02113740593155113,-0.005144691680338553,-0.00590658204019515,-0.0026067590752219933,-0.08258079608344766,-0.009759557745011499,-0.01722849902515581,0.002306437204101933,-0.001071061946454272,-0.006193264692348265,0.0075963881920798745,0.0018283066017623362,6.353933941884692E-4,0.005156817494617811,0.004582669721589402,0.0021727578404348888,-0.0028468988264304,-0.002771279841730578,0.6464468220364146,0.03270031705662171,0.004314902265357558,0.007939253653870607,-0.10374983182929917,0.016021377512981366,0.002022554298691937,0.004162376143491205,0.004707139075673947,0.014271181551342039,-0.012019331873576783,0.026890385778861964,0.00905487432595824,7.17198226226655E-4,0.00454606147576536,0.0011638737890957603,-0.005049126547330462,-0.0076180952866044885,2.935235082085043E-4,0.01504822533048638,0.01615454001449361,-0.0046546273151745286,-0.0031977323088201396,-0.0061694204530551005,-0.00223345456708303,-0.0012277400264843015,0.005071165067180658,0.02275220803438102,-0.004785363042612639,-0.0027628515433202145,-0.006390987545733641,0.0028441778588355906,-0.006915568474814952,-0.001216023326094003,0.008799198276939686,0.010611045168004422,-4.0019497602358323E-4,-0.003869157000893233,-0.010164846057172001,-0.004525370670451284,0.007386592899871222,-0.0015820655979361968,-0.0018209247689329943,-0.01321877783351653,0.0011046088095810595,-0.005407887716368782,0.007668093274613541,0.02283870839903001,-0.007785503927758555,0.007574526045687157,0.009400624909951754,0.006437493351361427,0.00270868625863533,3.5848837295421467E-4,-0.024946509304923137,0.1027742040054667,0.01779229636868574,-0.003553211667378773,0.007817150260850609,0.05239095423728109,-0.06285319896154243,-0.0261875141425141,0.0824856899912888,0.041724604572348625,0.015011104957389033,0.016974061066983036,0.021947667870539818,-0.004199154116242478,0.028991563639382656,-5.723620582365427E-4,-0.021152791668765176,-0.047612799879351506,0.016175671403729427,0.005173098390790512,-0.004426816122426512,0.021844335473610428,0.008794676990337324,-0.012049930503151809,-0.04881977384619698,0.018479126980018093,0.0057618325433577925,0.03425741613641971,-0.015229661249769226,-0.003411591220425262,-0.02475676493840373,-0.0063680288376559546,0.038462462382158946,-0.02577402182408426,-0.041648907682186265,-0.0035411637655217106,0.0051871977460635045,-0.00957004433287557,-0.014758361561811713,-0.010185827529224912,-0.08206257205677213,-0.04213538272352723,-0.004162404231679899,-0.0042438739894356745,-0.002239643255415182,-0.01440481511943079,-0.0098046170601931,0.13056612171371118,-0.007718101588902227,0.04248302599918356,-0.036521830228553404,-0.06671873728635586,-0.0033702322955269197,0.05478672716152635,-0.015627380454120918,0.015205252770191071,-0.00975180930945169,0.11698090287714683,-0.09951958900297597,-0.021109794468104306,0.07832641080481723,0.10689571670953751,0.009385735378280831,-0.09057304093661787,0.014922118090129655,0.0034088083600090258,0.011062064918310913,0.019967137933122214,-0.03271065639700837,1.1844619217826923E-13,-0.013549685785808027,0.1351146773855258,-0.056186483295887844,-0.05182520345099734,-0.009274442736943642,-0.0028132379271598555,-0.0362406957751006,0.011896906134690228,-0.44163123279646077,-0.1011343550700624,0.28394379511121676,-0.09021349848081378,0.08898542383221414,-0.04346081125677873,-0.05595920047953103,0.05980803875095837,0.06550299843382357,0.11694514624703417,-0.2680775818612474,0.050748703763103795,0.38378482349701926,0.5639686720908237,0.07680340197360314,0.24722569327693006,0.23242569994248924,-0.05932780316250501,0.42256070670022483,0.10519889082149299,0.05738549451612802,0.2562251019010674,-2.081743487583693,0.04152361345622974,-0.13519226074770987,0.03579022910380007,-0.17251833042907186,-0.10532381744906197,0.15345658164561807,0.04010108634777873,-0.8988793519385567,1.0001088116585628,0.17168929927088264,0.4685323762665239,6.289414125657195E-14,0.24891489899093777,0.1318324305405597,-0.27661334240711766,8.516567880967577E-4,0.014153939339877939,-4.209362984975992E-5,-0.02295364188295985,0.016736263339427772,-0.06756300264250209,0.024249152544793158,0.09076725529761442,0.04082434781455434,0.6012482903421457,0.06100831473715104,-0.08999855576324778,-0.07029685015867468,-0.08391958927630708],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x27e5818c
                           "[t1_erzt5ys,WrappedArray(Je suis d'avis que quasi personne ou presque n'a envie d'aller à la plage en costume intégrale pénible pour nager quand il fait chaud à moins d'avoir une culture ou religion qui t'as inculqué que ton corps est indécent.

                            Si tu laisses les gens libres avec de l'eau et de la chaleur et que tu leur lave pas le cerveau en leur disant qu'ils doivent se cacher et bien personne ne porte ce genre d'habit.

                            Si j'avais tort, il y aurait plein de femme et d'homme non-musulmans sur les plages dans ces costumes mais c'est pas le cas.

                            , Dans la cas où ta pensée s'avère vraie, c'est les violences conjugales (physiques et psychologique) qui sont le problème et pas le burkini.

                            Mais avancer que la majorité des femmes portant un burkini ont subi un lavage de cerveau c'est un peu gonflé

                            ),WrappedArray(avi, quasi, person, presqu, a, env, aler, plag, costum, integral, penibl, nag, quand, fait, chaud, moin, avoi, cultur, religion, inculqu, corp, indecent, si, lais, gen, libr, eau, chaleu, lave, cerveau, disant, doivent, cach, bien, person, port, genr, habit, si, tort, plein, feme, home, non, musulman, plag, costum, cas, cas, où, pens, aver, vra, violenc, conjugal, physiqu, psycholog, problem, burkini, avanc, majorit, feme, portant, burkini, subi, lavag, cerveau, peu, gonfl),(10000,[0,3,5,7,12,13,14,19,22,25,28,39,40,46,48,49,59,76,123,129,140,146,157,197,209,215,309,323,342,407,414,465,474,479,585,587,622,687,688,737,838,879,912,1022,1155,1248,1336,1464,1487,1513,1581,1631,1999,2290,2380,2383,2487,2591,3183,4622,5341],[1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,2.0,2.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0]),[7.369811021744547E-4,0.02658484523595649,4.2596868468267957E-4,-1.2404184203822975E-4,-0.06905967911929531,-0.011212767150301476,-3.502893363884972E-4,-0.0012527505699374889,8.540538618062506E-5,-0.001553329208989403,-0.0013899373902290858,-4.895029759631218E-6,-0.04434640132601892,-0.0026475663974345125,0.0043770526901188885,3.3326648985522965E-4,-2.2388179531771944E-4,-0.00416086720145868,-0.001508258630453639,-9.646649085915098E-7,-3.7129112314466693E-4,1.3950592586216973E-4,1.3950799032011173E-4,0.002096682079840994,-3.223179208124297E-4,-2.783855570620661E-4,-0.0012107332886360529,-5.488600165452447E-4,0.017220220898207052,0.0030703549466140345,-0.03616051965205947,0.0013632202850526055,0.002883220909566372,0.0016758733832168964,0.002931294739268657,0.001136201508282495,0.05839344066247574,-0.0038854178719251297,0.005885802968526527,0.0027934349712834938,-0.016883607612381425,-1.7967894622314588E-4,0.0019900958731573445,0.0181756142590776,0.012834747283497193,0.02068383147201866,0.01458653453238754,-0.0037706453826828598,-0.12691999673829335,-3.5005774714237957E-4,-0.004289187461422835,-0.009823846810193977,-0.09014533741545633,0.003285097557585002,0.14247617150228264,-0.06536081477394896,0.08933215201131538,0.03021615767890434,-0.033879997793729036,-0.013182655673315478,8.893641239176189E-4,0.008690572495707382,-0.002992395360673501,-0.001565951096631534,-0.06091293340998664,0.004692144854912168,0.05937185208891255,0.7606457517477637,-0.04636560116997914,-2.9251119321129042E-5,-5.736848716609694E-4,0.016598481435053478,-0.11288326371527227,-0.15875937793565997,-0.11119875235641173,-0.06567811711699742,0.02419018353701883,-0.007302615229597947,0.002634865017000487,-0.15405701207300648,0.010354923986068689,6.302415179928937E-4,-1.0503124870659026,-0.030390924180490327,-0.07410517877967977,-0.30012954374170103,8.365726310249849E-4,-0.022363470887974017,0.004291947866557171,-0.020483480996271175,-3.9777673043828047E-4,0.06310710763495553,0.0188293753439182,0.015422554877970628,0.05492514515810224,-0.0636302610061715,2.454764507857253,-0.08543773583917648,0.009631294735432006,0.241102090496546,0.14099763932735146,0.19894752072080926,-0.057555936595473596,0.047187419156996376,-0.011046433918172376,0.01846691626919641,0.0032303880823261587,0.19764745379602783,-0.1319507525926955,-0.03465051008337222,0.0146186931179039,-0.014141159265930275,0.07197495369784511,0.032650711670914226,0.06965720607442018,-0.007153634769481497,0.012909103582867875,0.023535620796825273,0.0399590602242596,0.5697089403393413,-0.06893102181286073,-0.34380747709294984,-0.11967098507869096,-0.2265245234134604,-0.38249309191198294,0.2079047540729738,0.09104639084084805,2.9551041937089617,-0.2512064669194274,-0.13104997945241706,0.25375130796479456,0.06133049166539787,0.01643925436406938,-0.001580578144432684,0.07485156801300186,0.06486903041842935,-0.12063632781563088,0.029983187158654254,0.0696011361883341,-0.7410680852027874,0.03603522099762102,-0.03085441125830258,-0.037436491692008994,-0.004301036780388009,-0.007473888753215051,-0.10443553814019678,-0.03420415088995937,-0.027390730065884748,0.05449218372620347,-0.008725361435448001,-0.13905025254430117,0.014361993644690527,0.006095750530978057,0.017187654687537447,-0.10965111279910941,-0.08344461951200491,-0.24027898925149271,-0.4577015908997559,-0.15428606039633463,-0.32067817341937765,0.27455594050542514,0.39318508220782955,-0.3640443350607887,0.04353395964555548,0.323510611570559,0.044910675757437915,0.055266082708150976,-0.057345806826715494,0.2623226381723459,0.852829718771926,-0.20822406528065052,0.7656455044983335,0.20660187245181433,-0.8779975933649115,1.229109983622307,0.06279839488601441,0.018325715669382195,-0.09724270779806035,-0.572628938022847,0.9836312219082443,0.1580802199202673,-0.9980618633266207,-0.30919465957122066,-1.1280913995636097,0.1577893976271161,-1.4171808750630277,3.626279135054989,-1.7221303556303273,2.0893089613041074,0.2582151282930163,-4.066462251077113,1.4557322870752651,-1.3683453643231043,-1.4433554132947886,-1.3850577271742681,6.636471671414844,-1.0478866971780652,3.559442149082698,-2.9257677378559626,-0.07383094671906584,-0.36651295997174416,-0.6816668100296204,-0.24448095146445903,-1.669505996888154,0.639468750764341,0.6944430396885264,0.05851510929225801,0.07268717090507716,-0.0548523073344462,0.0935008120869453,0.15483149397832657,-0.11669356820714918,0.04497923154416458,-0.06367773096732522,-0.08026582167366147,0.051616026433629975,-0.544602734724883,0.10584352965013671,-0.00905738244252546,0.08678989106064876,0.05939911148941468,-0.12619057732223746,0.0027692288406900925,0.07183535857312746,-0.0021613028418762618,0.024889849017590622,0.05093245593355935,-0.5215450211943231,0.07123413473530066,0.15461117501566723,-0.29149983862152073,0.04667631452345566,0.062139999079533735,0.009006053045649456,0.03708332003528761,0.06588060485426843,0.37793885575143515,0.1761738361143543,-0.22930236554353733,2.854906725210835E-13,-0.05258661699554508,-0.19506756132506847,-2.9149202841662405E-4,-0.07691625342584221,0.04433907584786978,0.04339648199071552,0.05722686913825071,-0.20990283293257406,0.16131971203704498,0.1023766641168235,-0.820488397533019,0.888784050066067,0.02120778467973628,0.07157018445074856,0.02878495136717154,0.04606842437136115,-0.046278441260810724,0.0754612588409278,-0.5421718702986665,0.0652018086192979,2.474092916929826,-3.47910248524563,0.1368486989244847,-0.7594356934135104,-1.0128244674740499,0.314384467711012,-0.18183167184389076,-0.12821337772499872,0.06371185901428207,0.04251866061724832,-0.07111385068647844,-0.27509972714126707,0.3370084852934002,-0.018236836836609847,-0.08146391990256815,-0.010145138601976331,0.24903791702891362,0.006074925112742102,0.057958432325648186,0.3476299362850821,0.13574292319959047,0.184228066466372,3.758627499953658E-15,0.05767302494551236,0.14917166060757284,-0.269316070103674,-0.3361968649384593,-0.013844639961708823,-3.7221236571396683E-5,0.44750363302791013,0.005720443148766616,-0.16725173754877798,-0.035199149559717216,0.12880089269167766,0.0665793203632594,-0.03448751764493006,0.20212209443379772,-0.04242798663872627,-0.038173710176144834,-0.07611680750710006],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x143ac87e
                           "[t1_e8o0gzg,WrappedArray(Un d'entre nous vient de toucher 5000€ de bourse pour payer une partie de ses études. Cette bourse est conditionnée aux revenus, que ses parents (aux revenus conséquents) ne touchent pas en France.

                            , [ETHIQUE] Toucher 5000€ de bourse dont on a pas besoin

                            , Bonjour à tous,

                            J'aimerais votre avis sur cette question d'éthique qu'on se pose avec des amis. Un d'entre nous vient de toucher 5000€ de bourse pour payer une partie de ses études. Cette bourse est conditionnée aux revenus, que ses parents (aux revenus conséquents) ne touchent pas en France.

                            Est-il éthique d'accepter cette bourse ? Est-il éthique de s'en vanter ?

                            Quelles sont vos avis sur le sujet ? Une partie de nous proposait des arguments légalistes ou rationnels, d'autres s'insurgeaient.

                            Avez-vous des ressources pour améliorer la qualité des débats d'éthiques ? Nous avons trouvé ça (PDF, sur les débats d'éthique ), mais on aimerait trouver plus.

                            Merci !

                            ),WrappedArray(entr, vient, touch, 5000, bours, pay, part, etud, bours, condition, revenu, parent, revenu, consequent, touchent, franc, ethiqu, touch, 5000, bours, dont, a, besoin, bonjou, tou, aimerai, avi, question, ethiqu, pose, ami, entr, vient, touch, 5000, bours, pay, part, etud, bours, condition, revenu, parent, revenu, consequent, touchent, franc, ethiqu, acept, bours, ethiqu, vant, avi, sujet, part, proposait, argument, legalist, rationel, autr, insurgeaient, resourc, amelior, qualit, debat, ethiqu, trouv, ça, pdf, debat, ethiqu, aimerait, trouv, plu, merci),(10000,[0,1,2,10,17,31,33,41,42,43,52,63,107,109,111,113,115,116,140,143,158,168,221,332,353,383,385,401,430,505,530,662,665,830,842,846,922,1021,1162,1164,1373,1414,1417,1434],[1.0,1.0,1.0,1.0,2.0,2.0,2.0,2.0,3.0,1.0,1.0,1.0,2.0,2.0,1.0,4.0,1.0,1.0,2.0,6.0,1.0,6.0,3.0,1.0,2.0,2.0,1.0,1.0,1.0,1.0,3.0,1.0,1.0,2.0,1.0,1.0,2.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0]),[0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0xeba13a1
                           "[t1_eshw03x,WrappedArray(Pour donner une certaine perspective, le CSA s'était opposé en 2012 à la création d'un comité scientifique à l'appel de 6 académies suite au scandale Séralini.

                            Suffit de voir tous les commentaires du type \"empoisonneur\" ou \"assassin\" au moment de ces séquences...

                            Le reportage a juste présenté tous les défenseurs comme étant vendus à des lobbys, mais à part ça aucun manquement niveau honnêteté et rigueur.

                            ),WrappedArray(don, certain, perspectif, csa, opos, 2012, creation, comit, scientif, apel, 6, academ, suit, scandal, seralini, sufit, voir, tou, comentair, type, empoisoneu, asasin, moment, sequenc, reportag, a, just, present, tou, defenseu, come, vendu, loby, part, ça, aucun, manqu, niveau, honetet, rigueu),(10000,[0,1,6,26,42,43,61,65,85,134,147,186,236,268,269,334,344,367,413,487,574,938,977,1509,1818,1880,2861,2866,3114,3628,3735,3830,4389,4565,4672,5581,9247,9486,9907],[1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0]),[0.005656029170550714,0.01260412612364406,0.10206107173677055,0.007612406264382369,-0.030937072874470947,0.08679733739646878,-0.009927459026899219,-0.4071992993885857,-0.011589077928435849,-0.42048981708833805,-0.004055644061467938,0.017787304875499548,-0.013280548555337975,-0.0172005612804328,-0.011014311938943516,0.022382092972027867,-0.1263510371299602,0.034874069100318525,0.04016992929135082,0.07006368057194236,0.11264616780718659,0.01780427631342073,0.10021245971955738,3.5703187102755163E-4,-0.39808852351511426,0.024586089899367627,-0.05440524745136229,-0.04797373761489879,0.03264013324741276,0.00804333165530938,-0.049687832389819525,0.06867173258661025,0.1685820155291536,-0.10123785001839034,-0.0382949591266386,-0.03406260471422836,0.09500877731416013,0.055544454967391486,-0.044201808194383886,0.22857649085999934,-0.03180945159315656,-0.534761050925591,-0.14528989128629055,1.0460501779708666,0.06135070094859313,0.1302476623322459,0.4065541841631874,-0.20687642755869318,-0.22073877802511874,-0.014584156455385312,-0.001617104407137973,-0.06834840788353815,-0.1289424310004044,-0.006755148928655293,0.9246219038169644,-0.7516203854595532,0.026505759173029185,0.46596993779468876,0.3434689089543826,2.0674935718397633,0.02378080496127233,0.8955664375622598,-0.08074527514857743,-0.033889197918298475,-0.07914859613263925,-0.1152564128083361,-0.23198846359451897,0.9187721701929717,0.1251548565288637,-0.008877635411274495,-0.06836712886998852,-1.2125602279448902,3.1289095033058922,-0.5337116015257509,0.2084614697824406,-2.3938369484788433,-0.09433111339882716,8.06931397541995E-4,-0.04188638355163768,-0.08773708994144316,0.06424027334864499,0.039922193442276925,-1.4041744487100265,-0.048158150953512384,0.023881578310976637,-0.050623308963668325,-0.008705791014344847,0.08763922846405187,-0.1856341806890017,-0.11081719323081701,0.23522027265316703,-0.07513163798302588,-1.6326052669196016,-18.120177722345097,0.7695676064965438,-0.9894119756287162,0.9030191245759202,-0.014443067605391768,0.015493330661573074,0.14987463798946438,-0.3471926707202807,0.5830612508740387,-0.9167950624114767,0.09336589829683628,-0.34302014329462294,0.6894950100737546,-0.8276460275735612,-1.4562921143564738,-0.9129419357820595,-0.17829056854991313,-0.536650047113322,-1.1244046020870357,-0.2925553925779523,-2.2198646900991568,-0.75176348419572,0.23839710801679329,0.2371897313622778,-12.89003205789368,-0.5729194738980319,-0.02703886794776795,0.7103273583741399,-0.8880960132938016,-0.27508105830834134,0.3902546029296577,0.11109285888042145,0.28316578926616087,-0.1305314124986242,-0.9763276430108419,0.35784157325731597,0.09600133562894841,0.13804460672937013,-0.03914832274290807,0.2525845849095067,-0.14016783603351812,3.1913308416290693,6.003139117626118,0.6690974333814491,4.220342880547285,-0.40985067087243665,3.2885284650598425,-1.7993039508057038,28.08751425954398,-1.7324362118886465,-0.7327954625338786,-1.7068547732154689,-7.308095410138119,2.9528093866775573,-4.729452306790603,-0.05027457409158311,-1.6832255366611057,0.541157294064414,0.4538394600885763,0.17973028029856825,0.28994678492442516,-3.9009360509575313,12.504167625604769,-1.6065759773323038,10.102953342738463,7.350213041793577,-9.445548544343152,3.678329668987785,11.991612656178543,-2.782427553615347,7.7782461920304256,-2.506514517467,0.9971795693758783,5.431678433111921,0.4357885965161004,4.8086352325062185,-6.020549621171824,0.8146262403278179,-4.2881060837698755,0.6786837223699121,4.739850783149021,-5.083288484831155,-6.433399279619131,-3.6123865613800747,8.663516738204638,-7.121394321430726,11.908689752015162,-8.87118708325769,17.788254262920166,-22.422588844055657,-3.7123896802032705,34.984195155142146,-22.016714889989814,-1.2224501914659247,-3.7627994433657603,-3.460776599349791,3.9760713009971207,-11.901282082390312,-34.4820024463028,0.4448151536199605,-4.343626416362302,0.6976783774676809,-5.242453507671034,-1.3111466923793176,-3.6928503826821295,-2.335560341509977,-8.427630813320443,-1.2921569231906622,-1.6612451782078272,-2.3111982204026646,-0.2901542575033218,2.424693224866968,1.7745179663308424,0.06328231655765634,9.92494075668773,3.078667075877636,-3.8299829469956554,1.3732229852723865,-3.24770666024359,6.067207624013499,9.873995256417453,16.34666589843317,-1.0549839503768474,1.8484336183542505,1.6421235337202167,0.10978586233308824,-0.628825438677346,5.482482425472234,4.105274043596326,-0.11599810762778594,-1.6567286033592976,0.21765528955395888,-0.3638832811639599,0.18675047387854699,3.3821524661879083,-0.47503779299440224,-0.08842935857454826,-2.779071303834645,0.2910450461718789,0.4298117174270281,-0.3138243182452902,3.213154512911207,-2.418606648589087,-1.2233493470161612,0.9667321164419738,-0.909857504604517,1.260319422590004E-12,0.2055939966435753,1.4599931491739255,-1.291149500437799,-2.0641199878577257,0.59545660657311,0.5196118286450122,2.1717961987973706,10.817903492787208,-2.343007664432293,-6.013677467639676,-5.03687660784081,-4.410581128084465,14.338389517699863,-2.626605596618742,2.035308940823525,4.620084570390459,2.7922147625908553,-4.083162172492821,4.753511931410551,-0.005019150971166079,1.4896452281110275,1.0071769851072414,4.82148656753992,1.1327614407074604,-8.659962447955394,-3.0460250882240145,11.276381125643754,-3.8234334524061926,-5.320060333110369,-0.4394967497524186,-0.5379810724228186,16.276343070826805,-14.752991405798848,-10.817045084326205,-0.05061808268028686,10.278090980426217,-14.815943748071572,-9.874604769471713,0.4472976036711951,1.8741622805639995,-8.497275683376973,-11.730537294660536,8.043127857956081E-12,10.498017707137324,5.785497582220155,-11.772464308204734,-13.82679111257037,2.9231825044738877,0.0024877242652245578,1.8926159467434887,9.14324264016084,2.809578652411475,-44.647203320537564,-85.96591722146161,-2.132821229675409,-6.728370002479602,-29.71750284003771,-6.804176677991166,33.80648442884636,-6.615756204336946],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x360573e9
                           "[t1_e9r8ter,WrappedArray(Justement, faudrait :-)

                            Taxer n'importe quel usage sans prendre en compte ledit usage et le comportement sous-jacent, c'est dogmatique.

                            , Sinon le principe de la taxe carbone c'est de taxer l'usage de carbone pour inciter aux changements de comportements, pas de prendre l'argent aux méchants riches.

                            ),WrappedArray(just, faudrait, tax, import, usag, prendr, compt, ledit, usag, comport, sou, jacent, dogmat, sinon, princip, taxe, carbon, tax, usag, carbon, incit, chang, comport, prendr, argent, mechant, rich),(10000,[26,82,105,128,160,198,207,230,238,273,277,461,637,709,911,1034,1385,1482,3453,3476,6577],[1.0,1.0,1.0,1.0,1.0,1.0,2.0,2.0,1.0,1.0,1.0,1.0,1.0,3.0,2.0,1.0,2.0,1.0,1.0,1.0,1.0]),[0.05587267925080674,0.016329514263730374,0.21621859720976597,7.506776549881756E-4,0.009416526005392157,-0.006656759739873638,-2.2238463095045194E-4,-0.004659646152992162,-0.27995741861366846,4.697484254535327E-4,-0.02914147028501138,-0.0023495645454082786,-0.06173227810028082,-0.0038554467405820417,5.055274223328002E-4,0.00697118470860844,-0.001470869002221035,6.842491597664224E-4,0.008495467997023793,0.0035520745304547952,-0.17055368958439598,-0.025570437711478006,-0.013506179760064376,0.006345474192214803,-0.005956442299593287,-0.0041902137105084385,-0.0026073795820107724,-0.0022119530418334447,0.003979193071904826,-0.006318349339176124,-0.010011501926216311,0.011437370620898632,0.055395672781960364,-0.017782311408025636,0.010824103138722091,0.001580563295811922,0.12601482562030492,0.01376674146248399,-0.012134822531132194,0.012044623198764958,-0.5271815211794737,0.00504074999930663,-0.008861355690542841,0.023816596583288958,0.016769047200695966,-0.00697054021540173,0.03153041989914056,-0.04143019218710278,-0.01812503054226817,-0.02870240174380618,-6.911290617923405E-4,-0.07256685157049698,0.004410744453222378,-0.007316752441202893,1.0032459016058988,0.006907042856902339,1.4802966120956096,-0.06863153543943178,-0.07373603058878914,-0.13364185777804738,0.03853924557946782,-0.09713323926851018,-0.009551668775567956,0.02045286037568992,-0.025645378562135633,0.0510351612638227,0.4058019436434979,-0.03144335633125603,-0.6490382690305729,-8.920561483160717E-4,-0.07515966463807491,0.018314006656798186,0.14505376909138698,-0.014275384466239372,0.006120223565160635,-0.06134758179807896,-0.019436818096815422,-0.018873722768978154,-0.459769295688269,-0.007329746749162774,0.005393074316364006,0.437349735441604,0.0466697067752576,-0.010145544707340387,0.038716265554014584,-0.20947403674664786,0.006379857185775785,0.2611874377548641,-0.0030506640731885835,-0.13733099408154326,0.008205122479493059,0.0797440693587555,-0.003769779933154832,-0.16279753177116554,-0.019691166146613218,-0.02763705513690059,-0.08683652849819676,0.005790702379667384,0.026040873340045956,-0.03615155574231946,-0.2567854041460975,0.11620318269565909,0.013785117708428562,0.022895055126934544,0.05416201555747371,0.08975979192367375,0.015849076607704182,-0.5832776322102169,0.09926342113745419,0.0643268698033645,0.053116877442719077,0.05680602875530836,0.005368758874251959,0.002060939412989531,-0.21802793723405195,0.009770119624550141,-0.7310297933330078,0.17224188664029805,-0.01771661409003763,-0.10539283831032292,-0.018486450057516493,-0.055191645201443186,-0.0345399908206922,0.12616533597695764,0.17531382434414677,-0.0019357861235827015,0.034763984901558756,-0.08902026363977843,0.12992783080901152,0.549657705021155,-0.018254299123968067,-0.011724271584188397,0.08273182018417216,-0.06923521110859168,0.6186534485720331,1.5471563978144287,0.024516880439319006,0.544192823896074,-0.10501668474070164,0.05122506560243733,-0.2996979428572992,5.476365139970086,0.07515261371506429,0.6652448833997969,-0.09908222466087191,-0.9285315531209899,-0.021251606351398875,-0.13140616319854875,-0.09268774634898494,-0.09257349123410635,0.11946697512884316,0.04468325573173386,0.10493591878980667,-0.024582864634008002,-0.3574137360451118,-1.5053932144060536,2.5780405885311986,-0.7577407276710354,0.5003528178270764,-0.6985838782725384,0.447081508263252,-0.9515455085842525,0.8776776287236682,-1.5253550067396087,0.7166719004470472,1.4748522450217068,5.321149331770963,0.07131248284302885,-0.9619763272154122,2.2315073899330953,-0.12805851823842665,0.16229070056563985,-0.46767865463500746,-0.8150337395582599,-1.2728229692028123,1.8465712768869686,-2.158964747585792,-4.21147141004396,7.763948592433413,6.261621000269313,0.038892050497545905,-0.9701450539266545,-3.8167824641519386,-2.3175032992917353,-1.3351021257447966,3.6092395236559502,-1.0252264890173823,-4.449778928534084,-6.326388033461026,0.06875472502757679,0.9217303615714281,2.1869346762531467,-3.7767915067330122,14.415210943711005,0.9576588350129374,-3.5734371936167713,0.981627735386275,4.216287395003146,-13.699079588892717,-0.21292890082240512,1.283350972718434,-1.7186995235260767,-2.358738491810926,0.4700126423354951,-4.9744729975530655,-1.7044398898527258,0.5355121689072457,-0.7547942080012232,-1.2837713604861785,-0.9612363096269508,1.1674690324040802,1.4291982957522187,-0.5563854590336874,-0.5926683571353005,0.4327640995641353,0.6737471980285148,0.48830787208374155,0.10565212792516707,0.1105725159051156,-1.0549644837062093,-0.026760646216042416,0.25578431773738486,-0.0744262758172104,0.04083651233405471,0.04140451902794903,0.05138982760340622,-0.12005524351637437,-0.22185571711955954,-0.11307088797161849,-0.09025928302920587,-0.3646474729527419,0.10648013178518245,0.8236538862384359,-0.5334506625634681,-0.6033686797223808,-0.16255764580806958,-0.37956137723235484,0.10043479105547198,-0.2929674435688322,3.969673869066712E-13,0.07888263727100313,-0.8985656849321437,-0.27558787135380114,-0.9024300123181793,0.4846263496229741,0.2908848282144073,4.4479997045279465,1.6879183711743335,-1.0209139401662999,1.8222756463461,-0.2718752949547115,-0.0360858765773608,-1.0274503401645143,0.16858691658072572,-0.016975971652125547,0.44890419006250215,-1.1755262955887174,-0.6944280698164361,0.40807474289237605,-0.0764679307855759,-1.1705136551963116,0.7372764277568745,0.791058377004058,1.2476701484891883,-4.486400287480755,-0.6214928920367485,2.0875403810013857,-1.0316855830805398,0.282584579707617,-1.176041530618909,-0.32193861471805124,-2.989379531123722,2.09401231558336,-2.268544773173907,-0.6434742472776552,-0.060827979958581135,1.6869032022902746,-1.8831558507062875,-1.0135073010093436,-0.745972246261095,-0.7317924652710711,-0.16224326111516263,2.2512402478809817E-14,-0.6887052877851656,0.09607681285751277,0.2674374421917476,0.3714640016219308,0.03117845793340413,-2.590667583546805E-4,-0.6483654634193763,-0.3696567718350722,0.4310071249225917,-0.1462491269176139,-0.09091531150790512,0.09709109861461947,0.44913556433252094,0.813311279991984,0.8667821274786713,0.5719605823112163,-0.6449301493066127],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x3d676d44
                           "[t1_euhbyzj,WrappedArray(Une partie des kickstarter sont des arnaques pour gogos, c'est donc pas certain que ça ait \"foiré\" dans les deux cas du coup.

                            , Faut savoir que l'iddée viens d un kickstarter américain qui a eu énormément de donation mais qui a foiré lamentablement. Donc ins ont meme pas eu l'iddée quoi. C'est juste du detournement de fond a ce niveau la...

                            ),WrappedArray(part, kickstart, arnaqu, gogo, donc, certain, ça, foir, deu, cas, coup, faut, savoi, ide, vien, kickstart, americain, a, enormement, donation, a, foir, lamentabl, donc, ins, meme, ide, quoi, just, detourn, fond, a, niveau),(10000,[0,1,15,23,26,40,42,57,60,61,62,147,204,218,399,421,538,676,1848,2180,3355,3920,3993,4018,5629,6615,8073],[3.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,2.0,2.0,1.0,1.0,1.0,1.0]),[0.019629100087477078,5.704248314063635E-4,0.042753984958738736,0.005722933409401092,-3.7664027286557597E-4,9.985433205367477E-4,-2.2952610300523658E-4,-6.310785815937137E-4,0.011820371430028759,-0.0014160101079091938,-5.540834332990969E-4,-7.994374704944707E-6,-0.043729296530216004,-0.005258590729853498,-0.0026654694542961204,3.4160263652311827E-4,0.0038241607764169638,-6.531716244163484E-5,0.0018795794489829556,-6.061068292501019E-4,0.002034583377694862,-5.705320138962085E-4,-0.043541578427316666,0.03462404999375993,-0.005055786311557986,0.032892699074013314,-0.057570238136753195,-0.00627675558497006,0.0015976050119636232,0.0302569145701595,2.0130326516721437E-4,0.020033602191904008,0.03608495599925374,-0.034133392978231945,0.009645913690645831,0.0034318804213566167,0.0034778033675981813,-0.12404514284679218,-0.02002557491959802,-0.015049754629793686,0.002528049770546063,3.320213264361184E-4,-0.004326196774259282,0.00614193412900011,-7.834009382947076E-5,0.013117100876659066,-0.001005276978918956,-0.0012716794768448958,-0.009728472004617083,0.001970934727669468,1.3464288645561413E-4,-0.004884298447417676,-6.235900863009632E-5,-7.097386886810769E-4,0.010808053879912877,-0.008708324506977375,0.007241404574930503,0.015254058639428431,0.015516640937603157,-0.004182207579460116,-0.017451720106783315,0.011790025192550464,-0.019222483335664998,-0.0062532057649492545,-0.05031192004292269,-0.025484512109504864,0.0015728127285249216,0.05622175516005519,0.005097161976891715,0.0018092682517963114,-0.0013545911064594932,0.020788161031799687,0.03845194536708482,-0.014700205263451404,-0.04126529135817469,-0.01016396554934159,0.00530239695685334,-0.011018468643380611,-0.11453246846569604,-0.016744488504975154,0.06872986452325217,-0.07018699508994536,0.04093836009652999,-2.407290920107873,0.06320307795189295,0.21605431323626662,-0.05193343082500526,0.006132671132367247,-0.04690396105288548,-0.06943027391966833,-0.042884065408483135,-0.0021897107585584505,0.00131587816676767,-0.029588506703244827,0.01759878063934165,-0.29574523383061635,-0.16681812902894494,-6.472285778254911,-0.030754189628111772,-0.4149359421279042,-0.14593632860392272,-0.10401426442204145,-5.885044219815618E-4,0.06381981142864718,-0.2851743665904124,0.18163694321916335,0.7463657108171549,0.23075777123694963,0.07676765119966514,-0.010650891820082909,-0.167565279979839,0.34024849665502266,-0.1429474972844616,1.1156306867898249E-4,0.06246985842004666,-0.02774209740373492,0.027665618183857786,0.03927850666645777,-0.009828439883708087,-0.1261598851774507,0.004849527087805685,0.09419084070040129,-1.0179010084941194,0.10522706293710606,0.004957551350653563,-0.04918931332677268,0.013917154869730865,-0.05666813442116715,0.03243540899040098,-0.05234516320212459,0.08172015110761492,0.16953865157824075,0.09521586077538921,0.0018009319741837929,0.035602701513615415,0.012949613601354663,0.5503242083496761,0.05332068202059991,0.1523345649260074,0.12404104822376029,4.72706092133585,0.2772088054183989,5.089544810745764,-0.03889336227211291,-6.235123262633412,0.37795635472203826,1.2597655800687733,-0.646062314950779,-2.1195192735184802,0.17407614785917447,-0.2704669426815121,-0.09645908153605212,2.2853152472560345,3.7459443459810924,-0.14283245211297502,-0.0016482783723947847,0.1621728086255441,0.42941669906129964,0.07995655852061145,-0.21544012837268825,0.16667549925734004,-0.1549850482821583,-0.15111143294260454,-0.16866717122954203,-0.6857234971842894,0.07893848356195456,-0.07825803061274064,-0.020652008954587648,0.017030584099369854,0.06337488616166984,0.2723505127570961,0.4048250368745588,-0.27253099108758566,-0.41587036122376675,-0.09837752581460905,0.30551042357392033,0.2896253211889496,0.08029152984638047,-0.026775552339178513,-0.20735395921202002,-0.13258902936861414,0.21703034123465278,0.05687267631005957,0.26000591010511837,-0.2146818442127677,-0.5750912185297958,0.18992363243424432,-0.10690252152662379,-0.1549934206060548,0.13691067332667867,0.043152968419481484,0.3755694124441561,-0.8051217051785632,-0.5615836308331122,-0.4056067685188793,-0.6842333957914558,-0.17849272953246081,-0.08354085824175214,-0.49937583099781324,-0.21086641329771927,0.38187475431395496,-0.39868707159058747,0.06620785848815841,-0.19573469613928185,0.1731004918915159,-0.732845228809283,-2.9679380552998014,-0.3192589888641184,4.561450070633246,-0.5204929774218322,0.6489613216619637,1.1429712992811867,10.764524338025005,-9.66501085287502,-0.377169197281519,0.9083131939112595,-0.7277539893948047,0.36410296675638765,-0.30106422329709326,-0.9316055411362176,0.15263898905704834,0.15435397186675495,0.18111932141615358,0.7923425289038191,-0.1112805032954737,-1.1071507711578952,3.9461237748592275,-2.1527998849610284,2.0222900864054414,0.6239177789579857,-0.23086889988960513,0.47378508816763276,-6.623681585232775,4.112244663187324,-0.13361251039657904,-1.1492843841096407,-4.506370615088959,-0.011397500348443532,-1.2484417730835446,2.845160813969146E-12,-0.1980930283158543,5.293831222093939,1.7920417048978026,2.857315311027387,-2.458606140424546,6.740107592630034,1.2882087645700684,-3.224581189689089,0.8093405231052095,3.434302169271395,8.908556411004422,3.9311872520792437,-2.866874341023814,-20.11419434613759,59.71261704423071,-2.185028067831359,-5.762199336885672,-0.9350156513386916,1.0483392742876914,0.22059501227382228,1.9970598099084151,2.1957889564737574,1.1188447195029512,3.478416341828348,-5.365095649633207,0.3130601839459698,4.299377398630837,0.7442996257187587,-0.8794114825565907,8.350037761564366,1.4340221059565414,1.1487506916766674,-1.0975734018793033,2.312420527820594,-0.49485978124028,2.1367085997389905,-2.5826800604441607,2.4931120612710824,2.751998014987001,0.33775788749742497,-8.957019329359737,6.322428856207848,2.63927101769068E-12,12.64555390588496,-2.4797960538197623,2.118861526283478,-4.933535015606321,1.063805842598853,-0.0017831988911670923,-2.46040376210478,0.9171244418463191,0.3408089371748363,-9.83490375342732,2.093456479439191,-0.41078674428291506,-17.165129123825864,17.967033699571513,6.167899485832667,-39.05360184009805,-3.2893944989581674],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x7f5c2bae
                           "[t1_eazzj91,WrappedArray(Parce que tu es un génie et que viens enfin de comprendre que c’était la question d'OP  ? Où même ça tu ne comprends toujours pas ?

                            , Oui, c'est exactement ce sue je te reproche, tu te concentres sur la France je ne sais pour quelles raisons. Enfin si je sais.

                            ),WrappedArray(parc, gen, vien, enfin, comprendr, question, op, où, ça, comprend, toujou, oui, exact, sue, reproch, concentr, franc, sai, raison, enfin, si, sai),(10000,[1,3,17,19,20,39,52,53,70,88,90,150,182,282,289,399,1170,1204,1362,6687],[1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0]),[-4.297928849636256E-15,1.4367489487158095E-15,-6.699868726793342E-16,-4.4308067648791754E-15,-4.018452830919337E-15,-4.096499004614509E-16,-3.8227950569721185E-16,-1.7968814430664663E-15,-2.8660357097936256E-15,-2.3468147741757464E-15,6.369645521521539E-16,-1.4424895482914886E-15,-2.0055150207256467E-15,2.7499166218329595E-15,-4.7672706917803704E-15,1.1588269906485165E-15,-2.8158254729861567E-15,-1.981694046586024E-15,5.095490973827935E-15,1.4270437557062614E-16,1.0288237583002617E-15,-2.3088007560588566E-16,-3.080867229704899E-15,-2.9067784794827195E-16,-2.775596037749115E-16,-2.24547883569185E-15,-5.048076309064335E-15,2.9868188059529784E-15,8.264741069371228E-16,-1.90347946154399E-15,4.257578633399142E-15,-3.0026985808735257E-15,3.4435712536178335E-15,2.0574170778782E-16,-2.068523340080235E-15,-5.922687796903647E-16,-3.1477470915329727E-16,-1.5011749037624954E-15,-1.869885639688361E-15,-1.271194420877851E-15,6.992619163522851E-15,3.25754575898581E-15,-4.879840873595182E-16,6.574514461847761E-15,-2.950089385230019E-15,9.392716663919765E-16,5.0361187655598865E-17,3.3530128114365286E-15,-2.7883424887437467E-15,-9.481072634302516E-16,5.208219933592507E-15,-1.510402594011225E-15,-7.584421016709678E-16,-2.0365779148981593E-15,7.839913932613931E-16,-3.5265298050500234E-15,3.734498201813507E-15,-4.828455065074392E-15,-1.0331787893819742E-14,2.910603402606455E-15,-8.181625457006734E-16,1.2847394805513003E-15,1.1281440657951588E-16,-7.688484722215445E-15,-1.931816976390559E-15,1.0030699584654308E-15,5.864975450391776E-16,4.967022687902974E-16,9.644644284379677E-16,-5.715181054784441E-15,3.4682738482984676E-15,1.0329451611837365E-15,-2.2596431070713377E-16,-8.290984547851804E-15,-2.059369073398204E-15,-2.214736022965086E-15,-8.548700523172083E-15,-3.0065934060042633E-15,3.174036717670466E-15,6.533852451960404E-15,-1.332236137274918E-15,1.4955883907155006E-15,2.7401228484991376E-15,-5.795935054777149E-16,-2.172134595908436E-15,6.8638862873832E-15,2.6910757763947754E-15,-4.024038851033366E-15,5.4166097407411825E-15,8.632747979674324E-16,1.0395480918570942E-14,1.2559949331975098E-15,3.922796228184442E-16,6.261274324178745E-17,3.2722981632676937E-15,8.460609621077505E-15,-1.3164756844515177E-15,1.6242892578281643E-15,-8.334722995198631E-16,-7.708448258702857E-15,6.6880378938640846E-15,4.5522658967948984E-15,1.7118923546162954E-15,4.460009179584759E-15,-1.1169533707895247E-15,-6.591396716478218E-16,4.2335440350329955E-15,-7.29779466153592E-16,-2.404650745853279E-15,-3.7682605737973075E-15,-8.3665816381032775E-16,2.500428441318561E-15,-4.156080727638351E-15,-4.627273699891858E-16,9.825081918252656E-16,2.3273927750392104E-15,-6.7723628647125696E-15,-5.718637709351147E-15,-1.906060035748634E-15,1.4473258142438767E-14,2.655157639259141E-15,8.109301512716852E-16,-2.8052660387982768E-15,6.679543920793497E-15,-2.1880515937200695E-15,1.664811761812095E-15,3.4064783515911305E-15,1.8625761334126447E-16,-6.141133567942782E-15,-7.026487227698628E-15,1.1163634777741165E-14,9.304048145280061E-15,3.0988031187114813E-15,-5.413148560716502E-16,-2.7295911493067743E-15,-2.9849228519038427E-15,-6.883604612111476E-16,1.8794902873517095E-17,2.0527345512987507E-15,-9.688961034696005E-16,-2.5327759693317824E-15,-7.139243573701563E-15,1.2229006160188516E-16,-6.444402011840525E-15,7.211094661585663E-15,-4.71497040216863E-15,1.735769537403327E-15,3.0839814339223386E-16,1.372648909506959E-14,-1.8374213557735304E-16,2.0475874908353594E-15,-5.936229531725131E-15,-2.3421002435900633E-15,3.8413850482268434E-15,4.217196796770277E-15,-4.738718022571747E-15,3.0600498358914062E-15,-2.6622898612879035E-15,-3.637208482030051E-15,-5.678679143844091E-15,1.4829536286183069E-15,-6.505149628692013E-15,2.481130769499112E-15,-1.8969828512093208E-15,-3.66963846814483E-16,1.037576909213417E-15,3.0727879711362673E-15,-1.5539073951958206E-15,-9.911712681138102E-15,-3.1040354714045285E-15,-5.268749641052447E-16,-1.3699645832945486E-15,-1.7115801108078826E-15,3.5056789072498913E-15,5.503082454426144E-15,-1.136033163070783E-15,4.557007122194869E-16,-2.5113741679151996E-15,-1.4324589879327046E-15,-8.096577792208119E-15,-2.4025104652618645E-14,-2.09809243026019E-15,-3.687078444918009E-16,-4.354783057424143E-15,1.1432354867336768E-14,7.069577348779416E-15,9.428584051767276E-15,9.92328576687325E-17,2.262339553604488E-14,-2.5821891603278427E-15,3.432303808065739E-15,-2.7079870166926105E-15,-1.9500137470019527E-15,4.898140486234235E-15,6.6501766815214375E-15,2.6214559576719162E-15,5.995007856052164E-15,-1.6711920362959674E-15,6.143563560098349E-15,-1.0924345422616092E-15,7.683520117872635E-16,-2.0895238263424057E-16,-1.3279107316197338E-16,2.1329775710644938E-14,-8.523431527784562E-15,4.436954104958119E-15,1.9087969057445237E-16,-6.223752824616704E-15,7.367352398001952E-15,5.532128083352006E-15,1.8404358560468807E-15,-1.1679899589446161E-14,-1.413787489416547E-15,-3.984332472570145E-15,-1.4018172668696465E-14,-1.3979756968229992E-14,1.7148627691893112E-14,-7.162880098109788E-15,-6.95596412234899E-15,-1.5123318855352395E-16,7.637983170352161E-15,-1.847352446776654E-15,3.386724070665659E-15,-3.9068408574815083E-16,1.8335413906389576E-15,-7.320257650299456E-15,-1.0187248736467443E-14,-3.805308661011319E-15,-2.027519832323605E-14,2.623419400161483E-15,8.544317932406205E-15,1.9798441733147592E-14,-3.622432614422588E-15,-2.361670166035619E-15,-3.3406236997058578E-15,-3.794390956554627E-15,-1.200452937366428E-14,-5.576963043120932E-15,-4.784740994043648E-15,1.735454161713418E-15,-3.436681501608782E-15,-9.07998867491667E-15,-5.71272789932854E-15,-5.888551673558949E-15,1.6871231009614204E-16,6.4852838759845296E-15,-2.577920638728781E-15,1.9549100890091044E-15,2.5910780685479625E-15,1.1471292621894008E-14,8.48693408726815E-15,1.04744509292699E-15,-8.895793034317105E-15,-4.101717307833543E-15,5.065696696102946E-16,1.2675026813998535E-15,1.625296753147417E-14,3.2286578465530804E-15,5.178951910726747E-15,-4.6445296706549516E-15,-6.530360047841137E-15,-3.1708489127358024E-15,-5.074368148601327E-15,1.5013728759287065E-15,6.14211125019758E-15,2.555601293286172E-15,-3.3644405696904902E-15,-7.550295331882218E-15,8.265931734934279E-15,9.147984599557734E-15,-1.3922448183478528E-15,1.3448427365570616E-15,-5.015592574637391E-15,-4.962729926586512E-15,1.927374063175214E-14,-1.5824468372324924E-16,4.340590179906986E-15,4.376258239384121E-15,3.598690475571854E-15,5.976244862760643E-15,-6.492422135421731E-15,2.667417183455993E-15,2.163852816352527E-15,-1.1997834236834272E-14,-1.110774008774088E-15,-1.2566084488616273E-14,-1.152351828231763E-15,-7.160702687041328E-16,-9.706595156021258E-15,1.147914139361336E-15,7.845778255843631E-15,-1.9506692949590423E-15,1.0251134158493586E-14,-5.789193189593429E-15,-2.0943718994245526E-15,3.6895981185052105E-15,-7.641200792766879E-15,-3.390879803964828E-15,-4.515657421076105E-16,-1.0021661380335687E-15],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x21a518ff
                           "[t1_eb80x1r,WrappedArray(\" son crédo c'était les appels au meurtre des forces de l'ordre. \"

                            Mais quelle stupidité tu nous donne là... un mouvement où ils sont à dire que les grosses atteintes au prestige de la police, comme Rémi Fraisse etc, n'était pas la faute des policiers mais de ceux qui donnent les ordres, et y'en à encore qui arrivent à dire qu'ils incitent à la violence sans AUCUNE preuve, sans AUCUNE source, sans AUCUN citation, sans RIEN...

                            , La Mélusine qui justifie et incite aux manifs violentes, il y a quelques temps son crédo c'était les appels au meurtre des forces de l'ordre.

                            Elle me fait penser à ces leaders syndicaux/politiques qui n'ont aucun scrupule à envenimer la situation tout en restant relativement à l'abri (pourquoi se salir les mains quand on peut envoyer des gens à sa place ?), pour ensuite accuser l'autre camp des conséquences.

                            ),WrappedArray(credo, apel, meurtr, forc, ordr, stupidit, done, là, mouv, où, dire, gros, ateint, prestig, polic, come, rémi, frais, etc, faut, polici, ceu, donent, ordr, y'en, encor, arivent, dire, incitent, violenc, aucun, preuv, aucun, sourc, aucun, citation, rien, melusin, justif, incit, manif, violent, a, quelqu, temp, credo, apel, meurtr, forc, ordr, fait, pens, lead, syndical, polit, aucun, scrupul, envenim, situ, tout, restant, relatif, abri, pourquoi, sali, main, quand, peut, envoy, gen, plac, ensuit, acus, autr, camp, consequenc),(10000,[0,4,5,6,10,11,14,19,23,24,28,29,32,38,39,55,71,85,91,92,112,119,133,141,161,186,197,250,296,346,358,545,553,556,563,614,718,832,844,858,887,953,1023,1068,1172,1352,1482,1586,1871,1913,1957,2133,2569,2908,3934,4306,4314,4378,5197,7008,7012,7673,8213,9569,9861],[1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,4.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,3.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0]),[0.07870576781016153,0.2444037319405476,11.849235872409318,-0.14984305664174882,0.08895941781379843,-0.0380275414164717,0.00288217304642378,0.008795956778218188,-0.19859771993276298,0.009893094834424295,-0.0010684339434319187,-0.0037054897790232606,0.003128192115973323,-0.018380474181029925,0.0870318999735487,-0.05669106737353517,0.0011979583890887012,-0.2235050212227498,1.002741166252689,0.17017353346785402,13.375640227980753,2.3358310141498495,-0.016262169251696355,-0.024129098785147615,-0.1340593309245966,0.11684725071917872,-0.0075379835840867675,-0.35782254667997554,-0.05170437805225543,0.795974526924282,-0.03253005533520739,-0.027732581774590957,-0.03954065696358851,-0.21947782279029632,0.25937487620614125,0.35493903999707843,8.230404594537356,-0.2677082979802269,1.2442481022854908,0.04833690060974484,1.1160425357506945,-0.15222785541629288,0.2976521505430959,-0.0425485714398618,0.1666377170759717,-0.16930224680827735,-0.03621648979089842,-0.17059906930039714,0.039580105057668936,-0.06515287147402912,-0.0010062215850353059,0.007083272656792849,0.022189675109415292,0.007119171728390555,-0.16920511352789144,0.18052654519779052,-0.6447650904938298,-0.05515677843077202,-0.07453715207799695,0.050252644792982866,0.054148111930820024,0.051690218836195984,0.030536338264451273,-0.0721815669817542,1.100673621039646,-0.4519627810127947,-0.1926170691590415,0.002808248157831609,0.06542284407094466,-0.0014543860741596485,0.05439436485689588,-0.04677253880471085,-0.004715494616279077,0.09561618983273631,-0.03231301844122001,-0.001153562035836586,0.17485087548511632,0.005632659499924124,-0.06286310912490091,0.13848938510258707,0.013090576531492733,0.9062488251363434,-0.2247820860628839,0.15290171754391502,-0.7071419658132408,0.1547952882682087,-0.3754844205201008,2.0682694349210244,-0.03517192523953923,-0.9190005515262019,0.6760619448660985,-7.40816899994811,0.0762704187942612,0.28565273171633154,0.17368060647670028,-0.06232992578059824,-0.578559430914995,-0.30667953045963364,0.10056290294125347,1.1012245520986643,0.015473579415970586,0.1191233152133753,0.15188067216258366,0.050762593639543316,-0.20918439190075594,-0.17860578182434572,0.10367231926729502,-0.12062107389719527,-1.6812714738200845,-0.0670621130394849,2.3157448438984405,0.2758934195058955,0.13693337349522763,-3.5980957133303786,-1.9136491382887546,0.03527370310917706,0.034633413998530496,0.255605403286388,-0.03507709112418434,-0.48125820026195043,0.024474547566685278,0.2811746164468711,0.3260483005226107,0.00295740806347455,-0.011043032584415975,-0.2023613304730384,0.2559511350023368,0.07586474301418378,-0.06667583206824626,0.49972575127572494,0.01772963401739502,0.19481119767267513,-0.11068302592800223,-0.009225682975818208,1.3618104194249803,-1.2053065583874898,-0.17950744181238587,0.4543610646766997,0.042770611414587136,-0.32550088856138304,-0.39202504740019306,0.07778323143047629,-0.4118636358844879,0.24741114735434525,0.09776638880117632,-0.3645051391372804,-0.3001879062993616,-0.039492863242761175,0.3381110420100757,0.1454013826087465,-2.5050609635736314,-0.06456626230305063,1.7547505480004577,0.17196131062362618,-0.9053335896483252,0.3301919973005397,0.16463053925468116,1.3861147770366973,2.728078113709272,-3.9365120718183713,3.1908959990916617,-2.8558486820518327,-1.0002168427045306,0.3764252120361117,0.17728008815826335,-0.43258411920452083,-2.2079107011655736,-0.5261059975758573,1.6893150950050424,4.099735398493725,2.103942758265964,3.299975383822651,6.08148653695015,35.52189006295797,17.239214907211473,-7.383892597342679,-4.908297821045916,-0.5427306047007028,2.0022681406651,-0.04851884158439201,-3.580105250275026,-5.773229281156451,-1.064897016446042,5.81577880503849,-2.8112190146943608,13.470342661394595,1.1324943091540738,-3.017729831056559,-0.4062921572915351,-6.81290056329008,-3.9452501596765823,-15.501400099916035,26.080230104788594,30.139275391626228,-6.952552358592669,23.195610512377545,-0.4289469574947519,1.5024507635356166,13.077273162032885,9.061986930088763,-8.527858299861256,6.497625212485083,-5.020865231493303,-1.2700805294045816,-3.8826411921752326,0.11732737369821075,-59.38619633071638,-20.079070270018025,95.27176889436817,-38.290426909940884,-9.299878340908734,22.198724907516542,-22.44994134842385,3.168771703341443,-6.518525480061521,-32.794817905731946,19.203026559126577,1.5551400408935392,0.717431192367481,-7.232605411525609,-1.0964765540300947,-2.180680948591586,-3.637516713606537,-132.51966312773033,-20.824662815778275,219.86279336955516,34.614326767343826,-12.25075554536165,-53.45156001551243,3.8939916005554545,0.8595523730801734,-14.5365280021595,-3.963839864550298,-9.622236945462651,8.837585994317937,2.1779747706635604,0.5140003466322415,3.5523735946071566,-5.632824304300534,7.845134166699595E-12,-2.7842632992583876,-4.49970168951831,3.0106011966806356,4.147231889706422,-0.09647304811078895,0.1825432836981311,-0.26197658576531435,2.3731793046375667,-1.3638146699217573,-2.6245521196012915,0.04603662264713612,-0.2337859161647668,-0.8156360677932698,-0.8287082738613233,1.8187484096534896,0.16930952697680285,-0.6794606879184674,-0.49897545652468916,-1.787710191160647,-0.045176563204909294,0.5727392643924444,-1.7675519297917928,0.4259181253466977,5.0267352628132835,2.872245289087608,-0.607247032486492,-1.6052236801310529,-2.814017580296367,0.6805767811066898,-0.5868143729680557,-0.4206463211985442,-2.0959435826343977,0.8768799507712385,-3.3875927021689525,-0.37141443339478847,0.6604335149546552,2.5650199001189447,-1.726992507658037,0.19257084312958656,-0.3110958924019655,-0.1210116624390813,-0.510822236834857,-3.06694694225606E-14,0.5537649624739165,-0.6470219901979316,1.5216279742583918,1.6180112044708914,0.3371747173969267,-0.0010051216341321716,-1.178632412506903,-0.9666858886524221,1.12809700973733,0.6773279220265542,1.6958600501207868,0.344563381497166,-0.004818081540757899,1.5976239332639097,-2.3776155969847053,-0.7177642016769733,1.050506892052453],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x3ca2ad19
                           "[t1_evhisv9,WrappedArray(petit rappel utile les parlementaires ne sont pas souverain, le peuple l'est, il délègue et comme toute délégation ça n'a de valeur que tant que le delegant y tient.

                            ),WrappedArray(petit, rapel, util, parlementair, souverain, peupl, delegu, come, tout, deleg, ça, a, valeu, tant, delegant, tient),(10000,[0,1,4,6,83,253,453,508,591,940,1119,1353,2717,4169,5101],[1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0]),[0.13204497356997014,10.465988281776948,-0.09820315763369673,-0.09316618058124342,-2.621110355500836,-0.6677997694822295,4.8702752770454354E-4,-0.055373120611309196,0.12487282767322044,-0.07541560105721759,0.0023004296286361905,0.003285899832466454,-0.10883658442198287,0.043593048410362406,-0.06928182971542543,-0.0056840435904453295,-0.05041285406574582,-0.16935522460351635,-0.0753395263849793,-0.18785877306679402,0.1355206703264179,0.012144223954606006,-0.05537027750328366,-0.0688507444587431,-0.016543161711147766,0.006442515403755077,-0.013161106959270369,0.13579612702196237,-0.11052241348137087,0.006132650166954523,6.827375082986615E-4,0.27073074155244486,0.007583609582136289,-0.014030752291477308,-0.12808740666625043,-0.011586685338516315,-0.12097512173149119,0.011304427397902802,0.02681157261080966,-0.007464170998228334,0.008619020030119951,-0.08868393729918211,-0.04327258355974767,0.04149111667601436,-0.025937173854000056,0.027461683876868977,-0.09503634337166345,-0.0016636069863754527,0.004602126558177275,0.008320882875636385,0.01866886769813498,0.006181093892883223,-0.0065503582237934135,0.0036118200354939684,-0.04915167175147946,0.05749824301191211,6.446056451286817E-4,0.01175647442992132,0.02685997633953077,0.021208590697676475,-0.03144211548807129,0.008650780580040228,0.0321978848482994,0.02850271104292333,0.007915944425166698,0.01710578708981249,0.1578686232040249,0.07248865327607913,-0.019398468383474,0.0030280441717549784,0.19915214196180547,-4.2959992169455905,0.06809284303830092,-0.01928309185362262,-1.0006343890321778,2.365854667639199,-0.04591818513394776,-0.004885652857997575,0.09541261596286135,0.008887988396973455,0.04042156668385467,0.19292602216386637,0.021198609188347185,-0.02936264271435526,0.04517894467242188,0.03065202629609405,0.04667003409473253,-0.10576528291573256,0.08221912359528803,0.050976841167015965,0.06817346736112531,-0.04442051420031759,-0.0019085841719329486,-0.014477671439552697,-0.009254724947676745,-0.014488889124035138,-0.016160026231527083,0.007803853741711849,-3.8026774046175625E-4,-0.024394308849788826,-0.008433267324873428,0.024580110084563858,-0.44555288892667466,0.07273374926109799,-0.012742651831590431,-0.015700350679395264,-0.03859020295832161,0.216965636483785,-0.026178659427408067,-0.00145597428843278,-0.03129451590460714,-0.015525498538643325,-0.010489033047157538,0.036440083251660295,-0.034786366978674876,-0.0027739213887601616,-0.0020795263106816583,-0.04141616263648655,-0.03869717014596809,-0.23461804555551774,0.46543507600219086,-0.021839921908378454,0.008004968711112365,-0.02945161711316919,-0.0673958374884874,-0.059747515081141275,0.01249731972861367,-0.011787433698311842,-0.007144295906602212,-0.008136413466243376,-0.033121110077709105,-0.00915011849985566,-0.0264406693158625,0.020076199550285303,0.002300473120277341,0.06426209495193896,0.012598206021639524,-0.06368323621428224,-0.13048538307368923,0.16924835018656265,0.012443082120985965,-0.09276083195535079,-0.1288193123550337,-0.03136567922685235,-0.08263719902633078,0.1918554060350184,0.0716274552640947,0.05684745892573199,0.07627868761774759,-0.0017919833327149407,-0.0700889800427851,-0.017706313581132643,0.029515748198798892,0.0016067525413563754,0.05618800435927989,0.019761741147396858,-0.2868471090488686,-0.10393914482656699,-0.03850734553290821,0.17651571915587733,-0.007341306351841849,-0.03601655986400297,-0.24188230106579933,-0.013494437719825055,-0.06323565031628113,-0.12986067895716635,-0.09072591786664548,0.01513724754600584,0.08491651305875697,0.242862697751565,0.08029572200370509,-1.3847231446637394,-0.4660909659738322,-0.30614851177478153,0.8368023062942263,0.0033909644301438058,0.6329308488438596,2.168330895877137,-0.8126935690967503,2.7146355365175387,0.8840797176150318,4.338887049858183,1.61055285097496,2.0951448424103276,-1.3629409871456346,1.4408928039507132,-0.2522454136759021,-2.0094363350710367,-0.24860351338821896,0.007356195620311644,-0.05479607825177243,7.077741491839928E-4,-0.27671521710042096,-0.17921895683419997,-0.27112030306384594,0.5946993932901213,0.014804629576987999,0.3744718198315297,0.08252797502066984,0.1953965430035681,-1.668794165341869E-4,-0.2488470982515962,0.3372478357037234,1.4768980170697275,-0.6062782419565671,-1.4972582841206856,-0.010520794000603004,-0.012283746023135603,-0.06066252154121052,-0.23828301435915128,-0.4420710523275079,-0.01594772846204511,0.4030210148334312,0.5088458563085513,0.10950323265041445,0.38608190998825,0.0764654802335338,-0.22748723602296186,0.03499323920079115,0.169658406617524,0.2769775601499103,0.14698073271522455,0.01960857818735458,0.0721578127789923,-0.0075365111352430555,0.05127087898040526,-0.030025571338150252,0.014512056097400486,-0.2087981153241979,-0.33041182909198413,-1.807304556999286,0.09594087425315719,-0.08581978949488046,0.07050600313682473,0.15191041811077677,0.10247772929453627,-0.18099728722326833,0.03673676594547699,-0.047022178867526826,5.916840918700057E-14,-0.014423977147093819,0.11293166348875944,0.012361398518014002,0.051321546743813895,-0.13755510781485206,-0.024033544896773625,-0.06556152493423717,0.0683520203826398,-0.049569997724935574,-0.04756091239383853,0.15953897789558952,-0.08124461859158433,0.02123613508349826,0.0011987561196932167,-0.06733353680497237,0.044964230116757375,0.07607588315873441,0.04462951695476895,0.12282589447434626,0.0201432073905198,-0.053754899495189745,0.16481852991172866,0.04548780591823771,0.17243189249759724,0.0440243922809223,-0.02781204937933359,-0.1428998560569843,-0.033326363812864294,0.09155496345369955,-0.2192843340276629,-0.0640120788463909,0.027373307295036092,-0.35886475707767596,0.2467748047937502,0.1516984408214962,-0.16371168173544395,-0.10206685392056583,-0.28695437618800745,0.14247499966207722,0.044827492298720295,0.248013507258945,0.02645110477773046,-5.581235819539087E-13,-0.41633380929866864,-0.04654052405469633,-2.0966014046307406,-3.5950698034950106,-20.41584516441486,-9.231922175096299E-4,-1.5617903201279222,-0.2605267728974152,0.26325434045931445,-1.0534590371768418,0.8651086714003879,0.15884550201112374,-0.225312437964308,0.33675276430942147,-0.011939869495019365,0.47309994322563553,-0.06729634985164716],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x3aa9ea84
                           "[t1_em213j7,WrappedArray(Non il s'est arrêté. Pas instantanément mais il s'est arrêté.



                            En résumé dans la situation suivante on n'est pas dans le cas d'une agression sexuelle ni d'une agression tout court:

                            Trucs sexy consentis en cours .

                            \"- Je ne veux plus.\"

                            \"- Tu es sûr, aller..\"

                            \"- Non je ne veux plus.\"

                            \"- Mais alleeeer mon chéri\"

                            \" - BORDEL J'AI DIT QUE JE NE VEUX PLUS TU ARRÊTES OUI ?\"

                            \" -Ok, ok\"



                            (modulo le contexte exact bien sûr)



                            La gravité est importante pour qualifier les faits. C'est important aussi de faire la distinction entre ce qui est mal et ce qui est suffisamment mal pour que la justice puisse être convoquée.

                            , Tu prends la chose complètement à rebours : ça ne te paraît  pas grave, donc ce n'est pas une agression sexuelle.

                            Pourtant, dans les fait, ça en est une : elle n'a pas consenti, elle a clairement exprimé son refus, et il a continué. La violence et la contrainte physiques  ne sont pas nécessaires pour établir une agression sexuelle.

                            Donc, c'est une agression sexuelle que tu ne trouves \"pas grave\". Est-ce que tu ne crois pas qu'il serait temps de revoir la façon dont tu considères ces choses et dont tu comprends le consentement ?

                            ),WrappedArray(non, aret, instantanement, aret, resum, situ, suivant, cas, agresion, sexuel, ni, agresion, tout, court, truc, sexy, consenti, cour, veu, plu, sûr, aler, non, veu, plu, aler, cheri, bordel, dit, veu, plu, aret, oui, ok, ok, modulo, context, exact, bien, sûr, gravit, important, qualifi, fait, important, ausi, fair, distinction, entr, mal, sufisament, mal, justic, puis, être, convoqu, prend, chos, complet, rebou, ça, parait, grav, donc, agresion, sexuel, pourtant, fait, ça, a, consenti, a, clair, exprim, refu, a, continu, violenc, contraint, physiqu, necesair, etabli, agresion, sexuel, donc, agresion, sexuel, trouv, grav, croi, temp, revoi, facon, dont, consid, chos, dont, comprend, consent),(10000,[0,1,2,4,5,7,8,9,13,15,18,30,33,40,41,45,53,55,72,94,115,122,129,130,152,161,170,182,192,195,197,219,226,271,280,289,291,299,312,391,439,463,467,475,486,561,573,604,605,767,775,778,782,834,879,925,1002,1123,1429,1901,2175,2331,2554,4185,4269,4352,4684,6900,7337,7431,8046],[3.0,2.0,3.0,1.0,2.0,1.0,1.0,1.0,2.0,2.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,2.0,1.0,2.0,3.0,2.0,1.0,3.0,1.0,1.0,1.0,5.0,4.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0]),[0.005987989812230839,0.046622813679891205,0.004291121226030214,7.499705404721087E-4,0.03453695126326512,0.014250396505318879,-0.0015566368287646408,-0.01739034323728578,0.008165091405635755,-0.005719575710618823,-0.07190983246104704,-0.034922176051587595,-0.009748304022764621,-0.12865363760344117,0.015663969836454766,0.008443464731445716,-0.15420683669574653,-0.08923683625469359,0.40933707220140436,0.020507435765498783,-0.025734016971769885,-0.01621299613286047,-0.005633397543205185,0.010622519048939431,0.0047163364375415375,0.026054399298584805,-0.0059803234437618485,0.04274600484028816,0.06679105681298861,1.6463012459301052E-4,-0.11504508399002224,-0.04677055772471772,0.45603638668999663,-0.3149085466572639,0.0038368812954472048,0.005482409121477298,0.4490913333001953,-0.17196762147049408,0.06870142542199395,-0.010232018376201078,-0.8797775635709171,-0.016989475103236038,0.014927560481126624,0.03116274836585249,0.19091143513057088,-0.09705037405128747,1.0805637668353771,-0.0764242818125304,-0.2745510316386572,-0.02117078491378256,-0.0011956686702814276,-0.12214803260933405,-0.2261503323254446,0.006240937813360705,2.9703401450186626,0.38720997380130423,4.532332450817505,0.9122620296376859,-0.8575340042509987,-0.42185404986331204,0.003803902353624241,-0.5086116485353431,0.03846947738175089,0.2464089393303512,0.18723398073480482,0.2834929420944988,1.5691201997750035,1.7769300240472883,-1.3375917115531621,-0.001680762441730223,-0.5189393601235991,-0.04058495844518282,-0.07736346328383067,-0.08744836667152134,0.02773840594336969,-0.10820660421381996,-0.9738242182346766,-0.009865739525928077,0.04284267023120377,0.017269163586230422,0.04605280562985795,-0.05665585780677255,0.0338147911245272,0.1785992322867452,0.29726977147401895,-0.09442428483705503,0.07150960550760675,1.6994456954266886,-0.063169539687166,0.38651583656642813,-0.008974931975020608,0.4564096700864137,0.0018765837574452977,0.0035298662223777555,-1.029242065241118,0.03425752706073285,0.5129567080862024,-0.07793558545384649,0.031438297974521266,0.23965389963382142,-0.10326952696698896,-0.11627573398654537,0.15288674952916548,-0.016176585577426423,-2.2026852496489684,-0.006561233096372444,-0.7040557272589061,-0.3620931406534718,-0.073892919772877,0.42676236224861896,0.22767013432470243,-0.013235092228169891,0.49945916835887305,-0.04677344191980058,0.16691385059227198,0.08557915323770186,-0.03128841672651808,-0.03588596239061642,-0.2529074634131726,0.10542439924204176,0.11269392785623528,0.032803252900707,-0.018533735978817713,0.12155920991683242,0.17630517693401104,-0.10454610173562592,-0.034717503288839394,-1.1466677184451515,0.12789393711517952,0.04963939934059701,-0.09140267094281279,0.17149691954597404,0.05676265348586044,-0.014293766161207816,0.12380815820899892,0.07031623569185978,0.11256110101795892,0.09903362803355187,-0.06407780452658592,0.006675324375143042,-0.04478078498666219,0.3645583174267384,-0.067247594160709,0.1085122116598661,-0.14610684725802342,-0.4446352653200197,-0.030333487219152723,-0.12920993098117894,-0.13171917324171672,0.12458865214400391,0.10401711440880047,0.03472131877833601,0.13824048192473803,0.1284980605140616,-0.16967423240066248,0.022479162546421346,0.4144545493520326,1.3072604868486095,0.547555954226394,-1.5436385410528666,0.3878281082604482,1.7243913206785202,-0.6077349719891735,1.4056627693199668,-0.8545912819779596,0.011559343678777697,-0.240810608930838,-0.35570970604741015,0.2685343555462808,-0.5810672488835855,0.06735395268566366,1.0242841428832554,3.300643447057722,0.9240189524445754,1.699961911922139,-1.2123263237517001,0.2854824430824482,1.1009383562510275,-1.5562185327516853,1.7979266267493885,-3.0396665435802546,-1.9794297199665147,-0.29431615460143523,0.5227879819396686,-0.6945842418195974,0.9389812570052675,-0.05909062981908355,0.6943617360208444,-0.8225946644447586,4.029424688913293,4.053726300309186,0.9202221364571695,0.04709010470384866,-1.509878186469562,-0.31626683005831197,0.4184273287576763,0.9428374174961867,0.21717956615236467,0.8391785626634379,0.23557056541009058,-0.33576490397865216,-0.40457295748464434,-0.11188322888380806,0.09313094687536984,0.6852580634398624,-0.2746663988457383,-2.477421693181248,-0.06356320190939162,1.6334740341524159,2.293799884603481,-1.8698762382985963,-0.19968342041850282,1.7725537131809843,1.8154802299544353,-3.753281648079453,8.899916559466352,-4.081596462972377,4.9909110881049035,-0.26426790468161654,1.6517008741660004,1.7685351536328233,-7.887353898984801,0.7476376471587118,2.77514542669985,4.115297653966515,-2.4001365648954853,-1.0496298198856726,-26.00747830553268,-9.450708082923333,6.457177402814331,-0.8122455089291093,-4.617266550304851,-3.3049401560915213,-20.724566783310113,-4.620852176472811,3.199646166740247,13.098981238172076,0.4208471799606943,0.8367465732446524,-2.0058670122556337E-12,0.5327938932017308,-9.81210155083016,2.4931727401837778,-0.6488000610948363,-1.3052834199444994,0.8770959821101142,-1.956438961149181,4.9346349450972244,-2.2557211361930336,0.16589225814269942,-2.600900107569371,-2.197095736151879,6.244055104299744,-0.9843658398758734,2.0719883031545554,4.231227799644825,-1.920461059485584,-3.401455523618939,-6.6420078927863155,0.9521221519153347,8.314980233825654,5.533295723359867,-4.498074220218062,-8.739384974398448,20.857008812390944,-4.0360766021961725,10.355329940493878,-10.101635456940784,13.108822470541764,3.287873134223771,-0.6171849309279923,0.20735663622640071,20.221956805646847,-7.219927529188717,-0.7725643320516449,-4.874634127894876,10.491993215690359,-0.8796273187730185,26.051190771294856,-12.096986298681392,3.983463912315197,26.05606119291926,-1.0750572315094052E-11,-16.34607988457964,11.685760787084682,-21.341164860167005,-20.1540018080317,6.180862890329699,0.001075032817591814,2.98058372028593,-1.6240093649301526,14.413598669804049,-3.336826929711912,1.976239376372171,3.1467500692098382,0.8600043028841409,14.020338141399826,-2.0565880947765938,-4.23381442222611,-3.744460437824759],0]"]
                   #object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x2436b724
                           "[t1_eygo7fq,WrappedArray(Bonjour,

                            Ce commentaire a été supprimé. Merci de t’exprimer sans insulter les autres.

                            Les règles de /r/france sont disponibles ici . Pour contester cette action, ou pour toute question, merci d'envoyer un message aux modérateurs .

                            Merci de ta compréhension.

                            ),WrappedArray(bonjou, comentair, a, suprim, merci, exprim, insult, autr, regl, r, franc, disponibl, contest, action, tout, question, merci, envoy, mesag, moder, merci, comprehension),(10000,[0,4,10,17,52,111,145,148,269,390,393,401,467,498,614,628,739,794,849,928],[1.0,1.0,1.0,1.0,1.0,3.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0]),[0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0],0]"]]}
   {:n-docs-in-cluster 5.0,
    :characteristic-words [[["dire" "lais"] 0.004064923568714671 1.0 1 2198]
                           [["dire" "pensent" "expresion"] 0.004064923568714671 1.0 1 2198]
                           [["juge" "ouvert" "peu"] 0.004064923568714671 1.0 1 2198]
                           [["où" "peu" "prè"] 0.004064923568714671 1.0 1 2198]
                           [["x" "aprecieront" "ça"] 0.004064923568714671 1.0 1 2198]
                           [["peu" "rien" "a"] 0.004064923568714671 1.0 1 2198]
                           [["ira" "trè"] 0.004064923568714671 1.0 1 2198]
                           [["chaqu" "individu" "ira"] 0.004064923568714671 1.0 1 2198]
                           [["foutr" "etait"] 0.004064923568714671 1.0 1 2198]
                           [["propo" "deco"] 0.004064923568714671 1.0 1 2198]
                           [["problemat" "incluerait"] 0.004064923568714671 1.0 1 2198]
                           [["a" "foutr" "etait"] 0.004064923568714671 1.0 1 2198]
                           [["dire" "lais" "x"] 0.004064923568714671 1.0 1 2198]
                           [["tien" "ça" "où"] 0.004064923568714671 1.0 1 2198]
                           [["deco"] 0.004064923568714671 1.0 1 2198]
                           [["x" "aprecieront"] 0.004064923568714671 1.0 1 2198]
                           [["x" "soin" "dire"] 0.004064923568714671 1.0 1 2198]
                           [["aprecieront" "ça"] 0.004064923568714671 1.0 1 2198]
                           [["longu" "tien" "ça"] 0.004064923568714671 1.0 1 2198]
                           [["tent" "expliqu"] 0.004064923568714671 1.0 1 2198]],
    :cluster-id 47,
    :doc-examples [#object[org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
                           0x2ddd21bd
                           "[t1_e9jvpxr,WrappedArray(Tu sais meme si tu me juges ouvertement, j'en ai un peu rien a foutre...

                            C'etait quoi du coup ton objectif? Derailler un sujet en imaginant que peut-etre des gens hypothetiques pourraient ne pas apprecier une tournure de phrase sans lien avec le fond s'ils la tordaient pour y lire un jugement de valeur?

                            Ca fait longtemps que tu te detestes?

                            , Dire : \"les X apprécieront\", ça veut dire qu'on laisse aux X le soin d'en dire ce qu'ils en pensent.

                            C'est une expression. Une \"façon de parler\".

                            Mais bon... chacun ses os à ronger. Je te juge pas (pas ouvertement)

                            ),WrappedArray(sai, meme, si, juge, ouvert, peu, rien, a, foutr, etait, quoi, coup, objectif, derail, sujet, imaginant, peut, etre, gen, hypothet, pouraient, apreci, tournur, phras, lien, fond, tordaient, lire, juge, valeu, ca, fait, longtemp, detest, dire, x, aprecieront, ça, veut, dire, lais, x, soin, dire, pensent, expresion, facon, parl, bon, chacun, os, rong, juge, ouvert),(10000,[0,1,3,5,11,12,19,24,29,36,47,50,57,62,63,90,170,203,209,218,327,338,406,423,453,533,538,596,617,621,650,657,1106,1222,1308,1539,1727,1746,1767,2516,3018,3263,4103,5589,6134,6701,7466,7510],[1.0,1.0,1.0,1.0,1.0,1.0,1.0,3.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,3.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,2.0,1.0,1.0,2.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0]),[0.001361284373018535,0.018624355090220993,0.007961532541512514,0.11281675257624524,-0.03545911610216321,-0.003474201655235903,-0.030790330919282915,-0.0018488619974713256,-0.004290785840188439,-0.0024857105009670885,-0.005412940460232759,-2.21880101148657E-4,-0.014829639777820994,-0.03858290694342049,-0.029509460255644538,-1.051070757258196E-5,0.029031645572086415,-0.009936111833755888,0.002154701655240353,0.01061356739864233,0.0070853161370681445,9.146972132748781E-6,0.016717328141559126,6.913800153476113E-4,-0.02799029516980898,0.4848700021177441,0.004189876863944307,-0.03165538510453519,-5.443979678908621E-4,0.00196016730217689,0.005246360628821675,0.010541578009943136,0.03117492149753939,0.0012386174444634025,0.022418630992457105,0.00993191036095605,-3.505758614288012E-5,-0.08144666389448758,-0.0022498631687171695,0.06002940955201278,-0.004070314027829867,-0.009665421761504273,-0.014390975279000033,1.4577263577695783,-0.05213476187754609,0.013873071610874593,-4.282019304834424E-5,-0.022890987751285036,-0.005064521809917787,-0.07486357144833274,-0.0028469570983752236,-0.008911048786722626,-0.0025549654231128774,-0.010629905904654298,0.018246755436363095,-0.03264189171260337,0.005552249799762958,-0.20440366600128096,-0.3836576865506378,0.018250141995581334,0.07008683792584562,-0.005942176982273679,-0.14506506225309915,0.00773157600421735,-0.017558641243571912,-0.021904089520729808,-0.07045725340857506,0.0013889217568007813,0.008786303037081866,0.007569068667849024,0.017511656409848905,-0.03233984055649133,0.10957355597972371,-0.029358296121064924,0.008565668758599694,-0.12099571016025719,0.10706010296066154,-0.0027498407389154006,-0.030492974473850357,-4.712145113310438E-4,0.2691399991718669,-0.022461992603651282,-0.030250314944560746,-0.015614388420105063,0.02521615575321129,0.1311402923331524,0.011543607918604516,0.43476809625249513,-0.06048220328923864,-0.04863437138248446,0.009048295905701083,0.18414811295889677,-0.02085519185514549,-0.16329882388235523,0.045985922538921226,-0.14496047345873547,0.07822587085530515,0.0367165296704221,0.009340728598313504,-0.05620282003105865,-0.9721833198467019,0.5919767113288823,-0.036231794519109586,-0.08297268184181196,-0.1335784820234484,-1.997535772888388,0.6787050700904806,-0.22328870950937385,0.1753697905397172,0.15289433080018797,-0.007066008994430282,0.7172876256603612,0.28207424971878503,-0.01250268094956586,-0.0749774648566988,-0.14878011998204016,0.0035833439769261885,-0.2043476918258414,0.2323129337086125,-0.08751196601410881,0.01710072691693063,0.04382029806548112,-0.020362500410560397,0.024655560250419276,0.06920867202638105,0.05694147717886172,0.09624367469860072,0.09627377265086817,1.2259690749666783,0.11919938367167798,-0.05819678165145267,0.11657005227344154,0.036024254278821784,0.03506808045320092,-0.09701790385371234,-0.6929757746188948,-0.23766120952777464,-0.5046325579185529,0.44205195121071617,0.19952772150847384,-0.1920312743946062,2.5637057838722184,0.43331495132612335,0.025415179706422174,-0.13122068016806834,-1.7113417351639773,0.29182818258351484,-8.158357623941279E-4,-0.022615789447396797,-0.118176467348741,-0.22330313152823333,-0.24730226827275764,0.5943320060370586,-0.1848453565294358,0.08502133290937136,-0.44672398159403237,0.5629214039115243,-1.1522406124631885,3.647812276992971,2.275322286408014,-0.7079026836125656,0.9487320113710221,-0.5864843577690816,-0.6776691942187436,-0.12864232916247212,5.715630922859039,-1.3219023112089197,-0.036083979547911184,0.42630115925514,0.052763143127627275,-0.1358199087393851,0.1372294314983551,0.4788933145277914,0.12297435882242826,0.3894444706398985,-0.09762601701314012,-0.5424027348569865,-0.17917183392047864,0.6505195042771541,0.2082354989989733,0.03049963806836973,-0.26420331443106077,0.7053850576611242,0.5468788418072242,-0.41206414709628125,0.39027995878505084,0.2054022934329421,0.8481391201515686,0.002940054912018491,0.10149246751722421,0.35181609339416114,0.8992738085590347,0.27082430978708555,1.0866158563987651,-0.025715174829841984,0.12750951561391344,-0.32095862923167473,0.5161285163458749,-0.45933472740000053,0.6409810620253478,-0.15919882248330594,0.08979921667948539,-0.49240728862882366,0.31588086536510673,-0.06451795247733197,0.27002813912975515,-0.09585681951910219,-0.34007096116613883,-0.996726051977137,0.5672995050964974,0.26837700329997116,0.39296823791470875,0.382026578492533,-0.7182506876237488,0.14047810867376795,0.09483962269207928,-0.24608575471728092,-0.401102918975308,-0.21673978216993295,-0.5876257425252048,-0.6583449619093739,-0.6199099988261189,-1.721693737377353,0.12075479050548525,2.169897136925383,0.8018611994684481,0.016806917659609663,0.011656735728460259,1.6131967600370773,0.0370989119164286,-0.1252007474447366,0.1381233625136935,-1.7955628560977626,-0.007549690502147778,-0.2790483769737127,-0.03221609292410986,0.05813529680207154,-0.057851667697325804,0.15774829737532325,-4.412401330399368E-13,-1.5733652333669728,0.1880989718295357,0.8071001979214096,0.042317045286206584,0.6520246661695718,0.2728119309175032,1.507036832316461,0.5591919094065503,0.41015892107403845,0.9069208442716228,0.7523840758522996,0.11859390432474806,-1.318226435852987,-0.2057511086030096,2.0291479626851383,0.6041601127760964,0.3496170360068703,-0.4837383992848727,4.931535871708327,-0.003817922537958183,0.9049250237382228,0.9685447843395464,0.11502527289893097,1.680217731459012,-8.508261571235881,-2.5138829246396157,11.099712567920523,-0.86951828492343,5.039947766398481,10.916178857942874,1.8223167155030278,6.685877293180617,-1.1363290521055047,2.094373578031751,7.110085214337678,15.875376065714402,6.21135055150574,8.44941906484189,71.82697421000222,60.80418779308357,16.716223053333557,14.220956702044818,-1.4960714999048876E-13,4.891198138909197,-14.679005261126525,26.978243082705067,32.94846393154641,-7.587513194461366,-0.013445458514750066,0.42392799242631507,4.598186340469056,-36.12361395747701,6.129853728766555,10.84179997738385,12.068815562733665,-0.9224317045985602,13.139951729935785,-16.47095762705868,50.58305151187628,-3.934133782944839],47]"]]}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["sauvegard"] 0.00868427128798618 2.0 2 2198]
                           [["sauvegard" "indust"] 0.00868427128798618 2.0 2 2198]
                           [["italien"] 0.006865631731880963 2.0 4 2198]
                           [["indust"] 0.005938473362762604 2.0 7 2198]
                           [["francais" "surtout" "decalag"] 0.0042307688719905034 1.0 1 2198]
                           [["sauvegard" "indust" "grac"] 0.0042307688719905034 1.0 1 2198]
                           [["grac" "volont" "polit"] 0.0042307688719905034 1.0 1 2198]
                           [["francais" "surtout"] 0.0042307688719905034 1.0 1 2198]
                           [["econom" "italien"] 0.0042307688719905034 1.0 1 2198]
                           [["coment" "fai" "compens"] 0.0042307688719905034 1.0 1 2198]
                           [["temp" "parc"] 0.0042307688719905034 1.0 1 2198]
                           [["bien" "coment"] 0.0042307688719905034 1.0 1 2198]
                           [["polit" "aide"] 0.0042307688719905034 1.0 1 2198]
                           [["indust" "grac" "volont"] 0.0042307688719905034 1.0 1 2198]
                           [["si" "tau"] 0.0042307688719905034 1.0 1 2198]
                           [["dis" "ital" "a"] 0.0042307688719905034 1.0 1 2198]
                           [["su" "sauvegard"] 0.0042307688719905034 1.0 1 2198]
                           [["mitigent" "idée"] 0.0042307688719905034 1.0 1 2198]
                           [["mitigent"] 0.0042307688719905034 1.0 1 2198]
                           [["grac" "volont"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 10,
    :doc-examples []}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["coruption" "a" "cout"] 0.0042307688719905034 1.0 1 2198]
                           [["fouil" "pot"] 0.0042307688719905034 1.0 1 2198]
                           [["plein" "fouil"] 0.0042307688719905034 1.0 1 2198]
                           [["revanch" "done"] 0.0042307688719905034 1.0 1 2198]
                           [["rien" "malheureus"] 0.0042307688719905034 1.0 1 2198]
                           [["production" "clé"] 0.0042307688719905034 1.0 1 2198]
                           [["clé" "asie"] 0.0042307688719905034 1.0 1 2198]
                           [["contr" "coruption" "a"] 0.0042307688719905034 1.0 1 2198]
                           [["done" "miliard" "subvention"] 0.0042307688719905034 1.0 1 2198]
                           [["cout" "tre" "cher"] 0.0042307688719905034 1.0 1 2198]
                           [["aileu" "chain"] 0.0042307688719905034 1.0 1 2198]
                           [["a" "cout" "tre"] 0.0042307688719905034 1.0 1 2198]
                           [["plein" "fouil" "pot"] 0.0042307688719905034 1.0 1 2198]
                           [["dire" "popul"] 0.0042307688719905034 1.0 1 2198]
                           [["asie"] 0.0042307688719905034 1.0 1 2198]
                           [["production" "clé" "asie"] 0.0042307688719905034 1.0 1 2198]
                           [["fouil" "pot" "vin"] 0.0042307688719905034 1.0 1 2198]
                           [["popul" "constat"] 0.0042307688719905034 1.0 1 2198]
                           [["but" "ca"] 0.0042307688719905034 1.0 1 2198]
                           [["constat" "sert" "a"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 34,
    :doc-examples []}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["plu" "rigoureu"] 0.00868427128798618 2.0 2 2198]
                           [["rigoureu"] 0.007431508469954606 2.0 3 2198]
                           [["analys"] 0.005266360590213702 2.0 11 2198]
                           [["cit" "conclusion"] 0.0042307688719905034 1.0 1 2198]
                           [["critiqu" "fait"] 0.0042307688719905034 1.0 1 2198]
                           [["rigoureu" "cit"] 0.0042307688719905034 1.0 1 2198]
                           [["analys" "peu" "plu"] 0.0042307688719905034 1.0 1 2198]
                           [["richard" "perkin"] 0.0042307688719905034 1.0 1 2198]
                           [["public" "pdf"] 0.0042307688719905034 1.0 1 2198]
                           [["beaucoup" "souvent" "vra"] 0.0042307688719905034 1.0 1 2198]
                           [["file" "public"] 0.0042307688719905034 1.0 1 2198]
                           [["perkin"] 0.0042307688719905034 1.0 1 2198]
                           [["default" "file" "public"] 0.0042307688719905034 1.0 1 2198]
                           [["rigoureu" "baricade.b" "site"] 0.0042307688719905034 1.0 1 2198]
                           [["souvent" "vra"] 0.0042307688719905034 1.0 1 2198]
                           [["conclusion" "portent" "autr"] 0.0042307688719905034 1.0 1 2198]
                           [["portent" "autr"] 0.0042307688719905034 1.0 1 2198]
                           [["critiqu" "fait" "beaucoup"] 0.0042307688719905034 1.0 1 2198]
                           [["fait" "beaucoup"] 0.0042307688719905034 1.0 1 2198]
                           [["chanel" "uc3111rvadtbpuy9jbqdmzg"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 45,
    :doc-examples []}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["physiqu"] 0.00617994367511214 2.0 6 2198]
                           [["a" "fait"] 0.00465163991572376 2.0 17 2198]
                           [["rien" "touchera"] 0.0042307688719905034 1.0 1 2198]
                           [["physiqu" "premier" "y'en"] 0.0042307688719905034 1.0 1 2198]
                           [["fait" "trè"] 0.0042307688719905034 1.0 1 2198]
                           [["campagn" "a" "perdu"] 0.0042307688719905034 1.0 1 2198]
                           [["vraiment" "a" "fait"] 0.0042307688719905034 1.0 1 2198]
                           [["physiqu" "metra"] 0.0042307688719905034 1.0 1 2198]
                           [["souvien" "plu"] 0.0042307688719905034 1.0 1 2198]
                           [["projet" "mort"] 0.0042307688719905034 1.0 1 2198]
                           [["a" "perdu" "scor"] 0.0042307688719905034 1.0 1 2198]
                           [["merci" "segolen" "projet"] 0.0042307688719905034 1.0 1 2198]
                           [["fait" "physiqu"] 0.0042307688719905034 1.0 1 2198]
                           [["retrait" "15k" "net"] 0.0042307688719905034 1.0 1 2198]
                           [["mort" "né"] 0.0042307688719905034 1.0 1 2198]
                           [["y'en" "terminal" "pourtant"] 0.0042307688719905034 1.0 1 2198]
                           [["souvien" "plu" "vraiment"] 0.0042307688719905034 1.0 1 2198]
                           [["a" "fait" "trè"] 0.0042307688719905034 1.0 1 2198]
                           [["bone" "campagn"] 0.0042307688719905034 1.0 1 2198]
                           [["trouv" "dur"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 48,
    :doc-examples []}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["bais"] 0.005141462489626464 2.0 12 2198]
                           [["consom"] 0.00465163991572376 2.0 17 2198]
                           [["gen" "reduit" "consom"] 0.0042307688719905034 1.0 1 2198]
                           [["taxe" "tabac" "injust"] 0.0042307688719905034 1.0 1 2198]
                           [["parl" "solution"] 0.0042307688719905034 1.0 1 2198]
                           [["a" "aide" "vélo"] 0.0042307688719905034 1.0 1 2198]
                           [["tabac" "injust"] 0.0042307688719905034 1.0 1 2198]
                           [["injust" "pauvr"] 0.0042307688719905034 1.0 1 2198]
                           [["elect" "a"] 0.0042307688719905034 1.0 1 2198]
                           [["pauvr" "gen" "reduit"] 0.0042307688719905034 1.0 1 2198]
                           [["elect" "a" "petit"] 0.0042307688719905034 1.0 1 2198]
                           [["vites" "prendr"] 0.0042307688719905034 1.0 1 2198]
                           [["exempl" "bais" "limit"] 0.0042307688719905034 1.0 1 2198]
                           [["carburant" "pêle"] 0.0042307688719905034 1.0 1 2198]
                           [["fair" "bais" "consom"] 0.0042307688719905034 1.0 1 2198]
                           [["manier" "fair"] 0.0042307688719905034 1.0 1 2198]
                           [["gouvern" "parl"] 0.0042307688719905034 1.0 1 2198]
                           [["just" "info" "ane"] 0.0042307688719905034 1.0 1 2198]
                           [["ausi" "exempl"] 0.0042307688719905034 1.0 1 2198]
                           [["mêle"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 49,
    :doc-examples []}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["agit"] 0.004498047229207214 2.0 19 2198]
                           [["agit" "cas"] 0.0042307688719905034 1.0 1 2198]
                           [["sait" "rien" "enjeu"] 0.0042307688719905034 1.0 1 2198]
                           [["intrant"] 0.0042307688719905034 1.0 1 2198]
                           [["2014"] 0.0042307688719905034 1.0 1 2198]
                           [["tout" "sembl"] 0.0042307688719905034 1.0 1 2198]
                           [["autosufisanc" "debaraseront" "frai"] 0.0042307688719905034 1.0 1 2198]
                           [["fake" "bidon"] 0.0042307688719905034 1.0 1 2198]
                           [["frai" "lié"] 0.0042307688719905034 1.0 1 2198]
                           [["balanc" "manif" "2014"] 0.0042307688719905034 1.0 1 2198]
                           [["sait" "rien"] 0.0042307688719905034 1.0 1 2198]
                           [["vai" "reformul" "question"] 0.0042307688719905034 1.0 1 2198]
                           [["enjeu"] 0.0042307688719905034 1.0 1 2198]
                           [["internet" "fake" "bidon"] 0.0042307688719905034 1.0 1 2198]
                           [["trans" "entr"] 0.0042307688719905034 1.0 1 2198]
                           [["dit" "exploit"] 0.0042307688719905034 1.0 1 2198]
                           [["video" "trainent"] 0.0042307688719905034 1.0 1 2198]
                           [["pasent" "autosufisanc"] 0.0042307688719905034 1.0 1 2198]
                           [["pasent" "autosufisanc" "debaraseront"] 0.0042307688719905034 1.0 1 2198]
                           [["reformul"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 55,
    :doc-examples []}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["bagnol"] 0.0120048235689507 3.0 4 2198]
                           [["alarmist"] 0.00868427128798618 2.0 2 2198]
                           [["retourn"] 0.006865631731880963 2.0 4 2198]
                           [["lycen"] 0.006477352867114837 2.0 5 2198]
                           [["discou"] 0.005404587252775615 2.0 10 2198]
                           [["regardera" "troi" "post"] 0.0042307688719905034 1.0 1 2198]
                           [["fair" "mutil"] 0.0042307688719905034 1.0 1 2198]
                           [["abat"] 0.0042307688719905034 1.0 1 2198]
                           [["alarmist" "cert"] 0.0042307688719905034 1.0 1 2198]
                           [["regardera" "troi"] 0.0042307688719905034 1.0 1 2198]
                           [["ni" "represion"] 0.0042307688719905034 1.0 1 2198]
                           [["bagnol" "devant" "lyce"] 0.0042307688719905034 1.0 1 2198]
                           [["fair" "mal"] 0.0042307688719905034 1.0 1 2198]
                           [["justif" "ni" "represion"] 0.0042307688719905034 1.0 1 2198]
                           [["ça" "fair"] 0.0042307688719905034 1.0 1 2198]
                           [["discou" "alarmist"] 0.0042307688719905034 1.0 1 2198]
                           [["discou" "exagerement"] 0.0042307688719905034 1.0 1 2198]
                           [["mouv" "lycen" "ni"] 0.0042307688719905034 1.0 1 2198]
                           [["regardera"] 0.0042307688719905034 1.0 1 2198]
                           [["exagerement" "alarmist"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 69,
    :doc-examples []}
   {:n-docs-in-cluster 4.0,
    :characteristic-words [[["falacieu"] 0.01127280236297348 3.0 5 2198]
                           [["informatif" "remerc" "ça"] 0.0042307688719905034 1.0 1 2198]
                           [["meh" "atention" "bien"] 0.0042307688719905034 1.0 1 2198]
                           [["atention" "bien" "prendr"] 0.0042307688719905034 1.0 1 2198]
                           [["epichlorohydrin"] 0.0042307688719905034 1.0 1 2198]
                           [["demeurant"] 0.0042307688719905034 1.0 1 2198]
                           [["rayon" "ultraviolet"] 0.0042307688719905034 1.0 1 2198]
                           [["ça" "meh"] 0.0042307688719905034 1.0 1 2198]
                           [["methanesulfonat" "methyl" "encor"] 0.0042307688719905034 1.0 1 2198]
                           [["dose" "toxicolog" "là"] 0.0042307688719905034 1.0 1 2198]
                           [["atention" "bien"] 0.0042307688719905034 1.0 1 2198]
                           [["informatif" "remerc"] 0.0042307688719905034 1.0 1 2198]
                           [["come" "rayon" "ultraviolet"] 0.0042307688719905034 1.0 1 2198]
                           [["comentair" "repons"] 0.0042307688719905034 1.0 1 2198]
                           [["moin" "falacieu"] 0.0042307688719905034 1.0 1 2198]
                           [["meh"] 0.0042307688719905034 1.0 1 2198]
                           [["compt" "deu" "type"] 0.0042307688719905034 1.0 1 2198]
                           [["come" "rayon"] 0.0042307688719905034 1.0 1 2198]
                           [["asez" "similair" "parl"] 0.0042307688719905034 1.0 1 2198]
                           [["contredit" "comentair" "repons"] 0.0042307688719905034 1.0 1 2198]],
    :cluster-id 89,
    :doc-examples []}]



  (.stop sc)

  *e)


