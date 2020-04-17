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
            [discussion-gems.utils.misc :as u])
  (:import (org.apache.spark.sql Dataset Row SparkSession RowFactory)
           (org.apache.spark.ml.feature CountVectorizer CountVectorizerModel)
           (org.apache.spark.ml.clustering LDA LDAModel)
           (org.apache.spark.api.java JavaSparkContext JavaRDD)
           (org.apache.spark.sql.types DataTypes)
           (org.apache.spark.sql.catalyst.expressions GenericRow GenericRowWithSchema)
           (org.apache.spark.sql functions)
           (java.util.zip GZIPOutputStream)))

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
              (assoc child :link_flair_text flair)))
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
    (quot (.getTime #inst "2018-09-01") 1000))

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

  (def n-docs
    (spark/count txt-ctnts-df))

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
      (fn [^GenericRowWithSchema row]
        (-> row
          (.values)
          (vec)))))
  ;; TODO comments still missing!
  ;; TODO remove URLs, figures
  ;; TODO stemming


  org.apache.spark.ml.linalg.DenseVector
  org.apache.spark.sql.Dataset

  (->> txt-ctnts-df-2
    .toJavaRDD
    (spark/map
      (fn [^GenericRowWithSchema row]
        (-> row
          (.values)
          (vec))))
    (label-clusters-with-words-PMI
      {::vocab-size 20000}
      (fn [row-vec]
        (set
          (let [words
                (-> row-vec
                  (nth 2)
                  (.array)
                  vec)]
            (into #{}
              (comp
                (mapcat
                  (fn [n-gram-size]
                    (partition n-gram-size 1 words)))
                (map vec))
              [1 2 3]))))
      (fn [row-vec]
        (set
          (-> row-vec
            (nth 4)
            (.values))))
      sc)
    (spark/collect)
    (sort-by :n-docs-in-cluster))



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


