(ns discussion-gems.sandbox.clustering
  (:require [sparkling.conf :as conf]
            [discussion-gems.utils.spark :as uspark]
            [sparkling.core :as spark]
            [discussion-gems.utils.encoding :as uenc]
            [clojure.java.io :as io]
            [discussion-gems.parsing :as parsing]
            [clojure.reflect :as reflect]
            [sparkling.destructuring :as s-de]
            [clojure.string :as str])
  (:import (org.apache.spark.sql Dataset Row SparkSession RowFactory)
           (org.apache.spark.ml.feature CountVectorizer CountVectorizerModel)
           (org.apache.spark.ml.clustering LDA LDAModel)
           (org.apache.spark.api.java JavaSparkContext)
           (org.apache.spark.sql.types DataTypes)
           (org.apache.spark.sql.catalyst.expressions GenericRow GenericRowWithSchema)
           (org.apache.spark.sql functions)))

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

  (.persist df2)


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

(comment



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
        ;; TODO submissions as well (Val, 15 Apr 2020)
        (->>
          (spark/union
            (->> comments-rdd
              (spark/map
                (fn [c]
                  (merge
                    (select-keys c [:name :parent_id])
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
                      (select-keys c [:name :parent_id])
                      :dgms_txt_contents [body_raw])))))
            (->> subm-rdd
              (spark/map
                (fn [s]
                  (assoc (select-keys s [:name :link_flair_text])
                    :dgms_txt_contents
                    (into []
                      (remove nil?)
                      [(some-> s :title
                         (->> (parsing/trim-markdown {::parsing/remove-quotes true})))
                       (some-> s :selftext
                         (->> (parsing/trim-markdown {::parsing/remove-quotes true ::parsing/remove-code true}))
                         (as-> selftxt-raw
                           (when (-> selftxt-raw count (> min-comment-body-length))
                             selftxt-raw)))]))))))
          (flow-parent-value-to-children
            map? :name :link_id :link_flair_text
            (fn conj-parent-flair [child flair]
              (assoc child :link_flair_text flair)))
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


(comment ;; actual clustering

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

  (def early-2019-ts
    (quot (.getTime #inst "2019-01-01") 1000))

  (defn created-lately?
    [m]
    (-> m :created_utc
      (as-> ts
        (cond-> ts
          (string? ts) (Long/parseLong 10)))
      (>= early-2019-ts)))


  [(def subm-rdd
     (->>
       (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/submissions/RS.seqfile")
       (spark/filter created-lately?)))
   (def comments-rdd
     (->> (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
       (spark/filter created-lately?)))]


  (def txt-ctnts-df
    (txt-contents-df sprk subm-rdd comments-rdd))


  [(def ^CountVectorizerModel cv-model
     (-> (CountVectorizer.)
       (.setInputCol "txt_contents_words")
       (.setOutputCol "txt_contents_bow")
       (.setVocabSize 10000)
       (.fit txt-ctnts-df)))

   (def txt-ctnts-df-1
     (.transform cv-model txt-ctnts-df))



   (def ^LDAModel lda-model
     (-> (LDA.)
       (.setK 100)
       (.setFeaturesCol "txt_contents_bow")
       (.fit txt-ctnts-df-1)))

   (def txt-ctnts-df-1
     (.transform lda-model txt-ctnts-df-1))]


  *e)


