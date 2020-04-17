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
           (org.apache.spark.ml.clustering LDA LDAModel)
           (org.apache.spark.api.java JavaSparkContext JavaRDD)
           (org.apache.spark.sql.types DataTypes)
           (org.apache.spark.sql.catalyst.expressions GenericRow GenericRowWithSchema)
           (org.apache.spark.sql functions)
           (java.util.zip GZIPOutputStream)
           (scala.collection.mutable WrappedArray$ofRef)
           (org.apache.spark.ml.linalg SparseVector DenseVector)))

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
                                           {::parsing/remove-quotes true}
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
                         (->> (parsing/trim-markdown {::parsing/remove-quotes true})))
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
                                  (mapcat parsing/split-words-fr)
                                  (map str/lower-case))
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
    (spark/take 10) #_#_
    (spark/sample false 1e-3 43255)
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
  [{:n-docs-in-cluster 88925.5453520601,
    :characteristic-words [[["je"] 0.016742528040595284 22654.23652875935 43316 245558]
                           [["j'"] 0.014581346820395247 12232.652906793359 20626 245558]
                           [["ai"] 0.011408946753890037 8363.578472371742 13515 245558]
                           [["tu"] 0.011242635789121547 13285.573646526114 24286 245558]
                           [["j'" "ai"] 0.009185825569345107 6904.215988865323 11199 245558]
                           [["les"] 0.007352618857031423 14662.673163460171 53889 245558]
                           [["t'"] 0.004105299455884115 3624.287206761963 6088 245558]
                           [["te"] 0.003650438967968128 2779.2528496899995 4488 245558]
                           [["me"] 0.0032105278167837703 4295.177847945746 7930 245558]
                           [["pas"] 0.0031143167591241028 21651.204203131758 51031 245558]
                           [["suis"] 0.0030704133565124803 4277.670391195725 7962 245558]
                           [["as"] 0.00288359786693948 3074.3765427324465 5395 245558]
                           [["la"] 0.0028445630613458395 16574.50493617037 54177 245558]
                           [["que" "tu"] 0.0026940165681355044 2671.945622667257 4608 245558]
                           [["es"] 0.0026877054833842617 1707.9774943009875 2619 245558]
                           [["l'"] 0.002635427514588784 11748.362528509404 39598 245558]
                           [["m'"] 0.0025128244038798986 1980.7140803156417 3223 245558]
                           [["que"] 0.0023508834158700864 21979.67731224116 52990 245558]
                           [["bonjour"] 0.002225419641086024 153.75278542976272 1815 245558]
                           [["ton"] 0.0022123690961664 2267.160581009367 3937 245558]],
    :cluster-id 37}
   {:n-docs-in-cluster 65970.06240580273,
    :characteristic-words [[["j'"] 0.007718849881848566 2626.7889803301814 20626 245558]
                           [["ai"] 0.005577743286857784 1610.431688585114 13515 245558]
                           [["j'" "ai"] 0.004867366537194107 1288.6310171936077 11199 245558]
                           [["les"] 0.0036912606579582175 17748.800611707233 53889 245558]
                           [["est"] 0.0031901112625067896 23612.716825698528 75359 245558]
                           [["pays"] 0.0030941309777222648 1279.3485800518229 2119 245558]
                           [["on"] 0.003004450271618886 8672.598957768223 24270 245558]
                           [["je"] 0.0026860523579337325 9155.531445767248 43316 245558]
                           [["bonjour"] 0.0024795963302625124 38.511232686294754 1815 245558]
                           [["c'" "est"] 0.0017430308350763202 15077.527174723713 48121 245558]
                           [["été"] 0.001602237181001387 570.3455921115151 4500 245558]
                           [["t'"] 0.001534890002143019 900.132081815132 6088 245558]
                           [["a" "été"] 0.0015327378434903949 210.5300376845129 2447 245558]
                           [["problème"] 0.0015045042326644609 1592.438959875364 3592 245558]
                           [["ils"] 0.0014890110212302243 3604.184255904872 9711 245558]
                           [["sont"] 0.0014454505381722438 3979.5493962331047 10955 245558]
                           [["the"] 0.0014319059994215566 53.94902217713876 1324 245558]
                           [["l'"] 0.001430671068826772 12444.57574831958 39598 245558]
                           [["la"] 0.0013917007769639334 16556.463066161694 54177 245558]
                           [["c'"] 0.0013389709425115637 16191.687007912209 53018 245558]],
    :cluster-id 2}
   {:n-docs-in-cluster 17308.426103782054,
    :characteristic-words [[["gilets"] 0.003993463435489009 565.5154727614306 1282 245558]
                           [["les"] 0.0037509285880235055 5763.6929291321785 53889 245558]
                           [["gilets" "jaunes"] 0.0037120107148200243 508.67348072829253 1113 245558]
                           [["jaunes"] 0.003698368461141077 520.202843790549 1170 245558]
                           [["des"] 0.0026489537433639354 3701.2614483742345 32894 245558]
                           [["c'"] 0.0022453092064269597 2363.58974761618 53018 245558]
                           [["je"] 0.0021768523702256903 1813.2764689089693 43316 245558]
                           [["est"] 0.0021357029053405974 3780.4786890151695 75359 245558]
                           [["c'" "est"] 0.002100352768316549 2114.612275271017 48121 245558]
                           [["tu"] 0.001672210152039666 880.3684776127172 24286 245558]
                           [["police"] 0.0013698668312930318 236.63833391630416 649 245558]
                           [["les" "gilets"] 0.001336034076118986 227.23465030179727 614 245558]
                           [["les" "gilets" "jaunes"] 0.0012447614672237983 205.02741611686923 537 245558]
                           [["pas"] 0.0012417941916216257 2577.1573145536613 51031 245558]
                           [["policiers"] 0.0011441335234156913 155.26713223972715 334 245558]
                           [["des" "gilets"] 0.0010646063416469564 148.7772139798866 330 245558]
                           [["ont"] 0.001038216485314769 915.3257694492073 6813 245558]
                           [["la" "police"] 0.001008012912362688 174.50811596148458 479 245558]
                           [["violences"] 0.0010024860414304393 131.56314061159327 273 245558]
                           [["que"] 9.900388560841744E-4 2806.630940292678 52990 245558]],
    :cluster-id 17}
   {:n-docs-in-cluster 15500.802051300001,
    :characteristic-words [[["voiture"] 0.0018357064399586176 269.2715968561619 688 245558]
                           [["200"] 0.001342301353043196 267.19310332188815 918 245558]
                           [["#x"] 0.0012911803523229515 246.77619842506775 817 245558]
                           [["km"] 0.0011677349344944576 145.86690574621315 312 245558]
                           [["#x" "200"] 0.0011590574847139545 221.99953391487443 736 245558]
                           [["b"] 0.0011373660629478421 227.9126438543191 787 245558]
                           [["200" "b"] 0.0010772952002373604 200.63939731810854 648 245558]
                           [["#x" "200" "b"] 0.0010772952002373604 200.63939731810854 648 245558]
                           [["électrique"] 9.932755049811837E-4 119.7718670930652 246 245558]
                           [["prix"] 9.378659236759512E-4 225.85017463652994 910 245558]
                           [["paris"] 8.932396389972896E-4 243.73583373483453 1081 245558]
                           [["2"] 8.487789836123527E-4 330.6767418872376 1859 245558]
                           [["ville"] 7.972877697280945E-4 153.57568390665247 511 245558]
                           [["transports"] 7.297092850222509E-4 97.600038543736 225 245558]
                           [["la" "voiture"] 7.103979581240538E-4 100.65775842614923 247 245558]
                           [["voitures"] 7.045057930512022E-4 120.77602547824526 360 245558]
                           [["avion"] 6.981918626721706E-4 104.55819333603854 272 245558]
                           [["h"] 6.97809516156056E-4 145.90805521735587 522 245558]
                           [["transport"] 6.807821725950403E-4 93.73490279821051 223 245558]
                           [["cher"] 6.737589152597256E-4 150.85829814666184 572 245558]],
    :cluster-id 39}
   {:n-docs-in-cluster 14203.434320764625,
    :characteristic-words [[["gauche"] 0.004134657144717668 503.357975298396 1128 245558]
                           [["droite"] 0.0034052273782284592 420.8720165217604 958 245558]
                           [["macron"] 0.0028721267860916666 535.339861726264 1883 245558]
                           [["vote"] 0.002324889533017016 311.42238502015044 775 245558]
                           [["parti"] 0.002261176969575618 311.90874833932855 802 245558]
                           [["extrême"] 0.002064374486289877 242.33085808441302 517 245558]
                           [["mélenchon"] 0.0015872642047287666 195.5405451627887 441 245558]
                           [["lfi"] 0.0015088636653728393 153.0448391444808 273 245558]
                           [["lrem"] 0.0014785144062514632 188.65565932785856 443 245558]
                           [["partis"] 0.0014557911535837964 162.53664628275743 326 245558]
                           [["rn"] 0.0014521252899071246 143.54818969924517 248 245558]
                           [["voter"] 0.0014133073252149964 184.18599197563444 443 245558]
                           [["voté"] 0.0013805476140918982 167.32254220167576 370 245558]
                           [["extrême" "droite"] 0.0012843561679222737 134.15690396820804 248 245558]
                           [["élections"] 0.001283797771517492 147.7044976471025 307 245558]
                           [["politique"] 0.0012713581163069088 338.8689223118051 1604 245558]
                           [["de" "gauche"] 0.0012686560163630012 159.90896161051745 370 245558]
                           [["fn"] 0.0012592412901159533 138.80782176130992 274 245558]
                           [["trump"] 0.0012269241919786733 192.26332940970886 565 245558]
                           [["la" "gauche"] 0.0011919519476929485 149.52724078109654 344 245558]],
    :cluster-id 76}
   {:n-docs-in-cluster 14053.759751834024,
    :characteristic-words [[["le"] 0.0025820725051852733 6142.956033671227 78825 245558]
                           [["je"] 0.0021221578549550646 1377.0255929431553 43316 245558]
                           [["de"] 0.0020952902849153965 7100.809267517774 97511 245558]
                           [["tu"] 0.00203694928697129 577.3577726613099 24286 245558]
                           [["pas"] 0.0015740477587280477 1890.528460669324 51031 245558]
                           [["ministre"] 0.001558502380178739 203.51136558671698 495 245558]
                           [["que"] 0.0014235409475960026 2035.0609593826334 52990 245558]
                           [["l'"] 0.001361824588886451 3220.464466754199 39598 245558]
                           [["c'"] 0.0012225630879043603 2106.25717621835 53018 245558]
                           [["c'" "est"] 0.0012214827169769737 1863.0067699238978 48121 245558]
                           [["président"] 0.0011851880371406276 206.86761577001465 684 245558]
                           [["article"] 0.001116221595487632 508.96648905571703 3446 245558]
                           [["les"] 0.001001495472231717 2235.1260675305275 53889 245558]
                           [["de" "le"] 9.968161381374019E-4 1605.5701127982773 17761 245558]
                           [["le" "président"] 9.513948022014662E-4 138.9596485914565 382 245558]
                           [["gens"] 8.475545639707316E-4 78.58691120115886 5711 245558]
                           [["assemblée"] 7.923035270417933E-4 106.68576018533983 268 245558]
                           [["la"] 7.91332318402338E-4 3904.779740712593 54177 245558]
                           [["de" "l'"] 7.779409969962403E-4 749.4847398276 7042 245558]
                           [["emmanuel"] 7.55775470475506E-4 96.46551450946637 228 245558]],
    :cluster-id 26}
   {:n-docs-in-cluster 7593.282449011045,
    :characteristic-words [[["homéopathie"] 0.001504251838760906 120.36559110117668 241 245558]
                           [["l'" "homéopathie"] 0.0013641836304678912 107.91346729776886 212 245558]
                           [["ca"] 8.139010671712743E-4 341.5354051131289 4001 245558]
                           [["c'"] 7.842157157772478E-4 2237.7880800151856 53018 245558]
                           [["tu"] 7.284332459567278E-4 381.66713578636313 24286 245558]
                           [["c'" "est"] 7.274066205955609E-4 2045.5037750831575 48121 245558]
                           [["placebo"] 5.871249533144218E-4 39.08392870771357 58 245558]
                           [["est"] 5.798847261688778E-4 2896.819323798649 75359 245558]
                           [["médicaments"] 4.920145501357553E-4 39.26602391813006 78 245558]
                           [["je"] 4.7243328853352473E-4 942.2157676399838 43316 245558]
                           [["effet" "placebo"] 3.651239687448571E-4 22.91329938776055 31 245558]
                           [["meme"] 3.333161280237029E-4 83.11178110276354 667 245558]
                           [["pma"] 3.312468448674333E-4 31.601831357792484 83 245558]
                           [["un"] 3.3095673734551134E-4 1527.8742446938468 38349 245558]
                           [["médecine"] 3.3090802738508507E-4 36.42271161056936 118 245558]
                           [["médecins"] 3.2841180836354344E-4 44.58269955527946 191 245558]
                           [["boiron"] 3.263454005543176E-4 24.904098510032092 46 245558]
                           [["de" "l'" "homéopathie"] 3.1780140447834615E-4 25.294874576481135 50 245558]
                           [["médicament"] 3.1335731896323105E-4 24.084390901266854 45 245558]
                           [["ils"] 3.065169472262763E-4 485.2156754278643 9711 245558]],
    :cluster-id 30}
   {:n-docs-in-cluster 3146.3685471911926,
    :characteristic-words [[["the"] 0.02072138029260337 952.412229554995 1324 245558]
                           [["i"] 0.012729580423729847 566.5665125248014 710 245558]
                           [["to"] 0.012005638459393092 544.1337004362044 705 245558]
                           [["of"] 0.01028100259209258 488.0471923759334 691 245558]
                           [["is"] 0.00997534586683789 453.9560582846869 588 245558]
                           [["you"] 0.009776450202935466 428.01981465072066 514 245558]
                           [["and"] 0.0075402351794579825 350.9690075359351 471 245558]
                           [["in"] 0.007372770624336639 361.73486254830203 542 245558]
                           [["that"] 0.007062487699134798 314.7500916133589 387 245558]
                           [["it"] 0.006757732891979687 306.14575843596054 388 245558]
                           [["are"] 0.005277401051340086 234.23487625320175 284 245558]
                           [["for"] 0.005277295930271542 240.87670276997343 308 245558]
                           [["this"] 0.004356863399411451 197.1363033374319 247 245558]
                           [["not"] 0.00403662879387387 190.49656525404757 259 245558]
                           [["have"] 0.003407208092777725 154.03142899953744 192 245558]
                           [["de"] 0.0030410664495077455 433.3575609854339 97511 245558]
                           [["be"] 0.003002855738632945 140.7639476260489 188 245558]
                           [["we"] 0.0029229462439326337 139.46899856775843 193 245558]
                           [["french"] 0.002914710166687401 133.4195210373475 170 245558]
                           [["people"] 0.0027267912428667884 124.02363400793921 156 245558]],
    :cluster-id 5}
   {:n-docs-in-cluster 2555.9417335739463,
    :characteristic-words [[["c"] 0.002881765415708462 198.6808872177984 737 245558]
                           [["c" "3"] 0.0019031712082412944 98.67614990841756 187 245558]
                           [["c" "3" "a"] 0.0016303358374975124 84.39130419498741 159 245558]
                           [["3" "a"] 0.001596573517740063 84.39330046257838 167 245558]
                           [["3"] 0.0013340338403415586 147.63230600313395 1323 245558]
                           [["3" "a" "9"] 0.0013182255996202624 66.71833377155961 119 245558]
                           [["a" "9"] 0.0012831872509894288 66.92076206021729 128 245558]
                           [["9"] 0.001115670992910378 78.06298792074995 293 245558]
                           [["climatique"] 9.994781039408124E-4 74.22367069222373 316 245558]
                           [["réchauffement"] 9.786800195590861E-4 66.3479202530162 232 245558]
                           [["2"] 7.251388656640267E-4 120.12872761388311 1859 245558]
                           [["le" "réchauffement"] 7.205062884818175E-4 49.69854519858329 180 245558]
                           [["co"] 6.868042869663804E-4 58.44667544924616 326 245558]
                           [["réchauffement" "climatique"] 6.711867434548019E-4 46.07138097119877 165 245558]
                           [["co" "2"] 6.611413624126178E-4 52.742229280305914 259 245558]
                           [["l"] 5.535072455640361E-4 52.9048485368117 365 245558]
                           [["le" "réchauffement" "climatique"] 5.24304624049024E-4 36.62106426173953 136 245558]
                           [["c" "est"] 4.956111946544384E-4 45.5165745240094 292 245558]
                           [["d"] 4.554537079215909E-4 44.97570753784739 328 245558]
                           [["émissions"] 3.458103046276584E-4 32.615057185681216 219 245558]],
    :cluster-id 15}
   {:n-docs-in-cluster 2157.75369280028,
    :characteristic-words [[["bonjour"] 0.03727260643884671 1412.4496782405324 1815 245558]
                           [["a" "été"] 0.024991478745966828 1171.410387288918 2447 245558]
                           [["été"] 0.02009055926201264 1181.2199787213601 4500 245558]
                           [["cette" "soumission"] 0.01687320214842887 613.8524875131997 647 245558]
                           [["cette" "soumission" "a"] 0.016828739966554027 612.1606165020004 645 245558]
                           [["soumission" "a"] 0.016828739966554027 612.1606165020004 645 245558]
                           [["soumission" "a" "été"] 0.01677258242640052 610.2190776765301 643 245558]
                           [["a" "été" "retirée"] 0.016728724859349398 609.2820587953438 643 245558]
                           [["été" "retirée"] 0.016723142593794108 609.5315752481268 644 245558]
                           [["retirée"] 0.01664916958772608 609.7481611586034 649 245558]
                           [["soumission"] 0.01660464060817958 615.3609446447083 668 245558]
                           [["été" "retirée" "car"] 0.015879665758686115 578.1114304440746 608 245558]
                           [["retirée" "car"] 0.015879665758686115 578.1114304440746 608 245558]
                           [["retirée" "car" "il"] 0.015853507349641888 577.1790334514485 607 245558]
                           [["car" "il" "s'"] 0.015818564618924863 577.1795168695013 609 245558]
                           [["bonjour" "cette"] 0.015204405154466305 553.9498431805846 582 245558]
                           [["bonjour" "cette" "soumission"] 0.015187058079945429 552.4426182275163 579 245558]
                           [["a" "été" "supprimé"] 0.014420598810718915 540.6879494240272 594 245558]
                           [["été" "supprimé"] 0.01437278368153845 540.873191133081 598 245558]
                           [["bonjour" "ce"] 0.01428812941064768 524.9227515040645 557 245558]],
    :cluster-id 16}
   {:n-docs-in-cluster 2157.614564741317,
    :characteristic-words [[["taxe"] 0.0026277422508340503 169.94512566082318 632 245558]
                           [["carbone"] 0.0015929196836466653 99.02766839961225 331 245558]
                           [["la" "taxe"] 0.0012765436518960283 79.86866504795515 270 245558]
                           [["taxe" "carbone"] 0.0011662495366757258 65.34740149801164 169 245558]
                           [["taxes"] 9.564794767038115E-4 69.844019970983 334 245558]
                           [["une" "taxe"] 8.326971701825836E-4 52.083364757495985 175 245558]
                           [["la" "taxe" "carbone"] 7.162215382463949E-4 40.09648365780727 103 245558]
                           [["taxer"] 4.930820634837657E-4 39.162875977563154 222 245558]
                           [["les" "taxes"] 4.731237310747205E-4 33.30806652994562 146 245558]
                           [["entreprises"] 4.6735607618425545E-4 53.9756284982308 599 245558]
                           [["une" "taxe" "carbone"] 3.7823883126293645E-4 20.192238958361386 46 245558]
                           [["des" "taxes"] 2.925065940784649E-4 21.07883227678985 97 245558]
                           [["les" "entreprises"] 2.835797850488653E-4 30.775436572163258 308 245558]
                           [["taxe" "sur"] 2.6781744317266876E-4 19.24606662437972 88 245558]
                           [["riches"] 2.519387262654993E-4 35.02293457816071 506 245558]
                           [["carburant"] 2.491680186918199E-4 20.59420743380851 126 245558]
                           [["fossiles"] 2.4025229180238417E-4 15.534800462238824 56 245558]
                           [["les"] 2.3571560495316835E-4 651.6682411263404 53889 245558]
                           [["taxes" "sur"] 2.3294806505905208E-4 16.061856857651364 67 245558]
                           [["énergies"] 2.101409433487994E-4 16.6797957770014 94 245558]],
    :cluster-id 11}
   {:n-docs-in-cluster 1921.7295968836836,
    :characteristic-words [[["vitesse"] 8.172403871698486E-4 52.442032514392814 207 245558]
                           [["vélo"] 7.94847493204201E-4 54.303558343644454 248 245558]
                           [["route"] 7.62596699586976E-4 49.15947382918935 196 245558]
                           [["cyclistes"] 5.723355272395647E-4 30.963070944000634 80 245558]
                           [["cyclables"] 5.544513971607601E-4 27.908395228816378 60 245558]
                           [["voie"] 5.299664593218512E-4 35.68617500269908 157 245558]
                           [["piste"] 4.884065818541727E-4 27.940541047550155 83 245558]
                           [["la" "route"] 4.725306434545662E-4 29.85370366609733 113 245558]
                           [["cyclable"] 4.71852038431711E-4 23.066896821495025 46 245558]
                           [["pistes"] 4.6522478805575973E-4 27.80413269855347 92 245558]
                           [["cycliste"] 4.620375411947958E-4 26.384325272467454 78 245558]
                           [["pistes" "cyclables"] 4.5698945023786575E-4 23.080168128069303 50 245558]
                           [["voitures"] 4.446782747227901E-4 41.55994102540685 360 245558]
                           [["la" "vitesse"] 3.952854733849098E-4 25.476826946839203 101 245558]
                           [["rouler"] 3.8523636343099077E-4 24.492894971424636 94 245558]
                           [["km"] 3.792145805320002E-4 35.662168537933276 312 245558]
                           [["piste" "cyclable"] 3.7257116779761124E-4 18.028231453463846 35 245558]
                           [["piétons"] 3.646726950517626E-4 18.81519421541024 43 245558]
                           [["radars"] 3.638366620328376E-4 22.43575977936994 80 245558]
                           [["km" "h"] 3.271029176715734E-4 21.194713048521614 85 245558]],
    :cluster-id 21}
   {:n-docs-in-cluster 1346.058989737039,
    :characteristic-words [[["alcool"] 8.278733154249568E-4 48.21713392495656 205 245558]
                           [["cannabis"] 6.305395345998013E-4 34.820256992052556 128 245558]
                           [["l'" "alcool"] 5.840576693768515E-4 33.88857105932603 142 245558]
                           [["le" "cannabis"] 5.181194803716219E-4 28.614197061425195 105 245558]
                           [["tabac"] 4.7812444757599354E-4 26.269668255679782 95 245558]
                           [["cigarette"] 4.131382445725154E-4 21.41645623439785 66 245558]
                           [["news"] 3.997399670963228E-4 31.538140125073095 274 245558]
                           [["fake"] 3.7759166177148834E-4 26.39853490050947 175 245558]
                           [["fake" "news"] 3.3668083782065206E-4 22.454600309816538 133 245558]
                           [["fumer"] 3.072181238657143E-4 18.130524370572676 79 245558]
                           [["le" "tabac"] 3.0271972505074157E-4 16.928915694826173 64 245558]
                           [["la" "cigarette"] 2.9303744835936646E-4 15.214397396832249 47 245558]
                           [["fumeurs"] 2.817075927759316E-4 15.858570928197885 61 245558]
                           [["drogue"] 2.678314990488609E-4 17.265579892894873 94 245558]
                           [["vin"] 2.406056921229402E-4 15.75129292811987 89 245558]
                           [["les"] 2.0904405239152712E-4 429.0332308038993 53889 245558]
                           [["drogues"] 1.908819964711317E-4 11.33001560413122 50 245558]
                           [["canada"] 1.8034563609891502E-4 14.594972059919675 133 245558]
                           [["légalisation"] 1.7854958189196524E-4 11.534168653488791 63 245558]
                           [["électronique"] 1.7546020626509756E-4 11.286392069390464 61 245558]],
    :cluster-id 69}
   {:n-docs-in-cluster 1151.1587242757348,
    :characteristic-words [[["parents"] 5.02594010446486E-4 43.4650590529545 533 245558]
                           [["enfants"] 3.436701171547901E-4 39.04421861542597 779 245558]
                           [["tl"] 2.676611470590409E-4 19.41771432856615 162 245558]
                           [["les" "parents"] 2.1533507208994218E-4 16.419746584461972 153 245558]
                           [["intelligence"] 2.1241029574196185E-4 14.470226556806438 104 245558]
                           [["ia"] 1.7118366113176575E-4 12.37025759037608 102 245558]
                           [["dr"] 1.6952066619985162E-4 12.286617968100265 102 245558]
                           [["des" "parents"] 1.6191731355121725E-4 11.02345461457374 79 245558]
                           [["tl" "dr"] 1.5396857793126967E-4 11.02615723900872 89 245558]
                           [["parents" "qui"] 1.40476730687282E-4 8.880977887873122 53 245558]
                           [["marre"] 1.3226846768556177E-4 11.696667230328375 148 245558]
                           [["philosophe"] 1.265574116570134E-4 8.01370577664421 48 245558]
                           [["enfant"] 1.2191467067397543E-4 15.922436656044788 389 245558]
                           [["l'" "intelligence"] 1.1259049667712573E-4 7.6709633941642155 55 245558]
                           [["des" "enfants"] 1.0885318923920667E-4 11.279215295340348 191 245558]
                           [["tl" "pl"] 1.0452661508526806E-4 7.620596906033269 64 245558]
                           [["pl"] 1.0094014339407514E-4 7.6809813606220105 71 245558]
                           [["philosophie"] 9.920501122850228E-5 7.205938832764512 60 245558]
                           [["l'" "ia"] 9.85559243093792E-5 6.790926761631859 50 245558]
                           [["leurs" "enfants"] 9.714768597189616E-5 5.808680310378009 30 245558]],
    :cluster-id 60}
   {:n-docs-in-cluster 1080.8025872816477,
    :characteristic-words [[["emploi"] 4.4361433349095203E-4 32.160578240770434 286 245558]
                           [["assurance"] 4.280989608274749E-4 26.95392065315571 170 245558]
                           [["chômage"] 3.309536321675771E-4 25.707028198723496 266 245558]
                           [["alexandre"] 3.028342120606972E-4 16.83566699780136 76 245558]
                           [["salaire"] 2.9398354244163094E-4 28.048877069284618 439 245558]
                           [["benalla"] 2.6786777700002484E-4 23.855656837222323 327 245558]
                           [["aient" "éventuellement"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["éventuellement" "fait"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["autres" "aient" "éventuellement"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["fait" "pareil" "ce"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["aient" "éventuellement" "fait"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["autres" "aient"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["éventuellement" "fait" "pareil"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["les" "autres" "aient"] 2.481658730303163E-4 11.528543356165658 31 245558]
                           [["pareil" "ce" "qui"] 2.455163390007384E-4 11.528627747421991 32 245558]
                           [["pareil" "ce"] 2.4052583165844188E-4 11.529013677291053 34 245558]
                           [["ce" "qui" "reste"] 2.218708883100079E-4 11.531295115047758 43 245558]
                           [["laurent"] 2.1884271686262863E-4 12.235604191496254 56 245558]
                           [["fait" "pareil"] 2.1260689242536684E-4 11.624086155165099 50 245558]
                           [["pôle"] 1.9932308529872073E-4 11.086649120284715 50 245558]],
    :cluster-id 56}
   {:n-docs-in-cluster 952.7979433548047,
    :characteristic-words [[["vous"] 0.004404299566428876 299.7087732550724 2998 245558]
                           [["avez"] 7.736389641713956E-4 49.64317606695361 374 245558]
                           [["si" "vous"] 7.087296199706425E-4 44.565747908520706 318 245558]
                           [["êtes"] 6.201673870594518E-4 39.40272072304103 288 245558]
                           [["vous" "avez"] 5.724675381031247E-4 36.771855215217876 276 245558]
                           [["vos"] 5.592212825047196E-4 34.24727596302604 227 245558]
                           [["vous" "êtes"] 5.373053244108195E-4 33.91070809151062 243 245558]
                           [["que" "vous"] 5.009592623042777E-4 35.35809267665643 334 245558]
                           [["votre"] 3.983197505528918E-4 32.385887626658786 419 245558]
                           [["voulez"] 3.5776545949043476E-4 21.64060749944181 138 245558]
                           [["vous" "voulez"] 3.1787989292163255E-4 18.25237219703827 101 245558]
                           [["pouvez"] 2.726024342765837E-4 16.407406654656196 103 245558]
                           [["si" "vous" "voulez"] 2.228244126289483E-4 11.31432091647657 44 245558]
                           [["vous" "pouvez"] 2.2271879320660826E-4 12.904069169273217 73 245558]
                           [["allez"] 2.1964203775276525E-4 20.32947862375619 339 245558]
                           [["vous" "vous"] 2.181356591898359E-4 13.818211325758968 99 245558]
                           [["vous" "n'"] 2.0985417176628413E-4 13.24576471488544 94 245558]
                           [["je" "vous"] 1.7947354276513783E-4 14.487709471155547 183 245558]
                           [["-vous"] 1.6688469163179953E-4 14.764656697933196 225 245558]
                           [["vous" "allez"] 1.5545212315073303E-4 9.332725315041765 58 245558]],
    :cluster-id 19}
   {:n-docs-in-cluster 497.20956938351713,
    :characteristic-words [[["prénom"] 4.149362305820581E-4 18.95984084136486 95 245558]
                           [["musulmans"] 3.0074514079076603E-4 18.78827666767751 243 245558]
                           [["prénoms"] 2.791654663279186E-4 12.821782072538019 65 245558]
                           [["un" "prénom"] 2.2993390524926952E-4 9.836061594728893 39 245558]
                           [["islam"] 1.692978702925521E-4 12.9274425081691 273 245558]
                           [["des" "prénoms"] 1.6469618526620058E-4 7.162590608568313 30 245558]
                           [["musulman"] 1.3590060959226685E-4 7.367535995411775 63 245558]
                           [["l'" "islam"] 1.0924766850990697E-4 9.003602061649076 224 245558]
                           [["zemmour"] 1.0716912280612709E-4 6.61797110434647 82 245558]
                           [["les" "musulmans"] 1.009343781989136E-4 6.748559153560851 103 245558]
                           [["musulmane"] 9.358605283401461E-5 4.8149409586210625 35 245558]
                           [["des" "musulmans"] 8.985508305954862E-5 4.884207304127495 42 245558]
                           [["religieux"] 8.400369325703427E-5 6.645754571718393 151 245558]
                           [["immigrés"] 7.577174166289655E-5 6.23237775535666 154 245558]
                           [["français"] 7.545763527598559E-5 18.273335374467752 2104 245558]
                           [["chouard"] 6.648626771865035E-5 3.9286856821529264 43 245558]
                           [["juifs"] 6.346850366438853E-5 5.681628984729222 167 245558]
                           [["d'" "immigrés"] 5.9530321656259266E-5 3.752487334766106 49 245558]
                           [["génération"] 5.7952901596925593E-5 5.562644096009125 187 245558]
                           [["religion"] 5.693671317662935E-5 6.87411271449015 342 245558]],
    :cluster-id 38}
   {:n-docs-in-cluster 433.72548135629677,
    :characteristic-words [[["sfr"] 4.529006492829564E-4 16.711122104554974 44 245558]
                           [["red"] 3.676956335074942E-4 13.516734645253143 35 245558]
                           [["forfait"] 2.9806410263771843E-4 12.041949458042122 44 245558]
                           [["go"] 2.672523531444121E-4 12.523627015938843 77 245558]
                           [["bouygues"] 2.0152300285080152E-4 8.103768042405306 29 245558]
                           [["chez"] 1.9955185177635004E-4 23.557737185006523 1320 245558]
                           [["free"] 1.5710937723664373E-4 8.13954078706278 69 245558]
                           [["sms"] 1.430319214617698E-4 6.704215562413081 41 245558]
                           [["offre"] 1.3015305542955768E-4 9.10091487748041 178 245558]
                           [["orange"] 1.248144945889644E-4 6.820611015412293 68 245558]
                           [["box"] 1.1311531762145735E-4 5.287775613397568 32 245558]
                           [["mois"] 1.0482565537799082E-4 14.330623124725077 989 245558]
                           [["amis"] 1.0174398742665222E-4 8.388542051227976 239 245558]
                           [["mobile"] 9.059315003047061E-5 5.535961300276776 76 245558]
                           [["appels"] 8.348632651502055E-5 4.940732812563756 62 245558]
                           [["opérateurs"] 7.89116250327869E-5 4.0147643828673285 32 245558]
                           [["la" "même"] 7.670597294147374E-5 12.936366133314989 1169 245558]
                           [["abonnement"] 7.644291809559209E-5 4.378099648117034 50 245558]
                           [["technique"] 7.012524029322356E-5 6.122162076722906 196 245558]
                           [["fait" "la" "même"] 6.569760569462985E-5 3.851440601432419 47 245558]],
    :cluster-id 90}
   {:n-docs-in-cluster 220.09945515244348,
    :characteristic-words [[["église"] 1.1668034504887757E-4 5.515994139524777 66 245558]
                           [["upr"] 1.0314371183805376E-4 4.563542029507075 43 245558]
                           [["catholiques"] 9.929309719638145E-5 4.290056222999011 37 245558]
                           [["l'" "église"] 9.845131920110592E-5 4.54660430931301 50 245558]
                           [["l'" "upr"] 7.459786318717368E-5 3.322744404063626 32 245558]
                           [["asselineau"] 5.518018829366554E-5 3.0930539515018247 64 245558]
                           [["tendances"] 4.1123078565488E-5 2.109715280738362 33 245558]
                           [["incroyable"] 3.311167749577709E-5 3.104494998576383 224 245558]
                           [["de" "la" "population"] 3.142718684425588E-5 3.1432804659514977 256 245558]
                           [["collomb"] 3.0943929700868664E-5 1.7001581735183111 33 245558]
                           [["5" "de"] 3.087393442580133E-5 1.7220852350294562 35 245558]
                           [["catholique"] 2.7004807962152655E-5 1.7806143785411654 58 245558]
                           [["commentaires"] 2.6990642102632523E-5 3.895000764676924 565 245558]
                           [["récupération"] 2.3659693784825256E-5 1.527191438276517 47 245558]
                           [["contredit"] 2.3118586235035266E-5 1.492958081524954 46 245558]
                           [["les" "commentaires"] 2.2603879687131678E-5 2.521497265566791 248 245558]
                           [["lui-même"] 2.2239354730120564E-5 1.7835059605892642 93 245558]
                           [["la" "population"] 2.1933253222299054E-5 3.2450302878191786 486 245558]
                           [["facebook"] 2.121252609498664E-5 2.686722175819473 323 245558]
                           [["complot"] 2.107033686390286E-5 2.0304509525021124 154 245558]],
    :cluster-id 32}
   {:n-docs-in-cluster 175.8099543443987,
    :characteristic-words [[["bac"] 3.8204429726990466E-4 17.276212106611883 227 245558]
                           [["le" "bac"] 1.9265481928753582E-4 8.102433909558137 79 245558]
                           [["grève"] 1.5814382463320321E-4 7.897708470822606 143 245558]
                           [["notes"] 1.566121570586651E-4 6.587594300799977 64 245558]
                           [["élèves"] 8.101023022182713E-5 4.843517096861761 152 245558]
                           [["blanquer"] 4.7248491912196475E-5 2.2188337933718767 32 245558]
                           [["les" "élèves"] 4.500043733153547E-5 2.442235749172009 57 245558]
                           [["un" "bac"] 4.279001514533726E-5 2.1097243667001124 36 245558]
                           [["copie"] 3.646322535669731E-5 1.9775845375839212 46 245558]
                           [["enseignants"] 3.223270124480537E-5 1.910462455358216 58 245558]
                           [["prof"] 3.128959090412456E-5 2.579204437141681 179 245558]
                           [["la" "bac"] 3.0451343583856594E-5 1.7173280398288122 45 245558]
                           [["la" "grève"] 2.741861735505724E-5 1.4917997163505612 35 245558]
                           [["profs"] 2.2022984859679934E-5 1.92981255385169 152 245558]
                           [["les" "enseignants"] 2.109472287905277E-5 1.1689941673118756 29 245558]
                           [["de" "la" "terre"] 1.8989790756424388E-5 1.1331535872481238 35 245558]
                           [["champion"] 1.8276293132111696E-5 1.1332214516229344 39 245558]
                           [["lycée"] 1.7990436406115085E-5 1.668264413008049 147 245558]
                           [["education"] 1.6021394284852775E-5 1.0899256071320793 48 245558]
                           [["candidats"] 1.5707961661042685E-5 1.5299920939163658 148 245558]],
    :cluster-id 93}
   {:n-docs-in-cluster 156.8737623538271,
    :characteristic-words [[["i" "have"] 1.735778596688841E-4 6.085149094120446 31 245558]
                           [["to"] 1.7122275561444467E-4 12.227290944598133 705 245558]
                           [["you"] 1.6972348491397365E-4 11.124407677972192 514 245558]
                           [["i"] 1.663280296800712E-4 12.00567105126856 710 245558]
                           [["these"] 1.6186810289749165E-4 6.089593716966413 42 245558]
                           [["link"] 1.3973854297015705E-4 5.664768624596807 53 245558]
                           [["why"] 1.2096144069011737E-4 5.39761655980819 73 245558]
                           [["sénat"] 1.156329814622388E-4 6.116500051564697 149 245558]
                           [["bot"] 1.1529379109012465E-4 5.3855601996069895 86 245558]
                           [["i" "m"] 1.1083862976242129E-4 5.388836578815732 99 245558]
                           [["have"] 1.0733388337012123E-4 6.151812115746987 192 245558]
                           [["le" "sénat"] 1.0345598368857907E-4 5.452399991000964 131 245558]
                           [["for"] 9.225566107305683E-5 6.239438402749348 308 245558]
                           [["direct"] 8.738141701423313E-5 5.546294787465571 231 245558]
                           [["m"] 7.982527085268962E-5 6.0993466512695 405 245558]
                           [["this"] 7.339114763837626E-5 4.980321283813773 247 245558]
                           [["parlementaires"] 4.435163309376815E-5 2.6171029735998474 88 245558]
                           [["lr"] 4.0578127677661294E-5 2.8809141954074784 159 245558]
                           [["72"] 3.724856622369481E-5 1.8935379480477872 40 245558]
                           [["_"] 3.705456336296209E-5 2.2207829123307876 78 245558]],
    :cluster-id 98}
   {:n-docs-in-cluster 134.34516719264724,
    :characteristic-words [[["chasseurs"] 3.561733086982627E-4 13.037177329225324 95 245558]
                           [["chasse"] 2.21305063847042E-4 9.344165321419394 121 245558]
                           [["les" "chasseurs"] 1.9943984655501623E-4 7.297593165302632 52 245558]
                           [["la" "chasse"] 1.3532983022009754E-4 5.598701707952463 66 245558]
                           [["chasseur"] 8.776868058557423E-5 3.3683021596244984 29 245558]
                           [["de" "chasse"] 6.332248030156522E-5 2.5924507127517904 29 245558]
                           [["tuent"] 5.584876727501506E-5 2.3933458296743058 32 245558]
                           [["oiseaux"] 4.2656084361658575E-5 2.2622417636529364 64 245558]
                           [["chiens"] 4.168570750629104E-5 2.3173755460307346 76 245558]
                           [["accidents"] 3.718219829869536E-5 2.1544568997922577 80 245558]
                           [["ski"] 2.8742518174451268E-5 1.4025746535769907 30 245558]
                           [["belge"] 1.6722276068876357E-5 0.9270936209148217 30 245558]
                           [["défenseurs"] 1.588640164736088E-5 0.8992609763497552 31 245558]
                           [["les"] 1.5816161822113095E-5 41.06764407226468 53889 245558]
                           [["les" "chiens"] 1.4936073855434037E-5 0.8982790102417633 37 245558]
                           [["vegan"] 1.4152424077209028E-5 0.9510843114409329 53 245558]
                           [["bêtes"] 1.399568284491011E-5 0.804286955271833 29 245558]
                           [["liés"] 1.3391378534077097E-5 1.0576113425489264 87 245558]
                           [["tirent"] 1.2725972927929083E-5 0.7746094695161164 33 245558]
                           [["cyclistes"] 1.2401149150053845E-5 0.9773625808400901 80 245558]],
    :cluster-id 61}
   {:n-docs-in-cluster 132.27698503825124,
    :characteristic-words [[["inde"] 9.813185273853733E-5 4.4463838737712065 75 245558]
                           [["itt"] 4.61874702766818E-5 2.188347546991767 43 245558]
                           [["l'" "inde"] 4.4240239649953605E-5 2.023333587914989 35 245558]
                           [["amérique"] 3.737288234867582E-5 2.0617405549365397 67 245558]
                           [["paul"] 2.785866049057409E-5 1.394218543467321 33 245558]
                           [["ingérence"] 2.4845108302367844E-5 1.2857579411950537 34 245558]
                           [["new"] 1.9136920924369208E-5 1.3012561351357073 76 245558]
                           [["mortalité"] 1.5427124699592815E-5 0.8833090799245341 32 245558]
                           [["les" "chercheurs"] 1.3789313400092498E-5 0.8123376649604476 32 245558]
                           [["par" "jour"] 1.3510588292490675E-5 1.1660127972808592 118 245558]
                           [["surpris" "que"] 1.3318749001706154E-5 0.7720405434513616 29 245558]
                           [["fin" "de" "le"] 1.3236022119084145E-5 1.0870342233789472 99 245558]
                           [["orientation"] 1.2658836483917316E-5 0.8140967765564753 41 245558]
                           [["chercheurs"] 1.2340818252467092E-5 0.9913724249138629 86 245558]
                           [["civilisation"] 1.2293430392320767E-5 0.8371723458098413 49 245558]
                           [["ère"] 1.1506657475908455E-5 0.8253586335263853 55 245558]
                           [["génétique"] 1.0987974089307975E-5 0.8196381663203923 60 245558]
                           [["l'" "existence" "d'"] 1.0955607569210213E-5 0.6910200209959725 33 245558]
                           [["existence" "d'"] 1.0837295154657312E-5 0.6910667933538865 34 245558]
                           [["bien" "sur"] 1.0828121596615642E-5 1.3559414928207743 266 245558]],
    :cluster-id 43}
   {:n-docs-in-cluster 112.32522389710985,
    :characteristic-words [[["honte"] 7.539722544517083E-5 3.781094872291664 107 245558]
                           [["membres"] 7.208418287580481E-5 3.9682730785506797 152 245558]
                           [["groupe"] 4.91508782564376E-5 4.078528794395903 453 245558]
                           [["député"] 3.8307228035831115E-5 2.667659288887745 196 245558]
                           [["en" "marche"] 3.2205677222833795E-5 1.6368899326636133 48 245558]
                           [["philippe"] 3.118725090869509E-5 2.017128832781407 122 245558]
                           [["députés"] 3.0455082209477485E-5 2.5757136898657986 296 245558]
                           [["les" "républicains"] 3.0108580185672987E-5 1.5981583042130767 54 245558]
                           [["liste"] 2.8666218428917423E-5 2.7825114570565948 421 245558]
                           [["et" "faire"] 2.764523365553738E-5 1.5732494243674708 66 245558]
                           [["républicains"] 2.6556148567519894E-5 1.6032941056376655 80 245558]
                           [["29"] 2.4487202859637194E-5 1.3007536545981098 44 245558]
                           [["patrick"] 2.2464777784211853E-5 1.1837627322464905 39 245558]
                           [["liste" "des"] 2.2288944104643937E-5 1.4937321865225677 99 245558]
                           [["bruno"] 2.192148108360291E-5 1.175801446709937 41 245558]
                           [["voté" "pour"] 2.1759808114802173E-5 1.4722303559861651 100 245558]
                           [["handicapés"] 2.1130585775575896E-5 1.070692194424211 31 245558]
                           [["nom"] 2.1016031638069338E-5 2.3907851486570744 477 245558]
                           [["robert"] 2.0592717955589777E-5 1.065026062811289 33 245558]
                           [["david"] 1.99362934716861E-5 1.0462684181136697 34 245558]],
    :cluster-id 59}
   {:n-docs-in-cluster 105.09896282376593,
    :characteristic-words [[["laïcité"] 1.7103761011057206E-4 6.273417053703674 57 245558]
                           [["la" "laïcité"] 1.295605766650637E-4 4.814494473586336 46 245558]
                           [["québec"] 9.424496701500307E-5 3.8001304619789398 51 245558]
                           [["le" "québec"] 9.059885307473835E-5 3.623561851031263 47 245558]
                           [["manu"] 4.3681159716478166E-5 2.292221010122125 80 245558]
                           [["catholique"] 3.231550512188333E-5 1.6888692099643305 58 245558]
                           [["de" "votre"] 2.7278082958499816E-5 1.31226075667467 34 245558]
                           [["invités"] 2.726221506666772E-5 1.3116675440145287 34 245558]
                           [["héritage"] 2.6996344694550298E-5 1.5259691686799137 67 245558]
                           [["démontrer"] 2.202662127083997E-5 1.3159795509139562 68 245558]
                           [["enfer"] 2.0400853024453436E-5 1.1327100042707716 47 245558]
                           [["minorités"] 2.032217190796326E-5 1.111421528223887 44 245558]
                           [["les" "manifestations"] 2.0299868930144516E-5 1.3229701187564433 87 245558]
                           [["l'" "enfer"] 1.9985576096760715E-5 1.0028616571694182 30 245558]
                           [["-vous"] 1.6807984718723856E-5 1.547494964059715 225 245558]
                           [["religieux"] 1.6607274041702605E-5 1.3486063838923128 151 245558]
                           [["religions"] 1.5234253015312949E-5 1.1654133044566553 114 245558]
                           [["manifestations"] 1.4879424992288517E-5 1.3440279871058358 188 245558]
                           [["la" "française"] 1.3850240204405125E-5 0.7639119208728408 31 245558]
                           [["voile"] 1.374868582743917E-5 1.0705306164396082 109 245558]],
    :cluster-id 35}
   {:n-docs-in-cluster 94.50647714275577,
    :characteristic-words [[["html"] 4.834258982017916E-4 16.175738372071127 113 245558]
                           [["morts"] 3.2380412904136485E-5 2.3706305691980756 234 245558]
                           [["faim"] 3.185619483085135E-5 1.6168088286783981 56 245558]
                           [["vincent"] 3.072178961954948E-5 1.5036474588709716 46 245558]
                           [["de" "faim"] 2.800687119233758E-5 1.26520933446779 29 245558]
                           [["psychiatrie"] 2.6963525349784148E-5 1.2946225380056682 37 245558]
                           [["mr"] 2.1777998128105075E-5 1.1361881358268158 43 245558]
                           [["bourdieu"] 2.076784509255636E-5 1.0089175581745609 30 245558]
                           [["43"] 1.8261366088669247E-5 0.9063452228939242 29 245558]
                           [["tue"] 1.7044623805135065E-5 1.2434700868384851 121 245558]
                           [["mourir"] 1.6880986324838143E-5 1.1949659394922332 108 245558]
                           [["greenpeace"] 1.6849021883712928E-5 1.058599972937222 70 245558]
                           [["t'" "assure"] 1.5942457848982378E-5 0.8645922960022866 37 245558]
                           [["je" "t'" "assure"] 1.5942457848982378E-5 0.8645922960022866 37 245558]
                           [["sur" "le" "même"] 1.3688388835165002E-5 0.848550314358251 54 245558]
                           [["assure"] 1.2377163740077968E-5 0.8727740115491431 78 245558]
                           [["ou" "en"] 1.1805811456700593E-5 0.8742352142299589 88 245558]
                           [["honneur"] 1.1599674337160054E-5 0.7773214507297453 61 245558]
                           [["article" "en"] 1.1592055658152939E-5 0.8205081382955787 74 245558]
                           [["condamnations"] 1.1229364040263833E-5 0.6455306560673059 33 245558]],
    :cluster-id 75}
   {:n-docs-in-cluster 93.76589989666448,
    :characteristic-words [[["puissance"] 1.365579696957931E-4 6.210619407872336 151 245558]
                           [["la" "puissance"] 5.7987570796435954E-5 2.5240257093236993 51 245558]
                           [["éoliennes"] 2.9315278469358927E-5 1.5327375988301697 59 245558]
                           [["ondes"] 1.2685977538122624E-5 0.7360442711612578 39 245558]
                           [["renouvelables"] 1.2394374842256833E-5 0.8137270962643822 61 245558]
                           [["énergies" "renouvelables"] 1.1533888593997198E-5 0.6718243601034716 36 245558]
                           [["ferme"] 1.0676241472135084E-5 0.9089275011189315 126 245558]
                           [["énergies"] 1.066088797969239E-5 0.8284215323864091 94 245558]
                           [["flux"] 1.0298026387740682E-5 0.6387895044713302 41 245558]
                           [["hmm"] 1.028166226661048E-5 0.843509357428993 108 245558]
                           [["capacité"] 9.33445986027806E-6 0.8865202117465611 153 245558]
                           [["attends" "de" "voir"] 8.841786731167076E-6 0.526185846566613 30 245558]
                           [["j'" "attends" "de"] 8.823066928907546E-6 0.5253580762419445 30 245558]
                           [["compter" "les"] 8.495461877224691E-6 0.5622650325819338 43 245558]
                           [["énergie"] 8.433412337988805E-6 1.1103029765940784 331 245558]
                           [["surface"] 8.137979841414678E-6 0.6385535113308293 74 245558]
                           [["attends" "de"] 8.07340534819765E-6 0.5275598008153422 39 245558]
                           [["pour" "tes"] 7.777430063899034E-6 0.4907553911087298 33 245558]
                           [["ici" "pour"] 7.752498102444466E-6 0.49352879523865734 34 245558]
                           [["la" "surface"] 7.5438309672356055E-6 0.47598996880845384 32 245558]],
    :cluster-id 40}
   {:n-docs-in-cluster 73.04819218214658,
    :characteristic-words [[["fox" "news"] 3.4668839886662674E-5 1.4860970706063952 36 245558]
                           [["fox"] 3.2369155231301745E-5 1.487996457071427 47 245558]
                           [["placé"] 1.8964915628247488E-5 1.0661013676793563 66 245558]
                           [["news"] 1.812767602647064E-5 1.5398673209649876 274 245558]
                           [["détention"] 1.7301017769264307E-5 0.9299092665141238 50 245558]
                           [["vincent"] 1.6320725027741192E-5 0.8720783904450667 46 245558]
                           [["prendre" "de"] 1.5634010284162024E-5 0.8069035803989233 38 245558]
                           [["pour" "prendre"] 1.4506715786357337E-5 0.8098664630482885 49 245558]
                           [["l'" "antisionisme"] 1.2513389807845311E-5 0.667640113298231 35 245558]
                           [["israel"] 1.1838043611916288E-5 0.789096983778006 79 245558]
                           [["antisionisme"] 1.1743406140607947E-5 0.6689509552878357 43 245558]
                           [["pas" "beaucoup"] 1.0914162560351354E-5 0.7795721847409566 93 245558]
                           [["condescendance"] 1.0495251751347322E-5 0.6256737559812565 46 245558]
                           [["le" "sucre"] 1.0408901790539757E-5 0.6148210186744508 44 245558]
                           [["projet"] 1.0045625726044088E-5 1.1070560216650402 321 245558]
                           [["il" "faut" "que"] 9.642078307222496E-6 0.7813463114367204 125 245558]
                           [["problématique"] 9.462899308360431E-6 0.7309924683391773 105 245558]
                           [["iront"] 9.304937612850984E-6 0.5259882755581151 33 245558]
                           [["phrases"] 9.115962072725817E-6 0.6709049913982531 86 245558]
                           [["avocat"] 9.057411747436309E-6 0.8894454989833449 210 245558]],
    :cluster-id 51}
   {:n-docs-in-cluster 70.69072521971489,
    :characteristic-words [[["verre"] 1.1735813570695752E-4 4.828067453836867 105 245558]
                           [["le" "verre"] 6.048365481601658E-5 2.3874267182853295 43 245558]
                           [["obligatoires"] 2.104749405866177E-5 1.1005129671520713 56 245558]
                           [["porno"] 1.861154711898464E-5 0.9934114323118656 54 245558]
                           [["pdg"] 1.7490600099965593E-5 0.9640794173358277 58 245558]
                           [["vinci"] 1.7332870676001894E-5 0.8165766672112194 29 245558]
                           [["étude" "qui"] 1.5459959624140353E-5 0.803483418797725 40 245558]
                           [["le" "porno"] 1.3588764213076643E-5 0.6960484089365776 33 245558]
                           [["le" "pdg"] 1.1482479952443399E-5 0.6074546687684382 32 245558]
                           [["j'" "avais" "lu"] 1.0792864715980163E-5 0.8110772253137154 113 245558]
                           [["coûtent"] 1.0385129501103325E-5 0.5790341414005364 36 245558]
                           [["avais" "lu"] 1.0049981913248449E-5 0.813128107567593 134 245558]
                           [["amateur"] 1.0041307189474075E-5 0.55265303950903 33 245558]
                           [["boire"] 9.150415272888429E-6 0.6946048552732274 99 245558]
                           [["des" "sous"] 9.134557211792384E-6 0.5221159531605329 35 245558]
                           [["une" "étude"] 8.393256421749054E-6 0.8270392905652737 203 245558]
                           [["sinon"] 7.963603203250957E-6 1.5417023721550793 992 245558]
                           [["sent"] 7.53031125057084E-6 0.7216357876612369 168 245558]
                           [["documentaire"] 7.394908450152754E-6 0.647372236601945 126 245558]
                           [["ce" "mouvement"] 7.0548107395356635E-6 0.5554584530102175 86 245558]],
    :cluster-id 68}
   {:n-docs-in-cluster 68.58448091312401,
    :characteristic-words [[["tir"] 7.110530167090626E-5 2.6816421376945616 41 245558]
                           [["lbd"] 5.592148092210033E-5 2.3953904575898157 62 245558]
                           [["rap"] 4.853578745375297E-5 1.856422400932511 30 245558]
                           [["63"] 4.758772634757668E-5 1.8154297347341024 29 245558]
                           [["balles"] 4.220284480031464E-5 2.3000966800041347 139 245558]
                           [["tirs"] 3.643807983840647E-5 1.5516311972667693 39 245558]
                           [["la" "tête"] 2.469966047040882E-5 1.755744544895701 222 245558]
                           [["en" "plastique"] 2.44885933342303E-5 1.0941493300510257 33 245558]
                           [["tête"] 2.1906542661476874E-5 2.071300819590572 489 245558]
                           [["prétends"] 1.648233485619134E-5 0.8381110817305032 40 245558]
                           [["prouver" "que"] 1.61800057056306E-5 0.8402023688576177 43 245558]
                           [["plastique"] 1.4767148671077784E-5 1.1206094356694536 165 245558]
                           [["quotidien"] 1.1617035545162524E-5 0.8530656882476677 116 245558]
                           [["prouver"] 1.0844364009226712E-5 0.8527848565237688 136 245558]
                           [["a" "bien"] 8.957143596693973E-6 1.0017662475050404 317 245558]
                           [["alors" "qu'" "ils"] 8.644062700667067E-6 0.5175479545441941 41 245558]
                           [["se" "sont" "fait"] 8.455420597452794E-6 0.5185670985490954 44 245558]
                           [["tiré"] 8.236408289276527E-6 0.5679701264552319 66 245558]
                           [["sont" "fait"] 7.824486342127186E-6 0.5202322657935612 55 245558]
                           [["fais" "l'"] 6.720003426186408E-6 0.39285393819271675 29 245558]],
    :cluster-id 27}
   {:n-docs-in-cluster 62.02690045728515,
    :characteristic-words [[["android"] 1.627115832306585E-4 5.525606861161714 59 245558]
                           [["app"] 9.190598260474473E-5 3.1736715574298024 36 245558]
                           [["windows"] 7.150695918856434E-5 2.950202191759862 73 245558]
                           [["microsoft"] 4.793534137039271E-5 2.012309801377074 53 245558]
                           [["abandonner"] 2.3027007604232465E-5 1.162455433555241 60 245558]
                           [["massacre"] 1.9225157504353628E-5 0.8855084038971828 33 245558]
                           [["conseille"] 1.757928867645968E-5 1.1819525004080678 143 245558]
                           [["10"] 1.7205556218993778E-5 2.24849485169489 1013 245558]
                           [["faut" "avoir"] 1.5953186049738957E-5 0.8889517941630541 63 245558]
                           [["et" "voilà"] 1.3573839660976396E-5 0.6797064971754896 34 245558]
                           [["dommage"] 1.3379334394585801E-5 1.4049853283738827 443 245558]
                           [["pc"] 1.2059044582362136E-5 0.7802169100390667 85 245558]
                           [["mobile"] 1.1169031953974298E-5 0.7158853742627909 76 245558]
                           [["voilà"] 1.0450510068420182E-5 1.2862850762068168 526 245558]
                           [["google"] 9.727415573330261E-6 1.165811574361739 457 245558]
                           [["original"] 8.923025500158499E-6 0.6485349074176505 95 245558]
                           [["et" "pour" "les"] 8.659505174217833E-6 0.4856644602383557 35 245558]
                           [["de" "passer"] 8.397273797968938E-6 0.6964755817341417 138 245558]
                           [["conflits"] 8.285560831058046E-6 0.4524389542421565 30 245558]
                           [["téléphones"] 7.601667952758517E-6 0.44662689797663613 37 245558]],
    :cluster-id 34}
   {:n-docs-in-cluster 60.40430848570261,
    :characteristic-words [[["hé"] 1.7480430391271506E-5 1.0479621446597105 95 245558]
                           [["banlieues"] 1.3417865880775877E-5 0.6909864139395903 39 245558]
                           [["délinquants"] 1.322525771734178E-5 0.666589881492798 35 245558]
                           [["un" "pays" "qui"] 1.2483594759310143E-5 0.7165673224373758 57 245558]
                           [["pas" "me"] 1.1656354484434507E-5 0.7149657978104652 69 245558]
                           [["je" "ne" "veux"] 1.1451969885161763E-5 0.7162431125106326 73 245558]
                           [["ne" "veux" "pas"] 1.0727534175537443E-5 0.7183736427841226 88 245558]
                           [["démonstration"] 1.046940153749936E-5 0.6396147420762986 61 245558]
                           [["ne" "veux"] 9.666346940257961E-6 0.7219882213394085 116 245558]
                           [["dans" "un" "pays"] 9.451686845737134E-6 0.7250708577863645 124 245558]
                           [["rumeurs"] 9.30461353264387E-6 0.5230818266344551 39 245558]
                           [["pays" "qui"] 9.207786865765913E-6 0.7975497419191788 178 245558]
                           [["pense" "que" "tu"] 9.11214209557698E-6 0.8431151594042319 215 245558]
                           [["profits"] 8.66521435459195E-6 0.4861131833714325 36 245558]
                           [["étape"] 8.612018403683815E-6 0.5952039514644003 79 245558]
                           [["la" "colonisation"] 8.334292139163153E-6 0.5077791417664066 48 245558]
                           [["veux" "pas"] 7.755082418682904E-6 0.7334698604245611 195 245558]
                           [["colonisation"] 7.5890835190151745E-6 0.5101240361418461 63 245558]
                           [["personnes" "qui" "ont"] 7.076066323655726E-6 0.43458845547482833 42 245558]
                           [["internationale"] 6.966419423667415E-6 0.4922262735077873 69 245558]],
    :cluster-id 67}
   {:n-docs-in-cluster 58.0409115548683,
    :characteristic-words [[["alsace"] 1.9933458369586834E-5 0.9040981299890598 34 245558]
                           [["voir" "les"] 1.4920808813004283E-5 1.1784419263198067 225 245558]
                           [["ciel"] 1.2328714369528627E-5 0.6174600352272196 33 245558]
                           [["28"] 1.0670765236308298E-5 0.6447207076498132 62 245558]
                           [["regarde" "le"] 1.0565198659634456E-5 0.6426525955045841 63 245558]
                           [["est" "stupide"] 1.0510973863991219E-5 0.5461528155038127 33 245558]
                           [["toutes" "façons"] 1.0425731001005058E-5 0.6229313409821756 58 245558]
                           [["de" "toutes" "façons"] 1.0425731001005058E-5 0.6229313409821756 58 245558]
                           [["le" "consommateur"] 9.223963494784E-6 0.5475654221522405 50 245558]
                           [["façons"] 8.860118384501278E-6 0.6271373599250305 92 245558]
                           [["crois" "que" "le"] 8.492591273242742E-6 0.5498011466166566 64 245558]
                           [["dans" "quel"] 8.317925174280809E-6 0.588089374407533 86 245558]
                           [["consommateur"] 8.133443978044011E-6 0.5503788634592476 72 245558]
                           [["région"] 7.713122241412643E-6 0.663811386869157 152 245558]
                           [["pubs"] 7.595802646971454E-6 0.502631931811886 62 245558]
                           [["merde" "je"] 7.105051464050341E-6 0.444298932712119 47 245558]
                           [["souhaites"] 7.028074040930574E-6 0.3898734242095974 29 245558]
                           [["descendants"] 6.859764887135085E-6 0.3922131264451686 32 245558]
                           [["de" "toutes"] 6.810053237871899E-6 0.6427153460485411 177 245558]
                           [["stupide"] 6.394720409920798E-6 0.5630026008328053 135 245558]],
    :cluster-id 62}
   {:n-docs-in-cluster 50.83090071219725,
    :characteristic-words [[["envoyé"] 6.24825729949579E-5 2.789935224015301 115 245558]
                           [["envoyé" "spécial"] 5.936511183496276E-5 2.1318110359883775 35 245558]
                           [["spécial"] 5.51504913463563E-5 2.3590885867651905 82 245558]
                           [["histoires"] 4.836878821312413E-5 2.1920936006494793 95 245558]
                           [["belles"] 4.806736688203663E-5 1.8639729604524717 43 245558]
                           [["des" "politiciens"] 2.7752677076249913E-5 1.1578698890118928 36 245558]
                           [["politiciens"] 2.5188153072612518E-5 1.4388322672852807 135 245558]
                           [["schiappa"] 1.8569008084426755E-5 0.9881982862595583 74 245558]
                           [["france" "tv"] 1.596736087703353E-5 0.7475170512955279 36 245558]
                           [["plutôt" "que"] 1.4239623528072148E-5 1.198925599412343 301 245558]
                           [["tv"] 1.0640515759486463E-5 0.7643584456836148 133 245558]
                           [["que" "des"] 9.22867951743539E-6 1.2686750158535267 746 245558]
                           [["does"] 9.021306666568557E-6 0.46080317261332726 30 245558]
                           [["plutôt"] 7.2548824077978136E-6 1.7512768653825268 1959 245558]
                           [["oui" "de"] 5.573851301015828E-6 0.3804016857737125 58 245558]
                           [["arnaque"] 5.5268551231116986E-6 0.3835072819442534 61 245558]
                           [["ministre"] 5.203284116572782E-6 0.767698385369035 495 245558]
                           [["obligatoire"] 5.055371937762176E-6 0.48156176295150965 154 245558]
                           [["what"] 5.037584106677359E-6 0.48892303414591626 162 245558]
                           [["citation"] 4.751665615405562E-6 0.48175916893645665 173 245558]],
    :cluster-id 88}
   {:n-docs-in-cluster 48.22211188337413,
    :characteristic-words [[["ais"] 2.2571594660259278E-5 0.942990046042455 31 245558]
                           [["l'" "aéroport"] 2.2243270013639835E-5 0.9437007542705074 33 245558]
                           [["aéroport"] 1.9310469501984846E-5 0.9476658147374252 57 245558]
                           [["quand-même"] 1.9277101427463486E-5 0.9463964126834806 57 245558]
                           [["a" "0"] 1.8341672264284882E-5 0.7939235668350935 30 245558]
                           [["toulouse"] 1.8208583648402638E-5 0.9584407893321145 73 245558]
                           [["score"] 1.3263343524088853E-5 0.8395605718561802 111 245558]
                           [["comme" "l'"] 1.3001178440823213E-5 0.9694652838025011 195 245558]
                           [["justifie"] 1.2711477542641167E-5 0.7978338635049921 103 245558]
                           [["un" "tel"] 1.2545357641498972E-5 0.798234242844863 107 245558]
                           [["comportement"] 1.0272460302307093E-5 0.809903417989998 185 245558]
                           [["les" "théories"] 9.391376069290379E-6 0.47996779333847533 33 245558]
                           [["0"] 9.386283834433504E-6 0.8305927761875813 243 245558]
                           [["pourcentage" "de"] 9.294317226291211E-6 0.5038694372457462 42 245558]
                           [["tel"] 8.978589886420724E-6 0.8204625830696485 256 245558]
                           [["complotistes"] 8.738742542777587E-6 0.4808662415485808 42 245558]
                           [["peuple" "qui"] 8.604353956481042E-6 0.44232425779198514 31 245558]
                           [["on" "va" "pas"] 8.501048243899495E-6 0.5321259968025572 68 245558]
                           [["un" "peuple"] 8.33171645090152E-6 0.443962607284377 35 245558]
                           [["regarde" "le"] 8.28728634009665E-6 0.5118600432641837 63 245558]],
    :cluster-id 71}
   {:n-docs-in-cluster 47.329545165050035,
    :characteristic-words [[["île"] 2.0561083780450996E-5 0.9039104148431308 37 245558]
                           [["les" "différences"] 1.4222371006132432E-5 0.6880654292719033 40 245558]
                           [["différences"] 1.0904530859517751E-5 0.6944873895963082 95 245558]
                           [["quelles" "sont" "les"] 1.0386247982466408E-5 0.5200371510628 34 245558]
                           [["les" "coûts"] 9.989617990077693E-6 0.520835336958312 39 245558]
                           [["quelles" "sont"] 9.711275316848234E-6 0.5215656185324377 43 245558]
                           [["coûts"] 8.32156186841241E-6 0.5252909432561181 70 245558]
                           [["sexiste"] 7.689490477143235E-6 0.4516995039731279 49 245558]
                           [["fasciste"] 7.2798982822660716E-6 0.45510736570881755 59 245558]
                           [["homophobe"] 6.8909549984595E-6 0.4542442719322363 68 245558]
                           [["les" "salaires"] 6.651922609672567E-6 0.5403010431015084 134 245558]
                           [["reste" "plus"] 6.4521005058429046E-6 0.3691834474110829 37 245558]
                           [["quelles"] 6.066845439137852E-6 0.5406265187655255 163 245558]
                           [["stat"] 5.899863149897225E-6 0.3452697639475266 37 245558]
                           [["qu'" "elle" "n'"] 5.416518633230387E-6 0.3488125420365149 49 245558]
                           [["cet" "article" "est"] 5.37583623452971E-6 0.3468999586853178 49 245558]
                           [["initial"] 5.356661172700554E-6 0.3478941434929137 50 245558]
                           [["salaires"] 5.208088339208813E-6 0.5575753998370513 237 245558]
                           [["migrants"] 4.799713333496214E-6 0.47471997240771735 175 245558]
                           [["témoignages"] 4.794039716863284E-6 0.35334241030862473 70 245558]],
    :cluster-id 1}
   {:n-docs-in-cluster 46.93522555010756,
    :characteristic-words [[["historiens"] 3.112477106056739E-5 1.24321827337193 35 245558]
                           [["le" "bilan"] 1.7166104383915456E-5 0.8160163540203811 45 245558]
                           [["bilan"] 1.4255448045817805E-5 0.8233745379870216 86 245558]
                           [["je" "partage"] 1.4152419972397051E-5 0.81892127182071 86 245558]
                           [["les" "réformes"] 1.0512266497261606E-5 0.5307451896357129 36 245558]
                           [["partage"] 9.8952216397441E-6 0.8460956537463322 237 245558]
                           [["facture"] 9.859115153449359E-6 0.532276309992081 45 245558]
                           [["belle"] 9.594529920348843E-6 0.8648713312248071 270 245558]
                           [["réformes"] 8.004030787943199E-6 0.5387766552256126 86 245558]
                           [["burkini"] 6.6883622651808805E-6 0.40421335192376867 48 245558]
                           [["révèle"] 5.452994773710862E-6 0.3156255939776554 33 245558]
                           [["la" "très"] 5.394623218033315E-6 0.31068998547713716 32 245558]
                           [["rouge"] 4.5400336232351984E-6 0.42973884613785407 147 245558]
                           [["sont" "tous"] 4.3839352828114325E-6 0.34597387811992664 81 245558]
                           [["que" "la" "plupart"] 3.937668311361844E-6 0.3459611930587112 102 245558]
                           [["je" "trouve"] 3.651481742271423E-6 1.011217295411719 1373 245558]
                           [["pourtant"] 3.5963259351762256E-6 0.8946905146024573 1107 245558]
                           [["colère"] 3.5740704754459035E-6 0.32533550072513323 103 245558]
                           [["trouve"] 3.431696764005787E-6 1.199513231702649 1947 245558]
                           [["la" "plupart" "des"] 3.3547604246543516E-6 0.534850513160149 412 245558]],
    :cluster-id 14}
   {:n-docs-in-cluster 45.95021579805142,
    :characteristic-words [[["hum"] 1.9759019609639702E-5 1.126605572975393 116 245558]
                           [["con" "comme"] 6.187951915277885E-6 0.3551649866365669 37 245558]
                           [["appelé"] 5.115220302763859E-6 0.37563538994505313 76 245558]
                           [["pareil" "pour"] 5.065330120222235E-6 0.375916962043014 78 245558]
                           [["un" "mouvement"] 4.619377570180827E-6 0.4573751878566107 174 245558]
                           [["d'" "extrême-droite"] 4.426645329179933E-6 0.27497011707257696 36 245558]
                           [["ça" "un"] 4.395923755509505E-6 0.36424005011253446 97 245558]
                           [["différences"] 4.373015198465106E-6 0.3605616791642162 95 245558]
                           [["la" "con"] 4.285965987617726E-6 0.36721012697695216 105 245558]
                           [["la" "croissance"] 3.9510855660857686E-6 0.3858748117497238 143 245558]
                           [["a" "plein" "de"] 3.925382131219138E-6 0.3688479341251766 127 245558]
                           [["décision" "de"] 3.7752788712760305E-6 0.24834302133416408 38 245558]
                           [["cathos"] 3.763873693742731E-6 0.24779829602581277 38 245558]
                           [["sont" "pour"] 3.431371549258294E-6 0.26595752728972216 61 245558]
                           [["y" "a" "plein"] 3.4012489202126656E-6 0.3747286410631814 172 245558]
                           [["ou" "d'"] 3.2036156101551E-6 0.2839437551147413 87 245558]
                           [["extrême-droite"] 3.1631773486856876E-6 0.28366565866296556 89 245558]
                           [["de" "justice"] 3.1469003422095188E-6 0.2504792216190094 61 245558]
                           [["croissance"] 3.0646494346368153E-6 0.39880991758666273 238 245558]
                           [["a" "plein"] 3.0300818844308797E-6 0.38100769627756337 216 245558]],
    :cluster-id 49}
   {:n-docs-in-cluster 43.55711306745972,
    :characteristic-words [[["drapeau"] 3.388532287801945E-5 1.4429735258539882 57 245558]
                           [["caisses"] 5.450121721094735E-6 0.32295700117326614 39 245558]
                           [["nan" "c'" "est"] 5.136527258220794E-6 0.2893038335178182 30 245558]
                           [["nan" "c'"] 5.042733764848602E-6 0.2899224441285958 32 245558]
                           [["qu'" "on" "m'"] 5.014725526308536E-6 0.3242014145334088 50 245558]
                           [["oblige"] 4.443322019352831E-6 0.32682688512987557 70 245558]
                           [["sorti" "de"] 2.9059533916946795E-6 0.19797324937851815 35 245558]
                           [["on" "m'"] 2.8698048358047346E-6 0.3501843719311 200 245558]
                           [["nulle" "part"] 2.2510598777260304E-6 0.20110240096887153 66 245558]
                           [["nulle"] 1.8979920186892527E-6 0.20593272011994232 97 245558]
                           [["américain"] 1.7577213067016678E-6 0.24444486350242675 169 245558]
                           [["sorti"] 1.5292072611007568E-6 0.2168670705256764 154 245558]
                           [["nan"] 1.4627514604466374E-6 0.3760403143851576 514 245558]
                           [["pendant" "un"] 1.356458763562829E-6 0.12728664132228398 46 245558]
                           [["l'" "espagne"] 1.2773527896204825E-6 0.11369327150601352 37 245558]
                           [["porte" "pas"] 1.081264777555782E-6 0.09384725360367 29 245558]
                           [["est" "pas" "un"] 9.940485157025614E-7 0.367143494478227 663 245558]
                           [["vous" "vous"] 9.893040448585855E-7 0.1399716218440534 99 245558]
                           [["bat"] 9.554494809285718E-7 0.1053926628869569 51 245558]
                           [["espagne"] 9.013335942774023E-7 0.12379249889538423 84 245558]],
    :cluster-id 79}
   {:n-docs-in-cluster 43.35375645868656,
    :characteristic-words [[["couleurs"] 9.548052847166595E-6 0.4839868806507634 36 245558]
                           [["a" "trois"] 7.510288693123729E-6 0.3828768707112613 29 245558]
                           [["renouvelable"] 7.444660825251838E-6 0.49530855374113764 83 245558]
                           [["rentable"] 6.143649964250944E-6 0.5023514791666318 138 245558]
                           [["maths"] 5.72647756518635E-6 0.4534981289467518 116 245558]
                           [["l'" "ia"] 4.787469705994606E-6 0.3130435242129268 50 245558]
                           [["de" "tomber"] 4.706946434142149E-6 0.28740393732962677 38 245558]
                           [["chimie"] 4.5591595714935054E-6 0.2807129498051381 38 245558]
                           [["rouge"] 4.439699197783184E-6 0.4121500972589995 147 245558]
                           [["remplacement"] 4.071870651146253E-6 0.31713408408698646 78 245558]
                           [["les" "maths"] 3.942026878862685E-6 0.28100671622225587 56 245558]
                           [["fossile"] 3.892477029510642E-6 0.24636702899931354 36 245558]
                           [["ia"] 3.637216562113639E-6 0.31957594337401396 102 245558]
                           [["impopulaire"] 3.632184387430612E-6 0.24905070115430217 45 245558]
                           [["il" "faudrait" "que"] 3.6295872609080904E-6 0.27442941888564754 63 245558]
                           [["sera" "jamais"] 3.6056207936051646E-6 0.2199865879430775 29 245558]
                           [["programme" "de"] 3.4843921778611453E-6 0.293766009559382 86 245558]
                           [["le" "nouveau"] 3.3425441949520504E-6 0.30202579805141255 102 245558]
                           [["jaune"] 3.2708372969189603E-6 0.44906286591193895 307 245558]
                           [["ainsi" "que"] 3.247006554110178E-6 0.28933355076633116 95 245558]],
    :cluster-id 57}
   {:n-docs-in-cluster 43.106867369986176,
    :characteristic-words [[["ho"] 2.5302011750436178E-5 1.158890828079242 61 245558]
                           [["hey"] 1.1385047644454395E-5 0.6895380044947242 90 245558]
                           [["hein"] 6.692801656757452E-6 0.8922229843238276 591 245558]
                           [["aveugle"] 4.8494471707721115E-6 0.32054982665143533 53 245558]
                           [["le" "père"] 3.992431865573222E-6 0.28879314576493986 60 245558]
                           [["calme"] 3.91522889104691E-6 0.3265579297698396 94 245558]
                           [["_"] 3.7808514879019914E-6 0.30073615587461144 78 245558]
                           [["sarko"] 3.540927502121774E-6 0.29535603324611626 85 245558]
                           [["technique"] 3.2440489349649243E-6 0.37306343849080387 196 245558]
                           [["on" "dit"] 3.1738872445109118E-6 0.37515101723633637 206 245558]
                           [["faites"] 2.995479007519841E-6 0.38533434747165896 241 245558]
                           [["devenir"] 2.8673840031731568E-6 0.3553260625950646 210 245558]
                           [["suisse"] 2.7706548550478216E-6 0.3861669763103662 271 245558]
                           [["les" "enfants"] 2.1995990807568283E-6 0.3183045763944589 235 245558]
                           [["père"] 2.1562122418140872E-6 0.319236988030637 243 245558]
                           [["ce" "qu'" "on"] 2.1333887122894357E-6 0.41506761552032023 438 245558]
                           [["ne" "veut"] 2.109403622125444E-6 0.3647110104492834 337 245558]
                           [["personne" "ne"] 1.7280605482880573E-6 0.3730460527571688 438 245558]
                           [["ouais" "enfin" "c'"] 1.6387882966137357E-6 0.13065615718262916 34 245558]
                           [["on" "se"] 1.5437265407682033E-6 0.41280796788708196 590 245558]],
    :cluster-id 58}
   {:n-docs-in-cluster 42.74108457309159,
    :characteristic-words [[["associer"] 9.802169779261558E-6 0.47266504871178466 30 245558]
                           [["rappel" "que"] 8.44574694248805E-6 0.4332358898789484 34 245558]
                           [["autorise"] 8.075870967546965E-6 0.4497788683833602 46 245558]
                           [["évite"] 7.420462827516933E-6 0.4822332366790375 77 245558]
                           [["amalgame"] 7.317738371266647E-6 0.4505872224182234 62 245558]
                           [["communistes"] 6.8904142207582356E-6 0.487569322692406 97 245558]
                           [["j'" "aimerai"] 6.859589150491965E-6 0.48601586714209344 97 245558]
                           [["aimerai"] 6.821273405907893E-6 0.486932892526952 99 245558]
                           [["ou" "on"] 6.418671865293657E-6 0.4551596580293101 91 245558]
                           [["que" "même"] 5.145219395348913E-6 0.44741458999006944 142 245558]
                           [["après" "avoir"] 4.810110842455931E-6 0.45970658257877245 176 245558]
                           [["garde"] 4.6129147140867854E-6 0.47848270363660056 213 245558]
                           [["rappel"] 3.897345799362337E-6 0.4807925833457874 285 245558]
                           [["uniquement"] 2.975302576872091E-6 0.513375872630582 478 245558]
                           [["ah" "merci"] 2.7695604219628647E-6 0.18392106488603438 31 245558]
                           [["est" "bien" "mais"] 2.411227837063358E-6 0.18433725061281445 44 245558]
                           [["le" "communisme"] 1.6321876539909796E-6 0.20001178921100926 117 245558]
                           [["un" "réseau"] 1.4911581307369692E-6 0.131695137923552 43 245558]
                           [["communisme"] 1.472458164630125E-6 0.2034524233959601 142 245558]
                           [["et" "les"] 1.355093899903237E-6 0.7114259792142649 1622 245558]],
    :cluster-id 91}
   {:n-docs-in-cluster 42.60737421301899,
    :characteristic-words [[["marques"] 1.5862409107305492E-5 0.7285354080860735 39 245558]
                           [["moto"] 1.4829868756285718E-5 0.7128267583047592 45 245558]
                           [["désigne"] 1.4723014121802158E-5 0.7287780104404668 51 245558]
                           [["complet"] 1.0376356815196622E-5 0.7270953411253199 142 245558]
                           [["choisir"] 9.378423270331102E-6 0.7555351588778054 205 245558]
                           [["bons"] 9.34188980704874E-6 0.7348597615612621 189 245558]
                           [["les" "plus"] 6.089215681386734E-6 0.8107824329323694 542 245558]
                           [["fait" "qu'" "ils"] 5.298853061229229E-6 0.31653206159119485 40 245558]
                           [["énorme"] 2.516985494166457E-6 0.34488288906557013 239 245558]
                           [["le" "fait" "qu'"] 2.037094342474677E-6 0.3530760201482808 331 245558]
                           [["fait" "qu'"] 1.8196492021053945E-6 0.36131158260077284 394 245558]
                           [["intérêt"] 1.4720374391340163E-6 0.3853240119300457 547 245558]
                           [["très"] 1.1590990649235078E-6 1.3868146268162536 4484 245558]
                           [["que"] 7.739443081966613E-7 7.842176923444058 52990 245558]
                           [["est"] 7.719463076538702E-7 11.550430749604335 75359 245558]
                           [["je"] 7.472898815308326E-7 6.2894924115088395 43316 245558]
                           [["doublon"] 6.319914611641053E-7 0.09250331885566229 70 245558]
                           [["tu"] 6.162529997966182E-7 3.3503738424793963 24286 245558]
                           [["qu'" "ils"] 5.370016706723879E-7 0.7354203728533341 2470 245558]
                           [["pas"] 5.196094426951348E-7 7.758481896220126 51031 245558]],
    :cluster-id 25}
   {:n-docs-in-cluster 42.496222028528734,
    :characteristic-words [[["cv"] 9.475509044906162E-6 0.5121864803321204 48 245558]
                           [["fiscales"] 3.051571164666353E-6 0.22006550266301023 46 245558]
                           [["je" "te" "parle"] 2.7377414462993743E-6 0.21981689554745018 59 245558]
                           [["te" "parle"] 2.293292931834605E-6 0.22517814646667447 91 245558]
                           [["flagrant"] 1.7222565460951397E-6 0.1334166859363597 33 245558]
                           [["de" "la" "valeur"] 1.6834551747092752E-6 0.13488260063995958 36 245558]
                           [["parle" "des"] 1.6491363171201867E-6 0.24058226297104784 182 245558]
                           [["donc" "si" "on"] 1.6107405743188627E-6 0.13418949715405054 39 245558]
                           [["parts"] 1.5697977163338372E-6 0.12742533633899114 35 245558]
                           [["le" "parc"] 1.3305149499610522E-6 0.128241702211233 50 245558]
                           [["automobile"] 1.3105549834082145E-6 0.13044258243148513 54 245558]
                           [["des" "propos"] 1.2783834818655762E-6 0.1395292070890223 68 245558]
                           [["html"] 1.0632325030627415E-6 0.15269877177060923 113 245558]
                           [["parc"] 1.0556750180508098E-6 0.13262700033141203 81 245558]
                           [["la" "valeur"] 9.974709252475594E-7 0.14439560168888063 108 245558]
                           [["est" "juste" "un"] 8.86032214519536E-7 0.14295029528513023 123 245558]
                           [["hyper"] 8.761398891422856E-7 0.1540757854610968 147 245558]
                           [["pas" "des"] 8.249626752034056E-7 0.29701684460854955 540 245558]
                           [["prenait"] 7.342537003979938E-7 0.07779574742607215 36 245558]
                           [["donc" "si"] 6.45635820938073E-7 0.15663010362410607 208 245558]],
    :cluster-id 96}
   {:n-docs-in-cluster 42.458043875945116,
    :characteristic-words [[["formule"] 1.0973410373977914E-5 0.5331245585793564 35 245558]
                           [["développe"] 1.0102127729613893E-5 0.5349925067454971 47 245558]
                           [["magique"] 9.319860534196672E-6 0.5361704595885832 61 245558]
                           [["utilises"] 8.446405994199692E-6 0.539828206305947 83 245558]
                           [["tu" "l'"] 6.977746754441294E-6 0.5472550770731669 140 245558]
                           [["lol"] 4.488315758638395E-6 0.5701365893825331 356 245558]
                           [["imagine"] 3.318108820520721E-6 0.649937421417231 703 245558]
                           [["tu" "te"] 2.9267153467474327E-6 0.635561125063366 764 245558]
                           [["limite" "de"] 2.822580271302777E-6 0.18612720787401812 31 245558]
                           [["écouté"] 2.768273226289031E-6 0.2293530171694176 66 245558]
                           [["contribution"] 2.629868318003957E-6 0.1858870087109995 37 245558]
                           [["as" "lu"] 2.5006539181244083E-6 0.23271767104469332 85 245558]
                           [["exercice"] 2.266947532108231E-6 0.18888880082176507 55 245558]
                           [["on" "fait" "quoi"] 2.221884664723589E-6 0.1581234334139655 32 245558]
                           [["quand" "tu"] 2.1889463616767135E-6 0.7115288172613108 1208 245558]
                           [["une" "alternative"] 1.942749108599373E-6 0.1617444564969074 47 245558]
                           [["récemment"] 1.7404846862200535E-6 0.2469007754149269 180 245558]
                           [["fait" "quoi"] 1.6782939351606824E-6 0.1642613923364018 66 245558]
                           [["le" "transport"] 1.6341065274486782E-6 0.19828969354864667 115 245558]
                           [["on" "trouve"] 1.5916885552549193E-6 0.1669143175655059 76 245558]],
    :cluster-id 46}
   {:n-docs-in-cluster 42.42533950623829,
    :characteristic-words [[["dieudonné"] 1.8094631309095027E-5 0.7603130916211008 29 245558]
                           [["pisse"] 7.118837724117907E-6 0.3820524495887155 35 245558]
                           [["tu" "manges"] 6.9514901795643055E-6 0.3802306618922224 37 245558]
                           [["mais" "ma"] 6.89343780460594E-6 0.3778215048277921 37 245558]
                           [["manges"] 6.547105168542254E-6 0.3814019185499397 45 245558]
                           [["pleine"] 3.146760723309744E-6 0.2861495972884814 100 245558]
                           [["produits"] 2.531107364797669E-6 0.43409849469746825 404 245558]
                           [["plastique"] 2.527800512616696E-6 0.2976936663527477 165 245558]
                           [["et" "vous"] 1.3654835444845345E-6 0.12855301524577928 48 245558]
                           [["prostitution"] 1.251153346999885E-6 0.13139200217125158 60 245558]
                           [["clients"] 1.0254637303318964E-6 0.13342308354417282 86 245558]
                           [["en" "plus"] 8.748029702854154E-7 0.43350591594021004 963 245558]
                           [["que"] 7.311735100712369E-7 7.842925040883803 52990 245558]
                           [["je"] 7.102111747592232E-7 6.290092408041199 43316 245558]
                           [["doublon"] 6.341219662090586E-7 0.09251214336277885 70 245558]
                           [["ma"] 6.162061144260322E-7 0.6983403584314268 2220 245558]
                           [["html"] 5.156757620517621E-7 0.10290008024186542 113 245558]
                           [["est"] 4.795043466732096E-7 11.81722524260724 75359 245558]
                           [["pas"] 4.50101382165613E-7 7.797631093798542 51031 245558]
                           [["c'"] 4.423667229191963E-7 8.134755113873263 53018 245558]],
    :cluster-id 42}
   {:n-docs-in-cluster 42.327319281834754,
    :characteristic-words [[["accusé"] 1.2059218518264275E-5 0.6532793295267575 62 245558]
                           [["le" "nouveau"] 1.0434428610492721E-5 0.66514016139106 102 245558]
                           [["venu"] 1.0009283509573516E-5 0.661863783022019 112 245558]
                           [["régulièrement"] 8.083953563910867E-6 0.6716151719513469 196 245558]
                           [["nouveau"] 5.667439634222254E-6 0.7457585606770933 493 245558]
                           [["films"] 5.471468947972416E-6 0.34291255435667056 50 245558]
                           [["le" "manque"] 3.526625268739786E-6 0.3549986811134546 151 245558]
                           [["gagner"] 3.0194739292065814E-6 0.3646933928613453 211 245558]
                           [["cette" "histoire"] 2.808014416088092E-6 0.3349690391051403 190 245558]
                           [["manque"] 1.7616412860445296E-6 0.4225786038766946 559 245558]
                           [["par" "chez" "moi"] 1.5508350475956431E-6 0.12155554573165113 31 245558]
                           [["moi" "une"] 1.5381309536868434E-6 0.12086428891866571 31 245558]
                           [["histoire"] 1.5210618792838027E-6 0.5432113044558909 988 245558]
                           [["une" "place"] 1.4788390674666183E-6 0.12216565244894566 35 245558]
                           [["par" "chez"] 1.4461512376382976E-6 0.12246728144299379 37 245558]
                           [["pot"] 1.412672495199399E-6 0.12261008760504466 39 245558]
                           [["est" "l'"] 1.0389776259681072E-6 0.4957179230001392 1081 245558]
                           [["d'" "année"] 9.507595322942597E-7 0.08814062392300102 32 245558]
                           [["place" "de"] 8.672632633884014E-7 0.13790915582877192 117 245558]
                           [["je"] 6.915337454893589E-7 6.289631291408515 43316 245558]],
    :cluster-id 54}
   {:n-docs-in-cluster 42.16371523857124,
    :characteristic-words [[["l'" "informatique"] 1.0379157445965584E-5 0.5609743241433932 53 245558]
                           [["terres"] 9.665442718394777E-6 0.5630878921024409 67 245558]
                           [["rares"] 8.078220629079147E-6 0.5696328522268352 114 245558]
                           [["informatique"] 7.691786183695176E-6 0.5729256877501606 131 245558]
                           [["radio"] 7.2536547178481126E-6 0.5383530037777703 122 245558]
                           [["tri"] 4.039852613040018E-6 0.24174527948651667 31 245558]
                           [["une" "attaque"] 3.892128448357984E-6 0.24453616205108655 36 245558]
                           [["aujourd'hui"] 3.3485594042315103E-6 0.656577542610089 716 245558]
                           [["car" "je"] 2.4992644608989173E-6 0.2513284678564225 107 245558]
                           [["attaque"] 2.0120835993488184E-6 0.27395731186394795 190 245558]
                           [["national"] 1.8264358034592032E-6 0.2921244890299147 251 245558]
                           [["que"] 6.734560555887725E-7 7.842309083091555 52990 245558]
                           [["doublon"] 6.369628048228868E-7 0.09250487776030454 70 245558]
                           [["tu"] 5.593523242364284E-7 3.350430304381529 24286 245558]
                           [["perso"] 5.221539930294616E-7 0.3914041623358291 1070 245558]
                           [["html"] 5.186336610365355E-7 0.10289199880465694 113 245558]
                           [["est"] 4.425038065969389E-7 11.787438216791488 75359 245558]
                           [["pas"] 4.3922778214522396E-7 7.758612645403971 51031 245558]
                           [["je"] 4.1190360688947436E-7 6.526411219161392 43316 245558]
                           [["n'"] 3.036855055205123E-7 2.7223519335935387 19018 245558]],
    :cluster-id 24}
   {:n-docs-in-cluster 42.15151618150581,
    :characteristic-words [[["le" "cul"] 7.515970586678342E-6 0.5532598627626852 123 245558]
                           [["cul"] 6.644617986342749E-6 0.5621074882690067 171 245558]
                           [["propre"] 4.653995084362428E-6 0.5874111129416137 366 245558]
                           [["la" "génération"] 2.591922093054394E-6 0.17464330451397922 31 245558]
                           [["de" "voiture"] 2.4830126335220054E-6 0.21147632936910032 65 245558]
                           [["accidents"] 2.271596717890248E-6 0.21345282457262038 80 245558]
                           [["liés"] 2.1935663322347407E-6 0.21474515456953047 87 245558]
                           [["morts"] 2.1813384968351335E-6 0.31321061138829187 234 245558]
                           [["franchement" "je"] 1.622341902104453E-6 0.1858249102364285 99 245558]
                           [["génération"] 1.1367358472898909E-6 0.19730616230144027 187 245558]
                           [["actuelle"] 1.081741418293078E-6 0.19544977711513942 194 245558]
                           [["coller"] 1.0105100277362639E-6 0.11672596981724255 63 245558]
                           [["que" "cette"] 8.467209075095627E-7 0.21727750908319574 306 245558]
                           [["chiffres" "de" "le"] 8.033068083944037E-7 0.08133118710389507 35 245558]
                           [["éducation"] 7.866621848234834E-7 0.21954509344081297 332 245558]
                           [["de" "lui"] 7.840062194930039E-7 0.12299613674583607 103 245558]
                           [["libre" "toi"] 7.221954042195902E-7 0.07273519339773239 31 245558]
                           [["ministère" "de" "l'"] 7.112400886005038E-7 0.08539029102301333 49 245558]
                           [["de" "ces"] 7.041648012844071E-7 0.2922493931912116 588 245558]
                           [["de" "le" "ministère"] 7.028250751297399E-7 0.08477223629139653 49 245558]],
    :cluster-id 36}
   {:n-docs-in-cluster 41.92836376093232,
    :characteristic-words [[["faut" "pas" "se"] 4.014433701933842E-6 0.2422397362440476 32 245558]
                           [["leçon"] 3.765853502803347E-6 0.24518700487099607 40 245558]
                           [["retenir"] 3.4387708763771938E-6 0.2458408946889039 51 245558]
                           [["sur" "les" "réseaux"] 3.120445941434506E-6 0.2125108234830319 39 245558]
                           [["de" "haine"] 2.84273789932267E-6 0.2111116720641081 48 245558]
                           [["menaces"] 2.6299308604135763E-6 0.21736209341879206 63 245558]
                           [["le" "coup" "on"] 2.436917809427175E-6 0.2484106388226093 109 245558]
                           [["on" "appelle"] 2.407460300713621E-6 0.24888828046036574 112 245558]
                           [["les" "réseaux" "sociaux"] 2.368999989540814E-6 0.22089348712564 82 245558]
                           [["coup" "on"] 2.3618614951835537E-6 0.24978557415654024 117 245558]
                           [["réseaux" "sociaux"] 2.145279883324254E-6 0.2259111988985284 105 245558]
                           [["les" "réseaux"] 2.0188367042817124E-6 0.22628561533160044 117 245558]
                           [["pas" "se"] 1.9279103847403417E-6 0.2689103428509441 194 245558]
                           [["messages"] 1.753642918695883E-6 0.22818959809469933 149 245558]
                           [["ne" "faut" "pas"] 1.6677043606569408E-6 0.27214014068777365 241 245558]
                           [["réseaux"] 1.6585018806913876E-6 0.23772999962986793 178 245558]
                           [["il" "ne" "faut"] 1.4996007184353566E-6 0.27948768608510494 289 245558]
                           [["administrative"] 1.4814383567226738E-6 0.12084094999941375 34 245558]
                           [["ne" "faut"] 1.4667212813353203E-6 0.28123493018183454 300 245558]
                           [["formule"] 1.4387673495834877E-6 0.1195360477828428 35 245558]],
    :cluster-id 12}
   {:n-docs-in-cluster 41.668322093325294,
    :characteristic-words [[["préfecture"] 1.807208038930551E-5 0.8120775961600959 41 245558]
                           [["bordeaux"] 1.4843236489076213E-5 0.8217625574501853 85 245558]
                           [["journaliste"] 9.747586657133517E-6 0.8640488056106229 294 245558]
                           [["doublon"] 6.424265271065197E-7 0.09249346308586834 70 245558]
                           [["que"] 5.698118324959367E-7 7.841341378391204 52990 245558]
                           [["je"] 5.69174556819263E-7 6.288822297289209 43316 245558]
                           [["html"] 5.243228376472509E-7 0.10287930240748432 113 245558]
                           [["est"] 5.181142470611633E-7 11.549200108499814 75359 245558]
                           [["tu"] 4.992631614242349E-7 3.350016876764839 24286 245558]
                           [["pas"] 3.57701132713828E-7 7.757655268457283 51031 245558]
                           [["c'"] 3.181061878354541E-7 8.13311252434282 53018 245558]
                           [["c'" "est"] 3.0869732192684296E-7 7.345916237240043 48121 245558]
                           [["n'"] 2.648708964936297E-7 2.722016007945347 19018 245558]
                           [["d'" "accord"] 2.411224177267801E-7 0.34122038506350566 3157 245558]
                           [["accord"] 2.3813300041586327E-7 0.36434876298229096 3317 245558]
                           [["c" "3"] 2.213377763900931E-7 0.09195959582396165 187 245558]
                           [["ne"] 2.0315303717044486E-7 3.224792921946889 21792 245558]
                           [["que" "tu"] 2.0179751361593112E-7 0.5637013138201525 4608 245558]
                           [["c" "3" "a"] 1.9094744558439958E-7 0.07863372376750905 159 245558]
                           [["3" "a"] 1.8806999241630096E-7 0.08058196130149466 167 245558]],
    :cluster-id 99}
   {:n-docs-in-cluster 41.568860496607805,
    :characteristic-words [[["que" "tu" "parles"] 1.2398567409246594E-5 0.6200045709909212 46 245558]
                           [["pas" "que" "tu"] 1.136350082503023E-5 0.6217849836002493 62 245558]
                           [["oublies"] 9.696808488190468E-6 0.6241023898528142 100 245558]
                           [["bête"] 9.031797016723075E-6 0.6363245118228021 129 245558]
                           [["et" "oui"] 8.286474896448265E-6 0.635580168385885 158 245558]
                           [["tu" "parles"] 4.3966133918271755E-6 0.6886318335730957 587 245558]
                           [["parles"] 3.960682073540778E-6 0.6988194410097414 688 245558]
                           [["pas" "que"] 2.2184682745493567E-6 0.8264282052257402 1579 245558]
                           [["rats"] 8.881651009373659E-7 0.08551442567810155 34 245558]
                           [["doublon"] 6.438663908252262E-7 0.09251878316044913 70 245558]
                           [["je"] 5.496408587157831E-7 6.290543861649323 43316 245558]
                           [["que" "tu"] 5.264516995395763E-7 1.1780123004864822 4608 245558]
                           [["html"] 5.257792018865254E-7 0.1029074656043511 113 245558]
                           [["une" "telle"] 4.815907610400058E-7 0.09315588173988253 101 245558]
                           [["est"] 4.292590678467434E-7 11.630206694326612 75359 245558]
                           [["oui"] 3.81211871874898E-7 2.1400280060393753 9837 245558]
                           [["telle"] 3.2033727267143097E-7 0.103911060508516 179 245558]
                           [["c'" "est"] 2.381915364946252E-7 7.42577217460418 48121 245558]
                           [["d'" "accord"] 2.3812249201615732E-7 0.34131379410356116 3157 245558]
                           [["accord"] 2.3506728381861297E-7 0.36444850341306717 3317 245558]],
    :cluster-id 22}
   {:n-docs-in-cluster 41.512390107454586,
    :characteristic-words [[["tiens"] 3.511613297844246E-6 0.6361805363351687 646 245558]
                           [["demi"] 1.4691457909379521E-6 0.14789230383261967 64 245558]
                           [["plus" "d'" "un"] 1.4021656773036476E-6 0.15202370480043492 75 245558]
                           [["les" "attaques"] 1.3953263265824647E-6 0.15303889531190712 77 245558]
                           [["siècle"] 1.113923372247222E-6 0.15704232458126788 116 245558]
                           [["attaques"] 1.0711796864463358E-6 0.16121110108608025 130 245558]
                           [["donc" "les"] 1.0338239114678072E-6 0.16011925891145307 134 245558]
                           [["carrément"] 8.75977383634971E-7 0.17291816364427426 192 245558]
                           [["contre" "la"] 6.792598316110454E-7 0.19699313826948348 312 245558]
                           [["doublon"] 6.443353039366911E-7 0.09250420846742859 70 245558]
                           [["je"] 5.407894885234299E-7 6.289552898057037 43316 245558]
                           [["que"] 5.376127176770495E-7 7.842252342282536 52990 245558]
                           [["html"] 5.262888735663857E-7 0.10289125435870487 113 245558]
                           [["tu"] 4.802774097867335E-7 3.350406063290821 24286 245558]
                           [["est"] 4.792118940555312E-7 11.550541831014488 75359 245558]
                           [["plus" "d'"] 4.570504013624854E-7 0.22494877647767894 508 245558]
                           [["pas"] 3.326997189967429E-7 7.758556510156874 51031 245558]
                           [["pendant"] 3.2324924335164207E-7 0.3070516414363649 936 245558]
                           [["c'"] 2.939440804095028E-7 8.134057384600297 53018 245558]
                           [["c'" "est"] 2.8628945347719537E-7 7.3467696453647475 48121 245558]],
    :cluster-id 95}
   {:n-docs-in-cluster 41.489210758891815,
    :characteristic-words [[["veux" "dire" "que"] 3.2517895958575696E-6 0.27741526036206915 87 245558]
                           [["croyais" "qu'"] 3.1373712545638283E-6 0.19978380732319204 31 245558]
                           [["dire" "que" "la"] 2.7900503945799127E-6 0.27939437212577556 120 245558]
                           [["cité"] 2.693821373146374E-6 0.28419144960170895 134 245558]
                           [["n'" "est" "plus"] 1.71857258044264E-6 0.21642387785745984 136 245558]
                           [["chan"] 1.6992669189964255E-6 0.13241280633531552 34 245558]
                           [["tu" "veux" "dire"] 1.5649936807443643E-6 0.31642039833820523 361 245558]
                           [["croyais"] 1.5271307420260116E-6 0.23745884267296122 200 245558]
                           [["veux" "dire"] 1.114792639169554E-6 0.35545222642566277 607 245558]
                           [["il" "reste"] 9.962813940708748E-7 0.1445054450449862 111 245558]
                           [["est" "probablement"] 9.685752603329212E-7 0.14382869190521996 114 245558]
                           [["entre" "les"] 9.68383929097344E-7 0.2515917085119056 364 245558]
                           [["n'" "en"] 8.851190707037659E-7 0.24929350709221307 386 245558]
                           [["suffisamment"] 7.9842926940972E-7 0.15182867219876117 162 245558]
                           [["doublon"] 6.446592370028259E-7 0.09250906919656156 70 245558]
                           [["tu" "veux"] 5.285960913425924E-7 0.4579136632241114 1350 245558]
                           [["html"] 5.266178807646071E-7 0.10289666088588754 113 245558]
                           [["je"] 4.987980842807715E-7 6.325868920027292 43316 245558]
                           [["dire" "que"] 4.943854690422911E-7 0.48302475092884384 1490 245558]
                           [["r"] 3.969374098994338E-7 0.30779922056149317 867 245558]],
    :cluster-id 85}
   {:n-docs-in-cluster 41.42123184435382,
    :characteristic-words [[["violences" "policières"] 1.992021507932727E-6 0.18321837568801017 67 245558]
                           [["black"] 1.890830613394956E-6 0.19548506832119905 89 245558]
                           [["policières"] 1.8673210241014135E-6 0.18503862820813918 78 245558]
                           [["les" "violences"] 1.5796002982588347E-6 0.1956153971489343 120 245558]
                           [["nier"] 1.5450841641603819E-6 0.1791031237850447 99 245558]
                           [["j'" "ai" "connu"] 1.3852788984559938E-6 0.11914457484480198 38 245558]
                           [["les" "quelques"] 1.3320521887504391E-6 0.11992507950254745 42 245558]
                           [["autant" "je"] 1.2947268711750032E-6 0.18525930628607015 140 245558]
                           [["ai" "connu"] 1.2699441919693244E-6 0.12069384867987619 47 245558]
                           [["violences"] 1.0654245198374324E-6 0.2268266351890862 273 245558]
                           [["contre" "les"] 8.600489740072381E-7 0.2380812624709437 364 245558]
                           [["gentil"] 8.439990618700627E-7 0.13351730190950742 115 245558]
                           [["doublon"] 6.453203263241322E-7 0.09249937483538499 70 245558]
                           [["et" "leurs"] 5.568190721951319E-7 0.0743856899957946 51 245558]
                           [["html"] 5.273197576025068E-7 0.10288587797127005 113 245558]
                           [["les" "syndicats"] 4.929545585532352E-7 0.0737032053408006 59 245558]
                           [["entre" "autres"] 4.758893186861818E-7 0.058869489504545125 36 245558]
                           [["je" "peux"] 4.6506168907903267E-7 0.24816623452068332 587 245558]
                           [["tu"] 4.275065377812304E-7 3.3836665452446075 24286 245558]
                           [["savoir" "ce"] 4.2508648281577854E-7 0.07326777446604155 70 245558]],
    :cluster-id 78}
   {:n-docs-in-cluster 41.37351249006314,
    :characteristic-words [[["touché"] 1.0254231230272248E-5 0.5152601170407382 39 245558]
                           [["doublon"] 6.45896897110039E-7 0.09250178986942252 70 245558]
                           [["html"] 5.279151301818691E-7 0.102888564182909 113 245558]
                           [["je"] 5.169106521707434E-7 6.289388452564753 43316 245558]
                           [["que"] 5.106232132678912E-7 7.8420473001964375 52990 245558]
                           [["tu"] 4.6419871513325717E-7 3.3503184641906385 24286 245558]
                           [["est"] 4.46928088981835E-7 11.550239832674023 75359 245558]
                           [["pas"] 3.1189666949149597E-7 7.75835365636658 51031 245558]
                           [["c'"] 2.7390559320661367E-7 8.133844713033264 53018 245558]
                           [["c'" "est"] 2.6767218275214333E-7 7.346577558077999 48121 245558]
                           [["n'"] 2.424336222328627E-7 2.722261059188676 19018 245558]
                           [["d'" "accord"] 2.3292059556090372E-7 0.341251103648322 3157 245558]
                           [["accord"] 2.2975818832204897E-7 0.3643815637142181 3317 245558]
                           [["c" "3"] 2.239112636120283E-7 0.09196787454577192 187 245558]
                           [["c" "3" "a"] 1.931541359339456E-7 0.07864080281910929 159 245558]
                           [["que" "tu"] 1.9256348871010687E-7 0.5637520613937941 4608 245558]
                           [["3" "a"] 1.9030171112023597E-7 0.08058921574443303 167 245558]
                           [["ne"] 1.81979086133488E-7 3.225083236005549 21792 245558]
                           [["problème"] 1.5723000501566275E-7 0.4354694176343492 3592 245558]
                           [["3" "a" "9"] 1.4606145643490664E-7 0.05909891381205612 119 245558]],
    :cluster-id 9}
   {:n-docs-in-cluster 41.31845412658356,
    :characteristic-words [[["attaque"] 3.8084720427444124E-6 0.40141521749181946 190 245558]
                           [["degré"] 3.1956632340589264E-6 0.2931100147039673 107 245558]
                           [["personnelle"] 2.9762864991386695E-6 0.2875198722444274 116 245558]
                           [["qu'" "le"] 1.2383427980197648E-6 0.2038334161225275 185 245558]
                           [["un" "connard"] 1.0866531198602467E-6 0.10300238981639229 40 245558]
                           [["une" "attaque"] 1.0732762802979912E-6 0.09857416038902678 36 245558]
                           [["tir"] 9.803033059752053E-7 0.09713323572300125 41 245558]
                           [["le" "final"] 9.573705239233898E-7 0.21285481788216504 268 245558]
                           [["coup" "je" "suis"] 9.502155494788694E-7 0.09096999340287658 36 245558]
                           [["sauf" "qu'"] 9.291787170257609E-7 0.21804244207609133 289 245558]
                           [["base" "c'"] 9.215458384561129E-7 0.09727095224736992 46 245558]
                           [["des" "règles"] 8.558527165233865E-7 0.09281947620074853 46 245558]
                           [["final"] 8.547446802391334E-7 0.21928284750287638 315 245558]
                           [["a" "la" "base"] 8.178329734438716E-7 0.09786314715495407 57 245558]
                           [["connard"] 7.557827227024777E-7 0.1109834428678717 87 245558]
                           [["nazi"] 7.412683022929925E-7 0.10307206901625363 75 245558]
                           [["doublon"] 6.465002247947257E-7 0.09249939285512551 70 245558]
                           [["mais" "une"] 5.522356897030151E-7 0.10877918252227299 121 245558]
                           [["html"] 5.285458271261922E-7 0.10288589801439824 113 245558]
                           [["que"] 5.002128454778543E-7 7.841844087917816 52990 245558]],
    :cluster-id 64}
   {:n-docs-in-cluster 41.295992958072496,
    :characteristic-words [[["ski"] 8.911967009566507E-6 0.4356112079171652 30 245558]
                           [["artiste"] 8.838278783861175E-6 0.43598822039555785 31 245558]
                           [["avocat"] 4.444214320836401E-6 0.45912067537852835 210 245558]
                           [["doublon"] 6.467263652361679E-7 0.09249674510802733 70 245558]
                           [["html"] 5.287849569792641E-7 0.10288295295898214 113 245558]
                           [["je"] 5.040513039178407E-7 6.2890454487795475 43316 245558]
                           [["que"] 4.961272002113049E-7 7.841619619202001 52990 245558]
                           [["tu"] 4.5549818911139184E-7 3.350135748188345 24286 245558]
                           [["est"] 4.29717406214003E-7 11.54960991833297 75359 245558]
                           [["pas"] 3.0078416113354933E-7 7.757930539764516 51031 245558]
                           [["c'"] 2.632280257097719E-7 8.133401118310811 53018 245558]
                           [["c'" "est"] 2.577383714230663E-7 7.346176898470186 48121 245558]
                           [["n'"] 2.3689845868313242E-7 2.7221125954938885 19018 245558]
                           [["d'" "accord"] 2.3085140832268536E-7 0.34123249286904656 3157 245558]
                           [["accord"] 2.276467650913494E-7 0.36436169147122255 3317 245558]
                           [["c" "3"] 2.2454720088188607E-7 0.09196285890246646 187 245558]
                           [["c" "3" "a"] 1.9369933245728943E-7 0.07863651399305842 159 245558]
                           [["3" "a"] 1.908535319557897E-7 0.0805848206579701 167 245558]
                           [["que" "tu"] 1.9024813541212993E-7 0.5637213161007598 4608 245558]
                           [["ne"] 1.767951687270397E-7 3.224907349945088 21792 245558]],
    :cluster-id 81}
   {:n-docs-in-cluster 41.177972375383256,
    :characteristic-words [[["le" "mythe"] 1.419312009887444E-6 0.11985724602608343 37 245558]
                           [["coucou"] 1.4125086353691807E-6 0.12150987655731547 39 245558]
                           [["hein" "mais"] 1.3857252271669632E-6 0.11584678289527998 35 245558]
                           [["d'" "extrême" "droite"] 1.0529726691800925E-6 0.13578620124638524 89 245558]
                           [["figures"] 9.572245972056317E-7 0.0930242076509001 38 245558]
                           [["mythe"] 9.213445373041501E-7 0.12721478474482775 92 245558]
                           [["tu" "racontes"] 9.151880193273079E-7 0.11903474094776574 79 245558]
                           [["racontes"] 8.590076648945164E-7 0.11988622943073136 88 245558]
                           [["would"] 7.968883313841033E-7 0.10069386654480994 64 245558]
                           [["est" "juste" "un"] 7.907090427972108E-7 0.13239373775920202 123 245558]
                           [["d'" "extrême"] 7.841348254478825E-7 0.1485320013730715 159 245558]
                           [["in" "the"] 6.754145432906478E-7 0.10860081682947348 96 245558]
                           [["doublon"] 6.480835924521991E-7 0.09249654981025328 70 245558]
                           [["extrême" "droite"] 6.099907951459538E-7 0.1647302475196662 248 245558]
                           [["quoi" "c'" "est"] 5.863293165118966E-7 0.14189029985178267 194 245558]
                           [["quoi" "c'"] 5.587438236410308E-7 0.1450153833882685 211 245558]
                           [["be"] 5.400167760669095E-7 0.1342286384512126 188 245558]
                           [["html"] 5.301959932757505E-7 0.10288273573175241 113 245558]
                           [["twitter"] 5.285239818471343E-7 0.200363489804992 389 245558]
                           [["calcul"] 5.095056867521208E-7 0.1342948471616775 198 245558]],
    :cluster-id 23}
   {:n-docs-in-cluster 41.17642702298227,
    :characteristic-words [[["en" "masse"] 3.3625744192983947E-6 0.21500507044889547 34 245558]
                           [["commenter"] 2.793107693698643E-6 0.21955733265299182 58 245558]
                           [["masse"] 1.7234295220710377E-6 0.23605837870715063 169 245558]
                           [["de" "vie"] 1.1825062946370368E-6 0.2571238965965857 318 245558]
                           [["patient"] 1.136912620467584E-6 0.10324512208205407 37 245558]
                           [["sociale"] 1.114051469196936E-6 0.26031989971122943 345 245558]
                           [["ou" "alors"] 1.091499056141837E-6 0.26885975027680126 374 245558]
                           [["les" "20"] 1.085533334286952E-6 0.10368629364134621 41 245558]
                           [["jugé"] 1.012936402942921E-6 0.105572959714262 49 245558]
                           [["doublon"] 6.481547521397166E-7 0.09250094007836751 70 245558]
                           [["html"] 5.302631504462002E-7 0.10288761897112846 113 245558]
                           [["je"] 4.836763763949037E-7 6.289330673509285 43316 245558]
                           [["que"] 4.732036876786694E-7 7.841975257248332 52990 245558]
                           [["12"] 4.6762799670724275E-7 0.13720280862821244 221 245558]
                           [["vie"] 4.560264495190314E-7 0.39260732600678405 1163 245558]
                           [["tu"] 4.41671369477703E-7 3.350287685643906 24286 245558]
                           [["grave"] 4.2259812873743363E-7 0.14476091021260468 262 245558]
                           [["est"] 4.0266685341627806E-7 11.550133723478675 75359 245558]
                           [["c'"] 2.464527365519942E-7 8.133769989414526 53018 245558]
                           [["c'" "est"] 2.42113234683039E-7 7.346510066887821 48121 245558]],
    :cluster-id 83}
   {:n-docs-in-cluster 41.150205693547825,
    :characteristic-words [[["bha"] 1.681383166597314E-6 0.23146994121948933 167 245558]
                           [["expliquent"] 1.565934512938634E-6 0.1467448071817986 56 245558]
                           [["que" "les" "gilets"] 1.3181068781991517E-6 0.15348479303589582 86 245558]
                           [["ça" "fait" "des"] 1.2585574604411648E-6 0.14985169711373222 87 245558]
                           [["semaines"] 7.127338047686199E-7 0.18334860320578245 265 245558]
                           [["doublon"] 6.484094748644004E-7 0.0924969910525048 70 245558]
                           [["fait" "des"] 5.785314647691053E-7 0.1919942976840285 340 245558]
                           [["gilets" "jaunes"] 5.457205987241531E-7 0.40079492719735055 1113 245558]
                           [["jaunes"] 5.416952334677538E-7 0.4141388640169425 1170 245558]
                           [["html"] 5.305340860569524E-7 0.10288322651989583 113 245558]
                           [["gilets"] 5.143387593053994E-7 0.43530344635948026 1282 245558]
                           [["les" "gilets" "jaunes"] 5.050428392647244E-7 0.2402541962362891 537 245558]
                           [["les" "gilets"] 4.652183660837361E-7 0.2545151995448507 614 245558]
                           [["je"] 4.5697553352486153E-7 6.311771469484548 43316 245558]
                           [["tu"] 4.388855041614903E-7 3.3501446560411097 24286 245558]
                           [["quelqu'un" "de"] 4.2965305460836156E-7 0.07816451217810033 80 245558]
                           [["est"] 3.9729559853096674E-7 11.549640628200345 75359 245558]
                           [["que"] 3.5082232319183504E-7 7.979712613828859 52990 245558]
                           [["a" "pas" "le"] 3.325739795926111E-7 0.06970502013741257 83 245558]
                           [["rien" "n'"] 3.0248459691878393E-7 0.08875528366629007 143 245558]],
    :cluster-id 33}
   {:n-docs-in-cluster 41.11270954120058,
    :characteristic-words [[["lycéens"] 3.4453026430351225E-6 0.25793584210968923 61 245558]
                           [["si" "j'" "étais"] 2.6456040761952196E-6 0.1995549877497163 48 245558]
                           [["je" "voudrais"] 2.236628878159695E-6 0.199357287451663 69 245558]
                           [["sous" "le"] 1.812514093112172E-6 0.20730840246024665 113 245558]
                           [["flic"] 1.774126973467724E-6 0.20934280618028356 120 245558]
                           [["voudrais"] 1.643312841042091E-6 0.20930321805634974 135 245558]
                           [["si" "j'"] 8.38229058643114E-7 0.25579522095339036 426 245558]
                           [["doublon"] 6.488079075306005E-7 0.09249412860528612 70 245558]
                           [["j'" "étais"] 6.460940488728362E-7 0.28317576961463514 604 245558]
                           [["16" "ans"] 5.665323915765921E-7 0.06319716622900988 33 245558]
                           [["html"] 5.309527578416934E-7 0.10288004265626682 113 245558]
                           [["étais"] 5.090398310023114E-7 0.3114942469475598 796 245558]
                           [["ans" "les"] 4.959396213578132E-7 0.06519984970477818 44 245558]
                           [["poche"] 4.733948532429505E-7 0.06745310298413426 51 245558]
                           [["que"] 4.618875878437123E-7 7.841397799296811 52990 245558]
                           [["tu"] 4.3478876210478745E-7 3.3500409811848337 24286 245558]
                           [["sous"] 4.152170688040413E-7 0.3909388114221898 1200 245558]
                           [["est"] 3.8941686575366674E-7 11.549283208609532 75359 245558]
                           [["argent" "de"] 3.804199193062699E-7 0.0669376531566385 66 245558]
                           [["je"] 3.034391787837265E-7 6.478183560561623 43316 245558]],
    :cluster-id 8}
   {:n-docs-in-cluster 41.099406249713695,
    :characteristic-words [[["de" "chômage"] 2.3113078021861494E-6 0.1926552189273535 58 245558]
                           [["pas" "de" "problème"] 1.4822957868400322E-6 0.16284694026771404 83 245558]
                           [["de" "problème"] 1.2029366293145327E-6 0.16984669651513432 127 245558]
                           [["chômage"] 1.090866435043969E-6 0.22571445617748112 266 245558]
                           [["allemagne"] 1.0004323736031995E-6 0.2640609159568015 391 245558]
                           [["l'" "allemagne"] 9.162123350819201E-7 0.18221687066037484 206 245558]
                           [["ou" "de"] 7.283049930258134E-7 0.20247761041167175 313 245558]
                           [["doublon"] 6.490121857985129E-7 0.0924982798443776 70 245558]
                           [["html"] 5.311586937853385E-7 0.10288466002670157 113 245558]
                           [["je"] 4.711166715631876E-7 6.289149798685561 43316 245558]
                           [["que"] 4.591115931873446E-7 7.84174972992817 52990 245558]
                           [["danemark"] 4.3887567582456127E-7 0.05399637192963212 33 245558]
                           [["tu"] 4.331056182582671E-7 3.3501913347401215 24286 245558]
                           [["problème" "de"] 3.8926735009686686E-7 0.2131701561938383 515 245558]
                           [["l'" "extrême-droite"] 3.695601117738659E-7 0.05601569265488876 46 245558]
                           [["est"] 3.633983066686497E-7 11.581354557161871 75359 245558]
                           [["pays-bas"] 3.226277086485971E-7 0.058172047635801825 59 245558]
                           [["extrême-droite"] 2.46535353971844E-7 0.06242788539217634 89 245558]
                           [["pays" "où"] 2.3131485175735544E-7 0.06561910456104966 103 245558]
                           [["c" "3"] 2.2625834835039715E-7 0.09196438478040161 187 245558]],
    :cluster-id 73}
   {:n-docs-in-cluster 41.081788180477375,
    :characteristic-words [[["vieilles"] 3.6222195941857174E-6 0.21960115464483848 30 245558]
                           [["une" "amie"] 3.354959712449819E-6 0.21784841384461934 36 245558]
                           [["amie"] 3.104512249935479E-6 0.21921533250589967 45 245558]
                           [["froid"] 2.753560020886739E-6 0.22397892607880732 64 245558]
                           [["doublon"] 6.492589956844097E-7 0.09250181727563217 70 245558]
                           [["html"] 5.314098111344756E-7 0.10288859466648742 113 245558]
                           [["je"] 4.679836074217292E-7 6.289390315969767 43316 245558]
                           [["que"] 4.555974122411044E-7 7.842049623619492 52990 245558]
                           [["tu"] 4.309694624504701E-7 3.3503194568150008 24286 245558]
                           [["est"] 3.820595515513503E-7 11.550243254751548 75359 245558]
                           [["dans" "les"] 3.0810426863980833E-7 0.5476368632580693 2047 245558]
                           [["a" "un"] 2.961255412478603E-7 0.4327785261885917 1534 245558]
                           [["pas"] 2.6993011803533307E-7 7.758355954993115 51031 245558]
                           [["c'"] 2.3367973711607704E-7 8.13384712290938 53018 245558]
                           [["c'" "est"] 2.3019787187550378E-7 7.346579734704492 48121 245558]
                           [["c" "3"] 2.2643375861136628E-7 0.09196790179379445 187 245558]
                           [["d'" "accord"] 2.2498680697646822E-7 0.34125120475338444 3157 245558]
                           [["accord"] 2.216625805961936E-7 0.3643816716723181 3317 245558]
                           [["n'"] 2.2134725097266994E-7 2.7222618657336914 19018 245558]
                           [["c" "3" "a"] 1.95316928026934E-7 0.07864082611861882 159 245558]],
    :cluster-id 55}
   {:n-docs-in-cluster 41.031176166187116,
    :characteristic-words [[["france" "si"] 2.1998898872260114E-6 0.16719561560239077 41 245558]
                           [["elle" "peut"] 2.0889285849120046E-6 0.16817110784658923 47 245558]
                           [["équilibre"] 1.935839628919994E-6 0.16884673155748087 56 245558]
                           [["ramener"] 1.7935807369194243E-6 0.17214971131280318 69 245558]
                           [["choisir"] 1.0604799920918562E-6 0.19598886132539883 205 245558]
                           [["doublon"] 6.498503378107173E-7 0.09250225409512287 70 245558]
                           [["html"] 5.320240065789794E-7 0.10288908053525025 113 245558]
                           [["je"] 4.5968917694594325E-7 6.289420016232492 43316 245558]
                           [["que"] 4.463091499706806E-7 7.842086655974366 52990 245558]
                           [["tu"] 4.2529466109941794E-7 3.3503352779618916 24286 245558]
                           [["est"] 3.712490050933326E-7 11.550297798235382 75359 245558]
                           [["la" "france"] 2.8258039384831957E-7 0.41002429735823726 1452 245558]
                           [["pas"] 2.6291141475009994E-7 7.758392592123022 51031 245558]
                           [["c'"] 2.2698140966781466E-7 8.133885533213768 53018 245558]
                           [["c" "3"] 2.268768913918362E-7 0.09196833609198665 187 245558]
                           [["c'" "est"] 2.2394266729897083E-7 7.346614427311744 48121 245558]
                           [["d'" "accord"] 2.2361657155134385E-7 0.34125281623715664 3157 245558]
                           [["accord"] 2.2026485890780378E-7 0.3643833923846342 3317 245558]
                           [["n'"] 2.1776406139428772E-7 2.722274721016725 19018 245558]
                           [["c" "3" "a"] 1.9569686913675777E-7 0.0786411974826268 159 245558]],
    :cluster-id 63}
   {:n-docs-in-cluster 41.006199069021385,
    :characteristic-words [[["technologie"] 1.3084337098859256E-6 0.15258430913097498 86 245558]
                           [["doublon"] 6.500942381706953E-7 0.09249850665175324 70 245558]
                           [["html"] 5.32283458381036E-7 0.10288491230166036 113 245558]
                           [["je"] 4.5588561981002584E-7 6.289165219789198 43316 245558]
                           [["que"] 4.420569267304941E-7 7.841768958033592 52990 245558]
                           [["tu"] 4.226833300036148E-7 3.3501995494669905 24286 245558]
                           [["est"] 2.7431107629816154E-7 11.688128726245873 75359 245558]
                           [["pas"] 2.597081391053635E-7 7.758078284788974 51031 245558]
                           [["c" "3"] 2.2707083809254958E-7 0.0919646102786573 187 245558]
                           [["d'" "accord"] 2.2297926059733353E-7 0.34123899143238806 3157 245558]
                           [["accord"] 2.1961514849733188E-7 0.36436863051595186 3317 245558]
                           [["n'"] 2.1611922457198673E-7 2.722164436457135 19018 245558]
                           [["importe"] 1.9961367885013015E-7 0.27229255129206226 947 245558]
                           [["c" "3" "a"] 1.958631078331774E-7 0.07863801157719168 159 245558]
                           [["3" "a"] 1.9304258740485136E-7 0.08058635534640768 167 245558]
                           [["que" "tu"] 1.8145810798397832E-7 0.5637320518271502 4608 245558]
                           [["ne"] 1.5746550707795848E-7 3.2249687663257753 21792 245558]
                           [["problème"] 1.4839419390511033E-7 0.43545396127519753 3592 245558]
                           [["3" "a" "9"] 1.4810086462584138E-7 0.059096816181314786 119 245558]
                           [["c'"] 1.4558004146625336E-7 8.27185486662437 53018 245558]],
    :cluster-id 48}
   {:n-docs-in-cluster 40.92351623045655,
    :characteristic-words [[["doublon"] 6.510591170913807E-7 0.09249892492828038 70 245558]
                           [["html"] 5.332862603093375E-7 0.10288537754532072 113 245558]
                           [["médian"] 5.222208927443961E-7 0.05996873146336514 33 245558]
                           [["je"] 4.42549184964669E-7 6.289193659277401 43316 245558]
                           [["1500"] 4.301291547659236E-7 0.06318103660150592 50 245558]
                           [["que"] 4.271587057402826E-7 7.841804418367018 52990 245558]
                           [["tu"] 4.1352115193182826E-7 3.3502146990070654 24286 245558]
                           [["est"] 3.491062988691951E-7 11.549882101679348 75359 245558]
                           [["le" "canada"] 3.1907193132410527E-7 0.07638899425748095 104 245558]
                           [["canada"] 2.7946219068562816E-7 0.08205493093692534 133 245558]
                           [["pour" "info"] 2.715427948538074E-7 0.08170268452172916 135 245558]
                           [["pas"] 2.4850900415263055E-7 7.758113366674704 51031 245558]
                           [["c" "3"] 2.2779530758909594E-7 0.09196502614091498 187 245558]
                           [["d'" "accord"] 2.207525663655563E-7 0.3412405345087614 3157 245558]
                           [["accord"] 2.1734424714148215E-7 0.3643702781841235 3317 245558]
                           [["c'"] 2.1326704791047035E-7 8.133592793730989 53018 245558]
                           [["c'" "est"] 2.1111972869913842E-7 7.34635002180731 48121 245558]
                           [["n'"] 2.1035017216952667E-7 2.7221767460341058 19018 245558]
                           [["c" "3" "a"] 1.9648425665826086E-7 0.07863836717681781 159 245558]
                           [["3" "a"] 1.9367105388856665E-7 0.08058671975640812 167 245558]],
    :cluster-id 45}
   {:n-docs-in-cluster 40.88930492367234,
    :characteristic-words [[["doublon"] 6.514868265833362E-7 0.09250138078915289 70 245558]
                           [["html"] 5.33727297288844E-7 0.10288810916812889 113 245558]
                           [["je"] 4.1774790138227047E-7 6.309495487771278 43316 245558]
                           [["tu"] 4.096553585242191E-7 3.3503036477294357 24286 245558]
                           [["que"] 4.035598691887188E-7 7.862147468918524 52990 245558]
                           [["est"] 3.419000355053825E-7 11.550188752849168 75359 245558]
                           [["pas"] 2.4381480800972355E-7 7.758319345793678 51031 245558]
                           [["c" "3"] 2.2811018229179636E-7 0.09196746782669149 187 245558]
                           [["d'" "accord"] 2.1981205214349764E-7 0.3412495944981337 3157 245558]
                           [["accord"] 2.1638499096698904E-7 0.36437995227179504 3317 245558]
                           [["c'"] 2.0880526840905134E-7 8.133808741887266 53018 245558]
                           [["n'"] 2.0791932942021418E-7 2.7222490202509504 19018 245558]
                           [["c'" "est"] 2.0694370983509458E-7 7.346545068545283 48121 245558]
                           [["c" "3" "a"] 1.967542524565491E-7 0.07864045503772189 159 245558]
                           [["3" "a"] 1.939441115345053E-7 0.08058885934637684 167 245558]
                           [["que" "tu"] 1.779358010378651E-7 0.5637495682545198 4608 245558]
                           [["ne"] 1.4990292529493132E-7 3.2250689733850995 21792 245558]
                           [["3" "a" "9"] 1.487717636011049E-7 0.059098652452793464 119 245558]
                           [["problème"] 1.4559044236861496E-7 0.4354674918127292 3592 245558]
                           [["a" "9"] 1.4209384943675296E-7 0.060718240941556714 128 245558]],
    :cluster-id 86}
   {:n-docs-in-cluster 40.873333083196414,
    :characteristic-words [[["doublon"] 6.517875346927909E-7 0.09251080315019064 70 245558]
                           [["html"] 5.340254097211533E-7 0.10289858954045207 113 245558]
                           [["je"] 4.337825674216589E-7 6.29000128420903 43316 245558]
                           [["que"] 4.1737583822420987E-7 7.842811421347097 52990 245558]
                           [["tu"] 4.0749260665773335E-7 3.350644915830364 24286 245558]
                           [["est"] 3.3785007791387045E-7 11.551365276351374 75359 245558]
                           [["pas"] 2.411766896193157E-7 7.759109622493313 51031 245558]
                           [["c" "3"] 2.2830978483115438E-7 0.09197683580237138 187 245558]
                           [["d'" "accord"] 2.1929522710240068E-7 0.3412843548104223 3157 245558]
                           [["accord"] 2.1585722231876048E-7 0.3644170686849328 3317 245558]
                           [["n'"] 2.0655628418797534E-7 2.722526313550625 19018 245558]
                           [["c'"] 2.0629784169834409E-7 8.134637266628458 53018 245558]
                           [["c'" "est"] 2.0459679883622073E-7 7.347293401158541 48121 245558]
                           [["c" "3" "a"] 1.9692550585169444E-7 0.07864846549933029 159 245558]
                           [["3" "a"] 1.9411687164974656E-7 0.08059706827603715 167 245558]
                           [["que" "tu"] 1.7735552937381271E-7 0.5638069928239867 4608 245558]
                           [["3" "a" "9"] 1.4890075356044363E-7 0.059104672350393926 119 245558]
                           [["ne"] 1.4864574088280236E-7 3.225397484851414 21792 245558]
                           [["problème"] 1.4512885636308592E-7 0.43551184933359083 3592 245558]
                           [["a" "9"] 1.4222251488310578E-7 0.06072442581342397 128 245558]],
    :cluster-id 97}
   {:n-docs-in-cluster 40.87167101411804,
    :characteristic-words [[["doublon"] 6.517610192467022E-7 0.09250704130025757 70 245558]
                           [["html"] 5.340036866516296E-7 0.10289440528262485 113 245558]
                           [["je"] 4.337649254226861E-7 6.289745508233623 43316 245558]
                           [["que"] 4.1735886358029717E-7 7.8424925020575085 52990 245558]
                           [["tu"] 4.074760338590444E-7 3.350508665544739 24286 245558]
                           [["est"] 3.378363374606508E-7 11.550895552803281 75359 245558]
                           [["pas"] 2.4116688068787084E-7 7.7587941068451975 51031 245558]
                           [["c" "3"] 2.283004985337289E-7 0.09197309566563242 187 245558]
                           [["d'" "accord"] 2.1928630834777696E-7 0.34127047685797174 3157 245558]
                           [["accord"] 2.1584844342448228E-7 0.3644022500661474 3317 245558]
                           [["n'"] 2.065478833523926E-7 2.722415605016256 19018 245558]
                           [["c'"] 2.062894516319247E-7 8.134306480562238 53018 245558]
                           [["c'" "est"] 2.0458847771465116E-7 7.346994631563554 48121 245558]
                           [["c" "3" "a"] 1.9691749604186515E-7 0.07864526734609192 159 245558]
                           [["3" "a"] 1.9410897618248046E-7 0.08059379088501276 167 245558]
                           [["que" "tu"] 1.773483162825773E-7 0.5637840662335128 4608 245558]
                           [["3" "a" "9"] 1.4889469712037195E-7 0.05910226892398155 119 245558]
                           [["ne"] 1.4863969538536637E-7 3.225266327614637 21792 245558]
                           [["problème"] 1.4512295383461993E-7 0.43549413972384227 3592 245558]
                           [["a" "9"] 1.4221672994113588E-7 0.06072195652152076 128 245558]],
    :cluster-id 44}
   {:n-docs-in-cluster 40.87070562669215,
    :characteristic-words [[["doublon"] 6.517456180967288E-7 0.09250485628720914 70 245558]
                           [["html"] 5.339910693156341E-7 0.10289197492040605 113 245558]
                           [["je"] 4.337546780641688E-7 6.28959694466686 43316 245558]
                           [["que"] 4.173490040226824E-7 7.842307262661576 52990 245558]
                           [["tu"] 4.074664076703094E-7 3.35042952664839 24286 245558]
                           [["est"] 3.3782835640039366E-7 11.550622721058245 75359 245558]
                           [["pas"] 2.411611834673977E-7 7.758610844402418 51031 245558]
                           [["c" "3"] 2.2829510468343195E-7 0.09197092326436108 187 245558]
                           [["d'" "accord"] 2.1928112796387733E-7 0.3412624160613609 3157 245558]
                           [["accord"] 2.1584334428115248E-7 0.36439364289787013 3317 245558]
                           [["n'"] 2.0654300392219938E-7 2.722351301655804 19018 245558]
                           [["c'"] 2.062845784189804E-7 8.134114348530447 53018 245558]
                           [["c'" "est"] 2.0458364458075806E-7 7.346821095809836 48121 245558]
                           [["c" "3" "a"] 1.969128436193207E-7 0.0786434097476548 159 245558]
                           [["3" "a"] 1.9410439015647707E-7 0.08059188726251847 167 245558]
                           [["que" "tu"] 1.77344126717216E-7 0.5637707496737805 4608 245558]
                           [["3" "a" "9"] 1.4889117929892115E-7 0.05910087293047685 119 245558]
                           [["ne"] 1.4863618386096178E-7 3.2251901469378863 21792 245558]
                           [["problème"] 1.4511952550755325E-7 0.4354838533676423 3592 245558]
                           [["a" "9"] 1.4221336995523526E-7 0.060720522271052214 128 245558]],
    :cluster-id 41}
   {:n-docs-in-cluster 40.86896607259355,
    :characteristic-words [[["doublon"] 6.517178666566939E-7 0.0925009190612809 70 245558]
                           [["html"] 5.339683335631173E-7 0.10288759559409039 113 245558]
                           [["je"] 4.3373621361197934E-7 6.289329244513997 43316 245558]
                           [["que"] 4.173312375677085E-7 7.841973475477524 52990 245558]
                           [["tu"] 4.074490623784399E-7 3.3502869244269275 24286 245558]
                           [["est"] 3.378139754595111E-7 11.550131099179266 75359 245558]
                           [["pas"] 2.4115091745713357E-7 7.758280619536292 51031 245558]
                           [["c" "3"] 2.282853855777034E-7 0.09196700876388775 187 245558]
                           [["d'" "accord"] 2.1927179356950877E-7 0.34124789112411164 3157 245558]
                           [["accord"] 2.158341561586674E-7 0.3643781334407838 3317 245558]
                           [["n'"] 2.065342114554447E-7 2.7222354319321984 19018 245558]
                           [["c'"] 2.0627579710996713E-7 8.133768141345232 53018 245558]
                           [["c'" "est"] 2.045749357693083E-7 7.346508397691391 48121 245558]
                           [["c" "3" "a"] 1.969044605490411E-7 0.07864006249774415 159 245558]
                           [["3" "a"] 1.940961265097657E-7 0.0805884570807867 167 245558]
                           [["que" "tu"] 1.7733657722840412E-7 0.563746754254488 4608 245558]
                           [["3" "a" "9"] 1.488848404979093E-7 0.059098357457250644 119 245558]
                           [["ne"] 1.486298566444333E-7 3.225052875201251 21792 245558]
                           [["problème"] 1.451133479352107E-7 0.43546531814270834 3592 245558]
                           [["a" "9"] 1.4220731558121924E-7 0.060717937861711355 128 245558]],
    :cluster-id 82}
   {:n-docs-in-cluster 40.86878000318636,
    :characteristic-words [[["doublon"] 6.517148981918103E-7 0.09250049792036254 70 245558]
                           [["html"] 5.339659015397846E-7 0.10288712716440414 113 245558]
                           [["je"] 4.3373423852521853E-7 6.289300610269912 43316 245558]
                           [["que"] 4.1732933719895726E-7 7.841937772308903 52990 245558]
                           [["tu"] 4.074472068071877E-7 3.3502716711415927 24286 245558]
                           [["est"] 3.3781243713448816E-7 11.55007851340102 75359 245558]
                           [["pas"] 2.411498192245176E-7 7.758245297406971 51031 245558]
                           [["c" "3"] 2.2828434589200475E-7 0.09196659005377203 187 245558]
                           [["d'" "accord"] 2.1927079503492042E-7 0.3412463374806264 3157 245558]
                           [["accord"] 2.158331734170016E-7 0.36437647448924815 3317 245558]
                           [["n'"] 2.0653327098552055E-7 2.7222230380582633 19018 245558]
                           [["c'"] 2.06274857750266E-7 8.133731109685204 53018 245558]
                           [["c'" "est"] 2.0457400395912373E-7 7.346474950290803 48121 245558]
                           [["c" "3" "a"] 1.9690356367271789E-7 0.07863970446294324 159 245558]
                           [["3" "a"] 1.940952426456033E-7 0.08058809017527735 167 245558]
                           [["que" "tu"] 1.7733576970768716E-7 0.5637441876116024 4608 245558]
                           [["3" "a" "9"] 1.4888416238756363E-7 0.05909808839250205 119 245558]
                           [["ne"] 1.4862917974145518E-7 3.225038192085197 21792 245558]
                           [["problème"] 1.451126871720998E-7 0.43546333554333677 3592 245558]
                           [["a" "9"] 1.422066679603734E-7 0.060717661423289236 128 245558]],
    :cluster-id 65}
   {:n-docs-in-cluster 40.868676367874144,
    :characteristic-words [[["doublon"] 6.517132448589577E-7 0.09250026335707853 70 245558]
                           [["html"] 5.33964547039939E-7 0.10288686626264713 113 245558]
                           [["je"] 4.3373313862726803E-7 6.289284661825982 43316 245558]
                           [["que"] 4.173282787123256E-7 7.841917886678887 52990 245558]
                           [["tu"] 4.0744617357812984E-7 3.3502631755038093 24286 245558]
                           [["est"] 3.3781158015333546E-7 11.550049224646822 75359 245558]
                           [["pas"] 2.4114920760265335E-7 7.758225624004812 51031 245558]
                           [["c" "3"] 2.282837667406945E-7 0.09196635684437457 187 245558]
                           [["d'" "accord"] 2.1927023907686305E-7 0.3412454721462405 3157 245558]
                           [["accord"] 2.1583262592439478E-7 0.3643755505013304 3317 245558]
                           [["n'"] 2.0653274712678638E-7 2.722216135029739 19018 245558]
                           [["c'"] 2.0627433439113219E-7 8.133710484124004 53018 245558]
                           [["c'" "est"] 2.0457348515190432E-7 7.3464563210582225 48121 245558]
                           [["c" "3" "a"] 1.969030641764402E-7 0.0786395050479374 159 245558]
                           [["3" "a"] 1.940947503154683E-7 0.080587885819544 167 245558]
                           [["que" "tu"] 1.7733531984531758E-7 0.563742758065914 4608 245558]
                           [["3" "a" "9"] 1.4888378474867126E-7 0.05909793853123641 119 245558]
                           [["ne"] 1.4862880282073831E-7 3.225030014015177 21792 245558]
                           [["problème"] 1.4511231920255607E-7 0.4354622312929558 3592 245558]
                           [["a" "9"] 1.422063072038099E-7 0.060717507455101176 128 245558]],
    :cluster-id 77}
   {:n-docs-in-cluster 40.868482217035265,
    :characteristic-words [[["doublon"] 6.517101476194789E-7 0.09249982392506895 70 245558]
                           [["html"] 5.339620095957259E-7 0.10288637748801276 113 245558]
                           [["je"] 4.337310776092451E-7 6.289254783932689 43316 245558]
                           [["que"] 4.173262959650259E-7 7.84188063284186 52990 245558]
                           [["tu"] 4.074442376822418E-7 3.350247259734199 24286 245558]
                           [["est"] 3.3780997521493106E-7 11.54999435495069 75359 245558]
                           [["pas"] 2.4114806196351424E-7 7.758188767756489 51031 245558]
                           [["c" "3"] 2.2828268206667723E-7 0.09196591994874279 187 245558]
                           [["d'" "accord"] 2.1926919700765435E-7 0.3412438510245475 3157 245558]
                           [["accord"] 2.1583160060567597E-7 0.3643738194978227 3317 245558]
                           [["n'"] 2.065317658006549E-7 2.722203202862156 19018 245558]
                           [["c'"] 2.0627335428624605E-7 8.13367184409625 53018 245558]
                           [["c'" "est"] 2.0457251315164626E-7 7.346421420962281 48121 245558]
                           [["c" "3" "a"] 1.969021285793543E-7 0.07863913146288495 159 245558]
                           [["3" "a"] 1.9409382782595297E-7 0.08058750297850846 167 245558]
                           [["que" "tu"] 1.7733447738033092E-7 0.5637400799479919 4608 245558]
                           [["3" "a" "9"] 1.48883077295478E-7 0.059097657780404496 119 245558]
                           [["ne"] 1.4862809660787235E-7 3.2250146931785793 21792 245558]
                           [["problème"] 1.4511162975405778E-7 0.43546016258486275 3592 245558]
                           [["a" "9"] 1.422056315151382E-7 0.0607172190103406 128 245558]],
    :cluster-id 20}
   {:n-docs-in-cluster 40.86838976358506,
    :characteristic-words [[["doublon"] 6.517086726205365E-7 0.09249961467024609 70 245558]
                           [["html"] 5.339608013139874E-7 0.10288614473653518 113 245558]
                           [["je"] 4.3373009628311365E-7 6.289240556263498 43316 245558]
                           [["que"] 4.1732535172034346E-7 7.841862892793582 52990 245558]
                           [["tu"] 4.07443315697531E-7 3.3502396807425128 24286 245558]
                           [["est"] 3.378092109374009E-7 11.549968226338546 75359 245558]
                           [["pas"] 2.4114751628889763E-7 7.75817121703749 51031 245558]
                           [["c" "3"] 2.282821655562317E-7 0.09196571190172742 187 245558]
                           [["d'" "accord"] 2.1926870096000695E-7 0.3412430790563575 3157 245558]
                           [["accord"] 2.158311120381562E-7 0.36437299520458766 3317 245558]
                           [["n'"] 2.065312986743173E-7 2.7221970446434183 19018 245558]
                           [["c'"] 2.062728877705311E-7 8.133653443952461 53018 245558]
                           [["c'" "est"] 2.045720504106896E-7 7.346404801751057 48121 245558]
                           [["c" "3" "a"] 1.969016830971615E-7 0.0786389535639785 159 245558]
                           [["3" "a"] 1.9409338880907456E-7 0.0805873206719597 167 245558]
                           [["que" "tu"] 1.7733407614572982E-7 0.5637388046446928 4608 245558]
                           [["3" "a" "9"] 1.4888274054575268E-7 0.05909752408858338 119 245558]
                           [["ne"] 1.486277602102959E-7 3.225007397490331 21792 245558]
                           [["problème"] 1.4511130130845373E-7 0.43545917747878043 3592 245558]
                           [["a" "9"] 1.4220530977944457E-7 0.06071708165471794 128 245558]],
    :cluster-id 80}
   {:n-docs-in-cluster 40.86828229908024,
    :characteristic-words [[["doublon"] 6.517069581820495E-7 0.09249937144007858 70 245558]
                           [["html"] 5.339593965453249E-7 0.10288587419471396 113 245558]
                           [["je"] 4.3372895563997815E-7 6.28922401854018 43316 245558]
                           [["que"] 4.17324254042839E-7 7.841842272409031 52990 245558]
                           [["tu"] 4.0744224422128994E-7 3.3502308711992295 24286 245558]
                           [["est"] 3.378083224259143E-7 11.549937855393592 75359 245558]
                           [["pas"] 2.4114688201848367E-7 7.758150816722409 51031 245558]
                           [["c" "3"] 2.2828156501925045E-7 0.09196547007547122 187 245558]
                           [["d'" "accord"] 2.192681242546568E-7 0.34124218174871407 3157 245558]
                           [["accord"] 2.1583054453377937E-7 0.36437203707622146 3317 245558]
                           [["n'"] 2.0653075544219135E-7 2.7221898865547636 19018 245558]
                           [["c'"] 2.0627234520453896E-7 8.133632056296992 53018 245558]
                           [["c'" "est"] 2.0457151239661187E-7 7.3463854841865865 48121 245558]
                           [["c" "3" "a"] 1.9690116519373302E-7 0.0786387467807836 159 245558]
                           [["3" "a"] 1.9409287827995558E-7 0.08058710876548228 167 245558]
                           [["que" "tu"] 1.7733360979654833E-7 0.5637373222786924 4608 245558]
                           [["3" "a" "9"] 1.488823488087626E-7 0.059097368690090815 119 245558]
                           [["ne"] 1.4862736946730237E-7 3.224998917248629 21792 245558]
                           [["problème"] 1.4511091954438893E-7 0.43545803242743014 3592 245558]
                           [["a" "9"] 1.4220493564295889E-7 0.06071692199755619 128 245558]],
    :cluster-id 0}
   {:n-docs-in-cluster 40.868099296970364,
    :characteristic-words [[["doublon"] 6.517040388679535E-7 0.09249895724160512 70 245558]
                           [["html"] 5.339570048907422E-7 0.10288541348702075 113 245558]
                           [["je"] 4.3372701319377427E-7 6.289195856327334 43316 245558]
                           [["que"] 4.1732238520442166E-7 7.841807157801794 52990 245558]
                           [["tu"] 4.0744041956974897E-7 3.350215869362019 24286 245558]
                           [["est"] 3.3780680963602094E-7 11.549886136484 75359 245558]
                           [["pas"] 2.411458023265922E-7 7.7581160768731054 51031 245558]
                           [["c" "3"] 2.2828054276058385E-7 0.09196505826772894 187 245558]
                           [["d'" "accord"] 2.192671424150472E-7 0.3412406537168086 3157 245558]
                           [["accord"] 2.1582957809851422E-7 0.3643704054722528 3317 245558]
                           [["n'"] 2.0652983051538953E-7 2.7221776969919764 19018 245558]
                           [["c'"] 2.0627142138796017E-7 8.133595635098352 53018 245558]
                           [["c'" "est"] 2.0457059624057194E-7 7.346352588161401 48121 245558]
                           [["c" "3" "a"] 1.969002831770511E-7 0.07863839464813269 159 245558]
                           [["3" "a"] 1.9409200900828705E-7 0.08058674790835524 167 245558]
                           [["que" "tu"] 1.7733281573728554E-7 0.5637347979465306 4608 245558]
                           [["3" "a" "9"] 1.4888168208514185E-7 0.059097104060834266 119 245558]
                           [["ne"] 1.486267038885991E-7 3.224984476181546 21792 245558]
                           [["problème"] 1.4511026981411934E-7 0.4354560825109121 3592 245558]
                           [["a" "9"] 1.422042988294403E-7 0.06071665011618001 128 245558]],
    :cluster-id 50}
   {:n-docs-in-cluster 40.86780992555128,
    :characteristic-words [[["doublon"] 6.516994222813266E-7 0.09249830229177941 70 245558]
                           [["html"] 5.339532227598837E-7 0.10288468499466102 113 245558]
                           [["je"] 4.3372394165075434E-7 6.289151324930942 43316 245558]
                           [["que"] 4.173194299017524E-7 7.841751632957139 52990 245558]
                           [["tu"] 4.074375340445968E-7 3.35019214776163 24286 245558]
                           [["est"] 3.378044174384698E-7 11.549804356146813 75359 245558]
                           [["pas"] 2.4114409458153574E-7 7.758061144613053 51031 245558]
                           [["c" "3"] 2.2827892585952636E-7 0.09196440709823815 187 245558]
                           [["d'" "accord"] 2.1926558953222486E-7 0.34123823752193155 3157 245558]
                           [["accord"] 2.1582804961284285E-7 0.3643678255044279 3317 245558]
                           [["n'"] 2.065283680185992E-7 2.7221584222902813 19018 245558]
                           [["c'"] 2.0626996055650437E-7 8.133538044210814 53018 245558]
                           [["c'" "est"] 2.045691475105471E-7 7.346300571441525 48121 245558]
                           [["c" "3" "a"] 1.9689888881499473E-7 0.07863783783966258 159 245558]
                           [["3" "a"] 1.9409063438044494E-7 0.08058617730433902 167 245558]
                           [["que" "tu"] 1.773315598530001E-7 0.5637308063555039 4608 245558]
                           [["3" "a" "9"] 1.4888062760051723E-7 0.059096685616787284 119 245558]
                           [["ne"] 1.4862565128614946E-7 3.2249616412968733 21792 245558]
                           [["problème"] 1.4510924220556554E-7 0.43545299921252856 3592 245558]
                           [["a" "9"] 1.4220329174439994E-7 0.060716220204745305 128 245558]],
    :cluster-id 84}
   {:n-docs-in-cluster 40.867418872704,
    :characteristic-words [[["doublon"] 6.516931836588608E-7 0.09249741720093287 70 245558]
                           [["html"] 5.339481117614536E-7 0.10288370051937144 113 245558]
                           [["je"] 4.337197908599322E-7 6.289091145769462 43316 245558]
                           [["que"] 4.173154358744213E-7 7.841676597389799 52990 245558]
                           [["tu"] 4.074336347748009E-7 3.350160090693223 24286 245558]
                           [["est"] 3.3780118435799977E-7 11.54969383924152 75359 245558]
                           [["pas"] 2.4114178664991215E-7 7.7579869098570144 51031 245558]
                           [["c" "3"] 2.2827674099785977E-7 0.09196352711608813 187 245558]
                           [["d'" "accord"] 2.192634911135638E-7 0.3412349723069703 3157 245558]
                           [["accord"] 2.1582598393188324E-7 0.3643643389687925 3317 245558]
                           [["n'"] 2.065263912109927E-7 2.7221323747040747 19018 245558]
                           [["c'"] 2.062679865799666E-7 8.133460216619447 53018 245558]
                           [["c'" "est"] 2.045671897432655E-7 7.3462302767093 48121 245558]
                           [["c" "3" "a"] 1.9689700406569366E-7 0.07863708537579313 159 245558]
                           [["3" "a"] 1.94088776649462E-7 0.08058540619734443 167 245558]
                           [["que" "tu"] 1.773298627105735E-7 0.5637254121702684 4608 245558]
                           [["3" "a" "9"] 1.4887920265528598E-7 0.059096120136835746 119 245558]
                           [["ne"] 1.4862422881289916E-7 3.2249307825247797 21792 245558]
                           [["problème"] 1.451078534692174E-7 0.4354488324823926 3592 245558]
                           [["a" "9"] 1.422019304669897E-7 0.06071563922791216 128 245558]],
    :cluster-id 53}
   {:n-docs-in-cluster 40.867220632831035,
    :characteristic-words [[["doublon"] 6.516900212614335E-7 0.09249696851401457 70 245558]
                           [["html"] 5.339455208079602E-7 0.10288320145061983 113 245558]
                           [["je"] 4.337176863211667E-7 6.2890606386157355 43316 245558]
                           [["que"] 4.173134111606913E-7 7.84163855894706 52990 245558]
                           [["tu"] 4.07431658189239E-7 3.3501438397204697 24286 245558]
                           [["est"] 3.377995454467708E-7 11.549637813930826 75359 245558]
                           [["pas"] 2.411406164748442E-7 7.757949277376605 51031 245558]
                           [["c" "3"] 2.2827563324334665E-7 0.09196308101896646 187 245558]
                           [["d'" "accord"] 2.1926242750602842E-7 0.3412333170427176 3157 245558]
                           [["accord"] 2.1582493694993765E-7 0.3643625715084966 3317 245558]
                           [["n'"] 2.0652538940124643E-7 2.7221191701712 19018 245558]
                           [["c'"] 2.062669856028876E-7 8.133420762791332 53018 245558]
                           [["c'" "est"] 2.0456619720388147E-7 7.346194641579966 48121 245558]
                           [["c" "3" "a"] 1.9689604869622956E-7 0.0786367039226394 159 245558]
                           [["3" "a"] 1.9408783485594383E-7 0.08058501529326666 167 245558]
                           [["que" "tu"] 1.7732900231548498E-7 0.5637226776483144 4608 245558]
                           [["3" "a" "9"] 1.4887848012040683E-7 0.05909583347309066 119 245558]
                           [["ne"] 1.4862350783406697E-7 3.224915139014966 21792 245558]
                           [["problème"] 1.4510714937965297E-7 0.43544672020509234 3592 245558]
                           [["a" "9"] 1.4220124052062577E-7 0.06071534470819603 128 245558]],
    :cluster-id 70}
   {:n-docs-in-cluster 40.86721900950674,
    :characteristic-words [[["doublon"] 6.51689995167723E-7 0.09249696483985786 70 245558]
                           [["html"] 5.339454994153503E-7 0.10288319736390208 113 245558]
                           [["je"] 4.337176691127098E-7 6.289060388802214 43316 245558]
                           [["que"] 4.1731339461836825E-7 7.841638247462155 52990 245558]
                           [["tu"] 4.074316418689605E-7 3.3501437066463358 24286 245558]
                           [["est"] 3.377995321240945E-7 11.54963735515712 75359 245558]
                           [["pas"] 2.411406070379485E-7 7.757948969216014 51031 245558]
                           [["c" "3"] 2.282756241724776E-7 0.09196307736601673 187 245558]
                           [["d'" "accord"] 2.1926241883241104E-7 0.3412333034882761 3157 245558]
                           [["accord"] 2.158249283457092E-7 0.3643625570353166 3317 245558]
                           [["n'"] 2.0652538118559605E-7 2.722119062043412 19018 245558]
                           [["c'"] 2.062669773872372E-7 8.133420439716295 53018 245558]
                           [["c'" "est"] 2.045661889882311E-7 7.34619434977504 48121 245558]
                           [["c" "3" "a"] 1.9689604096630176E-7 0.07863670079903891 159 245558]
                           [["3" "a"] 1.940878272127522E-7 0.0805850120922755 167 245558]
                           [["que" "tu"] 1.7732899537659108E-7 0.5637226552561707 4608 245558]
                           [["3" "a" "9"] 1.488784743836763E-7 0.05909583112569099 119 245558]
                           [["ne"] 1.4862350206090724E-7 3.224915010915158 21792 245558]
                           [["problème"] 1.4510714368975997E-7 0.4354467029083144 3592 245558]
                           [["a" "9"] 1.4220123494695924E-7 0.06071534229646629 128 245558]],
    :cluster-id 52}
   {:n-docs-in-cluster 40.86673903425201,
    :characteristic-words [[["doublon"] 6.516823381711584E-7 0.0924958784861653 70 245558]
                           [["html"] 5.33939226273622E-7 0.10288198902650908 113 245558]
                           [["je"] 4.337125745212944E-7 6.288986525362604 43316 245558]
                           [["que"] 4.173084925396253E-7 7.841546149384346 52990 245558]
                           [["tu"] 4.074268558640348E-7 3.3501043600473204 24286 245558]
                           [["est"] 3.377955639649599E-7 11.549501707558191 75359 245558]
                           [["pas"] 2.4113777452594576E-7 7.757857854047822 51031 245558]
                           [["c" "3"] 2.2827294245304774E-7 0.0919619972826989 187 245558]
                           [["d'" "accord"] 2.1925984321213843E-7 0.34122929578856587 3157 245558]
                           [["accord"] 2.1582239310979912E-7 0.3643582776883146 3317 245558]
                           [["n'"] 2.0652295518175379E-7 2.7220870914365967 19018 245558]
                           [["c'"] 2.0626455465855287E-7 8.133324914729675 53018 245558]
                           [["c'" "est"] 2.045637860215166E-7 7.346108070561755 48121 245558]
                           [["c" "3" "a"] 1.9689372787561055E-7 0.07863577723067715 159 245558]
                           [["3" "a"] 1.9408554717374737E-7 0.08058406564148543 167 245558]
                           [["que" "tu"] 1.7732691232064113E-7 0.5637160344747303 4608 245558]
                           [["3" "a" "9"] 1.4887672534699692E-7 0.05909513705995089 119 245558]
                           [["ne"] 1.4862175612417872E-7 3.224877135095816 21792 245558]
                           [["problème"] 1.4510543920598362E-7 0.4354415886958174 3592 245558]
                           [["a" "9"] 1.4219956447243665E-7 0.060714629209972976 128 245558]],
    :cluster-id 74}
   {:n-docs-in-cluster 40.86644549957399,
    :characteristic-words [[["doublon"] 6.516776552695225E-7 0.09249521411341288 70 245558]
                           [["html"] 5.339353898025506E-7 0.10288125005314785 113 245558]
                           [["je"] 4.337094586803758E-7 6.288941353281905 43316 245558]
                           [["que"] 4.173054946043919E-7 7.84148982568989 52990 245558]
                           [["tu"] 4.0742392914960845E-7 3.3500802971583425 24286 245558]
                           [["est"] 3.37793137350495E-7 11.549418750626854 75359 245558]
                           [["pas"] 2.4113604213393813E-7 7.757802131463628 51031 245558]
                           [["c" "3"] 2.2827130239516658E-7 0.09196133674467007 187 245558]
                           [["d'" "accord"] 2.192582680138333E-7 0.34122684483128923 3157 245558]
                           [["accord"] 2.158208427111008E-7 0.36435566060184815 3317 245558]
                           [["n'"] 2.0652147159072598E-7 2.7220675394249527 19018 245558]
                           [["c'"] 2.062630727328596E-7 8.13326649526765 53018 245558]
                           [["c'" "est"] 2.045623165303212E-7 7.346055305464336 48121 245558]
                           [["c" "3" "a"] 1.9689231310106303E-7 0.07863521241126457 159 245558]
                           [["3" "a"] 1.940841526260756E-7 0.0805834868280468 167 245558]
                           [["que" "tu"] 1.7732563845074267E-7 0.5637119854556869 4608 245558]
                           [["3" "a" "9"] 1.4887565566445993E-7 0.05909471259564497 119 245558]
                           [["ne"] 1.4862068842269593E-7 3.224853971679955 21792 245558]
                           [["problème"] 1.4510439665105235E-7 0.43543846103724876 3592 245558]
                           [["a" "9"] 1.4219854271857457E-7 0.060714193113295165 128 245558]],
    :cluster-id 47}
   {:n-docs-in-cluster 40.8664433395458,
    :characteristic-words [[["doublon"] 6.516776208612823E-7 0.09249520922450521 70 245558]
                           [["html"] 5.339353614849246E-7 0.10288124461527806 113 245558]
                           [["je"] 4.337094359208038E-7 6.288941020874944 43316 245558]
                           [["que"] 4.1730547273299834E-7 7.84148941122175 52990 245558]
                           [["tu"] 4.0742390727821487E-7 3.350080120087207 24286 245558]
                           [["est"] 3.377931194759043E-7 11.549418140173218 75359 245558]
                           [["pas"] 2.4113602947739565E-7 7.757801721418881 51031 245558]
                           [["c" "3"] 2.282712903735329E-7 0.09196133188398081 187 245558]
                           [["d'" "accord"] 2.1925825632873597E-7 0.3412268267954745 3157 245558]
                           [["accord"] 2.1582083137294816E-7 0.3643556413435411 3317 245558]
                           [["n'"] 2.065214607660515E-7 2.722067395547922 19018 245558]
                           [["c'"] 2.0626306185267396E-7 8.133266065377429 53018 245558]
                           [["c'" "est"] 2.0456230576115786E-7 7.346054917182796 48121 245558]
                           [["c" "3" "a"] 1.9689230292690985E-7 0.07863520825493825 159 245558]
                           [["3" "a"] 1.9408414224375559E-7 0.08058348256874284 167 245558]
                           [["que" "tu"] 1.7732562906935811E-7 0.5637119556602442 4608 245558]
                           [["3" "a" "9"] 1.4887564788422514E-7 0.059094709472147254 119 245558]
                           [["ne"] 1.486206804846013E-7 3.224853801227754 21792 245558]
                           [["problème"] 1.4510438901826905E-7 0.43543843802180604 3592 245558]
                           [["a" "9"] 1.421985351534455E-7 0.06071418990419852 128 245558]],
    :cluster-id 4}
   {:n-docs-in-cluster 40.86634481955428,
    :characteristic-words [[["doublon"] 6.516760490257387E-7 0.09249498623893392 70 245558]
                           [["html"] 5.33934074103265E-7 0.10288099659126357 113 245558]
                           [["je"] 4.337083900907146E-7 6.288925859623672 43316 245558]
                           [["que"] 4.173044665378711E-7 7.841470507118379 52990 245558]
                           [["tu"] 4.074229251749273E-7 3.350072043782105 24286 245558]
                           [["est"] 3.377923049052711E-7 11.54939029707061 75359 245558]
                           [["pas"] 2.4113544805359766E-7 7.757783019068076 51031 245558]
                           [["c" "3"] 2.2827074008455184E-7 0.09196111018547026 187 245558]
                           [["d'" "accord"] 2.1925772773767616E-7 0.3412260041727723 3157 245558]
                           [["accord"] 2.158203109281498E-7 0.364354762962363 3317 245558]
                           [["n'"] 2.065209627755138E-7 2.7220608332431393 19018 245558]
                           [["c'"] 2.0626256447275892E-7 8.133246457864818 53018 245558]
                           [["c'" "est"] 2.0456181260009032E-7 7.3460372074628255 48121 245558]
                           [["c" "3" "a"] 1.9689182807758332E-7 0.07863501868277571 159 245558]
                           [["3" "a"] 1.940836743732216E-7 0.08058328829972021 167 245558]
                           [["que" "tu"] 1.773252015502269E-7 0.5637105966749116 4608 245558]
                           [["3" "a" "9"] 1.488752888328948E-7 0.059094567007822174 119 245558]
                           [["ne"] 1.486203221601201E-7 3.224846026815719 21792 245558]
                           [["problème"] 1.45104039186994E-7 0.43543738827566464 3592 245558]
                           [["a" "9"] 1.421981923981075E-7 0.06071404353566291 128 245558]],
    :cluster-id 6}
   {:n-docs-in-cluster 40.86615326897583,
    :characteristic-words [[["doublon"] 6.516729931290571E-7 0.0924945526922538 70 245558]
                           [["html"] 5.339315704670777E-7 0.10288051436280626 113 245558]
                           [["je"] 4.33706356717245E-7 6.2888963818861425 43316 245558]
                           [["que"] 4.1730251021387943E-7 7.8414337522234 52990 245558]
                           [["tu"] 4.074210151472357E-7 3.3500563411730044 24286 245558]
                           [["est"] 3.377907213941711E-7 11.549336162246371 75359 245558]
                           [["pas"] 2.411343175134917E-7 7.757746656436877 51031 245558]
                           [["c" "3"] 2.2826966968036988E-7 0.0919606791411981 187 245558]
                           [["d'" "accord"] 2.1925669989319996E-7 0.34122440476282284 3157 245558]
                           [["accord"] 2.1581929927905197E-7 0.3643530551422501 3317 245558]
                           [["n'"] 2.0651999460552517E-7 2.7220480742765734 19018 245558]
                           [["c'"] 2.0626159757952678E-7 8.13320833534443 53018 245558]
                           [["c'" "est"] 2.0456085347841935E-7 7.346002774785196 48121 245558]
                           [["c" "3" "a"] 1.9689090508326346E-7 0.07863465010116197 159 245558]
                           [["3" "a"] 1.9408276438412364E-7 0.08058291058608907 167 245558]
                           [["que" "tu"] 1.773243701874705E-7 0.5637079544251256 4608 245558]
                           [["3" "a" "9"] 1.4887459070730968E-7 0.05909429001709669 119 245558]
                           [["ne"] 1.486196253286387E-7 3.2248309111716686 21792 245558]
                           [["problème"] 1.451033589533468E-7 0.4354353472738578 3592 245558]
                           [["a" "9"] 1.4219752549060605E-7 0.06071375895405386 128 245558]],
    :cluster-id 66}
   {:n-docs-in-cluster 40.86596270581286,
    :characteristic-words [[["doublon"] 6.516699531484635E-7 0.09249412138044412 70 245558]
                           [["html"] 5.33929079863868E-7 0.10288003462016702 113 245558]
                           [["je"] 4.3370433400191644E-7 6.288867056102075 43316 245558]
                           [["que"] 4.1730056399291726E-7 7.841397186794599 52990 245558]
                           [["tu"] 4.074191150005291E-7 3.350040719508586 24286 245558]
                           [["est"] 3.3778914609872146E-7 11.54928230647933 75359 245558]
                           [["pas"] 2.4113319285756774E-7 7.757710481249788 51031 245558]
                           [["c" "3"] 2.2826860503373514E-7 0.09196025031889679 187 245558]
                           [["d'" "accord"] 2.1925567728064976E-7 0.3412228135976007 3157 245558]
                           [["accord"] 2.1581829254269103E-7 0.3643513561257029 3317 245558]
                           [["n'"] 2.0651903143154016E-7 2.722035381080631 19018 245558]
                           [["c'"] 2.0626063557127594E-7 8.133170409340085 53018 245558]
                           [["c'" "est"] 2.0455989946377429E-7 7.3459685196030335 48121 245558]
                           [["c" "3" "a"] 1.9688998658014267E-7 0.0786342834195331 159 245558]
                           [["3" "a"] 1.940818591776583E-7 0.08058253481951701 167 245558]
                           [["que" "tu"] 1.773235432378506E-7 0.5637053257957536 4608 245558]
                           [["3" "a" "9"] 1.488738965976094E-7 0.05909401445421838 119 245558]
                           [["ne"] 1.4861893216089328E-7 3.2248158734465657 21792 245558]
                           [["problème"] 1.451026823834356E-7 0.4354333167931187 3592 245558]
                           [["a" "9"] 1.4219686236827123E-7 0.060713475839421875 128 245558]],
    :cluster-id 28}
   {:n-docs-in-cluster 40.86576967141039,
    :characteristic-words [[["doublon"] 6.516668736109704E-7 0.09249368447534598 70 245558]
                           [["html"] 5.339265568369417E-7 0.1028795486561841 113 245558]
                           [["je"] 4.337022850853245E-7 6.28883735001873 43316 245558]
                           [["que"] 4.1729859234784783E-7 7.841360147182151 52990 245558]
                           [["tu"] 4.0741719004033783E-7 3.350024895261078 24286 245558]
                           [["est"] 3.3778755015312356E-7 11.54922775230612 75359 245558]
                           [["pas"] 2.4113205365772217E-7 7.757673836939753 51031 245558]
                           [["c" "3"] 2.2826752641563752E-7 0.09195981593559115 187 245558]
                           [["d'" "accord"] 2.1925464158134567E-7 0.3412212017980107 3157 245558]
                           [["accord"] 2.1581727298325415E-7 0.3643496350761628 3317 245558]
                           [["n'"] 2.065180555455015E-7 2.722022523278236 19018 245558]
                           [["c'"] 2.0625966112852723E-7 8.133131991508083 53018 245558]
                           [["c'" "est"] 2.0455893290360905E-7 7.345933820196745 48121 245558]
                           [["c" "3" "a"] 1.9688905623233E-7 0.07863391198274552 159 245558]
                           [["3" "a"] 1.9408094219242056E-7 0.08058215417997193 167 245558]
                           [["que" "tu"] 1.773227054913118E-7 0.563702663078091 4608 245558]
                           [["3" "a" "9"] 1.4887319301805368E-7 0.059093735317817024 119 245558]
                           [["ne"] 1.486182300558525E-7 3.2248006407109617 21792 245558]
                           [["problème"] 1.4510199672357338E-7 0.4354312599809325 3592 245558]
                           [["a" "9"] 1.4219619059487043E-7 0.06071318905333506 128 245558]],
    :cluster-id 3}
   {:n-docs-in-cluster 40.86576742005729,
    :characteristic-words [[["doublon"] 6.516668376796431E-7 0.09249367937973776 70 245558]
                           [["html"] 5.339265275565441E-7 0.10287954298840392 113 245558]
                           [["je"] 4.3370226121552946E-7 6.28883700355778 43316 245558]
                           [["que"] 4.172985693662312E-7 7.841359715190513 52990 245558]
                           [["tu"] 4.074171677803662E-7 3.3500247107034653 24286 245558]
                           [["est"] 3.3778753194546596E-7 11.549227116042811 75359 245558]
                           [["pas"] 2.411320406681128E-7 7.757673409558518 51031 245558]
                           [["c" "3"] 2.2826751396899658E-7 0.09195981086939463 187 245558]
                           [["d'" "accord"] 2.1925462954930364E-7 0.341221182999651 3157 245558]
                           [["accord"] 2.1581726113162336E-7 0.36434961500362517 3317 245558]
                           [["n'"] 2.065180442767378E-7 2.7220223733181577 19018 245558]
                           [["c'"] 2.0625964991527468E-7 8.133131543442337 53018 245558]
                           [["c'" "est"] 2.045589218013788E-7 7.3459334154988545 48121 245558]
                           [["c" "3" "a"] 1.9688904552908615E-7 0.07863390765069193 159 245558]
                           [["3" "a"] 1.940809316296893E-7 0.08058214974058693 167 245558]
                           [["que" "tu"] 1.7732269574910475E-7 0.5637026320229123 4608 245558]
                           [["3" "a" "9"] 1.4887318487873114E-7 0.05909373206225943 119 245558]
                           [["ne"] 1.4861822211775788E-7 3.2248004630521323 21792 245558]
                           [["problème"] 1.4510198870221203E-7 0.43543123599240796 3592 245558]
                           [["a" "9"] 1.421961828701468E-7 0.060713185708559464 128 245558]],
    :cluster-id 92}
   {:n-docs-in-cluster 40.86567176779485,
    :characteristic-words [[["doublon"] 6.516653115800838E-7 0.09249346288485083 70 245558]
                           [["html"] 5.339252773656211E-7 0.10287930218389515 113 245558]
                           [["je"] 4.3370124580555114E-7 6.288822283621614 43316 245558]
                           [["que"] 4.1729759236996955E-7 7.841341361349509 52990 245558]
                           [["tu"] 4.0741621398776573E-7 3.350016869484189 24286 245558]
                           [["est"] 3.377867409115609E-7 11.549200083399759 75359 245558]
                           [["pas"] 2.4113147589766015E-7 7.75765525159745 51031 245558]
                           [["c" "3"] 2.282669794313047E-7 0.0919595956241045 187 245558]
                           [["d'" "accord"] 2.192541161266659E-7 0.341220384321926 3157 245558]
                           [["accord"] 2.1581675600790273E-7 0.36434876219044615 3317 245558]
                           [["n'"] 2.0651756071909944E-7 2.722016002029549 19018 245558]
                           [["c'"] 2.0625916696825897E-7 8.133112506666986 53018 245558]
                           [["c'" "est"] 2.0455844285116598E-7 7.34591622127504 48121 245558]
                           [["c" "3" "a"] 1.9688858459397662E-7 0.0786337235966132 159 245558]
                           [["3" "a"] 1.940804769985649E-7 0.08058196112636469 167 245558]
                           [["que" "tu"] 1.7732228049793797E-7 0.5637013125950517 4608 245558]
                           [["3" "a" "9"] 1.488728362617625E-7 0.05909359374479895 119 245558]
                           [["ne"] 1.486178740073285E-7 3.2247929149383916 21792 245558]
                           [["problème"] 1.4510164909886658E-7 0.43543021680237426 3592 245558]
                           [["a" "9"] 1.421958498917103E-7 0.060713043600532576 128 245558]],
    :cluster-id 72}
   {:n-docs-in-cluster 40.865670048938604,
    :characteristic-words [[["doublon"] 6.516652842711995E-7 0.09249345899447145 70 245558]
                           [["html"] 5.339252548541146E-7 0.10287929785667563 113 245558]
                           [["je"] 4.3370122781993814E-7 6.288822019106653 43316 245558]
                           [["que"] 4.1729757505049037E-7 7.841341031533848 52990 245558]
                           [["tu"] 4.074161969458423E-7 3.350016728578701 24286 245558]
                           [["est"] 3.377867267007062E-7 11.549199597627366 75359 245558]
                           [["pas"] 2.4113146590565293E-7 7.757654925301729 51031 245558]
                           [["c" "3"] 2.2826696996838813E-7 0.09195959175618022 187 245558]
                           [["d'" "accord"] 2.1925410696732595E-7 0.3412203699698117 3157 245558]
                           [["accord"] 2.1581674680692942E-7 0.36434874686552715 3317 245558]
                           [["n'"] 2.0651755228140445E-7 2.7220158875384834 19018 245558]
                           [["c'"] 2.0625915830851937E-7 8.133112164579117 53018 245558]
                           [["c'" "est"] 2.045584343024487E-7 7.345915912297518 48121 245558]
                           [["c" "3" "a"] 1.9688857620311917E-7 0.07863372028919009 159 245558]
                           [["3" "a"] 1.9408046888352848E-7 0.08058195773699653 167 245558]
                           [["que" "tu"] 1.77322273170466E-7 0.5637012888851374 4608 245558]
                           [["3" "a" "9"] 1.4887283002716634E-7 0.0590935912592557 119 245558]
                           [["ne"] 1.4861786779007957E-7 3.224792779299967 21792 245558]
                           [["problème"] 1.4510164299263995E-7 0.4354301984876877 3592 245558]
                           [["a" "9"] 1.4219584385313788E-7 0.06071304104687344 128 245558]],
    :cluster-id 10}
   {:n-docs-in-cluster 40.865478928531616,
    :characteristic-words [[["doublon"] 6.516622352830542E-7 0.09249302642142182 70 245558]
                           [["html"] 5.339227568835342E-7 0.10287881671117514 113 245558]
                           [["je"] 4.3369919922042754E-7 6.288792607568278 43316 245558]
                           [["que"] 4.1729562316739077E-7 7.841304359180566 52990 245558]
                           [["tu"] 4.0741429113699823E-7 3.3500010612334843 24286 245558]
                           [["est"] 3.3778514663129755E-7 11.549145584375575 75359 245558]
                           [["pas"] 2.411303381411045E-7 7.7576186443313215 51031 245558]
                           [["c" "3"] 2.2826590204312602E-7 0.09195916167991874 187 245558]
                           [["d'" "accord"] 2.1925308149595146E-7 0.3412187741517112 3157 245558]
                           [["accord"] 2.1581573748929994E-7 0.3643470428807228 3317 245558]
                           [["n'"] 2.0651658627635072E-7 2.7220031572251573 19018 245558]
                           [["c'"] 2.0625819374675558E-7 8.133074127671744 53018 245558]
                           [["c'" "est"] 2.0455747751224607E-7 7.345881556946523 48121 245558]
                           [["c" "3" "a"] 1.9688765538414255E-7 0.07863335253531256 159 245558]
                           [["3" "a"] 1.9407956123977665E-7 0.08058158087160966 167 245558]
                           [["que" "tu"] 1.7732144377835546E-7 0.5636986525691402 4608 245558]
                           [["3" "a" "9"] 1.488721338149812E-7 0.05909331489057774 119 245558]
                           [["ne"] 1.486171726239327E-7 3.2247776976016254 21792 245558]
                           [["problème"] 1.4510096423003827E-7 0.4354281620694273 3592 245558]
                           [["a" "9"] 1.421951786942377E-7 0.06071275710435901 128 245558]],
    :cluster-id 94}
   {:n-docs-in-cluster 40.86528180566779,
    :characteristic-words [[["doublon"] 6.516590904271508E-7 0.09249258026268982 70 245558]
                           [["html"] 5.339201805051874E-7 0.10287832045449302 113 245558]
                           [["je"] 4.3369710667207073E-7 6.288762272311185 43316 245558]
                           [["que"] 4.172936098889579E-7 7.841266535070534 52990 245558]
                           [["tu"] 4.0741232559815543E-7 3.3499849018290004 24286 245558]
                           [["est"] 3.377835171569643E-7 11.549089874746977 75359 245558]
                           [["pas"] 2.411291745163524E-7 7.757581223896152 51031 245558]
                           [["c" "3"] 2.282648006585175E-7 0.09195871809639079 187 245558]
                           [["d'" "accord"] 2.1925202370320918E-7 0.341217128214266 3157 245558]
                           [["accord"] 2.15814696155614E-7 0.3643452853794175 3317 245558]
                           [["n'"] 2.0651558985118612E-7 2.7219900270949835 19018 245558]
                           [["c'"] 2.062571985428363E-7 8.133034896151479 53018 245558]
                           [["c'" "est"] 2.0455649052397717E-7 7.345846122608098 48121 245558]
                           [["c" "3" "a"] 1.968867052639517E-7 0.07863297323150749 159 245558]
                           [["3" "a"] 1.9407862462440806E-7 0.08058119217013303 167 245558]
                           [["que" "tu"] 1.7732058812947038E-7 0.5636959334552162 4608 245558]
                           [["3" "a" "9"] 1.4887141515373958E-7 0.05909302984207773 119 245558]
                           [["ne"] 1.486164556419034E-7 3.2247621422372523 21792 245558]
                           [["problème"] 1.4510026420666566E-7 0.43542606169403536 3592 245558]
                           [["a" "9"] 1.4219449249834593E-7 0.060712464244153476 128 245558]],
    :cluster-id 18}
   {:n-docs-in-cluster 40.865188049482946,
    :characteristic-words [[["doublon"] 6.516575948292344E-7 0.09249236805929721 70 245558]
                           [["html"] 5.339189550843809E-7 0.1028780844233588 113 245558]
                           [["je"] 4.3369611146815146E-7 6.288747844162609 43316 245558]
                           [["que"] 4.172926523215992E-7 7.841248545050612 52990 245558]
                           [["tu"] 4.074113907903687E-7 3.3499772160431887 24286 245558]
                           [["est"] 3.377827418882262E-7 11.54906337796156 75359 245558]
                           [["pas"] 2.4112862151426384E-7 7.757563425873342 51031 245558]
                           [["c" "3"] 2.28264276789375E-7 0.09195850711782462 187 245558]
                           [["d'" "accord"] 2.1925152049462326E-7 0.3412163453684201 3157 245558]
                           [["accord"] 2.1581420105165616E-7 0.3643444494712244 3317 245558]
                           [["n'"] 2.0651511611902151E-7 2.721983782101958 19018 245558]
                           [["c'"] 2.062567252547609E-7 8.133016236734775 53018 245558]
                           [["c'" "est"] 2.0455602112168236E-7 7.345829269218729 48121 245558]
                           [["c" "3" "a"] 1.9688625333552645E-7 0.07863279282586146 159 245558]
                           [["3" "a"] 1.9407817929487092E-7 0.08058100729473744 167 245558]
                           [["que" "tu"] 1.7732018137150973E-7 0.5636946401818617 4608 245558]
                           [["3" "a" "9"] 1.4887107367168861E-7 0.05909289426643074 119 245558]
                           [["ne"] 1.4861611463690139E-7 3.2247547437468462 21792 245558]
                           [["problème"] 1.4509993129241394E-7 0.4354250627070107 3592 245558]
                           [["a" "9"] 1.421941662263504E-7 0.06071232495307901 128 245558]],
    :cluster-id 89}
   {:n-docs-in-cluster 40.865188036760905,
    :characteristic-words [[["doublon"] 6.516575944753508E-7 0.09249236803050254 70 245558]
                           [["html"] 5.33918954945603E-7 0.10287808439133088 113 245558]
                           [["je"] 4.3369611135712915E-7 6.288747842204802 43316 245558]
                           [["que"] 4.1729265198853227E-7 7.84124854260948 52990 245558]
                           [["tu"] 4.0741139051281294E-7 3.3499772150002736 24286 245558]
                           [["est"] 3.377827416661816E-7 11.549063374366105 75359 245558]
                           [["pas"] 2.4112862118119693E-7 7.757563423458249 51031 245558]
                           [["c" "3"] 2.2826427672865968E-7 0.09195850708919613 187 245558]
                           [["d'" "accord"] 2.1925152038360096E-7 0.3412163452621933 3157 245558]
                           [["accord"] 2.1581420098226722E-7 0.3643444493577976 3317 245558]
                           [["n'"] 2.065151160079992E-7 2.721983781254556 19018 245558]
                           [["c'"] 2.062567251437386E-7 8.133016234202808 53018 245558]
                           [["c'" "est"] 2.0455602123270467E-7 7.345829266931827 48121 245558]
                           [["c" "3" "a"] 1.968862534205279E-7 0.07863279280138158 159 245558]
                           [["3" "a"] 1.9407817922201254E-7 0.08058100726965105 167 245558]
                           [["que" "tu"] 1.7732018120497628E-7 0.5636946400063727 4608 245558]
                           [["3" "a" "9"] 1.4887107373240394E-7 0.059092894248033975 119 245558]
                           [["ne"] 1.4861611447036793E-7 3.2247547427429213 21792 245558]
                           [["problème"] 1.4509993125078058E-7 0.4354250625714542 3592 245558]
                           [["a" "9"] 1.421941661309406E-7 0.06071232493417808 128 245558]],
    :cluster-id 87}
   {:n-docs-in-cluster 40.8650931921194,
    :characteristic-words [[["doublon"] 6.516560817331624E-7 0.0924921533635476 70 245558]
                           [["html"] 5.339177153798613E-7 0.10287784562000764 113 245558]
                           [["je"] 4.336951046068904E-7 6.288733246553523 43316 245558]
                           [["que"] 4.172916834299656E-7 7.841230343735522 52990 245558]
                           [["tu"] 4.0741044493586287E-7 3.3499694399867987 24286 245558]
                           [["est"] 3.377819577377039E-7 11.549036569967852 75359 245558]
                           [["pas"] 2.411280616287925E-7 7.757545418810372 51031 245558]
                           [["c" "3"] 2.2826374691635454E-7 0.09195829366128727 187 245558]
                           [["d'" "accord"] 2.1925101156838878E-7 0.3412155533279464 3157 245558]
                           [["accord"] 2.1581370011902745E-7 0.3643436037451793 3317 245558]
                           [["n'"] 2.0651463655818603E-7 2.721977463760665 19018 245558]
                           [["c'"] 2.0625624619352578E-7 8.132997358160743 53018 245558]
                           [["c'" "est"] 2.0455554616827243E-7 7.345812217884074 48121 245558]
                           [["c" "3" "a"] 1.968857962185433E-7 0.07863261030132726 159 245558]
                           [["3" "a"] 1.9407772867789663E-7 0.08058082024795589 167 245558]
                           [["que" "tu"] 1.773197695897899E-7 0.5636933317188414 4608 245558]
                           [["3" "a" "9"] 1.4887072808354718E-7 0.05909275709842978 119 245558]
                           [["ne"] 1.4861576957958533E-7 3.224747258360196 21792 245558]
                           [["problème"] 1.4509959443687048E-7 0.4354240519867514 3592 245558]
                           [["a" "9"] 1.421938361223507E-7 0.06071218402601239 128 245558]],
    :cluster-id 31}
   {:n-docs-in-cluster 40.86509161052032,
    :characteristic-words [[["doublon"] 6.516560563888524E-7 0.09249214978382946 70 245558]
                           [["html"] 5.3391769471757E-7 0.10287784163833266 113 245558]
                           [["je"] 4.3369508784252275E-7 6.288733003161072 43316 245558]
                           [["que"] 4.1729166744275403E-7 7.841230040256873 52990 245558]
                           [["tu"] 4.074104291706959E-7 3.34996931033314 24286 245558]
                           [["est"] 3.377819444150276E-7 11.549036122986207 75359 245558]
                           [["pas"] 2.411280519698522E-7 7.757545118570572 51031 245558]
                           [["c" "3"] 2.2826373800681476E-7 0.09195829010223117 187 245558]
                           [["d'" "accord"] 2.19251003116816E-7 0.341215540121902 3157 245558]
                           [["accord"] 2.158136916396991E-7 0.3643435896440115 3317 245558]
                           [["n'"] 2.065146285090691E-7 2.72197735841214 19018 245558]
                           [["c'"] 2.062562384219646E-7 8.132997043389851 53018 245558]
                           [["c'" "est"] 2.0455553817466665E-7 7.345811933579532 48121 245558]
                           [["c" "3" "a"] 1.968857886967823E-7 0.07863260725801424 159 245558]
                           [["3" "a"] 1.9407772106766474E-7 0.08058081712924144 167 245558]
                           [["que" "tu"] 1.7731976273416272E-7 0.5636933099022524 4608 245558]
                           [["3" "a" "9"] 1.4887072230344855E-7 0.059092754811366416 119 245558]
                           [["ne"] 1.4861576375091445E-7 3.22474713355301 21792 245558]
                           [["problème"] 1.4509958880248863E-7 0.4354240351345612 3592 245558]
                           [["a" "9"] 1.4219383048796885E-7 0.06071218167627243 128 245558]],
    :cluster-id 7}
   {:n-docs-in-cluster 40.86499686556298,
    :characteristic-words [[["doublon"] 6.516545448731134E-7 0.09249193534249484 70 245558]
                           [["html"] 5.339164564216459E-7 0.10287760311796401 113 245558]
                           [["je"] 4.3369408209148474E-7 6.288718422850179 43316 245558]
                           [["que"] 4.1729069955032116E-7 7.8412118605103736 52990 245558]
                           [["tu"] 4.074094843153908E-7 3.349961543491394 24286 245558]
                           [["est"] 3.3778116137472836E-7 11.549009346760052 75359 245558]
                           [["pas"] 2.411274927505147E-7 7.757527132846025 51031 245558]
                           [["c" "3"] 2.282632085553321E-7 0.09195807689864023 187 245558]
                           [["d'" "accord"] 2.1925049473181524E-7 0.3412147490199967 3157 245558]
                           [["accord"] 2.1581319120667075E-7 0.3643427449201522 3317 245558]
                           [["n'"] 2.0651414978090088E-7 2.721971047558091 19018 245558]
                           [["c'"] 2.06255759915841E-7 8.132978187186975 53018 245558]
                           [["c'" "est"] 2.0455506388739053E-7 7.3457949024507565 48121 245558]
                           [["c" "3" "a"] 1.9688533212797177E-7 0.07863242494977198 159 245558]
                           [["3" "a"] 1.9407727112376316E-7 0.0805806303041107 167 245558]
                           [["que" "tu"] 1.773193516185767E-7 0.5636920029897635 4608 245558]
                           [["3" "a" "9"] 1.4887037697031147E-7 0.05909261780590987 119 245558]
                           [["ne"] 1.486154192487099E-7 3.2247396570365523 21792 245558]
                           [["problème"] 1.4509925233552323E-7 0.4354230256120072 3592 245558]
                           [["a" "9"] 1.4219350091652927E-7 0.06071204091620473 128 245558]],
    :cluster-id 29}
   {:n-docs-in-cluster 40.864992969314265,
    :characteristic-words [[["doublon"] 6.516544827908297E-7 0.09249192652390625 70 245558]
                           [["html"] 5.339164054641438E-7 0.10287759330916016 113 245558]
                           [["je"] 4.3369404101323283E-7 6.288717823256067 43316 245558]
                           [["que"] 4.172906601374038E-7 7.84121111289471 52990 245558]
                           [["tu"] 4.074094457351407E-7 3.3499612240913037 24286 245558]
                           [["est"] 3.3778112917826064E-7 11.549008245626608 75359 245558]
                           [["pas"] 2.41127470101965E-7 7.757526393209211 51031 245558]
                           [["c" "3"] 2.28263186881697E-7 0.09195806813095207 187 245558]
                           [["d'" "accord"] 2.192504737208445E-7 0.34121471648707974 3157 245558]
                           [["accord"] 2.15813170653667E-7 0.36434271018210995 3317 245558]
                           [["n'"] 2.0651413001893104E-7 2.721970788033386 19018 245558]
                           [["c'"] 2.0625574048693807E-7 8.132977411753007 53018 245558]
                           [["c'" "est"] 2.045550445695099E-7 7.345794202070309 48121 245558]
                           [["c" "3" "a"] 1.968853133547943E-7 0.07863241745261021 159 245558]
                           [["3" "a"] 1.9407725258477337E-7 0.08058062262119843 167 245558]
                           [["que" "tu"] 1.7731933474318673E-7 0.5636919492448841 4608 245558]
                           [["3" "a" "9"] 1.4887036287741795E-7 0.059092612171759415 119 245558]
                           [["ne"] 1.4861540509336635E-7 3.224739349575674 21792 245558]
                           [["problème"] 1.450992384716132E-7 0.4354229840968585 3592 245558]
                           [["a" "9"] 1.42193487248643E-7 0.06071203512765133 128 245558]],
    :cluster-id 13}]

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


  (.save cv-model "../models/lda_0/cv-model")
  org.apache.spark.ml.linalg.SparseVector

  (with-open [os
              (GZIPOutputStream.
                (io/output-stream "../models/lda_0-cv-model"))]
    (let [wtr (uenc/fressian-writer os)]
      (fressian/write-object wtr
        (vec
          (.vocabulary cv-model)))))

  (.stop sc)

  *e)


