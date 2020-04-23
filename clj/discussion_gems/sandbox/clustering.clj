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
            [sc.api])
  (:import (org.apache.spark.sql Dataset Row SparkSession RowFactory)
           (org.apache.spark.ml.feature CountVectorizer CountVectorizerModel)
           (org.apache.spark.ml.clustering LDA LDAModel KMeans)
           (org.apache.spark.api.java JavaSparkContext JavaRDD)
           (org.apache.spark.sql.types DataTypes)
           (org.apache.spark.sql.catalyst.expressions GenericRow GenericRowWithSchema)
           (org.apache.spark.sql functions)
           (java.util.zip GZIPOutputStream)
           (scala.collection.mutable WrappedArray$ofRef)
           (org.apache.spark.ml.linalg SparseVector DenseVector)
           (org.apache.lucene.analysis.fr FrenchAnalyzer)))

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


(defn flow-parent-value-to-children
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

  @(uspark/run-local
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
                 (take n-labels)))})))
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
          (flow-parent-value-to-children
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
          (flow-parent-value-to-children
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


(defn extract-cluster-label-tokens
  [stop-at-i, words-seq-col-index, ^GenericRowWithSchema row]
  (set
    (let [words
          (-> row
            (.get (int words-seq-col-index))
            (as-> wrapped-arr
              (.array ^WrappedArray$ofRef wrapped-arr))
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
       (spark/filter created-lately?)))
   (def comments-rdd
     (->> (uspark/from-hadoop-fressian-sequence-file sc "../derived-data/reddit-france/comments/RC-enriched_v1.seqfile")
       (spark/filter :created_utc)
       (spark/filter created-lately?)))]

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


  @p_clusters-summary
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
    :cluster-id 68}
   {:n-docs-in-cluster 3841.5745518674735,
    :characteristic-words [[["pari"] 0.0032826840815998737 360.7526591120119 2219 245558]
                           [["vile"] 0.0021604791950608215 256.3260869286446 1748 245558]
                           [["cyclabl"] 0.0019990090890424084 116.17221930417831 212 245558]
                           [["pist"] 0.0017877297441255824 124.68136315934937 331 245558]
                           [["vélo"] 0.0017584129933937986 155.51014254325125 651 245558]
                           [["prenom"] 0.0017417341607528503 119.6563258398695 308 245558]
                           [["pist" "cyclabl"] 0.0016692636664375204 97.23641679131163 178 245558]
                           [["cyclist"] 0.001524753060216183 109.53178850108112 308 245558]
                           [["parisien"] 0.0015245832768878975 139.90396622840296 624 245558]
                           [["trotinet"] 0.0013516939642995995 97.25523605560316 274 245558]
                           [["trotoi"] 0.0011142010331061936 75.47741408345263 188 245558]
                           [["rue"] 0.001019267355327813 124.52473839851265 873 245558]
                           [["pieton"] 0.0010095115333161875 74.25519103806622 218 245558]
                           [["quarti"] 9.954044098220427E-4 96.68407043342134 473 245558]
                           [["pma"] 7.816030848783323E-4 56.85460536673034 163 245558]
                           [["musulman"] 6.792346879432032E-4 96.49960061818182 828 245558]
                           [["imigr"] 5.043489279540625E-4 62.31921355695556 441 245558]
                           [["banlieu"] 4.400210906801705E-4 49.8405875300763 310 245558]
                           [["bobo"] 4.392946125316294E-4 41.2187563938025 189 245558]
                           [["rout"] 4.2883774091506677E-4 74.39121525537662 809 245558]],
    :cluster-id 30}
   {:n-docs-in-cluster 3268.300593049463,
    :characteristic-words [[["bonjou"] 0.034100066198653745 1481.6927578152333 1897 245558]
                           [["r"] 0.02215331306858348 1236.0756127585257 2677 245558]
                           [["r" "franc"] 0.02174968988255782 1094.6521378752416 1836 245558]
                           [["regl" "r"] 0.016465014335932948 668.8925983988668 711 245558]
                           [["regl" "r" "franc"] 0.016306821044510755 662.116250381134 703 245558]
                           [["soumision" "a"] 0.01566785524488641 635.9997203965808 674 245558]
                           [["soumision" "a" "retir"] 0.01560272373805728 631.7458090722923 667 245558]
                           [["a" "retir"] 0.015468249830455222 633.5974980067069 680 245558]
                           [["soumision"] 0.015223219132844523 646.540177619196 735 245558]
                           [["doublon"] 0.014872544427292661 632.8445578896774 721 245558]
                           [["car" "agit" "doublon"] 0.014280000640481752 577.9301381494098 608 245558]
                           [["retir" "car" "agit"] 0.014280000640481752 577.9301381494098 608 245558]
                           [["a" "retir" "car"] 0.014280000640481752 577.9301381494098 608 245558]
                           [["agit" "doublon"] 0.014263114451320696 577.9443924579624 609 245558]
                           [["a" "suprim"] 0.014254697940767364 612.3655376789968 708 245558]
                           [["retir" "car"] 0.01425278968200408 578.1056534314755 610 245558]
                           [["car" "agit"] 0.01410784597456853 578.2022311605689 619 245558]
                           [["bonjou" "soumision"] 0.013614264961348141 551.4666065278955 580 245558]
                           [["bonjou" "soumision" "a"] 0.013564504328481317 549.5245963278018 578 245558]
                           [["retir"] 0.013025735744559591 656.0850494402666 1046 245558]],
    :cluster-id 53}
   {:n-docs-in-cluster 2685.0820752394548,
    :characteristic-words [[["video"] 0.0016988283583535269 212.5684838834373 2209 245558]
                           [["photo"] 0.0013603541877349606 122.59291033619634 735 245558]
                           [["spatial"] 0.0010445618174780014 55.83964894967186 109 245558]
                           [["trou"] 6.628024241334268E-4 55.52017698854042 287 245558]
                           [["x200b"] 6.616783413347094E-4 114.84308436766293 1784 245558]
                           [["spac"] 5.912752455432824E-4 42.252005536926255 158 245558]
                           [["truc" "spatial"] 5.780562386647065E-4 23.196199858113687 25 245558]
                           [["espac"] 5.59839918478508E-4 59.13698899315955 462 245558]
                           [["trou" "noir"] 5.132384221385317E-4 27.840523318666445 56 245558]
                           [["orbit"] 5.0090925702935E-4 28.766356801097103 66 245558]
                           [["lune"] 4.970520166180659E-4 33.18364506926168 107 245558]
                           [["truc"] 4.8446675176253007E-4 156.14371599804758 4283 245558]
                           [["spatial" "semain"] 4.611415439859101E-4 18.527768621590496 20 245558]
                           [["truc" "spatial" "semain"] 4.611415439859101E-4 18.527768621590496 20 245558]
                           [["satelit"] 4.5950667153427105E-4 29.791593762475337 90 245558]
                           [["noir"] 4.5943101027312583E-4 60.645807720794586 663 245558]
                           [["etoil"] 4.274613998518362E-4 31.463358401052506 125 245558]
                           [["youtub"] 4.0909777365641387E-4 44.18558831541737 357 245558]
                           [["tere"] 3.9588537745489927E-4 61.97641767694271 846 245558]
                           [["lanc"] 3.923143036697607E-4 57.070619225648485 710 245558]],
    :cluster-id 56}
   {:n-docs-in-cluster 2621.600898611297,
    :characteristic-words [[["enfant"] 0.0053528423424236415 449.3199644542214 2554 245558]
                           [["parent"] 0.002279649435874165 204.55353885440397 1262 245558]
                           [["prison"] 0.0019053479040073151 150.65715706084927 722 245558]
                           [["mère"] 0.0016190215902521232 130.39191564746696 646 245558]
                           [["père"] 0.0012973277977958997 104.59170560876022 517 245558]
                           [["famil"] 0.0012826746317048388 139.1087597002844 1176 245558]
                           [["ans"] 5.535282893244509E-4 162.47632418566843 4261 245558]
                           [["viol"] 5.132786560115982E-4 59.547467465313645 552 245558]
                           [["pein"] 4.282030069855669E-4 68.70146171808692 990 245558]
                           [["pedophil"] 4.15623238213203E-4 31.74079004397896 139 245558]
                           [["balkany"] 3.969043542210726E-4 30.55592283675608 136 245558]
                           [["condamn"] 3.8988209658388784E-4 57.118924842177314 734 245558]
                           [["gamin"] 3.2434735616870825E-4 46.602355946180936 583 245558]
                           [["mineu"] 3.1792741217900877E-4 30.17377874078662 200 245558]
                           [["ans" "prison"] 3.158918430780644E-4 18.602000788086286 46 245558]
                           [["mort"] 2.915956154211363E-4 72.00916011056933 1617 245558]
                           [["feme"] 2.8386336053348726E-4 96.98742525706105 2818 245558]
                           [["victim"] 2.7114138479919503E-4 49.634055931204124 829 245558]
                           [["violeu"] 2.6999848624040257E-4 20.906169984304924 94 245558]
                           [["adult"] 2.6697959589212783E-4 28.238141176908105 225 245558]],
    :cluster-id 71}
   {:n-docs-in-cluster 2268.906803404323,
    :characteristic-words [[["http"] 0.015486309583958052 1099.38906969583 6010 245558]
                           [["http" "w.youtube.com"] 0.0020624777772455666 111.75912529026014 258 245558]
                           [["w.youtube.com"] 0.0020610147256713285 112.0778137894956 261 245558]
                           [["watch"] 0.001832316408195117 102.23732474568664 253 245558]
                           [["w.youtube.com" "watch"] 0.0017533437420039072 95.91676353376714 226 245558]
                           [["http" "w.youtube.com" "watch"] 0.0017533437420039072 95.91676353376714 226 245558]
                           [["v"] 0.00172995490049703 99.95682642188078 269 245558]
                           [["watch" "v"] 0.0016135586051667788 88.1820440972929 207 245558]
                           [["w.youtube.com" "watch" "v"] 0.0015639726197185372 85.28026875984676 199 245558]
                           [["2018"] 0.0014764693175504245 123.85511008633266 760 245558]
                           [["wiki"] 0.0014403355425035708 118.60616417837335 701 245558]
                           [["2019"] 0.0014272077316329762 117.160243505018 688 245558]
                           [["w.lemonde.f"] 0.0011285847317067527 62.51948844847622 151 245558]
                           [["http" "w.lemonde.f"] 0.0011285847317067527 62.51948844847622 151 245558]
                           [["http" "fr.wikipedia.org"] 9.864580717188348E-4 73.04241817458258 344 245558]
                           [["fr.wikipedia.org"] 9.864580717188348E-4 73.04241817458258 344 245558]
                           [["http" "fr.wikipedia.org" "wiki"] 8.942916296277165E-4 66.52502132003588 316 245558]
                           [["fr.wikipedia.org" "wiki"] 8.942916296277165E-4 66.52502132003588 316 245558]
                           [["articl"] 8.078148235100002E-4 215.8920812517983 6136 245558]
                           [["01"] 6.317764083857424E-4 39.05195570969803 122 245558]],
    :cluster-id 35}
   {:n-docs-in-cluster 1999.2188359264508,
    :characteristic-words [[["linu"] 0.001127868107100749 58.31827728532685 131 245558]
                           [["window"] 0.0010582613566045285 54.35899263036836 120 245558]
                           [["gogl"] 0.0010389881827990999 94.46671927288227 758 245558]
                           [["utilis"] 8.830676261099291E-4 157.18835481804695 3402 245558]
                           [["instal"] 8.662053002217313E-4 74.71414910552829 540 245558]
                           [["python"] 8.519261465545491E-4 44.34106556270532 101 245558]
                           [["logiciel"] 8.099941116870985E-4 52.19982994889956 201 245558]
                           [["android"] 7.067834269100931E-4 38.3646807613693 97 245558]
                           [["ia"] 6.595327584294208E-4 43.39156950625019 175 245558]
                           [["langag"] 5.738457899748667E-4 46.59782073501227 297 245558]
                           [["microsoft"] 5.178104277435286E-4 29.50205926531074 84 245558]
                           [["loup"] 5.126280738838584E-4 41.7290258074391 267 245558]
                           [["smartphon"] 5.088685059802533E-4 36.900793102498895 185 245558]
                           [["pc"] 4.483170759934374E-4 33.73212058223812 183 245558]
                           [["os"] 4.3511539917102937E-4 27.61171818502558 102 245558]
                           [["code"] 3.905530307631022E-4 44.27969953696994 513 245558]
                           [["recherch"] 3.617177576844266E-4 56.23417004950893 1016 245558]
                           [["aplic"] 3.496510498233185E-4 33.26280612188541 286 245558]
                           [["version"] 3.452737250573684E-4 39.17912493767391 454 245558]
                           [["app"] 3.4074286801553944E-4 22.035883621592397 85 245558]],
    :cluster-id 26}
   {:n-docs-in-cluster 1952.529373183628,
    :characteristic-words [[["train"] 0.0014756049756280143 151.88663391471883 1576 245558]
                           [["feme"] 0.0013745017785795899 182.3728340421733 2818 245558]
                           [["voil"] 0.0013342812366769347 87.92064108456226 367 245558]
                           [["lign"] 0.0010696584172639328 112.38654410409931 1196 245558]
                           [["metro"] 9.952597263783586E-4 67.65369657496414 302 245558]
                           [["bus"] 7.812256733662204E-4 62.214561322492315 391 245558]
                           [["sncf"] 7.004016680579456E-4 47.97847873694855 217 245558]
                           [["port"] 6.623486402667078E-4 101.68493370947661 1870 245558]
                           [["pari"] 5.906455838737834E-4 103.30265587846748 2219 245558]
                           [["gare"] 5.807141907185204E-4 39.32965257872695 173 245558]
                           [["port" "voil"] 4.918548221316682E-4 27.990498617529617 81 245558]
                           [["station"] 4.643995729440853E-4 34.666765505512224 189 245558]
                           [["trajet"] 4.5604949396917116E-4 40.716260866054384 319 245558]
                           [["bilet"] 4.390002782587582E-4 38.391115966724406 289 245558]
                           [["tgv"] 3.4821390128625396E-4 22.3906162933451 87 245558]
                           [["rer"] 3.2436429435525604E-4 19.8063127988643 68 245558]
                           [["toulous"] 3.194044189532286E-4 24.150386914342743 135 245558]
                           [["ticket"] 3.1120791030923856E-4 22.7363042737718 118 245558]
                           [["tram"] 3.0141451149533205E-4 20.030662609812875 84 245558]
                           [["controleu"] 2.9672801086223777E-4 19.523874073044233 80 245558]],
    :cluster-id 62}
   {:n-docs-in-cluster 1897.737124113039,
    :characteristic-words [[["site"] 0.0010262128353112493 111.84208224540399 1300 245558]
                           [["sfr"] 9.606499712328659E-4 42.345901263916865 66 245558]
                           [["forfait"] 7.641955349718677E-4 39.86342766419056 95 245558]
                           [["red"] 6.833794470072624E-4 30.87126195647591 51 245558]
                           [["moi"] 6.127622913105202E-4 101.21530306239333 2093 245558]
                           [["ofre"] 6.083006317520456E-4 56.883658152258164 501 245558]
                           [["abon"] 5.605720770699429E-4 41.643689876079755 231 245558]
                           [["dns"] 5.519903062184417E-4 28.002379080050897 62 245558]
                           [["oper"] 5.498744685955015E-4 39.52967855044438 204 245558]
                           [["cagnot"] 5.413568554045317E-4 32.240322346852054 107 245558]
                           [["chez"] 5.191130013937617E-4 103.27189351992159 2623 245558]
                           [["bouygu"] 5.097732303519092E-4 25.03583950295863 51 245558]
                           [["box"] 5.070801397875774E-4 29.094481243748863 88 245558]
                           [["internet"] 5.013721842830748E-4 63.637069895989 924 245558]
                           [["telephon"] 4.7814683750775633E-4 48.483403945887645 492 245558]
                           [["canard"] 4.679135372821497E-4 34.15225371383281 182 245558]
                           [["free"] 4.626128098178389E-4 28.284516036566256 100 245558]
                           [["orang"] 3.8885296759165466E-4 27.961542971544443 144 245558]
                           [["sms"] 3.832295377646511E-4 22.504872425589657 72 245558]
                           [["autr" "eventuel" "fait"] 3.2897974683342157E-4 15.89390900895972 31 245558]],
    :cluster-id 14}
   {:n-docs-in-cluster 1854.2314148949647,
    :characteristic-words [[["alcol"] 0.00263788236091575 157.06922535858303 547 245558]
                           [["drogu"] 0.0011785941374739123 79.4770915584389 366 245558]
                           [["canabi"] 0.0011461529081499 71.6924800510771 276 245558]
                           [["cigaret"] 8.851842444923957E-4 52.60850559585371 178 245558]
                           [["vin"] 8.365322909956452E-4 54.27071649269875 227 245558]
                           [["chaseu"] 8.077507276201407E-4 53.09716962498761 229 245558]
                           [["risqu"] 7.79588811414697E-4 100.04095034453042 1523 245558]
                           [["tabac"] 7.52394905905035E-4 48.31658626157468 197 245558]
                           [["fumeu"] 7.358372559880672E-4 48.06473096551895 204 245558]
                           [["consom"] 6.408488399561874E-4 97.38493512768443 1860 245558]
                           [["vere"] 5.991236174683889E-4 44.872065472068634 259 245558]
                           [["canc"] 5.92275517657323E-4 49.31545347737595 355 245558]
                           [["sang"] 5.630658705406411E-4 45.10875785515533 300 245558]
                           [["chas"] 5.286291649099217E-4 43.995368796835685 316 245558]
                           [["fum"] 5.087013844948807E-4 34.15111524466353 154 245558]
                           [["acident"] 4.7942701598154003E-4 47.34073548090148 469 245558]
                           [["mort"] 4.446565961726684E-4 74.99829495839401 1617 245558]
                           [["clop"] 3.9651957867900545E-4 27.66421699121969 136 245558]
                           [["sangli"] 3.853945516922863E-4 22.288436144285132 70 245558]
                           [["nicotin"] 3.607791383297976E-4 19.218210623017594 49 245558]],
    :cluster-id 85}
   {:n-docs-in-cluster 1329.7018517448184,
    :characteristic-words [[["bio"] 0.0017910541759115078 101.35464824653522 411 245558]
                           [["produit"] 0.0012784516970022336 126.68950705741658 1795 245558]
                           [["mang"] 8.593343943252008E-4 79.69676002922441 984 245558]
                           [["legum"] 8.585808856589397E-4 50.569290063426784 224 245558]
                           [["viand"] 7.780721992272782E-4 65.51895839833502 667 245558]
                           [["pesticid"] 7.568164492862337E-4 41.89266701802133 157 245558]
                           [["agricultur"] 7.310124658428396E-4 47.12071051344143 262 245558]
                           [["animal"] 6.621580348085271E-4 57.93948640421136 635 245558]
                           [["vegan"] 5.581976112978004E-4 37.124673575637445 222 245558]
                           [["elevag"] 5.384105420498664E-4 34.54046029862864 189 245558]
                           [["aliment"] 5.138582541479308E-4 41.41275771345649 382 245558]
                           [["ogm"] 4.310651961848405E-4 27.063314144809944 140 245558]
                           [["agricult"] 4.292079593544579E-4 31.19428809742058 229 245558]
                           [["fruit"] 4.238523826999435E-4 31.089600780999078 233 245558]
                           [["plant"] 4.168414416263061E-4 36.075873421649355 384 245558]
                           [["lait"] 3.878587338438655E-4 25.054562951063925 139 245558]
                           [["tomat"] 3.5113496154220297E-4 21.070981528187676 97 245558]
                           [["agricol"] 3.158858857556929E-4 22.787156789124907 164 245558]
                           [["vegetal"] 2.9678712809992625E-4 17.75486291244491 81 245558]
                           [["label"] 2.961930456504072E-4 17.02700061282442 70 245558]],
    :cluster-id 7}
   {:n-docs-in-cluster 1062.2338613615693,
    :characteristic-words [[["juif"] 0.002619115898060305 134.5718044860926 519 245558]
                           [["israël"] 0.0015638093407351825 75.7674070977612 240 245558]
                           [["antisemit"] 0.0013567090760244899 72.27568770812633 301 245558]
                           [["antisionism"] 0.0010144039654518058 42.48000512369979 86 245558]
                           [["antisemitism"] 9.828310700872867E-4 50.086277858200056 182 245558]
                           [["israel"] 9.141903970531148E-4 43.67330201569276 131 245558]
                           [["israelien"] 6.979523895682613E-4 35.67562344690926 130 245558]
                           [["sionism"] 6.741572269227877E-4 29.95149078887276 72 245558]
                           [["sionist"] 6.232090714980917E-4 29.268960869870597 83 245558]
                           [["colon"] 6.034736577700123E-4 38.73367812508043 262 245558]
                           [["racism"] 5.541553827216883E-4 45.43218342858213 541 245558]
                           [["defin"] 5.364543957738499E-4 55.60688125147049 1039 245558]
                           [["palestinien"] 4.934744053102999E-4 25.186497758005913 91 245558]
                           [["antisionist"] 4.1629366985512783E-4 18.673278104034715 46 245558]
                           [["palestin"] 2.8807410496906743E-4 14.107824565359282 45 245558]
                           [["race"] 2.865389492276993E-4 21.89897298943149 222 245558]
                           [["état" "israël"] 2.7355842098753663E-4 11.706172351327803 25 245558]
                           [["état"] 2.726464625060765E-4 66.08362664970086 3614 245558]
                           [["existenc"] 2.4588054539751575E-4 22.653850644643374 337 245558]
                           [["gauloi"] 2.4527622260614446E-4 13.894748998100484 67 245558]],
    :cluster-id 93}
   {:n-docs-in-cluster 950.1129962038905,
    :characteristic-words [[["rugy"] 0.0012992326759987197 59.84932013398256 178 245558]
                           [["langu"] 9.653972610993017E-4 61.69499697294156 464 245558]
                           [["francoi"] 9.561783565816412E-4 52.00347000014581 252 245558]
                           [["francoi" "rugy"] 6.711987573072981E-4 25.708736008008753 43 245558]
                           [["anglai"] 5.861581942554345E-4 50.47974790635726 744 245558]
                           [["afair"] 5.138398128565203E-4 58.525856762867676 1433 245558]
                           [["ministr"] 5.111927786123766E-4 50.83466675544557 985 245558]
                           [["alemand"] 4.5019900772728244E-4 38.51961835131244 557 245558]
                           [["mediapart"] 3.598131473597638E-4 25.540602867347808 244 245558]
                           [["din"] 3.4774445206534055E-4 18.67888781034068 86 245558]
                           [["algerien"] 3.215091328317432E-4 19.395527654599935 123 245558]
                           [["alg"] 3.0224679976679353E-4 20.182209613497673 166 245558]
                           [["homard"] 2.7688435066475225E-4 15.12903060771434 73 245558]
                           [["demision"] 2.1935035184650342E-4 18.796172825879232 270 245558]
                           [["refug"] 1.621314823005357E-4 13.982614318952155 203 245558]
                           [["nant"] 1.5869874187769E-4 11.393235238560916 111 245558]
                           [["asil"] 1.574290431030495E-4 9.873611816058254 69 245558]
                           [["francai"] 1.4725009762761587E-4 52.741397523528 4364 245558]
                           [["cabinet"] 1.3325801961241307E-4 10.091958526404541 111 245558]
                           [["minist"] 1.3202673172572205E-4 15.297229600690729 377 245558]],
    :cluster-id 27}
   {:n-docs-in-cluster 928.2346703945478,
    :characteristic-words [[["voie"] 0.0015140562218299153 86.48648711293306 500 245558]
                           [["rond"] 0.0014214888459079048 73.39603482573767 316 245558]
                           [["rond" "point"] 0.0013034800440169247 60.031005945199105 182 245558]
                           [["http" "twiter.com"] 0.0011200500932945848 54.300777810602895 192 245558]
                           [["twiter.com"] 0.0011187889302693918 54.324875201033805 193 245558]
                           [["statu"] 0.001077605294653991 56.1384961184452 246 245558]
                           [["gauch"] 8.255972165937403E-4 92.79622466113659 2317 245558]
                           [["rout"] 6.603689195097545E-4 55.7329058167795 809 245558]
                           [["conduct"] 5.992766145955855E-4 33.06820701078995 169 245558]
                           [["point"] 5.62356518459789E-4 97.94027688253206 4460 245558]
                           [["clignotant"] 4.3461025871738973E-4 17.928462945203925 38 245558]
                           [["priorit"] 4.205990084551192E-4 28.68160055623781 255 245558]
                           [["giratoir"] 4.118303950418553E-4 14.628528886053736 20 245558]
                           [["code"] 3.871176995734124E-4 33.65572786261904 513 245558]
                           [["code" "rout"] 3.831277909986594E-4 20.30941326965947 92 245558]
                           [["droit"] 3.6201684439829407E-4 89.31925453810308 5752 245558]
                           [["http"] 2.83489656969671E-4 81.99787018560424 6010 245558]
                           [["voie" "gauch"] 2.821041293694926E-4 11.445787869794211 23 245558]
                           [["voie" "droit"] 2.758812292214391E-4 11.132539402351965 22 245558]
                           [["inser"] 2.723902651950369E-4 14.704815953697107 70 245558]],
    :cluster-id 75}
   {:n-docs-in-cluster 923.4486226665248,
    :characteristic-words [[["mariag"] 0.0013201040360614757 67.20437251254701 278 245558]
                           [["homosexuel"] 9.742825462700222E-4 51.33209759451159 233 245558]
                           [["gay"] 6.921914550365796E-4 39.05119567554147 214 245558]
                           [["homo"] 5.3583968003975E-4 30.038591408337645 161 245558]
                           [["coupl"] 5.302787336363482E-4 40.50685697765467 474 245558]
                           [["mari"] 4.8614031891022064E-4 28.01913854534001 162 245558]
                           [["deput"] 4.134565943462315E-4 43.96761982852412 982 245558]
                           [["hetero"] 3.7871057124235635E-4 21.1160684153419 111 245558]
                           [["homophob"] 2.9916362373676175E-4 25.85770461007397 390 245558]
                           [["feme"] 2.935951739158338E-4 56.36153554635883 2818 245558]
                           [["homosexualit"] 2.869999625332159E-4 16.67475484494242 98 245558]
                           [["home"] 2.810835229565545E-4 51.38826007873013 2431 245558]
                           [["sexualit"] 2.0553717687092632E-4 12.337316247153467 79 245558]
                           [["corbier"] 2.0179625191411865E-4 11.95952775857284 74 245558]
                           [["heterosexuel"] 1.930051904651675E-4 9.791301944126442 39 245558]
                           [["meurtr"] 1.5725231921572685E-4 13.419082778940936 196 245558]
                           [["conjugal"] 1.558856025599062E-4 9.364297667029945 60 245558]
                           [["anarchist"] 1.5429560866920405E-4 11.597550214760533 129 245558]
                           [["lesbien"] 1.5049046859514498E-4 9.639005884021746 73 245558]
                           [["mariag" "tou"] 1.487632657199492E-4 8.751630342992515 53 245558]],
    :cluster-id 43}
   {:n-docs-in-cluster 888.2969644513424,
    :characteristic-words [[["heur"] 0.002562484390450509 163.6908889018046 1390 245558]
                           [["hiv"] 7.036471551911533E-4 40.170661475849684 236 245558]
                           [["matin"] 5.999240013059223E-4 46.31138817207741 578 245558]
                           [["heur" "hiv"] 4.605285434135173E-4 18.458557737596294 37 245558]
                           [["soleil"] 4.473142012348108E-4 28.53427742135567 224 245558]
                           [["journ"] 4.427358997730682E-4 36.92453249357204 541 245558]
                           [["someil"] 3.9351381915411765E-4 19.610480187534467 77 245558]
                           [["nuit"] 3.8701251213303645E-4 31.60761649954246 442 245558]
                           [["horair"] 3.708934389901816E-4 23.360286494939743 177 245558]
                           [["soir"] 3.2331520925141244E-4 33.10452595247098 716 245558]
                           [["travail"] 2.496312332999351E-4 55.097506229093796 3299 245558]
                           [["chang" "heur"] 2.402794414672338E-4 9.839493302953745 21 245558]
                           [["reveil"] 2.2563219847485105E-4 15.204270279445996 136 245558]
                           [["7h"] 2.2163423468622018E-4 11.574639246167568 52 245558]
                           [["8h"] 2.2095575222647046E-4 11.660003764195338 54 245558]
                           [["couch"] 2.174042512316024E-4 17.973193161525792 256 245558]
                           [["midi"] 2.027797681002294E-4 14.798111407993092 160 245558]
                           [["tôt"] 1.8776736428995078E-4 15.927761783267481 239 245558]
                           [["dormi"] 1.7744476522615904E-4 11.964438446470535 107 245558]
                           [["jour"] 1.6702082309172361E-4 45.57764006787102 3283 245558]],
    :cluster-id 84}
   {:n-docs-in-cluster 859.7076408893139,
    :characteristic-words [[["macron"] 6.166862271435591E-4 87.99870236241875 3373 245558]
                           [["citoyen"] 5.924206741155374E-4 52.305393329086705 896 245558]
                           [["emanuel"] 5.778241665023789E-4 39.57200260279058 385 245558]
                           [["emanuel" "macron"] 5.232613187168764E-4 34.215023859958556 296 245558]
                           [["fonctionair"] 4.8092246949157924E-4 36.8547021688697 465 245558]
                           [["reform"] 2.4220824229703036E-4 25.33370572825326 585 245558]
                           [["ric"] 2.2819533332006647E-4 17.253804705263597 209 245558]
                           [["anonc"] 2.184582972743071E-4 26.189545484208544 756 245558]
                           [["referendum"] 2.099011771391282E-4 22.54636445149732 544 245558]
                           [["initiatif"] 1.8839749701735076E-4 16.900748248093155 293 245558]
                           [["ena"] 1.7065733164690206E-4 9.73954905596148 58 245558]
                           [["pôle"] 1.5145339158442045E-4 10.941098224535637 119 245558]
                           [["publiqu"] 1.4501796578274273E-4 26.179339200033418 1297 245558]
                           [["local"] 1.3987339968331292E-4 19.63189364607985 710 245558]
                           [["referendum" "initiatif"] 1.3901832494018357E-4 8.344996690596133 57 245558]
                           [["tiré" "sort"] 1.3644010372088067E-4 6.6523424212843185 25 245558]
                           [["colectivit"] 1.337451544501933E-4 10.878477728623912 154 245558]
                           [["ecol"] 1.2701970636333493E-4 22.31520422860944 1070 245558]
                           [["fermetur"] 1.2399071970214948E-4 9.030667480051507 100 245558]
                           [["petition"] 1.1553094755283044E-4 9.44385268774946 135 245558]],
    :cluster-id 98}
   {:n-docs-in-cluster 827.6277992116546,
    :characteristic-words [[["jean"] 0.0017310974258873402 96.15124902323898 577 245558]
                           [["asembl"] 5.488889642170092E-4 43.68313914602619 624 245558]
                           [["luc"] 5.23224121973434E-4 27.63894508911318 138 245558]
                           [["jean" "luc"] 4.975736194967884E-4 24.976586611531665 107 245558]
                           [["michel"] 4.374982242102976E-4 26.005826823892153 181 245558]
                           [["jean" "luc" "melenchon"] 4.1437238803986726E-4 19.74762351346098 72 245558]
                           [["luc" "melenchon"] 4.1437238803986726E-4 19.74762351346098 72 245558]
                           [["deput"] 3.510634161802695E-4 38.13148581261914 982 245558]
                           [["philip"] 2.597322052893397E-4 19.059505975913304 224 245558]
                           [["pier"] 2.3222081333785366E-4 18.76387561392974 273 245558]
                           [["melenchon"] 2.3118297755159167E-4 27.536143066048766 818 245558]
                           [["jean" "michel"] 2.2220058638823537E-4 11.83402812985278 60 245558]
                           [["edouard"] 2.2139156524640657E-4 13.895862797694075 111 245558]
                           [["asembl" "national"] 2.0945811030460837E-4 15.61729444726603 190 245558]
                           [["vincent"] 1.9997063072207943E-4 11.84424722397769 81 245558]
                           [["list"] 1.7886252845744632E-4 26.52213872209049 1076 245558]
                           [["nicola"] 1.7694611245468866E-4 12.612155933979121 138 245558]
                           [["edouard" "philip"] 1.63967087920526E-4 10.324678474814352 83 245558]
                           [["francoi"] 1.4447442860375526E-4 13.315564200872887 252 245558]
                           [["auror"] 1.424780114187077E-4 7.131270523908327 30 245558]],
    :cluster-id 42}
   {:n-docs-in-cluster 803.4008896308441,
    :characteristic-words [[["islam"] 0.0019032120650726222 103.31241760865868 600 245558]
                           [["religion"] 0.0018895736947444203 115.8693958007185 952 245558]
                           [["religieu"] 0.0011719965826223408 71.40328905844721 561 245558]
                           [["eglis"] 8.795729086219367E-4 46.368234511062276 239 245558]
                           [["musulman"] 7.904539601375632E-4 60.56105221096035 828 245558]
                           [["cathol"] 7.567938738262392E-4 40.38483072594873 215 245558]
                           [["laïcit"] 7.414524573049752E-4 34.16838199758691 116 245558]
                           [["islamophob"] 4.517308103359624E-4 25.193617127182666 151 245558]
                           [["chretien"] 3.736761883672418E-4 24.268167501759915 219 245558]
                           [["cult"] 3.6079716150414637E-4 19.845743212783294 114 245558]
                           [["eglis" "cathol"] 2.5454605346399845E-4 12.246844263037154 47 245558]
                           [["coran"] 2.3100151431878352E-4 14.627003176272519 123 245558]
                           [["mosqu"] 2.180245746950249E-4 11.92040991060587 67 245558]
                           [["athe"] 1.968713476949227E-4 13.044079114590629 123 245558]
                           [["croyant"] 1.9604631956079976E-4 13.357512369810264 135 245558]
                           [["catholicism"] 1.884224463515119E-4 9.718610804902967 46 245558]
                           [["christianism"] 1.8004439837820962E-4 10.628351201838669 74 245558]
                           [["voil"] 1.7485723578966605E-4 17.001156956349057 367 245558]
                           [["islamist"] 1.6088139698734044E-4 11.850526458170158 144 245558]
                           [["sepa"] 1.5665624996036298E-4 11.149664539894266 125 245558]],
    :cluster-id 38}
   {:n-docs-in-cluster 563.4077201770017,
    :characteristic-words [[["mare"] 3.6381081058738107E-4 23.891412630838694 313 245558]
                           [["tl"] 2.9912931437490325E-4 17.921379948966973 183 245558]
                           [["dr"] 2.1952435565852219E-4 12.669383627803345 116 245558]
                           [["nouvel" "caledon"] 2.0732634900938116E-4 8.703428444153232 29 245558]
                           [["caledon"] 2.0732634900938116E-4 8.703428444153232 29 245558]
                           [["tl" "dr"] 1.901317187269283E-4 10.789790708947105 94 245558]
                           [["parent"] 1.858784370642047E-4 24.6427449099503 1262 245558]
                           [["npa"] 1.528844985916114E-4 7.8648021115576325 51 245558]
                           [["gauchist"] 1.5209196881912912E-4 12.528071124853582 276 245558]
                           [["enfant"] 1.5179366821109486E-4 30.200162231442622 2554 245558]
                           [["independanc"] 1.4262853316359542E-4 9.953083147864197 150 245558]
                           [["caisi"] 1.291919198216536E-4 7.513384928442117 70 245558]
                           [["reunion"] 1.1953358705242556E-4 9.926609655982242 222 245558]
                           [["bombard"] 1.1712172672560459E-4 6.407997525928104 50 245558]
                           [["cais"] 1.1655092006347678E-4 9.967750894983729 237 245558]
                           [["maman"] 1.1528116038317729E-4 8.043816623063895 121 245558]
                           [["mam"] 1.0845921771672137E-4 5.988986622148591 48 245558]
                           [["interveni"] 1.0220226269222824E-4 6.631150136688356 83 245558]
                           [["dysfonction"] 1.0132195925912149E-4 5.95291437789169 57 245558]
                           [["papa"] 1.005467507403969E-4 7.251005031152375 118 245558]],
    :cluster-id 44}
   {:n-docs-in-cluster 488.1866147908955,
    :characteristic-words [[["vote"] 0.0017326876747216363 116.09172421877653 2035 245558]
                           [["candidat"] 8.951737294788306E-4 56.16411543718292 777 245558]
                           [["system"] 5.507551586272641E-4 58.81706237854467 2567 245558]
                           [["majoritair"] 5.34766700461882E-4 32.80550123773426 417 245558]
                           [["system" "vote"] 5.117486375462998E-4 20.213995311498962 62 245558]
                           [["juge" "majoritair"] 4.8189682085302346E-4 17.9915243997656 45 245558]
                           [["juge"] 2.6970746391399847E-4 25.092165905989912 825 245558]
                           [["meileu"] 2.491735296868275E-4 25.500543917058124 1000 245558]
                           [["vote" "blanc"] 2.238415821772359E-4 12.924229801535018 136 245558]
                           [["vote" "util"] 2.1283335813586726E-4 9.808543069389215 51 245558]
                           [["condorcet"] 2.015344649750235E-4 8.156530319579788 27 245558]
                           [["meileu" "system"] 2.0044025631849038E-4 8.122008640787431 27 245558]
                           [["tour"] 1.6795603760851735E-4 18.239153111714664 787 245558]
                           [["scrutin"] 1.5828896657673133E-4 10.435810830805433 157 245558]
                           [["rejet"] 1.248356113265625E-4 11.358935558899706 353 245558]
                           [["blanc"] 1.240378044563492E-4 16.20360635393078 932 245558]
                           [["util"] 1.1571959892740791E-4 16.511465962954137 1075 245558]
                           [["elimin"] 1.1062246176827065E-4 7.556387865464615 124 245558]
                           [["vot"] 8.964040773053183E-5 13.889411256891702 1005 245558]
                           [["ecol"] 8.941561234302592E-5 14.259089673496174 1070 245558]],
    :cluster-id 94}
   {:n-docs-in-cluster 483.15621561439116,
    :characteristic-words [[["nazi"] 7.498733106793137E-4 40.400223001128694 360 245558]
                           [["anonymat"] 3.5551539399392845E-4 18.247352080530455 137 245558]
                           [["alt"] 3.1622913554660986E-4 15.343813981876705 96 245558]
                           [["handicap"] 3.048552653787326E-4 17.722775186761517 193 245558]
                           [["reportag"] 2.542825855979536E-4 16.884425081809272 263 245558]
                           [["mouton"] 2.2759059494330963E-4 11.578423660571461 84 245558]
                           [["néo"] 2.0469212759187047E-4 12.295402524961718 146 245558]
                           [["facebok"] 1.9424050080853877E-4 18.72258133830377 661 245558]
                           [["ouin"] 1.7646412609377202E-4 8.94512834222867 64 245558]
                           [["néo" "nazi"] 1.6939470794553185E-4 7.676772515538669 38 245558]
                           [["ouin" "ouin"] 1.6272215315221022E-4 8.004865019069344 52 245558]
                           [["boul"] 1.6063813585918962E-4 9.687407378027114 116 245558]
                           [["anonym"] 1.2214165746336075E-4 8.013868820406488 120 245558]
                           [["courag"] 1.0978970704458163E-4 9.62282125040011 280 245558]
                           [["alt" "right"] 9.932663884355819E-5 4.491360249869515 22 245558]
                           [["asument"] 9.318459035130566E-5 4.8954270678769864 39 245558]
                           [["chais"] 8.727347361845578E-5 5.13156792850513 57 245558]
                           [["afligeant"] 8.009784998240857E-5 4.630661211384579 49 245558]
                           [["jt"] 7.654697154718323E-5 4.429813984127656 47 245558]
                           [["salu"] 7.262613996173242E-5 4.330762860314657 50 245558]],
    :cluster-id 90}
   {:n-docs-in-cluster 479.74108670144403,
    :characteristic-words [[["sport"] 0.0011526201168093261 54.0859302521633 317 245558]
                           [["vall"] 3.976395758233456E-4 19.40463938058742 125 245558]
                           [["manuel"] 3.6680176166611206E-4 18.685551698641927 138 245558]
                           [["bill"] 3.4609100379092567E-4 15.873917557586136 83 245558]
                           [["gate"] 3.433342459401717E-4 15.324241810691872 73 245558]
                           [["bill" "gate"] 3.107480981178641E-4 13.422727316301767 57 245558]
                           [["manuel" "vall"] 2.872727302270575E-4 11.34386227414304 35 245558]
                           [["eps"] 2.6407588100749516E-4 9.403955204781356 20 245558]
                           [["sportif"] 2.3317847684664259E-4 12.12890905641594 95 245558]
                           [["anglo"] 2.2416187125098852E-4 11.619833750990962 90 245558]
                           [["equip"] 2.12302995113052E-4 18.118138265022562 507 245558]
                           [["saxon"] 1.9911447630506854E-4 10.067333956906337 72 245558]
                           [["barcelon"] 1.9405244969126004E-4 8.564117539616802 39 245558]
                           [["anglo" "saxon"] 1.8630867595390704E-4 9.508349243866029 70 245558]
                           [["rgpd"] 1.3031693992877208E-4 6.845852417840593 55 245558]
                           [["club"] 1.2833675903738476E-4 8.152950920851424 113 245558]
                           [["foot"] 1.2769420007703938E-4 8.65769467848375 142 245558]
                           [["match"] 1.1343673369166793E-4 7.150897888936625 97 245558]
                           [["feminin"] 9.500051152596875E-5 7.070343419657676 145 245558]
                           [["fotbal"] 9.483906754004581E-5 4.722193349949363 32 245558]],
    :cluster-id 1}
   {:n-docs-in-cluster 467.10772783120336,
    :characteristic-words [[["sein"] 3.1181280411655965E-4 21.80878174836251 402 245558]
                           [["senat"] 3.0428652785203375E-4 19.365736038583883 280 245558]
                           [["alpe"] 2.6605517116284547E-4 11.696958487186834 54 245558]
                           [["lr"] 2.4077668708163968E-4 16.6200379225442 295 245558]
                           [["deni"] 2.4073524320620057E-4 12.938046577554216 115 245558]
                           [["sein" "saint"] 2.144150763346911E-4 9.985464360518769 56 245558]
                           [["saint" "deni"] 2.1183695996312632E-4 10.203535657008747 64 245558]
                           [["haut"] 2.0021292753967523E-4 26.267083751346885 1605 245558]
                           [["sein" "saint" "deni"] 1.9535981519135104E-4 9.098904025713633 51 245558]
                           [["rhon"] 1.8313810674253778E-4 8.282523122335535 42 245558]
                           [["saint"] 1.7751173611098292E-4 14.258812684093222 359 245558]
                           [["rhon" "alpe"] 1.3901669210967388E-4 5.731619013697356 21 245558]
                           [["depart"] 1.2220303168714725E-4 13.081831684883307 573 245558]
                           [["loir"] 1.0850250359926289E-4 6.506567875422625 79 245558]
                           [["a" "troi"] 1.073263453225462E-4 5.5961800311663685 45 245558]
                           [["marn"] 9.343850516954286E-5 4.641545952133505 32 245558]
                           [["auvergn"] 9.342748134110943E-5 4.380853891108679 25 245558]
                           [["savo"] 8.846432778686006E-5 4.079162469929925 22 245558]
                           [["wauquiez"] 8.407114751348213E-5 5.9647287521569305 112 245558]
                           [["provenc"] 8.058451555162383E-5 3.9828943375451757 27 245558]],
    :cluster-id 49}
   {:n-docs-in-cluster 457.7194286256765,
    :characteristic-words [[["paseport"] 4.274835659581061E-4 19.070537984729846 95 245558]
                           [["pilot"] 3.730654780979663E-4 19.37216394232812 159 245558]
                           [["wifi"] 3.358713960910087E-4 14.640019583283433 67 245558]
                           [["hain"] 2.631156540112284E-4 19.838484099993696 445 245558]
                           [["amou"] 2.5483105632025105E-4 14.25952271877363 146 245558]
                           [["conect"] 2.504806125804282E-4 13.349823371704236 118 245558]
                           [["perquis"] 2.4289662756352137E-4 13.832054548830547 149 245558]
                           [["ipv6"] 1.9439252722433867E-4 7.638200442301777 24 245558]
                           [["oie"] 1.9255261073415977E-4 8.574022742666898 42 245558]
                           [["paseport" "diplomat"] 1.8188052737320992E-4 7.044497748255997 21 245558]
                           [["vlc"] 1.8097449652753825E-4 8.343260677140872 46 245558]
                           [["ip"] 1.7223060571455728E-4 8.148916411136952 49 245558]
                           [["dron"] 1.6135532144830392E-4 8.524982739264217 73 245558]
                           [["sci"] 1.5673591420612212E-4 8.015912650745422 62 245558]
                           [["hugo"] 1.4853193204642304E-4 6.8184999294714075 37 245558]
                           [["sci" "hub"] 1.4767628141876613E-4 6.659235947504026 34 245558]
                           [["diplomat"] 1.4679927165560033E-4 9.83337711963826 164 245558]
                           [["hub"] 1.396135859520961E-4 6.837956437904823 46 245558]
                           [["suspension"] 1.2571380381006664E-4 6.648622106361893 57 245558]
                           [["scan"] 1.23065917066012E-4 6.485848234951292 55 245558]],
    :cluster-id 60}
   {:n-docs-in-cluster 449.966925728896,
    :characteristic-words [[["taxi"] 9.246783359792635E-4 36.066923250242844 115 245558]
                           [["chaufeu"] 4.7119295325600413E-4 22.09935225857226 133 245558]
                           [["cart"] 4.650485304952198E-4 33.186135499418064 675 245558]
                           [["uber"] 4.1568822338141145E-4 18.81317425174663 100 245558]
                           [["vtc"] 3.9315362398375034E-4 14.82905431089563 41 245558]
                           [["plaqu"] 1.865431938737408E-4 12.601893773334035 219 245558]
                           [["bus"] 1.534713830633888E-4 13.122092512215596 391 245558]
                           [["hotel"] 1.3774591618707574E-4 8.359737446303997 109 245558]
                           [["apli"] 1.2600106642809278E-4 8.254319720177516 132 245558]
                           [["diplom"] 1.1597694908515796E-4 9.26527407382473 238 245558]
                           [["livreu"] 1.1189213677617604E-4 6.145133845085943 60 245558]
                           [["chaufeu" "bus"] 9.94770234053581E-5 4.33858349896801 20 245558]
                           [["prim"] 9.583113802589388E-5 9.06006177224562 328 245558]
                           [["bleu"] 7.52857547658553E-5 6.158831803637924 166 245558]
                           [["conduir"] 6.721239207621924E-5 5.162770533947133 121 245558]
                           [["client"] 6.1030818949189825E-5 7.8950069734276225 482 245558]
                           [["arier"] 5.8958587075476354E-5 5.505993574691547 194 245558]
                           [["vehicul"] 5.4416980998332753E-5 8.042827796728071 591 245558]
                           [["gris"] 4.896047446774701E-5 2.759828471802065 29 245558]
                           [["imatricul"] 4.473500257605109E-5 2.625830552209197 31 245558]],
    :cluster-id 20}
   {:n-docs-in-cluster 432.7018013211605,
    :characteristic-words [[["propriet"] 4.740888367626983E-4 26.042087120407995 270 245558]
                           [["comunism"] 4.6295660381450235E-4 25.274015953954585 257 245558]
                           [["toilet"] 3.111041646117335E-4 15.071113457489654 104 245558]
                           [["urin"] 2.7868863829150214E-4 11.247832034855271 41 245558]
                           [["gout"] 2.639243962169642E-4 15.458013468714679 191 245558]
                           [["papi"] 2.4846558524287826E-4 19.915171885381632 542 245558]
                           [["propriet" "priv"] 2.0843876548601661E-4 9.983710739352052 66 245558]
                           [["debaras"] 1.8867797077416065E-4 10.635023396203023 117 245558]
                           [["techn"] 1.767943095741381E-4 16.212153825098017 580 245558]
                           [["vête"] 1.6991380383875668E-4 10.293088621177494 139 245558]
                           [["comunist"] 1.60986159318581E-4 13.839815115211904 434 245558]
                           [["efarouch"] 1.4556513998013887E-4 5.9161445258294725 22 245558]
                           [["abol"] 1.400977484895885E-4 7.063405354713306 55 245558]
                           [["ultim"] 9.508816200485409E-5 5.943331000093803 87 245558]
                           [["copi"] 9.391396382014625E-5 5.777637611379098 81 245558]
                           [["sec"] 9.272765466313004E-5 5.380162927046937 64 245558]
                           [["ami"] 8.987041714790533E-5 11.632277696306303 742 245558]
                           [["pise"] 7.647028580832702E-5 4.959668583298827 80 245558]
                           [["assi"] 7.15039018406409E-5 4.206866458594496 52 245558]
                           [["feuil"] 7.1217526363309E-5 5.107470064752599 106 245558]],
    :cluster-id 24}
   {:n-docs-in-cluster 387.3213525462151,
    :characteristic-words [[["plast"] 0.001976522763298044 83.14264454271054 425 245558]
                           [["coca"] 6.983153221302128E-4 28.416025010123494 120 245558]
                           [["embalag"] 6.077832632519278E-4 25.267902823593477 115 245558]
                           [["coca" "cola"] 3.967019477446841E-4 15.101829495794728 49 245558]
                           [["cola"] 3.967019477446841E-4 15.101829495794728 49 245558]
                           [["recycl"] 2.940282414768715E-4 14.675832289240004 124 245558]
                           [["cash" "investig"] 2.67138226950972E-4 11.214002640581255 52 245558]
                           [["bouteil"] 2.2500983541247355E-4 14.464662089609169 257 245558]
                           [["cash"] 2.242316817482222E-4 12.716581372901922 159 245558]
                           [["carton"] 2.1722728988304157E-4 11.08172352061318 100 245558]
                           [["investig"] 2.0605424329116218E-4 11.307838407626043 128 245558]
                           [["recyclag"] 1.8086386259090828E-4 10.198619665520336 125 245558]
                           [["recyclabl"] 1.762471682387641E-4 7.657121692334493 40 245558]
                           [["jetabl"] 1.4054232226676927E-4 6.757184041793852 50 245558]
                           [["vere"] 1.3323823830713524E-4 9.99741529582785 259 245558]
                           [["quota"] 1.3293332105273128E-4 7.800620754061424 107 245558]
                           [["consign"] 1.3108899942229982E-4 8.025427754977063 124 245558]
                           [["toleranc"] 1.1570888387214123E-4 7.530294222520432 137 245558]
                           [["elis" "lucet"] 1.054873855641271E-4 4.709909776595545 27 245558]
                           [["lucet"] 9.970742278532507E-5 4.84348298501952 37 245558]],
    :cluster-id 34}
   {:n-docs-in-cluster 365.2940942281162,
    :characteristic-words [[["vpn"] 5.318001174274244E-4 21.939118933041456 102 245558]
                           [["black"] 5.170724794487519E-4 24.569813154377357 189 245558]
                           [["trol"] 3.081275489106791E-4 20.86051804773202 453 245558]
                           [["block"] 2.1601378084517078E-4 9.655327589569403 59 245558]
                           [["mair"] 2.0975929763160372E-4 17.894499939082902 658 245558]
                           [["black" "block"] 2.0647961823496222E-4 8.910030045733937 48 245558]
                           [["bloc"] 1.732221594482851E-4 10.000844096053042 139 245558]
                           [["acronym"] 1.6328487019699398E-4 6.3928413936306185 24 245558]
                           [["black" "bloc"] 1.5564305391178174E-4 6.407625459857981 29 245558]
                           [["hadopi"] 1.418428342386431E-4 6.4948930745093945 43 245558]
                           [["grenobl"] 1.24513817496133E-4 6.652329737842743 73 245558]
                           [["hlm"] 1.2426587224987837E-4 6.505450804451197 67 245558]
                           [["habitant"] 1.1285513251402951E-4 10.778803141162038 491 245558]
                           [["log"] 1.0749863744415403E-4 5.957302945969567 73 245558]
                           [["neanmoin"] 1.0485849256022112E-4 7.313120332449483 168 245558]
                           [["oui" "bien" "sûr"] 1.0264492146799808E-4 6.4487328751796085 113 245558]
                           [["poison"] 1.01137284718264E-4 7.389539620025018 190 245558]
                           [["bb"] 9.869931822260175E-5 4.715388425962146 36 245558]
                           [["lyon"] 9.587188065014393E-5 7.633949250317664 239 245558]
                           [["carement"] 8.603630245027777E-5 7.641512051062942 301 245558]],
    :cluster-id 67}
   {:n-docs-in-cluster 356.38543958016044,
    :characteristic-words [[["psychiat"] 4.958091458983523E-4 21.478334567152828 122 245558]
                           [["infirmi"] 3.0420293133479395E-4 15.504134416864233 152 245558]
                           [["psy"] 2.948397345236588E-4 14.32692071052523 120 245558]
                           [["troubl"] 2.70983501351265E-4 14.24993744917692 154 245558]
                           [["psychanalys"] 2.6114134401671735E-4 12.035509404648108 84 245558]
                           [["patient"] 2.489218934527346E-4 15.161010126829417 253 245558]
                           [["psychiatr"] 2.2049098704765915E-4 9.574140758165575 54 245558]
                           [["ad"] 2.1940355961146824E-4 11.50987691355575 123 245558]
                           [["hominem"] 1.7072803453767674E-4 8.270094455452107 68 245558]
                           [["ad" "hominem"] 1.6521366462642073E-4 8.036945038098963 67 245558]
                           [["hopital"] 1.611728030741777E-4 13.156978113692528 449 245558]
                           [["soin"] 1.3144832695080946E-4 9.727786080240572 265 245558]
                           [["bouc"] 9.043052505247964E-5 4.298464662555804 33 245558]
                           [["emisair"] 8.717970237874864E-5 4.089191211540629 30 245558]
                           [["bouc" "emisair"] 8.547885796059834E-5 3.9651141647532544 28 245558]
                           [["psychologu"] 8.435402131021577E-5 4.210215207697167 38 245558]
                           [["psychanalyst"] 8.326411877815007E-5 3.787863493142661 25 245558]
                           [["malad"] 7.85299236289172E-5 9.651914983042483 691 245558]
                           [["enferm"] 7.620960493722947E-5 5.378297579331381 130 245558]
                           [["hospital"] 6.151500282554345E-5 3.0788554313489387 28 245558]],
    :cluster-id 9}
   {:n-docs-in-cluster 347.3642444114986,
    :characteristic-words [[["robot"] 4.777664751163882E-4 22.061790592907176 161 245558]
                           [["presomption"] 2.8894389228265385E-4 11.951709313019519 58 245558]
                           [["inocenc"] 2.6987460887723663E-4 10.930979568085826 49 245558]
                           [["presomption" "inocenc"] 2.463719856749119E-4 9.646268375126692 38 245558]
                           [["poo"] 2.248829316829036E-4 7.939900746655016 21 245558]
                           [["blockchain"] 1.4177712851905916E-4 6.252378761802632 38 245558]
                           [["gorafi"] 1.3405338845481896E-4 6.276048219743053 47 245558]
                           [["bitcoin"] 1.0839328613151261E-4 6.294750248297179 93 245558]
                           [["tube"] 9.721386406759402E-5 4.457674892090489 31 245558]
                           [["scop"] 9.305584106551051E-5 4.903876908991036 54 245558]
                           [["dictionair"] 8.646903492610589E-5 4.931627433548066 69 245558]
                           [["antitout"] 8.50945343167965E-5 4.583729461499239 54 245558]
                           [["club"] 7.75558884169511E-5 5.195007924468261 113 245558]
                           [["osteopath"] 6.683612758106791E-5 3.5063801625178295 38 245558]
                           [["ruptur"] 6.081418811390224E-5 3.9057015382517988 76 245558]
                           [["sing"] 5.906274795122041E-5 3.523500564310556 56 245558]
                           [["lisant" "titr"] 5.839483249520799E-5 2.781924865371246 22 245558]
                           [["reptilien"] 5.7067015418424405E-5 3.0499806053957417 35 245558]
                           [["prud'hom"] 5.448590042708354E-5 2.903258114740699 33 245558]
                           [["lisant"] 5.214927623736765E-5 4.749722884688579 206 245558]],
    :cluster-id 69}
   {:n-docs-in-cluster 321.3709338117401,
    :characteristic-words [[["plaint"] 0.0012938366700564335 60.51757763800822 529 245558]
                           [["port" "plaint"] 3.9735815481280884E-4 17.735917613949994 123 245558]
                           [["comisariat"] 2.961122546141677E-4 12.294647879556694 65 245558]
                           [["impunit"] 2.7645011550878756E-4 11.977047500822772 74 245558]
                           [["bar"] 2.2877682824795978E-4 13.221677358404703 210 245558]
                           [["bour"] 2.092631152759844E-4 11.714613031529105 169 245558]
                           [["plaint" "contr"] 1.7897713792706244E-4 7.8363974277658315 50 245558]
                           [["igpn"] 1.6769573353175513E-4 8.054338906756982 71 245558]
                           [["port"] 1.6072660522567506E-4 21.056762639921836 1870 245558]
                           [["polic"] 1.6040093999415694E-4 18.98119441859588 1441 245558]
                           [["flic"] 1.4781358081842472E-4 14.912479099591554 860 245558]
                           [["depos"] 1.1818145473192071E-4 7.345940489555392 142 245558]
                           [["circonstanc"] 1.0663514903034438E-4 6.75054116410399 137 245558]
                           [["bafe"] 1.0063190880866754E-4 4.406964787834009 28 245558]
                           [["cogn"] 9.86301594227447E-5 4.7226353073808625 41 245558]
                           [["polici"] 9.816632694860383E-5 13.482929817769428 1267 245558]
                           [["agravant"] 9.393399026309812E-5 4.548561819639775 41 245558]
                           [["circonstanc" "agravant"] 9.254892167694442E-5 4.061918581237013 26 245558]
                           [["plaignant"] 8.75793143137911E-5 4.156838759481064 35 245558]
                           [["matricul"] 7.869163246116209E-5 4.03824267646679 44 245558]],
    :cluster-id 59}
   {:n-docs-in-cluster 286.2945894541555,
    :characteristic-words [[["airbu"] 7.546855299263543E-4 27.40507748363972 98 245558]
                           [["boeing"] 3.493507514341892E-4 13.097332716810467 52 245558]
                           [["a380"] 3.283733165664847E-4 11.385451204725328 33 245558]
                           [["chinoi"] 3.203073687259722E-4 20.536612991538213 495 245558]
                           [["comand"] 1.9398929122657615E-4 11.675312845979185 233 245558]
                           [["soja"] 1.5507000751692132E-4 7.7265756107242005 86 245558]
                           [["thal"] 1.2228359380738428E-4 4.863123801816005 24 245558]
                           [["dasault"] 1.1809005425486921E-4 4.884604627581616 28 245558]
                           [["compagn"] 1.0286532301634344E-4 8.188350794929555 327 245558]
                           [["traitr"] 1.0185473505270118E-4 5.502529592530624 79 245558]
                           [["apareil"] 9.704836246486256E-5 6.320847231453833 155 245558]
                           [["aeronaut"] 9.112960865121852E-5 3.8551883166320766 24 245558]
                           [["aerien"] 7.757165432631039E-5 5.412375289649209 158 245558]
                           [["compagn" "aerien"] 7.440651321958917E-5 3.5036302600329368 32 245558]
                           [["aviation"] 7.387790794838311E-5 3.9539416045527203 55 245558]
                           [["carnet"] 6.854745653358911E-5 3.138974969732873 26 245558]
                           [["marseilais"] 6.780504266637975E-5 3.2374955464843427 31 245558]
                           [["avion"] 5.925174537287081E-5 7.176784162724993 624 245558]
                           [["crash"] 5.4806716972289823E-5 2.9379209928326615 41 245558]
                           [["vol"] 5.106614458930819E-5 5.73576471299075 441 245558]],
    :cluster-id 86}
   {:n-docs-in-cluster 265.80490378093816,
    :characteristic-words [[["dieu"] 3.1421881615216127E-4 19.169871576177833 434 245558]
                           [["empir"] 2.955656923375148E-4 13.961392337444257 141 245558]
                           [["athe"] 2.8999105102041E-4 13.347520587861993 123 245558]
                           [["quebec"] 2.3513938008538904E-4 11.003599805293156 107 245558]
                           [["croyanc"] 2.3430199957182388E-4 12.796012232907621 207 245558]
                           [["canada"] 1.842538847267733E-4 11.290707589252293 255 245558]
                           [["canadien"] 1.7095521488052004E-4 7.991466059120011 77 245558]
                           [["atheism"] 1.5990311515363086E-4 7.075624041397644 56 245558]
                           [["homicid"] 1.570961830532694E-4 7.351105861657861 71 245558]
                           [["quebecoi"] 1.3656328892167405E-4 5.756395794084252 38 245558]
                           [["cacao"] 1.253902408316042E-4 5.049981809133886 28 245558]
                           [["francophon"] 1.1296998970689379E-4 5.917892983239431 83 245558]
                           [["dieu" "exist"] 9.613276861892422E-5 3.8929103236334193 22 245558]
                           [["romain"] 8.544504335769587E-5 5.090378485452222 105 245558]
                           [["meuf"] 8.163941588107404E-5 5.507187387500651 159 245558]
                           [["veil"] 7.961864350758407E-5 4.903551322103945 111 245558]
                           [["poudr"] 7.112162373790025E-5 3.886995550701952 62 245558]
                           [["alphabet"] 6.957204453892328E-5 3.2505453528091373 31 245558]
                           [["involontair"] 6.53010827291571E-5 3.2016840882551647 36 245558]
                           [["spiritualit"] 6.269277551374311E-5 2.723496218713085 20 245558]],
    :cluster-id 25}
   {:n-docs-in-cluster 265.3946960469183,
    :characteristic-words [[["camp"] 4.974847400591419E-4 25.928687517856787 372 245558]
                           [["drapeau"] 3.083457461860416E-4 14.641981971953673 151 245558]
                           [["concent"] 2.487790049673297E-4 12.225108621627175 141 245558]
                           [["camp" "concent"] 1.647960394583342E-4 6.485256459531418 33 245558]
                           [["royal"] 1.5474515436794678E-4 8.134576169340173 116 245558]
                           [["goulag"] 1.3213714034273835E-4 5.607279561266826 38 245558]
                           [["pilul"] 1.0949433050813986E-4 5.674777373141692 77 245558]
                           [["pharmacien"] 1.0584897159772455E-4 5.6955565322657575 87 245558]
                           [["danon"] 1.0503721931043666E-4 4.656539450184795 37 245558]
                           [["delation"] 1.004584446042435E-4 4.523280230885891 38 245558]
                           [["go"] 8.568459388260188E-5 5.173175484714858 111 245558]
                           [["segolen"] 6.0441692752915416E-5 3.2449070156969375 49 245558]
                           [["reduc"] 5.809307325394843E-5 2.805533941193649 30 245558]
                           [["contraception"] 5.588785370915124E-5 3.2174460925098116 60 245558]
                           [["segolen" "royal"] 5.3088368616263995E-5 2.617489389668141 30 245558]
                           [["transgenr"] 5.286078948709902E-5 2.525079055213756 26 245558]
                           [["proletair"] 5.27371475023547E-5 2.803217528629239 41 245558]
                           [["franchis"] 4.263323654926307E-5 2.139895120980406 26 245558]
                           [["leclerc"] 4.2320035788995236E-5 2.56039586681295 55 245558]
                           [["extermin"] 4.122920728204943E-5 2.4242011261778873 48 245558]],
    :cluster-id 55}
   {:n-docs-in-cluster 251.7208974992495,
    :characteristic-words [[["grev"] 8.06359303315126E-4 37.0681994130542 376 245558]
                           [["soignant"] 2.751171729616156E-4 11.642873008450286 83 245558]
                           [["urgenc"] 2.564752452128283E-4 15.961708348698924 401 245558]
                           [["urgentist"] 1.791299427492503E-4 6.688865074987651 29 245558]
                           [["kebab"] 1.454051893279261E-4 6.285161058568864 48 245558]
                           [["requisition"] 1.226239182204672E-4 4.799034179971064 25 245558]
                           [["blam"] 9.91874484081029E-5 5.701317226231643 112 245558]
                           [["moul"] 9.108765474282778E-5 4.120876574794003 37 245558]
                           [["fair" "grev"] 7.27822191197193E-5 3.4187569342650947 35 245558]
                           [["camembert"] 7.024284384352444E-5 3.2825466827733445 33 245558]
                           [["couleu"] 5.6873611394541584E-5 5.207351327612847 315 245558]
                           [["droit" "grev"] 5.138898428301564E-5 2.421135874135953 25 245558]
                           [["requis"] 5.1046938654694674E-5 2.8901823212562716 54 245558]
                           [["bleu"] 5.0415007755478386E-5 3.908247902271687 166 245558]
                           [["burn"] 4.937991330641878E-5 2.723394036646099 47 245558]
                           [["alerg"] 4.760894216697918E-5 2.7564620865475593 55 245558]
                           [["font" "grev"] 4.4299899937323695E-5 2.0753975447646758 21 245558]
                           [["personel"] 4.289048362145914E-5 7.536734264374217 1229 245558]
                           [["out"] 3.56339907228824E-5 2.7400598154546194 114 245558]
                           [["4chan"] 3.381671324671244E-5 1.9830254991786653 41 245558]],
    :cluster-id 97}
   {:n-docs-in-cluster 232.6978080305394,
    :characteristic-words [[["glyphosat"] 7.68934298916335E-4 32.083982123129275 244 245558]
                           [["oiseau"] 3.982814140526987E-4 16.8303850101049 130 245558]
                           [["monsanto"] 2.559085733456791E-4 12.248821924391272 147 245558]
                           [["georg"] 2.2588432318870408E-4 9.662709273802088 77 245558]
                           [["abeil"] 1.0821683589482857E-4 5.267793569729132 66 245558]
                           [["coli"] 8.770396143753176E-5 4.298439379708362 55 245558]
                           [["cancerigen"] 8.178965819837652E-5 4.090820126163758 56 245558]
                           [["whataboutism"] 7.726240737937137E-5 5.011852483172693 149 245558]
                           [["chant"] 7.469815439626812E-5 4.623821175114234 121 245558]
                           [["tron"] 6.757149420470211E-5 3.045215417149965 29 245558]
                           [["sand"] 6.418437012319957E-5 3.3708586450585796 54 245558]
                           [["politicien"] 6.0934522529892954E-5 5.888357655249708 428 245558]
                           [["shil"] 4.636117295700459E-5 3.1053728421708557 100 245558]
                           [["herbicid"] 4.018934487113636E-5 2.0368216309797487 29 245558]
                           [["etud"] 3.7015005280735536E-5 8.827808685496214 2139 245558]
                           [["favori"] 2.9427099288336506E-5 1.7424736196279094 40 245558]
                           [["coq"] 2.6938545129421426E-5 1.6429230098418666 41 245558]
                           [["insect"] 2.476462718609823E-5 1.9342182835657526 90 245558]
                           [["labou"] 2.4378316280179377E-5 1.4308326751868152 32 245558]
                           [["lobying"] 2.4054306455866675E-5 1.9712813593989493 102 245558]],
    :cluster-id 61}
   {:n-docs-in-cluster 219.86108718563776,
    :characteristic-words [[["sucr"] 7.537626241518429E-4 32.299960894638964 288 245558]
                           [["jus"] 5.53900717642667E-4 19.836454660876036 84 245558]
                           [["bolsonaro"] 3.492976399155426E-4 14.044114801498415 94 245558]
                           [["bresil"] 2.9145831347458795E-4 12.623504660079494 112 245558]
                           [["fruit"] 2.608281225548806E-4 13.63832869088734 233 245558]
                           [["jus" "fruit"] 2.5246802717856717E-4 8.019298879543742 20 245558]
                           [["bresilien"] 1.8515248300951873E-4 7.859903256605022 64 245558]
                           [["sirop"] 1.2505429070805603E-4 4.7932349397480625 26 245558]
                           [["orang"] 1.1923364675356374E-4 6.735950658235087 144 245558]
                           [["glucid"] 9.926100282294133E-5 4.186517087362657 33 245558]
                           [["amazon"] 7.878875603133106E-5 6.479753250852484 361 245558]
                           [["diabet"] 7.750953405115044E-5 3.4001706018229494 31 245558]
                           [["soda"] 7.022905193284149E-5 3.3463389974820235 41 245558]
                           [["boison"] 6.983754958036874E-5 3.665701549305421 62 245558]
                           [["gateau"] 6.318898068857076E-5 3.500169367961704 70 245558]
                           [["pur"] 5.4736122757872985E-5 4.1051224254251615 185 245558]
                           [["coca"] 5.4550730767460864E-5 3.615725374680354 120 245558]
                           [["eau"] 4.886573677201356E-5 6.959799621630107 999 245558]
                           [["gra"] 4.6801679624737524E-5 3.144652418891912 108 245558]
                           [["pome"] 4.4358017202508065E-5 2.9940368765908523 104 245558]],
    :cluster-id 41}
   {:n-docs-in-cluster 198.61529634562436,
    :characteristic-words [[["nicol"] 4.4642129332515476E-4 15.024381355092048 53 245558]
                           [["maxim"] 4.092117900133564E-4 13.956094085630472 52 245558]
                           [["breton"] 3.8844845889664074E-4 15.599497195720012 115 245558]
                           [["maxim" "nicol"] 3.679925690237302E-4 11.555602377771658 30 245558]
                           [["bretagn"] 3.5493774152313765E-4 15.142318862077383 141 245558]
                           [["fly"] 1.7291496113519544E-4 6.703487043670591 42 245558]
                           [["fly" "rid"] 8.765160812633163E-5 3.542047084719972 26 245558]
                           [["rid"] 8.602495302115383E-5 3.5426127412342683 28 245558]
                           [["beloubet"] 7.60118584442436E-5 2.968763714195455 19 245558]
                           [["asil"] 7.206541714440372E-5 3.7608598740886623 69 245558]
                           [["region"] 6.246876295243342E-5 6.216328425256131 560 245558]
                           [["raclur"] 3.9855154745905425E-5 2.026241920096636 34 245558]
                           [["carierist"] 3.7980826179163235E-5 1.7690554843564055 22 245558]
                           [["nant"] 3.4378410103662674E-5 2.4713596119090098 111 245558]
                           [["4g"] 3.4258100985369924E-5 2.0618723148158127 58 245558]
                           [["island"] 3.423407058779533E-5 1.8990739350883554 42 245558]
                           [["drapeau"] 3.2882699471416255E-5 2.6283121666761735 151 245558]
                           [["sic"] 3.182165891464292E-5 1.6299258520787734 28 245558]
                           [["bond"] 3.0469023615028318E-5 1.760723615784514 44 245558]
                           [["5g"] 2.9988826771847055E-5 1.9671041636946944 70 245558]],
    :cluster-id 95}
   {:n-docs-in-cluster 176.7689983061956,
    :characteristic-words [[["grenpeac"] 6.182267494148942E-4 21.780225502013415 106 245558]
                           [["p"] 2.4473106438783684E-4 12.419896336127715 239 245558]
                           [["w.legifrance.gouv.f"] 1.6690080926675017E-4 6.053468831454552 32 245558]
                           [["http" "w.legifrance.gouv.f"] 1.6690080926675017E-4 6.053468831454552 32 245558]
                           [["cidtext"] 1.4929069103922742E-4 5.109885929128049 21 245558]
                           [["nucleair"] 9.360806760452811E-5 11.07898389597344 1528 245558]
                           [["pensez"] 4.7387745062143355E-5 3.427848043961635 176 245558]
                           [["tousa"] 4.642123448649581E-5 2.3548918101845584 44 245558]
                           [["mentent"] 4.299032059382549E-5 2.220497457962517 44 245558]
                           [["derni" "temp"] 4.2412343952158774E-5 2.2974417608346016 53 245558]
                           [["http"] 3.890381222659833E-5 13.64774721566487 6010 245558]
                           [["tldr"] 3.4479914952352184E-5 1.8460302594674667 41 245558]
                           [["oxfam"] 3.3482196632581354E-5 1.6510141625870653 28 245558]
                           [["mensong"] 3.182955645477173E-5 3.519561889187874 426 245558]
                           [["twet"] 2.939640884567704E-5 3.2407679654615116 390 245558]
                           [["menti"] 2.9275357368425264E-5 2.839572828533746 272 245558]
                           [["ong"] 2.8237847589969098E-5 1.8862338715411309 79 245558]
                           [["loby"] 2.733555483694014E-5 2.723233582905877 274 245558]
                           [["consort"] 2.6140394117208504E-5 1.3990447222512898 31 245558]
                           [["incoherenc"] 2.5814200861428924E-5 1.502606627888402 43 245558]],
    :cluster-id 28}
   {:n-docs-in-cluster 166.431210089541,
    :characteristic-words [[["petain"] 6.194239518447168E-4 21.275164904933757 98 245558]
                           [["homag"] 4.2577989842040517E-4 14.410454474084265 61 245558]
                           [["marechal"] 2.9548939182881886E-4 10.857458986692766 65 245558]
                           [["soldat"] 1.162706449146355E-4 5.678263701628325 100 245558]
                           [["publicit"] 1.1327667203686609E-4 6.6882221942132905 215 245558]
                           [["polem"] 8.42849489403226E-5 5.52903803008271 237 245558]
                           [["héro"] 6.131712546895984E-5 3.2860138716815195 78 245558]
                           [["ceremon"] 5.848078189114696E-5 2.441377482977666 24 245558]
                           [["guer"] 5.601416049256375E-5 7.031172669273586 1117 245558]
                           [["premier" "guer"] 5.2527501829421896E-5 2.3982570979705775 33 245558]
                           [["marion"] 4.938802198165872E-5 2.2059963613018727 28 245558]
                           [["premier" "guer" "mondial"] 4.8429109639206575E-5 2.0248439344362805 20 245558]
                           [["rendr"] 4.39748286912775E-5 5.394994727413196 824 245558]
                           [["invalid"] 4.356741261604159E-5 2.614377146687314 87 245558]
                           [["celebr"] 4.307706897675734E-5 2.660211933614695 96 245558]
                           [["guer" "mondial"] 4.280367635369972E-5 2.647807880848435 96 245558]
                           [["placard"] 3.570485937182469E-5 1.9507602319030255 49 245558]
                           [["vichy"] 3.5212084476722236E-5 1.9679386300832735 53 245558]
                           [["bouch"] 2.7450444264630336E-5 2.76965003017563 303 245558]
                           [["afichag"] 2.713713171623687E-5 1.5797281445196538 48 245558]],
    :cluster-id 18}
   {:n-docs-in-cluster 157.39163190273467,
    :characteristic-words [[["philosoph"] 6.764197849739098E-4 26.802205341975498 244 245558]
                           [["marxist"] 1.8664274672249882E-4 7.901887922784352 88 245558]
                           [["fery"] 1.4868848560917712E-4 5.403245534487382 32 245558]
                           [["marxism"] 7.494270371978702E-5 3.035700070583814 28 245558]
                           [["peterson"] 6.06632967260802E-5 2.527173429941904 26 245558]
                           [["agreg"] 5.808792478662582E-5 2.856975603287118 54 245558]
                           [["luc"] 5.206318393244369E-5 3.3160847167138607 138 245558]
                           [["boureau"] 4.7998110139882075E-5 2.0086218570298215 21 245558]
                           [["bhl"] 4.7235832786188606E-5 2.3251573862089776 44 245558]
                           [["negationist"] 3.665671036411251E-5 1.9037330188645367 43 245558]
                           [["mechant"] 3.654821004341405E-5 3.425467372790729 346 245558]
                           [["doct"] 3.549190873293473E-5 2.1952927628730796 84 245558]
                           [["philo"] 2.980635754978693E-5 1.6268450331690125 43 245558]
                           [["ouvrag"] 2.5899978808811147E-5 1.5430965532355925 53 245558]
                           [["nient"] 2.459753787697333E-5 1.188163290153787 21 245558]
                           [["atendaient"] 2.2511539543388498E-5 1.1327401261733598 23 245558]
                           [["gentil"] 2.142064805841365E-5 2.475396543851236 361 245558]
                           [["quand" "beaucoup"] 2.122883965421235E-5 1.2911839914641536 47 245558]
                           [["doctorat"] 2.0139103814520096E-5 1.275873499655917 52 245558]
                           [["être" "mechant"] 1.791774075979302E-5 0.9705418063077474 25 245558]],
    :cluster-id 11}
   {:n-docs-in-cluster 149.60131212963879,
    :characteristic-words [[["buletin"] 4.4268094531593055E-4 17.34905824233528 154 245558]
                           [["imprim"] 3.539462842184777E-4 14.256647013504894 140 245558]
                           [["venezuela"] 1.3031407072253316E-4 6.6150682097807865 148 245558]
                           [["imprimant"] 1.2891882705022215E-4 5.027792653324076 42 245558]
                           [["buletin" "vote"] 8.299383386544713E-5 3.215710319538034 26 245558]
                           [["baril"] 8.062958380456245E-5 3.3133458494607644 34 245558]
                           [["petrol"] 6.683408319491102E-5 5.43304841411798 434 245558]
                           [["vignet"] 6.298895101717987E-5 2.890369988663422 45 245558]
                           [["pirat"] 5.330090671293655E-5 3.2561179414452037 127 245558]
                           [["crit'ai"] 5.3275936135891225E-5 2.2916825836694463 28 245558]
                           [["é"] 5.205097769206586E-5 2.1796639334130705 24 245558]
                           [["parti" "pirat"] 4.766393657393807E-5 2.622847753445495 75 245558]
                           [["depouil"] 4.3140159254344626E-5 2.0492671977814054 36 245558]
                           [["vote"] 3.980713445130013E-5 7.166506164071284 2035 245558]
                           [["cendr"] 3.826292793869361E-5 1.7524818882795945 27 245558]
                           [["papi"] 3.74357930668541E-5 4.002501135214459 542 245558]
                           [["las"] 3.173600892860014E-5 1.8366157113306483 61 245558]
                           [["chavez"] 2.850400858416327E-5 1.405093421644183 28 245558]
                           [["digital"] 2.7057776891010327E-5 1.4938937183667602 43 245558]
                           [["co"] 2.425826949525693E-5 1.9528683708128174 151 245558]],
    :cluster-id 22}
   {:n-docs-in-cluster 142.94214347443267,
    :characteristic-words [[["bouteil"] 6.197968798873632E-4 24.784367381589163 257 245558]
                           [["consign"] 4.535588241591452E-4 16.828671915109336 124 245558]
                           [["plast"] 1.4078425734623604E-4 9.001161660099148 425 245558]
                           [["vere"] 1.3602652179705785E-4 7.725279763660375 259 245558]
                           [["canet"] 1.1174807811676832E-4 4.191977322765278 31 245558]
                           [["recyclag"] 9.81092502564656E-5 5.067675619122037 125 245558]
                           [["bouteil" "plast"] 9.755901537845772E-5 3.6592866588058497 27 245558]
                           [["fight"] 5.064157737755606E-5 2.131320690975169 25 245558]
                           [["logo"] 4.67562311806842E-5 2.3757580683537314 55 245558]
                           [["bier"] 4.214468965115653E-5 2.765629160335908 137 245558]
                           [["recycl"] 3.704646971683205E-5 2.451444594054557 124 245558]
                           [["alu"] 3.6788230080950224E-5 1.758678085766698 33 245558]
                           [["alemagn"] 3.575706029980419E-5 4.317435106563341 748 245558]
                           [["magasin"] 2.8306685808110466E-5 2.4506533273219273 232 245558]
                           [["tri"] 2.7719346710213325E-5 1.9829487121399212 122 245558]
                           [["centim"] 2.3789524988890656E-5 1.5990154856290237 84 245558]
                           [["system"] 2.3613624703419656E-5 6.075284126976081 2567 245558]
                           [["conteneu"] 2.1136661915693855E-5 1.1579742763831196 34 245558]
                           [["automat"] 2.1134854482005028E-5 2.0807535504439536 253 245558]
                           [["vin"] 1.8979856596434974E-5 1.8684600562016047 227 245558]],
    :cluster-id 23}
   {:n-docs-in-cluster 137.28588647707625,
    :characteristic-words [[["moust"] 4.0056123912057297E-4 13.240487952390657 60 245558]
                           [["pieton"] 3.03675752121306E-4 13.586421697891527 218 245558]
                           [["pasag" "pieton"] 1.7840973503780236E-4 6.220373308091505 35 245558]
                           [["tigr"] 1.6390706494883757E-4 5.380178263647902 23 245558]
                           [["automobilist"] 1.2507049263714778E-4 6.66995602693999 191 245558]
                           [["pasag"] 9.997079253917796E-5 8.75326287596759 903 245558]
                           [["citron"] 6.440089305324338E-5 2.562045834236273 25 245558]
                           [["piqu"] 6.381124801623297E-5 3.72402043341543 139 245558]
                           [["travers"] 5.452943867267565E-5 3.773435535643219 224 245558]
                           [["piqur"] 3.9348041871230255E-5 1.6532794279488798 20 245558]
                           [["deresponsabilis"] 3.7720300201113735E-5 1.6455373516923761 23 245558]
                           [["antispecist"] 3.0483128599087528E-5 1.3783135405466422 22 245558]
                           [["verbalis"] 2.782901925675943E-5 1.519455864825996 46 245558]
                           [["proximit"] 2.733915294285405E-5 1.9857677180471667 132 245558]
                           [["vacinal"] 1.9758082978930946E-5 0.9934856323889807 23 245558]
                           [["aret"] 1.7048832888902354E-5 5.471459152027026 2871 245558]
                           [["calendri"] 1.6680669393120615E-5 1.0002426110399225 40 245558]
                           [["flic" "a"] 1.496403113900556E-5 0.8033001564998935 23 245558]
                           [["quart"] 1.4808538225762405E-5 1.2486537573076868 116 245558]
                           [["jamai" "avoi"] 1.3943539212619635E-5 0.8524656441111 36 245558]],
    :cluster-id 63}
   {:n-docs-in-cluster 135.19554619699753,
    :characteristic-words [[["liberalism"] 2.419401033464906E-4 11.643803541481589 245 245558]
                           [["capitalism"] 1.6107220054749696E-4 10.3022311973763 518 245558]
                           [["monopol"] 1.2475868547913785E-4 6.363915615741601 160 245558]
                           [["concurenc"] 8.621413537286335E-5 6.0641953976540375 384 245558]
                           [["neoliberalism"] 6.678143723581886E-5 2.93471930014392 43 245558]
                           [["cartel"] 6.133779908585116E-5 2.547851107839839 30 245558]
                           [["contrepart"] 4.207487869426624E-5 2.322030916227968 74 245558]
                           [["entent"] 3.7082181541744225E-5 1.7484130323848468 33 245558]
                           [["libr"] 3.559943601873922E-5 4.881303840947395 1082 245558]
                           [["conivenc"] 3.460052881603358E-5 1.5323608365421064 23 245558]
                           [["néo" "liberalism"] 3.0094968425995253E-5 1.3186786562903807 19 245558]
                           [["a" "develop"] 2.3278658044325468E-5 1.123874644751818 23 245558]
                           [["francai" "a"] 2.3256930475614238E-5 1.7153268284211465 120 245558]
                           [["devis"] 2.155638248407636E-5 1.1598183203990677 34 245558]
                           [["hyp"] 1.960357759034706E-5 2.174422115805555 345 245558]
                           [["1980"] 1.752243980785405E-5 1.0267635229998047 39 245558]
                           [["problem" "plutot"] 1.727593203449708E-5 0.9592796587335755 31 245558]
                           [["hydrocarbur"] 1.704546274712039E-5 0.8557792938229116 20 245558]
                           [["omnipresent"] 1.625548723277817E-5 0.9089231299327291 30 245558]
                           [["meurtri"] 1.5691951022705E-5 1.0158686471435259 51 245558]],
    :cluster-id 52}
   {:n-docs-in-cluster 128.14549193064653,
    :characteristic-words [[["schizophren"] 2.586846918336324E-4 9.786151448725887 85 245558]
                           [["parcoursup"] 1.3050302292230004E-4 4.8894121358132425 40 245558]
                           [["soufrant"] 6.564648441835172E-5 2.548217917310072 24 245558]
                           [["etudiant"] 6.081741725211948E-5 5.533757038639293 654 245558]
                           [["apb"] 4.35134094200814E-5 1.7849621065837662 21 245558]
                           [["roch"] 3.691869436939048E-5 1.797280658605573 40 245558]
                           [["myth"] 3.351158430506783E-5 2.467910418890567 182 245558]
                           [["decapit"] 3.3006329050901465E-5 1.5228373225123883 28 245558]
                           [["marian"] 3.174174261403109E-5 1.9513985286884938 90 245558]
                           [["malad"] 2.990130969655891E-5 3.598496704561542 691 245558]
                           [["convention" "genev"] 1.879605765099223E-5 0.9318227707674193 22 245558]
                           [["boxeu"] 1.6529320945804157E-5 1.091834699422731 61 245558]
                           [["infograph"] 1.597745931600572E-5 1.079684582175956 64 245558]
                           [["genev"] 1.4802734583933136E-5 0.9387533009850951 47 245558]
                           [["macdo"] 1.413484934880381E-5 0.8273038799874732 33 245558]
                           [["cagnot"] 1.3695602249378172E-5 1.1289560207859086 107 245558]
                           [["dit" "bone"] 1.3505518120425497E-5 0.6986897963089754 19 245558]
                           [["spontanement"] 1.3433928603487702E-5 0.7709073967058229 29 245558]
                           [["garag"] 1.2704209252568932E-5 0.6980331047393983 23 245558]
                           [["paneau" "publicitair"] 1.2537561794770391E-5 0.6983473733619148 24 245558]],
    :cluster-id 96}
   {:n-docs-in-cluster 126.18176621133172,
    :characteristic-words [[["qi"] 3.6056727359897595E-4 13.260753026046043 105 245558]
                           [["inteligenc"] 2.4278794136920953E-4 11.209837303000516 218 245558]
                           [["corel"] 7.726765143491535E-5 4.900191932883119 253 245558]
                           [["reusit"] 6.377030305547773E-5 3.7637405711825376 158 245558]
                           [["conform"] 3.992296123051138E-5 2.1729961282498262 71 245558]
                           [["cash" "investig"] 3.5332644773785477E-5 1.8388382377731372 52 245558]
                           [["genet"] 3.144267652391633E-5 2.2031341950371455 146 245558]
                           [["scolair"] 3.106964317139971E-5 2.352402918356838 188 245558]
                           [["proletariat"] 2.9501135499111417E-5 1.293823024749255 20 245558]
                           [["choc"] 2.744421538316416E-5 1.8593139170263793 113 245558]
                           [["nobles"] 2.66833042750457E-5 1.2650092740982726 26 245558]
                           [["luxe"] 2.6394099056756617E-5 1.90648720241904 136 245558]
                           [["investig"] 2.617502019182545E-5 1.8622607632550015 128 245558]
                           [["cash"] 2.4020432402556066E-5 1.8699033282805766 159 245558]
                           [["corel" "entr"] 2.257150716950332E-5 1.2781349953773737 47 245558]
                           [["clas" "social"] 2.2498443143935745E-5 1.566787911024722 102 245558]
                           [["causalit"] 2.2259272581664005E-5 1.5193494706284625 94 245558]
                           [["ital"] 2.1345248737793254E-5 1.8902842156115154 212 245558]
                           [["bourgeois"] 2.099762249907315E-5 1.5758310512027092 123 245558]
                           [["max"] 2.0472677549426832E-5 1.886460979622137 229 245558]],
    :cluster-id 45}
   {:n-docs-in-cluster 121.67384650198228,
    :characteristic-words [[["colomb"] 2.4345485957839516E-4 9.251564204745415 86 245558]
                           [["gerard"] 1.423724571101518E-4 5.7355931268598255 67 245558]
                           [["gerard" "colomb"] 1.2103460621010372E-4 4.050263922404101 21 245558]
                           [["talent"] 7.490793898652821E-5 3.462956619191026 68 245558]
                           [["nid"] 7.097746029526876E-5 3.0290658815301614 44 245558]
                           [["lyon"] 5.059107256391582E-5 3.5201557132343155 239 245558]
                           [["start" "up"] 4.026556686096555E-5 2.231723593387898 80 245558]
                           [["start"] 3.7934279025353054E-5 2.3188894365940156 111 245558]
                           [["instinct"] 3.687654780192007E-5 1.8498035614720842 48 245558]
                           [["demision"] 3.027683154043989E-5 2.5560814046315303 270 245558]
                           [["up"] 3.0153750252212563E-5 2.2678226104801036 185 245558]
                           [["nouil"] 2.877044864619486E-5 1.2966773397483047 23 245558]
                           [["embalag"] 2.5102433643000785E-5 1.7349739421472803 115 245558]
                           [["manquent"] 2.446363241005202E-5 1.471202546125701 67 245558]
                           [["trahison"] 2.3118913364402263E-5 1.3351291735752426 54 245558]
                           [["gramair"] 1.9679242789694476E-5 1.0404827264529484 32 245558]
                           [["yaourt"] 1.7913809690956253E-5 1.0540946998291938 45 245558]
                           [["bourin"] 1.7177498170842416E-5 0.8408988204946807 20 245558]
                           [["interieu"] 1.5638916000836056E-5 1.9755767550762076 428 245558]
                           [["senti"] 1.54208353927085E-5 1.5168942060672364 216 245558]],
    :cluster-id 99}
   {:n-docs-in-cluster 114.64932064107603,
    :characteristic-words [[["clip"] 1.7223177409634444E-4 6.366024455980867 55 245558]
                           [["rap"] 1.1740696130221884E-4 5.077271212396211 83 245558]
                           [["rom"] 9.443356808474905E-5 3.824554789863732 48 245558]
                           [["rapeu"] 7.645726338133783E-5 2.7056518276294272 19 245558]
                           [["roumain"] 6.050415479124955E-5 2.4726153698685605 32 245558]
                           [["agisant"] 4.752841520173391E-5 1.9093729711422065 23 245558]
                           [["cailou"] 4.554331398701379E-5 2.1096486687497906 44 245558]
                           [["expres"] 2.838429564163379E-5 1.5217611261689223 52 245558]
                           [["groupuscul"] 2.3492352275767542E-5 1.3314386099217181 54 245558]
                           [["samsung"] 2.0647912012899602E-5 1.0406619181892076 29 245558]
                           [["cofr"] 2.0177924191943156E-5 1.2764322306088378 71 245558]
                           [["noël"] 1.758712830725144E-5 1.3856402992262964 133 245558]
                           [["y'a" "bien"] 1.7352591417237224E-5 0.8716451726289822 24 245558]
                           [["bien" "truc"] 1.5217546749966143E-5 0.8750725562601623 37 245558]
                           [["pété"] 1.5058237760155399E-5 0.9432658797858361 51 245558]
                           [["minorit"] 1.4512401388184354E-5 1.808125982920826 407 245558]
                           [["mecreant"] 1.4138189520633906E-5 0.8167444245780857 35 245558]
                           [["ça" "defend"] 1.2815788948610657E-5 0.6621090258632076 20 245558]
                           [["odieu"] 1.2754662816888418E-5 0.7022751785998544 26 245558]
                           [["bnp"] 1.2583198126470997E-5 0.7079037863144497 28 245558]],
    :cluster-id 36}
   {:n-docs-in-cluster 114.1701402565564,
    :characteristic-words [[["manag"] 2.0758668972293767E-4 9.493395615350082 196 245558]
                           [["mutation"] 1.3860862593946706E-4 4.957688135477769 37 245558]
                           [["rh"] 5.51679049416217E-5 2.65635273429719 64 245558]
                           [["identitair"] 4.9008731698442845E-5 2.650863001099339 94 245558]
                           [["identit"] 3.378451147897921E-5 2.6170027751839995 244 245558]
                           [["blaireau"] 3.1622163722443836E-5 1.5362714041494423 38 245558]
                           [["campu"] 2.5210460451287678E-5 1.2899383730187377 38 245558]
                           [["giscard"] 2.3435460657048567E-5 1.1552633845297662 30 245558]
                           [["merdiqu"] 2.2070587269737083E-5 1.5582066181843963 116 245558]
                           [["malais"] 2.1574570277221178E-5 1.276254846229206 59 245558]
                           [["couri"] 1.7367336351785737E-5 1.424232861768106 150 245558]
                           [["consist"] 1.4226714057557002E-5 1.3466960739299925 190 245558]
                           [["bonbon"] 1.3910301724429086E-5 0.8339701211657894 40 245558]
                           [["agent"] 1.3180288161607212E-5 1.4334998342828005 260 245558]
                           [["modific"] 1.1071011947312581E-5 1.0088890073204586 132 245558]
                           [["coment" "ça" "pase"] 1.0376910774038961E-5 0.6584919406733307 37 245558]
                           [["pôle" "emploi"] 9.982411146787148E-6 0.7747717535347535 72 245558]
                           [["croi" "plu"] 9.804387582541124E-6 0.6223841963774275 35 245558]
                           [["construction"] 9.555184575578413E-6 1.1737812253594062 259 245558]
                           [["renvo"] 9.358472379150662E-6 0.7686324121277724 81 245558]],
    :cluster-id 5}
   {:n-docs-in-cluster 113.21061898087083,
    :characteristic-words [[["muel"] 1.2463194104786642E-4 5.024657732510914 63 245558]
                           [["ecos"] 8.836419859122209E-5 3.261993367388425 28 245558]
                           [["niqu"] 7.398748515824449E-5 4.02211126771554 147 245558]
                           [["cnil"] 4.217761659530521E-5 1.9815085312141811 44 245558]
                           [["gaucho"] 3.9592011968823254E-5 1.9406233947448928 50 245558]
                           [["facho"] 3.0766890108974154E-5 2.5253341078060103 270 245558]
                           [["ag"] 3.0298435850501307E-5 1.3307966664437887 23 245558]
                           [["irland"] 2.917497011548087E-5 1.8996715644901603 116 245558]
                           [["prolo"] 2.8193634223247846E-5 1.5231715024983323 54 245558]
                           [["europeist"] 2.8087700236732738E-5 1.4073918814546813 39 245558]
                           [["obstruction"] 2.482881864439377E-5 1.1377115829184274 23 245558]
                           [["putin"] 2.347819370862505E-5 1.1088837805137042 25 245558]
                           [["independanc"] 2.346399669959573E-5 1.749480234279127 150 245558]
                           [["incertitud"] 2.14054696094073E-5 1.0990714776291288 33 245558]
                           [["brexit"] 1.9405447756082328E-5 2.2497853800981336 459 245558]
                           [["irlandai"] 1.9385506491901607E-5 0.9885465634636691 29 245558]
                           [["tind"] 1.737598486958869E-5 0.9016094619740751 28 245558]
                           [["enquet"] 1.6337760288663572E-5 2.571912481037992 813 245558]
                           [["irland" "nord"] 1.4884116746853235E-5 0.7570711756695769 22 245558]
                           [["bresil"] 1.4361023660265648E-5 1.138186605736609 112 245558]],
    :cluster-id 91}
   {:n-docs-in-cluster 110.89945164515032,
    :characteristic-words [[["fromag"] 2.5240921797220137E-4 10.065814194789679 126 245558]
                           [["whatsap"] 1.0028223731641406E-4 3.754198519546733 35 245558]
                           [["fête"] 8.630557784731E-5 4.595597308006017 161 245558]
                           [["pirat"] 7.870267210617621E-5 4.052436182284402 127 245558]
                           [["specialit"] 6.606414121000138E-5 3.1893098040763057 80 245558]
                           [["parti" "pirat"] 5.9054460425946725E-5 2.882290500911894 75 245558]
                           [["clef"] 5.093501432885256E-5 2.389789476912739 54 245558]
                           [["hach"] 4.328987585772506E-5 1.8941300765075306 33 245558]
                           [["app"] 4.0326254267142314E-5 2.217048630683861 85 245558]
                           [["aujourd'hui"] 3.429541802357078E-5 4.952327289747512 1442 245558]
                           [["fournit"] 3.2748096257183626E-5 1.62907381582678 45 245558]
                           [["stream"] 2.3704938774965142E-5 1.2583032819045321 43 245558]
                           [["camembert"] 1.9578639813789753E-5 1.0216436557566657 33 245558]
                           [["twitch"] 1.810404176695677E-5 0.910368255339317 26 245558]
                           [["talk"] 1.7854995730862654E-5 0.9085593169874522 27 245558]
                           [["paf"] 1.741649557863295E-5 1.0565562703890081 54 245558]
                           [["mefi"] 1.6212666823309935E-5 0.9279570500802874 40 245558]
                           [["yep"] 1.5653344497084526E-5 1.232345800733513 122 245558]
                           [["jai"] 1.55072396083155E-5 0.9455109854769308 49 245558]
                           [["show"] 1.4702219310320022E-5 0.9554212109549414 59 245558]],
    :cluster-id 83}
   {:n-docs-in-cluster 110.08242080658243,
    :characteristic-words [[["http" "en.wikipedia.org"] 3.8460553699506267E-4 14.493292017089045 147 245558]
                           [["en.wikipedia.org"] 3.8460553699506267E-4 14.493292017089045 147 245558]
                           [["en.wikipedia.org" "wiki"] 3.71296059237804E-4 13.906532479467014 137 245558]
                           [["http" "en.wikipedia.org" "wiki"] 3.71296059237804E-4 13.906532479467014 137 245558]
                           [["wiki"] 3.341481279216449E-4 17.99150721577234 701 245558]
                           [["http"] 1.4670055680510163E-4 20.31079849498795 6010 245558]
                           [["link" "http"] 4.31782740424342E-5 1.811468456416038 27 245558]
                           [["http" "en.m.wikipedia.org" "wiki"] 3.395563145473183E-5 1.5085766304717334 28 245558]
                           [["en.m.wikipedia.org" "wiki"] 3.395563145473183E-5 1.5085766304717334 28 245558]
                           [["en.m.wikipedia.org"] 3.3639784489784136E-5 1.5086158146598616 29 245558]
                           [["http" "en.m.wikipedia.org"] 3.3639784489784136E-5 1.5086158146598616 29 245558]
                           [["desktop"] 2.4994959615986037E-5 1.147386301868038 24 245558]
                           [["pv"] 2.446594464904344E-5 1.2424645112819264 37 245558]
                           [["meto"] 1.4692024446896074E-5 0.9259101057357371 53 245558]
                           [["move"] 1.4599146708037768E-5 0.7909821461609052 29 245558]
                           [["link"] 1.3389931728877147E-5 1.9212110758623953 552 245558]
                           [["wikipedia"] 1.2812218567269384E-5 1.6961887125006012 435 245558]
                           [["timid"] 1.2098655195041767E-5 0.6418894028182157 22 245558]
                           [["biden"] 1.06165991339063E-5 0.5997596420507655 25 245558]
                           [["transparent"] 9.81818129176977E-6 0.706680002307202 57 245558]],
    :cluster-id 47}
   {:n-docs-in-cluster 107.21886089683065,
    :characteristic-words [[["numeru"] 1.9462711619250457E-4 6.409021630773321 35 245558]
                           [["clausu"] 1.9419076565770424E-4 6.3318486970607255 33 245558]
                           [["numeru" "clausu"] 1.9419076565770424E-4 6.3318486970607255 33 245558]
                           [["crech"] 5.713681103932701E-5 2.5210223196268067 47 245558]
                           [["medecin"] 5.444851262864647E-5 5.6189304047100865 1009 245558]
                           [["nov"] 3.570423259346197E-5 1.4581986452872868 20 245558]
                           [["apocalyps"] 2.3232617169284195E-5 1.2746303825566874 50 245558]
                           [["100" "ans"] 1.5184637632129072E-5 0.9823683571299193 62 245558]
                           [["uranium"] 1.3624159894910087E-5 1.1710666616591188 145 245558]
                           [["goufr"] 1.357833247002567E-5 0.7793104620940489 35 245558]
                           [["enr"] 1.3533840349411186E-5 0.8956026053438966 60 245558]
                           [["penu"] 1.3088940321901582E-5 0.9590170577002779 83 245558]
                           [["internat"] 1.0884225750870322E-5 0.6130755657284888 26 245558]
                           [["nucleair" "si"] 9.872930104564727E-6 0.5405630575088095 21 245558]
                           [["expansion"] 9.380321177437749E-6 0.5738803251954032 31 245558]
                           [["oup"] 9.165790186461617E-6 0.6242214438923536 45 245558]
                           [["parl" "nom"] 8.550272062522214E-6 0.4731956424053323 19 245558]
                           [["demantel"] 8.539015913333267E-6 0.7268313218853723 88 245558]
                           [["orient" "ver"] 8.279067117051425E-6 0.5451939383988633 36 245558]
                           [["font" "parti"] 8.221486966076066E-6 0.47648675187331624 22 245558]],
    :cluster-id 57}
   {:n-docs-in-cluster 106.15167480612635,
    :characteristic-words [[["camera"] 1.1118335871701697E-4 5.8144259973699155 202 245558]
                           [["invisibl"] 1.0408571503767866E-4 4.4389551953246045 74 245558]
                           [["sfam"] 1.0151047328752112E-4 3.6782466211172964 31 245558]
                           [["rose"] 9.482823488501761E-5 3.9776495355284913 62 245558]
                           [["surveilanc"] 8.706479261174488E-5 4.247511426692785 116 245558]
                           [["ufc"] 4.724292632541684E-5 1.8566471877306796 22 245558]
                           [["licorn"] 4.624148845675056E-5 1.824905028972427 22 245558]
                           [["fnac"] 4.458199209401818E-5 1.909581308247936 32 245558]
                           [["delinquanc"] 3.850658841327362E-5 2.1599381421259656 92 245558]
                           [["vendeu"] 3.153804087631905E-5 2.0789485015090547 140 245558]
                           [["chercheu"] 2.899677309307555E-5 2.3846513825212443 273 245558]
                           [["trouv" "argument"] 2.522916396367498E-5 1.1470268023565495 24 245558]
                           [["sodom"] 1.888444884868678E-5 0.897331607606897 22 245558]
                           [["anal"] 1.782737141348481E-5 0.8985122438878375 27 245558]
                           [["piza"] 1.6857172495530343E-5 0.9787933352502767 46 245558]
                           [["asuranc"] 1.662003294879491E-5 1.9635438669660827 440 245558]
                           [["cach"] 1.659938411469053E-5 2.050913375270443 493 245558]
                           [["vulgair"] 1.6254160568566524E-5 0.9022442201341754 37 245558]
                           [["visait"] 1.5690971532791344E-5 0.7850411805476293 23 245558]
                           [["depui" "ane"] 1.4210118102186628E-5 1.3692968031480381 215 245558]],
    :cluster-id 80}
   {:n-docs-in-cluster 105.79964462220902,
    :characteristic-words [[["mahjoubi"] 7.658668566561284E-5 2.8504101523496748 27 245558]
                           [["mathemat"] 6.0849165878364836E-5 3.2252695497215633 116 245558]
                           [["dron"] 5.603260350228734E-5 2.7222420157663394 73 245558]
                           [["ignobl"] 4.453321333254198E-5 2.0574691517731156 46 245558]
                           [["mathematicien"] 4.1170473225304524E-5 1.6901192657086292 24 245558]
                           [["bouton"] 3.520371538238455E-5 2.2582565618618986 142 245558]
                           [["hashtag"] 3.0334446419050948E-5 1.3585795301619126 27 245558]
                           [["controlent"] 2.320756113521373E-5 1.2507258480429744 47 245558]
                           [["indecent"] 2.2951426712299397E-5 1.186694484576275 39 245558]
                           [["vile" "pari"] 1.9249666633786196E-5 0.9975542665722055 33 245558]
                           [["20" "000"] 1.860321652027378E-5 0.9648515690598648 32 245558]
                           [["apuy"] 1.8261386799233587E-5 1.3753347607131396 129 245558]
                           [["devon"] 1.7622914593414148E-5 0.9645432405749366 38 245558]
                           [["ali"] 1.7455270466400566E-5 0.9696432552419589 40 245558]
                           [["crot"] 1.526412651038818E-5 0.7679511666593446 23 245558]
                           [["libr" "servic"] 1.4517728225619912E-5 0.7670802502244636 27 245558]
                           [["deploy"] 1.4237360445486236E-5 0.9774276959325788 73 245558]
                           [["moisi"] 1.3867432898483426E-5 0.7658569315904188 31 245558]
                           [["technolog"] 1.3088817536692499E-5 1.576844485613596 365 245558]
                           [["ordur"] 1.2764790397811615E-5 0.9146943822230992 76 245558]],
    :cluster-id 77}
   {:n-docs-in-cluster 105.37230382481604,
    :characteristic-words [[["poutr"] 1.1930403645096874E-4 4.032178671138044 25 245558]
                           [["œuf"] 9.566601703079117E-5 4.0504522888550705 66 245558]
                           [["finis"] 7.644028056142445E-5 2.953708834364341 33 245558]
                           [["pail"] 6.83710193944316E-5 3.945685543689881 186 245558]
                           [["filon"] 5.6447739694598376E-5 3.7393917130895526 259 245558]
                           [["fictif"] 4.062700365809087E-5 2.0452805572842 62 245558]
                           [["contredit"] 3.988965907174985E-5 2.2372712984359615 96 245558]
                           [["emploi" "fictif"] 3.8279574772882505E-5 1.6317962929519596 27 245558]
                           [["rythm"] 2.6934181355351902E-5 1.8369846388316515 136 245558]
                           [["où" "ça"] 2.4697075222273462E-5 1.845508875052425 171 245558]
                           [["reconaitr"] 2.1414299340848097E-5 1.879448082278751 248 245558]
                           [["bien" "come" "ça"] 2.0079798362754482E-5 0.9304382303311715 21 245558]
                           [["continueront"] 2.005148815253672E-5 0.9294108630983795 21 245558]
                           [["trivial"] 1.8503763043467396E-5 0.9231459834123052 27 245558]
                           [["atribut"] 1.8334483113216157E-5 0.9166233303181984 27 245558]
                           [["marcheu"] 1.807707059415728E-5 0.9216740668567656 29 245558]
                           [["trop" "bien"] 1.6834043494371592E-5 0.9367533345356859 39 245558]
                           [["sacag"] 1.6443914485702107E-5 0.9373956463313002 42 245558]
                           [["eton"] 1.6078994798794655E-5 2.0850066993665863 543 245558]
                           [["guyan"] 1.5677801567181085E-5 0.9456220100279517 50 245558]],
    :cluster-id 12}
   {:n-docs-in-cluster 105.28091796573574,
    :characteristic-words [[["romain"] 2.7569386834662597E-4 10.333133489277786 105 245558]
                           [["chretien"] 1.8529228443953626E-4 8.744907026687235 219 245558]
                           [["empereu"] 9.061040442319077E-5 3.3689850865873416 32 245558]
                           [["christianism"] 4.521064776928825E-5 2.311905224519972 74 245558]
                           [["sophia"] 3.045776725202256E-5 1.4304696936023804 34 245558]
                           [["alphabet"] 2.6250414301017336E-5 1.248402630682205 31 245558]
                           [["arab"] 2.524501080422223E-5 2.8399882731625516 595 245558]
                           [["sophia" "chikirou"] 2.0429764754823464E-5 0.9429443719147015 21 245558]
                           [["exil"] 1.916478336555852E-5 1.1297254393690785 56 245558]
                           [["ecrivent"] 1.5713366520684127E-5 0.9518699008212745 51 245558]
                           [["empir"] 1.5347147242210368E-5 1.2512020943617255 141 245558]
                           [["croisad"] 1.4999912713344922E-5 0.7715496031191713 25 245558]
                           [["chikirou"] 1.4890443814686433E-5 0.955210805603573 60 245558]
                           [["influent"] 1.4872264847997675E-5 0.8421848639452025 37 245558]
                           [["rôle"] 1.3898956914625699E-5 1.745286905924025 433 245558]
                           [["conaisent"] 1.3830766389387114E-5 1.292708669614294 193 245558]
                           [["jlm"] 1.3519407267783765E-5 0.9550321909399766 77 245558]
                           [["persecution"] 1.3375385575805157E-5 0.7443725058579942 31 245558]
                           [["spher"] 1.2301043343288312E-5 0.8506787528412382 65 245558]
                           [["manu"] 1.2118083403905508E-5 1.0611935112500444 139 245558]],
    :cluster-id 50}
   {:n-docs-in-cluster 104.34597966933084,
    :characteristic-words [[["bact"] 1.377255511111706E-4 5.01126000850937 44 245558]
                           [["envelop"] 7.958528141488799E-5 3.4496754947241346 62 245558]
                           [["antibiot"] 7.028812643723736E-5 3.116914724476887 61 245558]
                           [["urne"] 2.4908735605252627E-5 1.4552237044223777 71 245558]
                           [["ireversibl"] 2.1062429897457553E-5 0.9436662851617914 19 245558]
                           [["rivier"] 1.7743019252045023E-5 0.9783969357046288 40 245558]
                           [["douani"] 1.4925350377874985E-5 0.7448385790537806 22 245558]
                           [["toxiqu"] 1.4749657462960475E-5 1.222384951121388 144 245558]
                           [["descendu"] 1.4239755204945546E-5 0.759945396094691 28 245558]
                           [["resistanc"] 1.3514785831288123E-5 0.9594056035538124 79 245558]
                           [["poignard"] 1.3323838658881905E-5 0.6825212879368043 22 245558]
                           [["excedent"] 1.2819141115189697E-5 0.6955470109095948 27 245558]
                           [["cese"] 1.2777033956138523E-5 1.0625316009777044 126 245558]
                           [["epong"] 1.2608412502508604E-5 0.6988964464260665 29 245558]
                           [["marcheu"] 1.2517140733461427E-5 0.6950754880296262 29 245558]
                           [["permi"] 1.2095053860627925E-5 1.5994730584496881 432 245558]
                           [["ateli"] 1.1613234837797373E-5 0.6987260374616758 37 245558]
                           [["dilu"] 1.146171378381064E-5 0.7537480772382209 51 245558]
                           [["bone" "nouvel"] 1.1419929495819214E-5 0.9897425419299931 128 245558]
                           [["doigt" "mouil"] 1.0889358760688729E-5 0.6845482438672397 41 245558]],
    :cluster-id 40}
   {:n-docs-in-cluster 100.58546665437741,
    :characteristic-words [[["puisanc"] 1.0197501729288655E-4 6.2507784587247 372 245558]
                           [["discord"] 8.04674696010671E-5 3.3713253936435326 55 245558]
                           [["surfac"] 8.005648938945106E-5 4.689383793502046 243 245558]
                           [["pq"] 6.557477604323975E-5 2.700522415230218 41 245558]
                           [["anten"] 1.9238741662007397E-5 1.2372623289242435 82 245558]
                           [["solair"] 1.5321946694381305E-5 1.5317098655838202 272 245558]
                           [["admetant"] 1.4531705539373371E-5 0.771549162423683 29 245558]
                           [["m2"] 1.2254597123832756E-5 0.766599472378932 47 245558]
                           [["serveu"] 1.1263308040789996E-5 0.98109590420149 133 245558]
                           [["four"] 1.015712824701566E-5 0.7672278387075666 76 245558]
                           [["therm"] 9.585860884377129E-6 0.8765580896747722 131 245558]
                           [["quand" "question"] 9.08536205253014E-6 0.511145707215534 23 245558]
                           [["argument" "peu"] 8.83531467731069E-6 0.5110709402870232 25 245558]
                           [["50m"] 8.660477286672602E-6 0.48767007042792887 22 245558]
                           [["ocup"] 8.657512229413855E-6 1.421745247037623 531 245558]
                           [["choi" "polit"] 8.629114822389025E-6 0.5120591787846505 27 245558]
                           [["raison" "bien"] 8.358304705923664E-6 0.485286792122185 24 245558]
                           [["rouleau"] 7.71950466984389E-6 0.4473704885676878 22 245558]
                           [["si" "prend" "compt"] 7.388559988621979E-6 0.44742014074110237 25 245558]
                           [["terestr"] 7.364230263083929E-6 0.5591360179714935 56 245558]],
    :cluster-id 72}
   {:n-docs-in-cluster 98.95855777739041,
    :characteristic-words [[["huil"] 2.7100204128582654E-4 10.774507098310458 150 245558]
                           [["palm"] 1.5254209176666662E-4 5.480839950655056 48 245558]
                           [["huil" "palm"] 1.37414611446369E-4 4.705775176541371 33 245558]
                           [["huil" "esentiel"] 2.5599398637977944E-5 1.122853840489478 22 245558]
                           [["esentiel"] 1.4597075439586743E-5 1.6801940221184914 387 245558]
                           [["huil" "feu"] 1.430163124355524E-5 0.6891369898842596 19 245558]
                           [["oliv"] 7.0056296728278E-6 0.40454548599251045 20 245558]
                           [["rhon"] 6.680806187148516E-6 0.4773124168824695 42 245558]
                           [["danon"] 6.473571406407329E-6 0.4500558896067609 37 245558]
                           [["bon" "sant"] 6.047939517126767E-6 0.3625862809868624 20 245558]
                           [["indones"] 5.662243819828071E-6 0.3817100886967023 29 245558]
                           [["avantag"] 5.358288795536542E-6 1.1649394797762453 601 245558]
                           [["certain" "a"] 5.2891678111871565E-6 0.3448666391060097 24 245558]
                           [["system" "electoral"] 5.263234581671071E-6 0.34363695935621935 24 245558]
                           [["raison" "valabl"] 5.00741008376774E-6 0.3351989985750025 25 245558]
                           [["nutela"] 4.786280185878518E-6 0.3482033066019086 32 245558]
                           [["feu"] 4.734657601389303E-6 1.0441483610469038 546 245558]
                           [["win"] 4.433140022705971E-6 0.3359174462212996 34 245558]
                           [["manichen"] 4.306059857305672E-6 0.3377402255008287 37 245558]
                           [["take"] 4.2195376183757835E-6 0.33578018355825096 38 245558]],
    :cluster-id 82}
   {:n-docs-in-cluster 95.66897755081716,
    :characteristic-words [[["cuba"] 1.7510125751684508E-4 6.247445748919596 55 245558]
                           [["defil"] 7.952971174040625E-5 3.6956092865208765 94 245558]
                           [["poumon"] 3.114594307510911E-5 1.5869023759416654 55 245558]
                           [["cherchez"] 3.0127007102257602E-5 1.257407660909124 21 245558]
                           [["canc" "poumon"] 2.7840099292178108E-5 1.223745124620851 25 245558]
                           [["embargo"] 2.2968479600063603E-5 1.1534698526562168 38 245558]
                           [["canc"] 1.921081604273206E-5 1.9122948911536575 355 245558]
                           [["boulang"] 1.784595363791934E-5 1.2638385625392048 113 245558]
                           [["vacin" "contr"] 1.5644499614641746E-5 0.8317085984556005 33 245558]
                           [["contr" "canc"] 1.3598411741803061E-5 0.6577682437854679 19 245558]
                           [["jean" "luc"] 1.3251936824994898E-5 1.0084990583116056 107 245558]
                           [["luc"] 1.2022408250638578E-5 1.0208271753037823 138 245558]
                           [["vacin"] 1.1442374805416267E-5 1.4339823538408765 390 245558]
                           [["jean"] 9.320847423253259E-6 1.502447717647667 577 245558]
                           [["luc" "melenchon"] 8.82496724257699E-6 0.6740469772645646 72 245558]
                           [["jean" "luc" "melenchon"] 8.82496724257699E-6 0.6740469772645646 72 245558]
                           [["14" "juilet"] 7.792683133871041E-6 0.4595352242428918 25 245558]
                           [["tabac"] 5.950301882518341E-6 0.7381137351889057 197 245558]
                           [["loin" "idée"] 5.90686145851213E-6 0.43015058783594534 41 245558]
                           [["trip"] 5.637351395947239E-6 0.4250409671898644 44 245558]],
    :cluster-id 10}
   {:n-docs-in-cluster 94.5409572081118,
    :characteristic-words [[["ethiqu"] 1.8348949885535829E-4 8.596627300199101 234 245558]
                           [["5000"] 8.230228551901059E-5 3.678354474653414 82 245558]
                           [["bours"] 7.627946478423395E-5 4.063467917635008 167 245558]
                           [["like"] 3.2833854241175534E-5 2.0007369461920823 122 245558]
                           [["condition"] 3.116907114131956E-5 1.8831263871444042 112 245558]
                           [["be"] 2.7657908558506583E-5 2.02654059943937 200 245558]
                           [["touch"] 2.5061648564285444E-5 3.072190863488797 824 245558]
                           [["vant"] 2.4234774787209798E-5 1.3343549676435502 60 245558]
                           [["avi" "question"] 1.3829441703551264E-5 0.7006411848941154 24 245558]
                           [["benala"] 1.313097310013181E-5 1.8330790251957156 591 245558]
                           [["revenu"] 1.2940142958876855E-5 2.5052587548344176 1208 245558]
                           [["fait" "être"] 1.2377926342413414E-5 1.000284619502959 123 245558]
                           [["parent"] 1.1268253389047633E-5 2.3861094827827247 1262 245558]
                           [["partenariat"] 1.052156040341496E-5 0.6101687434141334 32 245558]
                           [["sterilis"] 1.0474907222545252E-5 0.5608373872874013 23 245558]
                           [["fabric"] 1.0045391056005479E-5 0.815012570745729 101 245558]
                           [["dont" "a"] 9.987048482533456E-6 0.711045801276545 65 245558]
                           [["recevoi"] 9.89892894345118E-6 0.9136591983027393 148 245558]
                           [["vraiment" "dire"] 9.819822130792584E-6 0.5603714875442525 28 245558]
                           [["acord" "beaucoup"] 9.785350108715458E-6 0.5151124743598117 20 245558]],
    :cluster-id 74}
   {:n-docs-in-cluster 93.61554709183665,
    :characteristic-words [[["thinkerview"] 1.0929274221654732E-4 4.320092232955808 60 245558]
                           [["amiant"] 1.0564467677969328E-4 3.6508718012745756 28 245558]
                           [["ciment"] 4.3340385133094905E-5 1.7342479770976156 25 245558]
                           [["comprehensibl"] 3.65365017971301E-5 1.9641264969829813 83 245558]
                           [["brilant"] 2.3183034771128222E-5 1.4222283190277005 89 245558]
                           [["gigantesqu"] 2.2078558559239687E-5 1.175683884004659 48 245558]
                           [["pige"] 2.033642706082174E-5 1.272163583259995 84 245558]
                           [["nah"] 2.0318168938848226E-5 0.9339728616478546 23 245558]
                           [["a" "home"] 2.0309041984529173E-5 0.9063657222901899 20 245558]
                           [["reptilien"] 1.8194767685407454E-5 0.9416276883088806 35 245558]
                           [["gueri"] 1.6857965441631484E-5 0.9384690972758319 44 245558]
                           [["infiltr"] 1.6604497250961685E-5 0.9430479119991 47 245558]
                           [["feminist"] 1.3941587616049522E-5 1.4794248999772424 314 245558]
                           [["perdent"] 1.36519605881541E-5 0.9501589107825827 83 245558]
                           [["vulgair"] 1.2978947746100644E-5 0.7386375764824573 37 245558]
                           [["incertitud"] 1.2160347031181344E-5 0.6836999122903392 33 245558]
                           [["embras"] 1.1929217899476173E-5 0.683836756145009 35 245558]
                           [["home" "pail"] 1.1728784149836122E-5 0.9256171535096861 109 245558]
                           [["gave"] 1.1206456281915612E-5 0.6880899587196297 43 245558]
                           [["ordur"] 1.086188667977564E-5 0.787418352841578 76 245558]],
    :cluster-id 0}
   {:n-docs-in-cluster 93.49885192215925,
    :characteristic-words [[["los"] 5.8206433204943155E-5 2.291492590682658 31 245558]
                           [["septembr"] 3.944446768855761E-5 2.295121211816011 124 245558]
                           [["leclerc"] 3.457953809557143E-5 1.71110474031103 55 245558]
                           [["centim"] 3.1667120368301244E-5 1.767465835132388 84 245558]
                           [["recens"] 3.1337620363640004E-5 1.611932342850278 59 245558]
                           [["asiat"] 3.067785236508108E-5 2.1703792533728596 199 245558]
                           [["kilo"] 3.0432248847526294E-5 1.8010044676499095 102 245558]
                           [["95"] 2.6890438911865086E-5 1.7308218010777123 124 245558]
                           [["59"] 2.5166753668412478E-5 1.1246529979696411 25 245558]
                           [["el"] 2.4781096711721726E-5 1.3953980402363098 68 245558]
                           [["mi"] 2.3886249693304967E-5 1.506520289724147 102 245558]
                           [["remarquera"] 1.6943208061899473E-5 0.9956328753873201 55 245558]
                           [["bureau"] 1.5991758735720008E-5 1.6570181499189889 338 245558]
                           [["chemin"] 1.521396773999581E-5 1.3193425110022565 191 245558]
                           [["droit" "fondamental"] 1.4880370485492213E-5 0.8130884594980136 36 245558]
                           [["25"] 1.4480382197614577E-5 1.830295299729303 517 245558]
                           [["noir"] 1.414877387860558E-5 1.9988395760232216 663 245558]
                           [["poel"] 1.3371521090241138E-5 0.6605954316355166 21 245558]
                           [["retenu"] 1.293760332611929E-5 1.0146145225712664 118 245558]
                           [["tout" "articl"] 1.224026547372737E-5 0.7386087275100396 44 245558]],
    :cluster-id 65}
   {:n-docs-in-cluster 90.0695744155695,
    :characteristic-words [[["thc"] 1.7755568549818277E-4 5.859463648869509 38 245558]
                           [["cbd"] 1.711017298427443E-4 5.3268961043919525 26 245558]
                           [["canabi"] 4.365801259952409E-5 3.0292205783576476 276 245558]
                           [["mecreant"] 3.2039356393024666E-5 1.4489576144401441 35 245558]
                           [["weed"] 2.5580858845583067E-5 1.2070869446836923 34 245558]
                           [["moin" "violent"] 1.9870780935715357E-5 0.8929406583807956 21 245558]
                           [["chos" "autr"] 1.691251092495686E-5 0.8997452647934624 38 245558]
                           [["flou"] 1.53951593216458E-5 1.082799843222527 101 245558]
                           [["chanvr"] 1.481642815107792E-5 0.7239787443775656 23 245558]
                           [["marseilais"] 1.4625646150762117E-5 0.7675601767446559 31 245558]
                           [["ça" "rend"] 1.3392626910158426E-5 1.0005892870493174 108 245558]
                           [["therapeut"] 1.2210610836817237E-5 0.7386673798580347 46 245558]
                           [["variet"] 1.2138416003322147E-5 0.7395562246846952 47 245558]
                           [["compagnon"] 1.2044748668572566E-5 0.6516621758314516 29 245558]
                           [["actif"] 1.1843448794748856E-5 1.3271708004688534 321 245558]
                           [["peut" "achet"] 1.087174189762577E-5 0.5818148858997993 25 245558]
                           [["nobl"] 8.978173741746448E-6 0.6352650682835526 60 245558]
                           [["cultiv"] 8.37638633622087E-6 0.7068023753840377 100 245558]
                           [["tau"] 8.349708095080344E-6 1.79640308973746 1011 245558]
                           [["hormon"] 7.635077999080045E-6 0.5147810860926485 43 245558]],
    :cluster-id 78}
   {:n-docs-in-cluster 89.68655509376097,
    :characteristic-words [[["fascist"] 9.033844338618771E-5 4.991243635217897 244 245558]
                           [["homard"] 5.562548748220056E-5 2.610751243318832 73 245558]
                           [["cuir"] 3.776811530518433E-5 1.6767707324081293 38 245558]
                           [["cran"] 3.2508457429560045E-5 1.804884590322595 88 245558]
                           [["cheveu"] 2.7605744179176606E-5 1.763193592866551 129 245558]
                           [["rang"] 2.72042165826384E-5 1.9519785547004507 193 245558]
                           [["aprè" "avoi"] 1.71562331866687E-5 1.7719925215812211 375 245558]
                           [["moralis"] 1.6841271194375194E-5 0.8667977386188156 33 245558]
                           [["perpetuel"] 1.5427996766612974E-5 0.767170071998877 26 245558]
                           [["dangereu"] 1.423543075213321E-5 1.9235285703339131 625 245558]
                           [["silenc"] 8.446610766949664E-6 0.8103321408508418 149 245558]
                           [["permetant"] 8.430395428591156E-6 0.7685729514918075 128 245558]
                           [["internaut"] 7.956191583661197E-6 0.5262709642316153 42 245558]
                           [["surtout" "trè"] 7.796216517192214E-6 0.4567846742198849 26 245558]
                           [["consultant"] 7.76598214770937E-6 0.5238541127489257 44 245558]
                           [["pauvr" "petit"] 7.712979154146649E-6 0.4345703390598922 22 245558]
                           [["rapel"] 7.341239173827563E-6 2.1632535605449976 1618 245558]
                           [["tonton"] 6.94397077520819E-6 0.4500157140314063 34 245558]
                           [["bon" "courag"] 6.7986811294138555E-6 0.5319538473793811 64 245558]
                           [["faisai" "referenc"] 6.640190957856763E-6 0.43909682029437974 35 245558]],
    :cluster-id 88}
   {:n-docs-in-cluster 88.76964117778635,
    :characteristic-words [[["vichy"] 8.134113897439923E-5 3.2941484584464398 53 245558]
                           [["prepa"] 6.361987299327229E-5 3.6006615947180056 190 245558]
                           [["architectur"] 1.966230442100765E-5 0.9743187882723792 33 245558]
                           [["xx"] 1.7505179207267206E-5 0.8294122285960519 24 245558]
                           [["show"] 1.3514516663213141E-5 0.8464407731859184 59 245558]
                           [["claus"] 1.2896171058313435E-5 0.8430400166910956 66 245558]
                           [["angois"] 1.0860391867697702E-5 0.6465839535613107 39 245558]
                           [["csp"] 9.030215705948912E-6 0.6034589019254373 50 245558]
                           [["puce"] 8.495239228007052E-6 0.5281405925190951 36 245558]
                           [["literair"] 8.40865266665651E-6 0.5354728594315311 39 245558]
                           [["v"] 8.083976955983868E-6 0.9751438610285972 269 245558]
                           [["no"] 6.981875183727226E-6 0.9071848560478994 280 245558]
                           [["majorit" "gen"] 6.071451599389682E-6 0.6232097528933345 131 245558]
                           [["guyan"] 5.94572173362104E-6 0.4482604584558774 50 245558]
                           [["requis"] 5.4010727507667125E-6 0.4292214153781444 54 245558]
                           [["question" "plutot"] 5.304909783626285E-6 0.37559870657601846 36 245558]
                           [["person" "pens"] 5.298810757111555E-6 0.3426980619312265 26 245558]
                           [["legal" "non"] 5.268563291489954E-6 0.32240738261510193 21 245558]
                           [["peu" "confirm"] 5.247389401163115E-6 0.3214503919672852 21 245558]
                           [["scolair"] 5.241315660943144E-6 0.6515962040637713 188 245558]],
    :cluster-id 58}
   {:n-docs-in-cluster 88.15231064075256,
    :characteristic-words [[["😂"] 1.1729228213793082E-4 3.8877748520067867 26 245558]
                           [["sat"] 2.005117394065191E-5 0.9739378759351391 31 245558]
                           [["voyant"] 1.3119755741549874E-5 1.0437654810491517 133 245558]
                           [["personag"] 1.302849601593839E-5 1.1354293777683424 176 245558]
                           [["boucli"] 1.2552687767278768E-5 0.7661397968543557 50 245558]
                           [["leve"] 1.2358660829788967E-5 0.7652138281385237 52 245558]
                           [["pb"] 1.2165766912861087E-5 0.7200073824235151 43 245558]
                           [["bien" "vrai"] 1.1126438096449934E-5 0.6205003164045039 31 245558]
                           [["exact" "pareil"] 1.1040091518876166E-5 0.606905681968524 29 245558]
                           [["ca" "bien"] 1.0798960932774555E-5 0.6253011054605875 35 245558]
                           [["toxicit"] 1.074101836187006E-5 0.6090017878968825 32 245558]
                           [["holywod"] 9.897895856117618E-6 0.5052444413978752 19 245558]
                           [["jambon"] 9.642300630475185E-6 0.5617211447726778 32 245558]
                           [["comencait"] 9.537737781154312E-6 0.548191020806118 30 245558]
                           [["é"] 9.408014303197328E-6 0.5135941825197801 24 245558]
                           [["decon"] 9.382477172253587E-6 0.9228090760911191 181 245558]
                           [["robe"] 9.217200528505859E-6 0.5056860571689409 24 245558]
                           [["san"] 9.158523313105085E-6 0.5131872044529284 26 245558]
                           [["hor" "pri"] 9.111111627348005E-6 0.5062885938217654 25 245558]
                           [["pa"] 8.138845990136867E-6 0.5145755685865309 37 245558]],
    :cluster-id 19}
   {:n-docs-in-cluster 86.03360883168064,
    :characteristic-words [[["glis"] 1.9972558980618782E-5 1.2122906344090514 80 245558]
                           [["ser"] 1.7215277703180876E-5 1.8111843189922936 413 245558]
                           [["3k"] 1.6632821112357726E-5 0.791613952458319 24 245558]
                           [["personag"] 1.466420973269926E-5 1.220345429355617 176 245558]
                           [["aportent"] 1.3850390632226366E-5 0.7974240136062426 45 245558]
                           [["preferai"] 1.2711826569494593E-5 0.6235774227415544 21 245558]
                           [["pakistan"] 1.1660954299245671E-5 0.6236839434439939 28 245558]
                           [["rpr"] 1.157503580032776E-5 0.6254097317066454 29 245558]
                           [["intrigu"] 1.1266212189122403E-5 0.5858165899765476 24 245558]
                           [["croirait"] 9.638902335027191E-6 0.6306644459444601 51 245558]
                           [["bien" "ecrit"] 9.202043400407221E-6 0.5596386365060031 37 245558]
                           [["euthanas"] 8.311274862968752E-6 0.543954651731721 44 245558]
                           [["fair" "beaucoup"] 8.309347617078099E-6 0.5153609883012951 36 245558]
                           [["comenc" "fair"] 8.198361667522379E-6 0.5672212492308087 53 245558]
                           [["privileg"] 8.05996219610007E-6 0.8393487322092478 187 245558]
                           [["vraiment" "bien"] 7.4938722961163245E-6 0.5725210538380254 68 245558]
                           [["rapelent"] 7.42167401884819E-6 0.4480966383023228 29 245558]
                           [["fair" "tour"] 7.103461714983578E-6 0.5731276249473787 77 245558]
                           [["belgiqu"] 6.850724853707524E-6 0.739057126458964 175 245558]
                           [["fur" "mesur"] 6.844489317891535E-6 0.4438131440799832 35 245558]],
    :cluster-id 29}
   {:n-docs-in-cluster 84.89298381765767,
    :characteristic-words [[["a" "lais"] 1.730653128523621E-5 1.017549517403951 62 245558]
                           [["debord"] 1.5032589031700044E-5 1.0226516007087527 93 245558]
                           [["maroc"] 1.1629144097207236E-5 0.8067004519975007 77 245558]
                           [["non" "clair"] 1.0777184758750842E-5 0.5645079144349221 24 245558]
                           [["cordon"] 1.0101039449537594E-5 0.6175104205295646 42 245558]
                           [["descendr"] 9.640334295735187E-6 0.7296141044988427 86 245558]
                           [["rest" "bien"] 8.89112439368938E-6 0.5064527650908716 28 245558]
                           [["black" "bloc"] 8.885272433824774E-6 0.510653675547088 29 245558]
                           [["être" "asez"] 8.460364234431743E-6 0.5717999562017859 51 245558]
                           [["cultur" "francais"] 6.7350560693226966E-6 0.44700109056291376 38 245558]
                           [["pouvoi" "executif"] 6.1063185832366945E-6 0.3513308861163653 20 245558]
                           [["administ"] 5.9322325551385E-6 0.8859220454956067 347 245558]
                           [["si" "elle"] 5.487878055935452E-6 0.5262626443336683 102 245558]
                           [["crot"] 5.463828609025834E-6 0.33524104120506865 23 245558]
                           [["naïf"] 5.430639303104223E-6 0.6032337221892113 152 245558]
                           [["fait" "pouvoi"] 5.30810515229907E-6 0.35572466120651075 31 245558]
                           [["habil"] 5.259156067118076E-6 0.5764382182205338 142 245558]
                           [["guichet"] 4.9300022622253414E-6 0.30344172441395595 21 245558]
                           [["merci" "beaucoup"] 4.841166734595101E-6 0.45348659865042107 84 245558]
                           [["bloc"] 4.822675370550963E-6 0.5416926886965064 139 245558]],
    :cluster-id 33}
   {:n-docs-in-cluster 82.56063949283451,
    :characteristic-words [[["chirac"] 5.50952087535056E-5 3.192103626281082 194 245558]
                           [["ss"] 5.4687188943023E-5 2.225604784048885 39 245558]
                           [["poursuivr"] 1.305060759921109E-5 0.86016753567829 74 245558]
                           [["specialit"] 1.271677433443627E-5 0.8626392877826539 80 245558]
                           [["anecdot"] 1.1702003478018808E-5 1.1814236076885538 260 245558]
                           [["saurait"] 1.1123112413029441E-5 0.6996291733098398 53 245558]
                           [["fictif"] 1.0593396162965861E-5 0.704505557709967 62 245558]
                           [["recit"] 1.0530238324875374E-5 0.7045773295984712 63 245558]
                           [["informat"] 1.0529234936773602E-5 1.1119278978605047 265 245558]
                           [["rein"] 9.74236859703926E-6 0.611384045342347 46 245558]
                           [["num"] 9.165574034573562E-6 0.8849144507711915 179 245558]
                           [["existant"] 8.17885854684404E-6 0.7172895120702064 120 245558]
                           [["personag"] 7.044970956698177E-6 0.7425334871575776 176 245558]
                           [["jacqu"] 6.630754277828221E-6 0.5483472086611341 81 245558]
                           [["lyce"] 6.571202282094207E-6 0.9592261988012724 375 245558]
                           [["complex"] 6.456216133120252E-6 0.916573165834285 345 245558]
                           [["cheval"] 6.410581859227563E-6 0.6471992675743402 142 245558]
                           [["influenceu"] 6.349064424044336E-6 0.4751304528516881 56 245558]
                           [["mail"] 5.782156825695842E-6 0.6459376606189519 169 245558]
                           [["donc" "ca"] 5.780983862690471E-6 0.37881467050251055 32 245558]],
    :cluster-id 54}
   {:n-docs-in-cluster 81.49373862868745,
    :characteristic-words [[["otag"] 3.451201303673532E-5 1.74948955876386 70 245558]
                           [["profeseu"] 3.227846218365783E-5 2.0769413750204593 171 245558]
                           [["sel"] 3.00056447285358E-5 1.6735170251078337 91 245558]
                           [["jouant"] 2.0976451756887782E-5 1.0248785959725992 36 245558]
                           [["certitud"] 1.7565451111426628E-5 1.1013930760534332 84 245558]
                           [["pyramid"] 1.725319594467461E-5 0.9187632717288025 43 245558]
                           [["airfranc"] 1.5748212426404916E-5 0.9189270394670883 57 245558]
                           [["100k"] 1.5601956873377044E-5 0.9165855369231646 58 245558]
                           [["satisfaisant"] 1.4569290731123938E-5 0.7587287838367757 33 245558]
                           [["hâte"] 1.4208229209462103E-5 0.9375487226590397 82 245558]
                           [["sufit" "lire"] 1.3829632245222247E-5 0.7587875569772236 39 245558]
                           [["bitcoin"] 1.3361393166039584E-5 0.9290674142553251 93 245558]
                           [["recolt"] 1.2370236253245528E-5 0.9335426858697087 114 245558]
                           [["lire" "comentair"] 1.2171420734847192E-5 0.7628707183982765 58 245558]
                           [["decision" "justic"] 1.2054546244640724E-5 0.6567601571338852 33 245558]
                           [["marcel"] 1.1168016197487564E-5 0.5958327626586275 28 245558]
                           [["iluminati"] 1.0993915945896326E-5 0.5985612881434105 30 245558]
                           [["masturb"] 1.0128085467592945E-5 0.5966953412641941 38 245558]
                           [["produisent"] 9.912450863028277E-6 0.7759745660037244 103 245558]
                           [["fai" "coment"] 9.741050357065482E-6 0.6262614201970597 51 245558]],
    :cluster-id 92}
   {:n-docs-in-cluster 80.93563138796809,
    :characteristic-words [[["anar"] 7.649501887290171E-5 2.9976406683682417 46 245558]
                           [["méga"] 1.7497232959402635E-5 0.9220144527151047 42 245558]
                           [["atendez"] 1.5754398544968497E-5 0.9374251277390755 62 245558]
                           [["catho"] 1.540416939488712E-5 1.1670145618260435 145 245558]
                           [["circulair"] 1.3644751350046572E-5 0.7306103994923668 35 245558]
                           [["fair" "metr"] 1.0845237726389567E-5 0.544028261380614 21 245558]
                           [["laisez"] 1.0586082509178238E-5 0.7577529721798151 82 245558]
                           [["65"] 1.0025733932573014E-5 0.7494083187290533 90 245558]
                           [["celebr"] 9.869398639370669E-6 0.7556842650885981 96 245558]
                           [["noi"] 9.154106681699865E-6 0.5449250089561822 36 245558]
                           [["ultra"] 8.308467112805307E-6 1.2791901869693505 548 245558]
                           [["a" "200"] 8.140177815021618E-6 0.45989098438323994 26 245558]
                           [["desou"] 7.99477943651955E-6 1.008530559067307 327 245558]
                           [["bute"] 7.890714744595954E-6 0.46173895179035485 29 245558]
                           [["mieu" "fair"] 7.635873409184146E-6 0.5516909195658057 61 245558]
                           [["coment" "font"] 7.539202543736974E-6 0.46508636344400534 34 245558]
                           [["plein" "gueul"] 6.803495707955257E-6 0.4021285770783359 26 245558]
                           [["asoc"] 6.7751749615267E-6 0.786857307624873 224 245558]
                           [["b"] 6.6837345275155835E-6 0.7803016005445453 224 245558]
                           [["manuel"] 5.5456506617909684E-6 0.5796643874514396 138 245558]],
    :cluster-id 46}
   {:n-docs-in-cluster 80.12106135896157,
    :characteristic-words [[["rtl"] 7.300665433310552E-5 2.496229662757507 21 245558]
                           [["intrusion"] 4.8408802220587487E-5 2.027064057251353 41 245558]
                           [["salpetri"] 4.40373294999909E-5 1.6512562372890773 21 245558]
                           [["pit"] 4.0977873932108305E-5 2.117132048280583 92 245558]
                           [["amput"] 3.908074840200566E-5 1.5373522696448578 24 245558]
                           [["gangren"] 1.67293333950113E-5 0.8518654920221952 35 245558]
                           [["actualit"] 1.457390974799605E-5 1.414960354731218 299 245558]
                           [["yve"] 1.010526512862462E-5 0.5850944537100218 36 245558]
                           [["regret"] 1.003242303614267E-5 0.8933020715847112 159 245558]
                           [["just" "con"] 9.32139702705323E-6 0.48838917533388965 22 245558]
                           [["journalist"] 9.315371566322173E-6 2.033603739651193 1308 245558]
                           [["tué"] 9.17313949802892E-6 0.9042734165634373 196 245558]
                           [["dira"] 7.221204934982142E-6 0.6902058615093059 141 245558]
                           [["1er" "mai"] 7.09961536410255E-6 0.397170891356751 22 245558]
                           [["entendr"] 7.077396066871439E-6 1.154647751488458 537 245558]
                           [["version"] 6.615730672274167E-6 1.0324523759971733 454 245558]
                           [["feminin"] 5.863592751524996E-6 0.6092379211479612 145 245558]
                           [["obtenu"] 5.671703888135773E-6 0.6183577651027002 160 245558]
                           [["mecontent"] 5.4418552248615415E-6 0.46132937880315933 74 245558]
                           [["formidabl"] 5.405263052314299E-6 0.4612136513997311 75 245558]],
    :cluster-id 16}
   {:n-docs-in-cluster 79.35608570673355,
    :characteristic-words [[["vili"] 6.617506521711114E-5 2.2989949358901467 21 245558]
                           [["cia"] 2.1454664418748062E-5 1.0172163203006603 33 245558]
                           [["politico"] 1.5110192071134641E-5 0.7217645539818134 24 245558]
                           [["charia"] 1.0783160495934571E-5 0.6408602582876741 43 245558]
                           [["aret" "delir"] 9.048597892733802E-6 0.4910678019459613 25 245558]
                           [["meh"] 6.649545399531158E-6 0.40817850603542094 30 245558]
                           [["furieu"] 6.132674185852817E-6 0.3811486435138185 29 245558]
                           [["philip"] 6.066742320134932E-6 0.7297827002997676 224 245558]
                           [["cotoy"] 5.828834513412623E-6 0.4093326442973671 43 245558]
                           [["placent"] 5.619919867060237E-6 0.33258622419020367 22 245558]
                           [["1" "si"] 5.45829675679E-6 0.3254472843956222 22 245558]
                           [["boudhist"] 5.295326901274926E-6 0.31054306217371613 20 245558]
                           [["spher"] 5.015945515084337E-6 0.41747746870532304 65 245558]
                           [["aprè" "guer"] 5.0044478647447155E-6 0.33757776758456437 32 245558]
                           [["declin"] 4.9817746146183645E-6 0.3853558981340211 51 245558]
                           [["a" "equivalent"] 4.791115402370469E-6 0.31214526587313995 27 245558]
                           [["rothschild"] 4.565337670920516E-6 0.3395515363375895 41 245558]
                           [["interpret"] 4.3026466977902356E-6 0.8191808427409898 459 245558]
                           [["farfelu"] 4.219467093600528E-6 0.314102069692984 38 245558]
                           [["rev"] 3.938530755601133E-6 0.43354669023733433 115 245558]],
    :cluster-id 13}
   {:n-docs-in-cluster 79.23855773373813,
    :characteristic-words [[["imaginez"] 3.434075125036476E-5 1.835955241056204 90 245558]
                           [["abusiv"] 3.0463426055934995E-5 1.3718127252498067 37 245558]
                           [["spectaculair"] 1.7835606514854732E-5 0.8868515445333068 34 245558]
                           [["louch"] 1.4422016311859895E-5 0.8980015418928551 69 245558]
                           [["bobo" "parisien"] 1.010166868601909E-5 0.5530646965147851 29 245558]
                           [["bobo"] 9.8212097804206E-6 0.9300281529438859 189 245558]
                           [["arogant"] 9.122756836599764E-6 0.5367448058613635 35 245558]
                           [["specialist"] 8.882149673343151E-6 0.9456774279293205 238 245558]
                           [["si" "non"] 7.862455418559608E-6 0.48070308744169143 35 245558]
                           [["aidera"] 6.569595851842365E-6 0.37363034145208074 22 245558]
                           [["homosexuel"] 6.2473767255891155E-6 0.7539856386620372 233 245558]
                           [["être" "simpl"] 5.601101044722112E-6 0.33163222915363677 22 245558]
                           [["devi"] 5.028686430993447E-6 0.3326262175480237 30 245558]
                           [["sondag"] 4.708523664687103E-6 1.107556921109356 770 245558]
                           [["bidon"] 4.56642927900569E-6 0.5780650348871069 192 245558]
                           [["foi" "vie"] 4.474204080404456E-6 0.31946019667914954 35 245558]
                           [["internet"] 4.126231840442807E-6 1.147640668318239 924 245558]
                           [["rempli"] 3.961393079916081E-6 0.610970782110228 267 245558]
                           [["allé" "voir"] 3.886277618693479E-6 0.30101990093493425 40 245558]
                           [["decouvert"] 3.505889185649591E-6 0.5563516044932433 252 245558]],
    :cluster-id 48}
   {:n-docs-in-cluster 79.12638469042147,
    :characteristic-words [[["friot"] 4.418781711999168E-5 1.6803574182183862 23 245558]
                           [["asie"] 2.8940888300917067E-5 1.7104479897920926 114 245558]
                           [["a" "chaqu" "foi"] 2.5916333085693535E-5 1.7168061958841236 157 245558]
                           [["a" "chaqu"] 2.2880647261304746E-5 1.735583288482834 222 245558]
                           [["çà"] 1.7233840896330177E-5 0.9758762939960395 57 245558]
                           [["espec"] 1.683391304773546E-5 1.8222634516692524 475 245558]
                           [["chaqu" "foi"] 1.4987684303108262E-5 1.8420211228390126 590 245558]
                           [["bernard"] 1.2184418489087287E-5 0.8323759687802155 82 245558]
                           [["sembl" "a"] 1.1656243764732004E-5 0.7299278031149601 57 245558]
                           [["salair" "vie"] 9.597171525001745E-6 0.4871148506543934 20 245558]
                           [["emanuel" "todd"] 8.774407558567211E-6 0.44932781675632166 19 245558]
                           [["tradition"] 6.934317071652027E-6 0.672537092438519 143 245558]
                           [["todd"] 6.88850317345259E-6 0.45811276940345186 42 245558]
                           [["chaqu"] 6.856703498175909E-6 2.253089393341108 2078 245558]
                           [["local"] 5.581395097584152E-6 1.1550700712757336 710 245558]
                           [["emergenc"] 5.303788781050797E-6 0.3392119928342041 28 245558]
                           [["pédé"] 5.1624917497558945E-6 0.31203888918952394 22 245558]
                           [["autr" "quand"] 5.120748818436029E-6 0.345890992960157 33 245558]
                           [["grenouil"] 5.016758815151387E-6 0.3125476525391472 24 245558]
                           [["intoleranc"] 4.805261028546955E-6 0.3440215284197143 38 245558]],
    :cluster-id 81}
   {:n-docs-in-cluster 78.8039908572782,
    :characteristic-words [[["brut"] 3.3821518930960554E-5 2.1873893140205647 189 245558]
                           [["xxx"] 2.379949052238369E-5 1.0122489001677966 22 245558]
                           [["lu" "livr"] 1.3123557124062463E-5 0.6397759830952835 23 245558]
                           [["rafin"] 1.0740332634628577E-5 0.5925329645136039 32 245558]
                           [["venezuelien"] 8.8269170657072E-6 0.5584658973262538 45 245558]
                           [["bonhom"] 8.123731835545794E-6 0.6655449979865989 101 245558]
                           [["zep"] 5.455053235649092E-6 0.3247248425665367 22 245558]
                           [["indemn"] 5.315376916139453E-6 0.32579996513133097 24 245558]
                           [["gitan"] 5.050726736495134E-6 0.3237714941410648 27 245558]
                           [["entretient"] 4.785727923117822E-6 0.32625398093585056 32 245558]
                           [["ambiguït"] 4.661739013522674E-6 0.3284153488119301 35 245558]
                           [["ciné"] 4.58880891528006E-6 0.285754055082439 22 245558]
                           [["vien" "regard"] 4.559112047115843E-6 0.3335718856603964 39 245558]
                           [["diverti"] 4.510469166454922E-6 0.32341238230780506 36 245558]
                           [["farfelu"] 4.420722580839552E-6 0.32391314308312047 38 245558]
                           [["religion" "abraham"] 4.410359331420352E-6 0.26706672249421143 19 245558]
                           [["abraham"] 3.977003402525284E-6 0.2694426076712425 26 245558]
                           [["non" "donc"] 3.6373769808483586E-6 0.26836947836251424 32 245558]
                           [["fait" "aprè"] 3.592181988206801E-6 0.2660171152709258 32 245558]
                           [["indemnit"] 3.5546346173111892E-6 0.3407107624514114 71 245558]],
    :cluster-id 64}
   {:n-docs-in-cluster 78.77311406430728,
    :characteristic-words [[["lise"] 4.109748590371085E-5 1.8928271409248847 56 245558]
                           [["chapitr"] 3.098520477941378E-5 1.4116266540387177 40 245558]
                           [["blond"] 2.737602813570987E-5 1.1969033303396905 29 245558]
                           [["contradiction"] 1.3556057241440392E-5 1.114586759887574 171 245558]
                           [["amus"] 1.0599954525970443E-5 0.9530193772782987 176 245558]
                           [["metro"] 8.373135197154574E-6 0.9946494643968025 302 245558]
                           [["sen" "vie"] 6.476588307628359E-6 0.3772500565995075 24 245558]
                           [["derni"] 6.420616484592578E-6 1.6261712890447442 1219 245558]
                           [["religieu"] 5.866846206098453E-6 1.0562563449478835 561 245558]
                           [["auteu"] 5.803857862304168E-6 1.0633138089855234 576 245558]
                           [["aret" "delir"] 5.706745090141782E-6 0.3469950998128851 25 245558]
                           [["fait" "polit"] 5.552500067355394E-6 0.3809303050262858 38 245558]
                           [["acord" "certain"] 4.9623943864825215E-6 0.3026953539080409 22 245558]
                           [["peu" "pas"] 4.669107925978598E-6 0.30573465224299223 27 245558]
                           [["articl" "asez"] 4.212042949399211E-6 0.2895037668727035 29 245558]
                           [["vivr" "ensembl"] 4.209876643760865E-6 0.28939691948676466 29 245558]
                           [["vif"] 4.160484592527144E-6 0.5010382868773071 155 245558]
                           [["coifeu"] 4.058746489272666E-6 0.2579098406060219 21 245558]
                           [["1970"] 3.751317121362778E-6 0.274240554086054 32 245558]
                           [["relir"] 3.734494590974108E-6 0.4127033794878338 111 245558]],
    :cluster-id 66}
   {:n-docs-in-cluster 77.90183645307641,
    :characteristic-words [[["reconversion"] 4.366623196320335E-5 1.722552829350551 28 245558]
                           [["grip"] 2.904678718741987E-5 1.4964604193335345 66 245558]
                           [["imposabl"] 1.3873851681385323E-5 0.7704424068460417 43 245558]
                           [["debut" "carier"] 1.0703368944526767E-5 0.5339169230878066 21 245558]
                           [["photograph"] 9.371775742346433E-6 0.6078035160852008 53 245558]
                           [["tirag"] 8.867172165238739E-6 0.59568848953947 57 245558]
                           [["comiqu"] 8.446758769426257E-6 0.545464580142462 47 245558]
                           [["celebr"] 7.492471022807132E-6 0.6174967522723365 96 245558]
                           [["tout" "non"] 7.350879115739992E-6 0.42599943530199236 27 245558]
                           [["être" "non"] 7.32582357385915E-6 0.4209091064535004 26 245558]
                           [["œuvr"] 5.984989592077231E-6 0.6288153400739306 157 245558]
                           [["chant"] 5.948466610949943E-6 0.5712101664617711 121 245558]
                           [["anex"] 5.94129717553394E-6 0.4310968011983148 50 245558]
                           [["aimerai" "voir"] 5.896945170952815E-6 0.35080480264171604 24 245558]
                           [["avoi" "pri"] 5.776766815270874E-6 0.42753487591857886 52 245558]
                           [["resist"] 5.577006235403352E-6 0.4376872146063349 61 245558]
                           [["art"] 5.447604199773984E-6 0.6442307211670772 196 245558]
                           [["benefici"] 4.964254208239191E-6 0.4389259526403976 79 245558]
                           [["k"] 4.850746141608388E-6 0.35985964213413646 44 245558]
                           [["gaul"] 4.335677965395038E-6 0.4589774652086554 116 245558]],
    :cluster-id 73}
   {:n-docs-in-cluster 77.57743375380777,
    :characteristic-words [[["chirurgien"] 5.571058663088064E-5 2.2853319832192662 44 245558]
                           [["distinction" "entr"] 2.0119043756625793E-5 1.0822530774193402 55 245558]
                           [["quid"] 1.754289404432008E-5 1.1500780827273815 104 245558]
                           [["distinction"] 1.3938274981318763E-5 1.1053057104868715 159 245558]
                           [["fichu"] 1.2772980346454493E-5 0.6243057376733326 23 245558]
                           [["sarcasm"] 1.1086874872017274E-5 0.9788958588550645 177 245558]
                           [["a" "vraiment"] 8.41439915164055E-6 1.0092791691718972 316 245558]
                           [["propr"] 8.22412530095129E-6 1.7660464061471524 1153 245558]
                           [["art"] 8.016989982352618E-6 0.8196222644096608 196 245558]
                           [["diplom"] 7.692695521706316E-6 0.857195904310606 238 245558]
                           [["roi"] 7.467736476139339E-6 0.657545460071864 118 245558]
                           [["independant"] 6.25997105507467E-6 0.8423384643939384 313 245558]
                           [["verit"] 6.195628997490016E-6 1.0528485002994634 531 245558]
                           [["stad"] 5.872799872018095E-6 0.6710531068356667 194 245558]
                           [["entier"] 5.045757192883443E-6 0.8789777525681487 456 245558]
                           [["design"] 4.881361428669961E-6 0.7031259673977358 287 245558]
                           [["philo"] 4.704982744640346E-6 0.34939980316985475 43 245558]
                           [["être" "preci"] 3.856849333684978E-6 0.2706401941789041 29 245558]
                           [["depech"] 3.213698400260541E-6 0.25811509395175947 38 245558]
                           [["justic" "fiscal"] 2.9333253233360806E-6 0.2159240349959974 26 245558]],
    :cluster-id 4}
   {:n-docs-in-cluster 76.60530656640144,
    :characteristic-words [[["search" "q"] 1.9282029637048698E-5 0.8434508735667605 21 245558]
                           [["donez"] 1.863229715821143E-5 0.9104836930065369 34 245558]
                           [["search"] 1.6335062790546334E-5 0.848822166010332 39 245558]
                           [["ama"] 1.5676790319572864E-5 0.9210127968402171 62 245558]
                           [["q"] 1.4347308806506032E-5 0.8587321023129305 61 245558]
                           [["teni"] 1.3868735231944984E-5 1.365843879108611 310 245558]
                           [["ultim"] 1.2544766631494148E-5 0.856391456022503 87 245558]
                           [["cens"] 1.2311691835523858E-5 1.3851262634971218 397 245558]
                           [["ferai"] 1.0091996825750682E-5 0.9670636497710796 208 245558]
                           [["kim"] 8.375230351699223E-6 0.47210592004799296 28 245558]
                           [["ferait"] 6.807978101215639E-6 0.9796022243234671 405 245558]
                           [["purg"] 6.6679466750631244E-6 0.4836178678173414 57 245558]
                           [["test"] 6.309105211883825E-6 1.057163887500831 531 245558]
                           [["misil"] 6.055539231183613E-6 0.4871677333804824 73 245558]
                           [["ouvert"] 5.4851779264369904E-6 1.0864858674611957 659 245558]
                           [["out"] 4.6719339408130695E-6 0.475511788828502 114 245558]
                           [["hé"] 4.572528240282675E-6 0.48344369037901586 124 245558]
                           [["conseil"] 4.237374080166834E-6 1.2529926655222932 1097 245558]
                           [["acesoir"] 4.184256387408658E-6 0.396186715006265 83 245558]
                           [["profond"] 3.697111411141152E-6 0.4943660514551316 184 245558]],
    :cluster-id 3}
   {:n-docs-in-cluster 76.09859769596699,
    :characteristic-words [[["bolor"] 2.4894888281275923E-5 1.1869882700574228 41 245558]
                           [["niel"] 1.9392158627696936E-5 0.9498093218120661 36 245558]
                           [["chateau"] 1.8286308890367448E-5 0.9553314970893781 45 245558]
                           [["basin"] 1.7480566176223365E-5 0.9509253720637947 51 245558]
                           [["concert"] 1.6704373535419807E-5 0.9562259701293468 60 245558]
                           [["gal"] 1.4646904782780051E-5 0.6710852342461715 20 245558]
                           [["piscin"] 1.300566271476497E-5 0.9782686723884298 127 245558]
                           [["vif"] 1.2427310769919098E-5 1.0073380930325881 155 245558]
                           [["feront"] 1.1720707058687427E-5 0.9910254411953775 167 245558]
                           [["sale"] 7.411744218491223E-6 1.0985195015174973 476 245558]
                           [["esper"] 5.4099198698795425E-6 1.2530775128382334 895 245558]
                           [["produit" "a"] 4.599058030080837E-6 0.29021644556718956 24 245558]
                           [["conteneu"] 4.4233555960377854E-6 0.3106208502123364 34 245558]
                           [["y'a" "quand"] 3.876310200582114E-6 0.29905574742633795 41 245558]
                           [["quand" "mal"] 3.055518009163413E-6 0.30503481773698554 71 245558]
                           [["diplo"] 3.0068281251594345E-6 0.2659782873515147 49 245558]
                           [["asez" "clai"] 2.9523478365873004E-6 0.2627420663621667 49 245558]
                           [["volum"] 2.4972686904680547E-6 0.3193811560392778 112 245558]
                           [["800"] 2.457912774135769E-6 0.25267806762340583 62 245558]
                           [["degener"] 2.440017532782772E-6 0.255821385215896 65 245558]],
    :cluster-id 31}
   {:n-docs-in-cluster 76.01172574157108,
    :characteristic-words [[["imatricul"] 3.1831831241142285E-5 1.357846343088017 31 245558]
                           [["tout" "vile"] 8.424807639685963E-6 0.46480149110641883 26 245558]
                           [["peut" "choisi"] 4.347731506038066E-6 0.2845469260571266 26 245558]
                           [["va" "tout"] 3.640441140984052E-6 0.28620029524572727 41 245558]
                           [["quarti"] 3.347249998241625E-6 0.7162306578469574 473 245558]
                           [["79"] 3.1860904688898323E-6 0.22241321776456607 24 245558]
                           [["2001"] 2.7658906644684525E-6 0.22344651670201213 34 245558]
                           [["plaqu"] 2.737193444018668E-6 0.44738164900639293 219 245558]
                           [["voitur" "tout"] 2.693736289313166E-6 0.189908597249116 21 245558]
                           [["a" "absolument"] 2.608222233238186E-6 0.2983599000887724 88 245558]
                           [["inaceptabl"] 2.513536486219159E-6 0.3003360742553311 95 245558]
                           [["kilo"] 2.303209766503153E-6 0.29295794670773223 102 245558]
                           [["inscrit"] 1.7246926324130524E-6 0.34241779102991704 209 245558]
                           [["accè"] 1.5653443019673918E-6 0.5866543694582714 613 245558]
                           [["plu" "propr"] 1.5268851006073475E-6 0.15764154126214813 39 245558]
                           [["pendant" "period"] 1.5230228716895874E-6 0.12564320205457669 20 245558]
                           [["interdir" "tout"] 1.5100582719416653E-6 0.1268649866115995 21 245558]
                           [["juilet"] 1.4757849013235375E-6 0.2556271561602452 134 245558]
                           [["jamai" "vu"] 1.437795151652821E-6 0.35293765093839513 265 245558]
                           [["fichi"] 1.391251187530737E-6 0.24420543028933478 130 245558]],
    :cluster-id 70}
   {:n-docs-in-cluster 75.99576544074512,
    :characteristic-words [[["coran"] 1.4156637234141733E-5 1.0282100930924556 123 245558]
                           [["asad"] 1.0939500442564733E-5 0.5341373967226016 20 245558]
                           [["plais"] 1.0494202121854625E-5 0.5442738858451825 25 245558]
                           [["ingrat"] 9.049659940420576E-6 0.46655141862910493 21 245558]
                           [["spam"] 8.28230724779326E-6 0.5632230451924867 57 245558]
                           [["aleatoir"] 8.03117372958316E-6 0.5562908485548735 59 245558]
                           [["34"] 6.179131615046372E-6 0.47996712417108245 67 245558]
                           [["vraiment" "trè"] 6.015640810225903E-6 0.48168531019146155 72 245558]
                           [["bouton"] 5.742663854878607E-6 0.5854540848992682 142 245558]
                           [["chop"] 5.453076369303991E-6 0.5956376234559397 163 245558]
                           [["dirait"] 4.95041458367651E-6 0.8824773109512621 480 245558]
                           [["injust"] 4.925975512899E-6 0.48911160389738917 113 245558]
                           [["gener"] 4.8502896936690465E-6 0.5908569748895601 193 245558]
                           [["polygam"] 3.5128707450114705E-6 0.2439767175396741 26 245558]
                           [["cese"] 3.267073861881395E-6 0.3933372398033043 126 245558]
                           [["a" "trop"] 3.2043161178156587E-6 0.3899225421850913 127 245558]
                           [["14"] 3.0102819483304732E-6 0.5558163553777171 314 245558]
                           [["citation"] 2.7617991815789833E-6 0.5600733746398164 350 245558]
                           [["arf"] 2.2339423397469554E-6 0.17743818651372512 26 245558]
                           [["actualit"] 2.143237629578798E-6 0.4559320298825218 299 245558]],
    :cluster-id 79}
   {:n-docs-in-cluster 74.05851811393742,
    :characteristic-words [[["veganism"] 7.791400603951101E-6 0.5043766910332941 46 245558]
                           [["intervienent"] 2.196311234798666E-6 0.16537607241560212 22 245558]
                           [["late"] 1.7914314619262017E-6 0.14942136545031617 25 245558]
                           [["dogmat"] 1.7812490869335493E-6 0.15256337915436027 27 245558]
                           [["vox"] 1.738201098555603E-6 0.17283276789838015 41 245558]
                           [["comprend" "rien"] 1.46742401263035E-6 0.18478139267862795 65 245558]
                           [["réac"] 1.4639887106725202E-6 0.16019286179827608 45 245558]
                           [["paf"] 1.4232686480772708E-6 0.16865884764526065 54 245558]
                           [["cas" "tout"] 1.3320431279219627E-6 0.1808203464622066 71 245558]
                           [["chain" "télé"] 1.3022640018957657E-6 0.12538036722240595 28 245558]
                           [["où" "bose"] 1.2275413274146635E-6 0.10586318234557962 19 245558]
                           [["caracterist"] 1.220510787150117E-6 0.19488824897246584 95 245558]
                           [["reconai"] 1.0721305072555753E-6 0.20157979398752135 119 245558]
                           [["pretendu"] 9.832668573152986E-7 0.16808616694770417 89 245558]
                           [["chemin"] 9.05422421974833E-7 0.23556921709727666 191 245558]
                           [["come" "ça" "a"] 8.757981716034716E-7 0.13223822583875663 60 245558]
                           [["vlc"] 8.354121480511761E-7 0.1149616136834151 46 245558]
                           [["tout" "person"] 8.133515923981066E-7 0.20389811490893717 160 245558]
                           [["figaro"] 7.742533356391856E-7 0.23856427648779643 222 245558]
                           [["a" "air" "être"] 7.464928309299651E-7 0.13642021108020377 78 245558]],
    :cluster-id 6}
   {:n-docs-in-cluster 73.54988662032189,
    :characteristic-words [[["perdur"] 1.781918244492947E-5 0.8080932355346251 24 245558]
                           [["selection" "naturel"] 1.756444165398191E-5 0.8127440631646929 26 245558]
                           [["acordent"] 1.5446485269127522E-5 0.8103891910433081 40 245558]
                           [["laison"] 1.5100054584371407E-5 0.814688083773118 44 245558]
                           [["meritent"] 1.1981909146757327E-5 0.8322269811790709 92 245558]
                           [["œuvr"] 9.704479656837822E-6 0.8458451265407776 157 245558]
                           [["selection"] 8.357450159776278E-6 0.8731388827755903 229 245558]
                           [["importanc"] 8.004730761996745E-6 0.8786807815980541 251 245558]
                           [["comunaut"] 5.81344655227814E-6 0.9123320036743339 440 245558]
                           [["naturel"] 5.307116357124858E-6 0.941120605007202 526 245558]
                           [["feme"] 1.5542084544073465E-6 1.589438662281234 2818 245558]
                           [["http"] 4.798999990729413E-7 2.3608587657449394 6010 245558]
                           [["a"] 4.6607260062447864E-7 15.022579773257197 54855 245558]
                           [["fair"] 2.9112571270362153E-7 5.113292362980476 14874 245558]
                           [["r" "franc" "coment"] 2.8161373829757586E-7 0.26373719552555136 451 245558]
                           [["franc" "coment"] 2.798421060434819E-7 0.26456355910068663 454 245558]
                           [["w.redit.com" "r"] 2.7334556145300337E-7 0.2751307504042495 483 245558]
                           [["http" "w.redit.com" "r"] 2.7334556145300337E-7 0.2751307504042495 483 245558]
                           [["http" "w.redit.com"] 2.6916679430472046E-7 0.2836275392737892 506 245558]
                           [["w.redit.com"] 2.6916679430472046E-7 0.2836275392737892 506 245558]],
    :cluster-id 87}
   {:n-docs-in-cluster 73.44580490131825,
    :characteristic-words [[["qque"] 4.05090142101272E-6 0.27333938012325665 28 245558]
                           [["overdos"] 3.830228750591191E-6 0.26254104813874657 28 245558]
                           [["thc"] 3.438920445832973E-6 0.2659993235350197 38 245558]
                           [["spectr"] 3.144444294394741E-6 0.2762511281129934 52 245558]
                           [["entendai"] 2.876799356618838E-6 0.279369844949189 64 245558]
                           [["g"] 2.7148159458412896E-6 0.2791378140648084 71 245558]
                           [["mortel"] 2.4339765405233943E-6 0.2833753561141832 89 245558]
                           [["djihadist"] 2.3206693331966066E-6 0.19970274108523509 36 245558]
                           [["volum"] 2.1564705017934616E-6 0.28855661282176687 112 245558]
                           [["barbar"] 1.9007238670756474E-6 0.20276310753618101 55 245558]
                           [["spac"] 1.8306679832342382E-6 0.3049673013406382 158 245558]
                           [["dose"] 1.7432251105346103E-6 0.2995184121452808 161 245558]
                           [["fake"] 1.3403130796167673E-6 0.38054745399397627 335 245558]
                           [["kilometr"] 1.2809569765598328E-6 0.2181625408145432 116 245558]
                           [["pein" "mort"] 1.2422754403756259E-6 0.22046628978142907 123 245558]
                           [["syndicat"] 1.2262761290085522E-6 0.35101542117426937 311 245558]
                           [["evoqu"] 1.1699926350250411E-6 0.33820230628801107 302 245558]
                           [["bouf"] 1.0054758560092136E-6 0.3953995760269316 441 245558]
                           [["tue"] 8.52683819163591E-7 0.2593700434457491 241 245558]
                           [["parc" "a"] 8.149330567273905E-7 0.389474058682066 489 245558]],
    :cluster-id 89}
   {:n-docs-in-cluster 72.85314050535365,
    :characteristic-words [[["king"] 1.4025134133718864E-6 0.11529509031930402 19 245558]
                           [["realism"] 1.3118328980200183E-6 0.11551293407218828 22 245558]
                           [["vrai" "pens"] 1.1816723981096858E-6 0.1184787077561736 29 245558]
                           [["jeu" "video"] 5.915227992200206E-7 0.1501046236969272 121 245558]
                           [["http"] 5.123773813286991E-7 2.360684091785507 6010 245558]
                           [["aprec"] 3.72512968978278E-7 0.16675724853240118 203 245558]
                           [["a"] 3.720645417315538E-7 15.021468290635704 54855 245558]
                           [["r" "franc" "coment"] 2.8878788823141766E-7 0.26371768227856635 451 245558]
                           [["franc" "coment"] 2.8701268918809864E-7 0.264543984713154 454 245558]
                           [["option"] 2.8558592184840803E-7 0.19061663071201504 286 245558]
                           [["r"] 2.815278936918464E-7 1.0837429523703668 2677 245558]
                           [["http" "w.redit.com" "r"] 2.806245452749434E-7 0.2751103941770018 483 245558]
                           [["w.redit.com" "r"] 2.806245452749434E-7 0.2751103941770018 483 245558]
                           [["w.redit.com"] 2.7653815731776277E-7 0.2836065543906575 506 245558]
                           [["http" "w.redit.com"] 2.7653815731776277E-7 0.2836065543906575 506 245558]
                           [["r" "franc"] 2.637956971196509E-7 0.7792844666632168 1836 245558]
                           [["acord"] 2.427381178005117E-7 1.1948825482618406 5169 245558]
                           [["w.redit.com" "r" "franc"] 2.3568540596582976E-7 0.2249352955859564 391 245558]
                           [["problem"] 2.1146667275595554E-7 1.7940818267202618 7321 245558]
                           [["si"] 1.9547999330349342E-7 7.5523857467793665 27777 245558]],
    :cluster-id 8}]


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


  (.stop sc)

  *e)


