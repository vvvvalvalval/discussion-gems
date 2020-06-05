(ns discussion-gems.experiments.detecting-praise-comments
  "Building a classifier for 'Praise Comments' in r/france.

  _Praise Comments_ are comments that express a judgement of high quality or helpfulness
  in reply to some content. Detecting them can lead us to Outstanding Comments, although
  clearly not all Outstanding Comments will have Praise Comments; therefore, in the retrieval
  of Outstanding Comments, Praise Comments help with precision more than with recall.

  Some examples of 'Praise Comments':

  1. 'Thank you, this is very interesting.'
  2. 'Thanks for such an informative reply.'
  3. 'Very clear explanation, I learned a lot.'
  4. 'Thanks for making such a well-articulated argument, that's interesting.'

  If a comment expresses nothing more than gratitude, it's not clear that it's a praise comment,
  so it should be labelled with a probability of 0.5."
  (:require [clojure.data.fressian :as fressian]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [discussion-gems.algs.linalg :as ulinalg]
            [discussion-gems.algs.word-embeddings :as wmbed]
            [discussion-gems.config.libpython]
            [discussion-gems.data-sources :as dgds]
            [discussion-gems.feature-engineering.reddit-markdown :as reddit-md]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.parsing.french :as parsing-fr]
            [discussion-gems.training.db :as trn-db]
            [discussion-gems.utils.encoding :as uenc]
            [discussion-gems.utils.misc :as u]
            [discussion-gems.utils.spark :as uspark]
            [jsonista.core :as json]
            [libpython-clj.python :refer [py. py.. py.-] :as py]
            [libpython-clj.require :refer [require-python]]
            [manifold.deferred :as mfd]
            [mapdag.step :as mdg]
            [next.jdbc :as jdbc]
            [sparkling.core :as spark]
            [sparkling.destructuring :as s-de]
            [vvvvalvalval.supdate.api :as supd])
  (:import (edu.stanford.nlp.pipeline StanfordCoreNLP CoreSentence CoreDocument)
           (edu.stanford.nlp.ling CoreLabel)
           (java.util.zip GZIPOutputStream GZIPInputStream)
           (org.apache.spark.api.java JavaRDD)))


;; ------------------------------------------------------------------------------
;; Building a dataset to label

;; Problem: to train and evaluate a classifier, we need to make a dataset with labeled examples.
;;
;; Praise Comments suffer from class imbalance: there are very few of them amongst all comments.
;; Therefore, we use a heuristic to gather a dataset sufficiently dense in Praise Comments:
;; We find comments that have some similarity to typical Praise sentences.
;;
;; We want that similarity measure to have high recall: that's why we make use of cosine similarity
;; on word embeddings.
;;
;; TODO: relate this approach to concepts and techniques of Relevance Feedback / Active Learning.


(def r-france-sociopolitical-flairs
  "We restrict ourselves to content marked with flairs dedicated to socio-political discussions.

  That's where we're likely to find most of the content of interest and less noise than in other discussions."
  #{;"Culture"
    "Politique"
    "Science"
    "Société"
    "Écologie"
    "Économie"})

(defn is-comment-of-interest?
  [c]
  (and
    (not (-> c :body (= ["[deleted]"])))
    (contains? r-france-sociopolitical-flairs
      (-> c :dgms_comment_submission :link_flair_text))))



(def nlp-pipeline
  (delay
    (StanfordCoreNLP.
      (u/as-java-props
        {"annotators" (str/join "," ["tokenize" "ssplit"])
         "tokenize.language" "French"
         "tokenize.keepeol" "true"}))))


(defn normalize-term
  "To improve recall, we do basic normalization on terms."
  [^String t]
  (-> t
    (str/lower-case)
    (parsing-fr/normalize-french-diacritics) ;; removing accents and such, as mispellings are frequent
    (str/replace #"\W" "") ;; this rids us of "'" in words, as well as punctuation tokens.
    (as-> t
      (when-not (or
                  (= t "")
                  ;; IMPROVEMENT maybe replace this with a compiled Regex for efficiency. (Val, 29 Apr 2020)
                  (contains? parsing-fr/lucene-french-stopwords t))
        t))))


(def d_w2vec
  "We use fastText's model for vectorizing individual terms."
  (delay
    (wmbed/load-word-embedding-index
      normalize-term
      50000
      (io/file "../models/fastText_cc.fr.300.vec.txt"))))


(defn phrase-tokens
  [^CoreSentence sntc]
  (into []
    (keep
      (fn [^CoreLabel tkn]
        (normalize-term
          (.value tkn))))
    (.tokens sntc)))

(comment
  (->>
    (doto (CoreDocument.
            "Merci pour cette argumentation détaillée, c'est intéressant.")
      (->> (.annotate @nlp-pipeline)))
    (.sentences)
    first
    phrase-tokens)
  => ["merci" "argumentation" "detaillee" "interessant"]

  *e)


(def reference-praise-sentences
  ["Merci, très intéressant."
   "Merci pour cette réponse claire et instructive."
   "Merci pour ce commentaire clair et instructif."
   "Merci pour cette argumentation détaillée, c'est intéressant."
   "Réponse intéressante, merci."
   "Explication claire et intéressante."
   "Excellente réponse, merci."
   "Excellent commentaire, merci."
   "Merci, c'est un point de vue intéressant."])


(defn doc-vector
  "Vectorizes human text as the normalized sum of its individual term vectors."
  [body-raw]
  (let [w2vec @d_w2vec
        doc (doto
              (CoreDocument. ^String body-raw)
              (->> (.annotate @nlp-pipeline)))
        sntcs (u/take-first-and-last
                ;; NOTE Why vectorize only the leading and ending sentences?
                ;; The id is that praise is usually located at the very beginning or end of the praising comment. (Val, 29 Apr 2020)
                2 2
                (.sentences doc))
        token-vectors
        (into []
          (comp
            (mapcat phrase-tokens)
            (keep #(wmbed/token-vector w2vec %)))
          sntcs)]
    (if (empty? token-vectors)
      nil
      (ulinalg/vec-project-unit
        (apply ulinalg/vec-add token-vectors)))))


(defn raw-body
  [c]
  (or
    (:dgms_body_raw c)
    (reddit-md/trim-markdown
      {::reddit-md/remove-quotes true
       ::reddit-md/remove-code true}
      (:body c))))


(def comment-pre-sim-score
  (let [d_praise-sentence-vectors
        (delay
          (mapv doc-vector
            reference-praise-sentences))]
    (fn comment-pre-sim-score [c]
      (if-some [v (some->
                    (raw-body c)
                    (doc-vector))]
        (apply max
          ;; NOTE why the max of the similarity scores, rather than e.g the sum or geometric mean? (Val, 30 Apr 2020)
          ;; The goal is to have a rather high recall, that's why the max is preferred.
          (map #(ulinalg/vec-cosine-sim % v)
            @d_praise-sentence-vectors))
        -1.))))

(comment

  (->>
    (uenc/json-read
      (io/resource "reddit-france-comments-dv-sample.json"))
    (map #'comment-pre-sim-score)
    (take 10))


  *e)


(defn enrich-with-parent-comments
  [^JavaRDD all-comments-rdd, comments-coll]
  (let [sc (uspark/rdd-java-context all-comments-rdd)
        selected-parent-ids--bv
        (uspark/broadcast-var sc
          (into #{}
            (keep :parent_id)
            comments-coll))
        name->parent-comment
        (->> all-comments-rdd
          ;; NOTE we're ignoring the cases when the parent is a post. That should be rare enough. (Val, 01 May 2020)
          (spark/filter
            (fn parent-of-selected? [pc]
              (contains?
                (uspark/broadcast-value selected-parent-ids--bv)
                (:name pc))))
          (spark/collect)
          (u/index-and-map-by :name identity))]
    (->> comments-coll
      (mapv
        (fn add-parent [c]
          (merge c
            (when-some [pc (get name->parent-comment
                             (:parent_id c))]
              {:dgms_comment_parent pc})))))))


(defn select-dataset-to-label
  [sc]
  (letfn [(rand-from-comment [c]
            (-> c :name u/draw-random-from-string))]
    (let [comments-rdd (->> (dgds/comments-all-rdd sc)
                         (spark/filter #(is-comment-of-interest? %)))
          n-comments (spark/count comments-rdd)
          high-sim-presim+comments
          (->> comments-rdd
            (spark/key-by #(comment-pre-sim-score %))
            (spark/sort-by-key compare false)
            (spark/take 20000))
          presim-threshold (-> high-sim-presim+comments last s-de/key)
          high-sim-comments
          (into []
            (map s-de/value)
            high-sim-presim+comments)
          ordinary-comments
          (->> comments-rdd
            (spark/sample false (/ 5e3 n-comments) 75701441)
            (spark/map #(assoc % ::sample-slice ::ordinary))
            (spark/collect))
          selected-comments
          (vec
            (concat
              (->> high-sim-comments
                (take 5000)
                (u/draw-n-by rand-from-comment 2000)
                (map #(assoc % ::sample-slice ::top-presim-0)))
              (->> high-sim-comments
                (drop 5000)
                (u/draw-n-by rand-from-comment 3000)
                (map #(assoc % ::sample-slice ::top-presim-1)))
              ordinary-comments))]
      {:n-considered-comments n-comments
       :presim-threshold presim-threshold
       :sampled-comments
       (->> selected-comments
         (enrich-with-parent-comments comments-rdd)
         (mapv
           (fn enrich-comment [c]
             (assoc c
               ::presim-score (comment-pre-sim-score c)))))})))



(def dataset-to-label-path
  "resources/detecting-praise-comments_dataset_v0.fressian.gz")

(comment ;; Saving the sampled comments to a file

  (def d_selected-comments
    (uspark/run-local
      (fn [sc]
        (select-dataset-to-label sc))))

  (->> @d_selected-comments :sampled-comments count)
  => 10058

  (-> @d_selected-comments (dissoc :sampled-comments))

  (rand-nth (:sampled-comments @d_selected-comments))

  (def d_saved
    (mfd/chain d_selected-comments
      (fn [_]
        (with-open [wtr
                    (uenc/fressian-writer
                      (GZIPOutputStream.
                        (io/output-stream
                          #_ dataset-to-label-path
                          "../detecting-praise-comments_dataset_v0.fressian.gz")))]
          (fressian/write-object wtr @d_selected-comments)))))



  (->> @d_selected-comments
    :sampled-comments
    (shuffle)
    (take 10)
    (map :dgms_body_raw))

  *e)




(def d_dataset-to-label
  (delay
    (with-open [rdr (uenc/fressian-reader
                      (GZIPInputStream.
                        (io/input-stream
                          dataset-to-label-path)))]
      (fressian/read-object rdr))))


(def dataset-id
  "discussion-gems.experiments.detecting-praise-comments--label")



(defn comment-body-as-hiccup
  "Parses a markdown text and rewrites it to a Hiccup data structure,
  ready to be rendered by the Labelling UI."
  [body-md]
  (letfn [(to-hiccup [node]
            (if (map? node)
              (into [(:tag node)
                     (merge
                       (case (:tag node)
                         :blockquote {:class "dgms-reddit-quote"}
                         {})
                       (:attrs node))]
                (map to-hiccup)
                (:content node))
              node))]
    (-> body-md
      (reddit-md/md->html-forest)
      (as-> nodes
        {:tag :div
         :attrs {:class "dgms-reddit-body"}
         :content nodes})
      (to-hiccup))))

(defn prepare-comment-for-labelling-ui
  [c]
  (-> c
    (assoc
      :dgms_body__hiccup
      (comment-body-as-hiccup (:body c "")))
    ;; NOTE keeping this might cause serialization issues. (Val, 30 Apr 2020)
    (dissoc :dgms_body_vector)
    (supd/supdate {:dgms_comment_parent #(prepare-comment-for-labelling-ui %)})))

(def d_ordinary-comments-to-label
  (delay
    (into []
      (filter (fn [c]
                (-> c ::sample-slice (= ::ordinary))))
      (:sampled-comments @d_dataset-to-label))))



(comment

  (next-comment-to-label)

  (trn-db/all-labelled-data dataset-id)

  (-
    (->> @d_dataset-to-label
      :sampled-comments
      (filter #(-> % ::sample-slice (= ::ordinary)))
      count)
    (->> (trn-db/all-labelled-data dataset-id)
      (filter #(-> % :labelled_data/datapoint_data ::sample-slice (= ::ordinary)))
      count))

  *e)





(comment ;; Role of the "Culture" flair

  (->> @d_dataset-to-label
    (map #(-> % :dgms_comment_submission :link_flair_text))
    frequencies
    (into (sorted-map)))
  => {"Culture" 1906,
      "Politique" 3710,
      "Science" 448,
      "Société" 3747,
      "Écologie" 154,
      "Économie" 93}

  (->>
    (trn-db/all-labelled-data dataset-id)
    (filter #(-> % :labelled_data/datapoint_label (> 0.5)))
    (map #(-> % :labelled_data/datapoint_data :dgms_comment_submission :link_flair_text))
    frequencies)
  => {"Culture" 223,
      "Société" 274,
      "Politique" 201,
      "Science" 57,
      "Économie" 6,
      "Écologie" 19}


  *e)

(comment ;; Replacing the :labelled_data/datapoint_data

  (->> @d_dataset-to-label
    :sampled-comments
    (map #(select-keys %
            [:name
             :link_id
             :dgms_comment_submission
             :body
             ::sample-slice
             ::presim-score]))
    (map
      (fn [datapoint-data]
        ["UPDATE labelled_data SET datapoint_data__fressian = ? WHERE dataset_id = ? AND datapoint_id = ?"
         (uenc/fressian-encode datapoint-data)
         dataset-id
         (:name datapoint-data)]))
    (run! #(jdbc/execute! trn-db/ds %)))

  *e)



(comment

  (->> (trn-db/all-labelled-data dataset-id)
    (map
      (let [presim-threshold (:presim-threshold @d_dataset-to-label)]
        (fn [dp]
          [(-> dp :labelled_data/datapoint_label (> 0.5))
           (-> dp :labelled_data/datapoint_data ::presim-score (>= presim-threshold))])))
    frequencies
    (into (sorted-map)))
  => {[false false] 3733,
      [false true] 2592,
      [true false] 39, ;; So we've got a lot of positive (about 1%) even outside of the pre-sim score range, interesting...
      [true true] 883}


  (->> (trn-db/all-labelled-data dataset-id)
    (filter
      (fn [dp]
        (-> dp :labelled_data/datapoint_data ::sample-slice (= ::ordinary))))
    (map (fn [dp]
           (-> dp :labelled_data/datapoint_label (> 0.5))))
    frequencies
    (into (sorted-map)))
  => {false 4039, true 52}


  (def false-negatives
    (->> (trn-db/all-labelled-data dataset-id)
      (filter
        (let [presim-threshold (:presim-threshold @d_dataset-to-label)]
          (fn [dp]
            (=
              [(-> dp :labelled_data/datapoint_label (> 0.5))
               (-> dp :labelled_data/datapoint_data ::presim-score (>= presim-threshold))]
              [true false]))))
      vec))

  ;; what do these look like?
  (->> false-negatives
    (shuffle) vec)

  (->> false-negatives
    (shuffle)
    (mapv
      (fn [dp]
        (merge
          (select-keys dp [:labelled_data/datapoint_label])
          (select-keys (:labelled_data/datapoint_data dp)
            [:name :body ::presim-score])))))


  ;; what are the selected presim-scores ?
  (->> false-negatives
    (map #(-> % :labelled_data/datapoint_data ::presim-score))
    (sort) vec)
  =>
  [0.328646631097854
   0.3353616256631584
   0.35485746351378716
   0.43699928424938816
   0.4450165557901709
   0.47929227111323897
   0.47951722693085913
   0.48021795633906644
   0.4946798026620642
   0.5088480350703977
   0.5221654887412079
   0.527545552815008
   0.5328231318306578
   0.5336899832684853
   0.5347677252720517
   0.5418351806112662
   0.5443363057904134
   0.5510727179435371
   0.5719249622703851
   0.5825668276606277
   0.5865640451017485
   0.5997814376554756
   0.6023557745110967
   0.6047920662378916
   0.6186300067654278
   0.6194654847638851
   0.6204175186691259
   0.6310424458314615
   0.6393959003496512
   0.6397522747417682
   0.6408699601020179
   0.6456308475351854
   0.6517955544287453
   0.6591850123609143
   0.6640644644155163
   0.6780984340094501
   0.6820847415085116
   0.689713100030212
   0.6911988416015605]


  ;; Correcting some labelling mistakes
  (mapv
    #(jdbc/execute! trn-db/ds
       ["UPDATE labelled_data SET datapoint_label__fressian = ? WHERE dataset_id = ? AND datapoint_id = ?"
        (uenc/fressian-encode 0.)
        dataset-id
        %])
    #{"t1_d75lqjw"
      "t1_du6b7yp"
      "t1_d1gmos3"
      "t1_dgti3wd"
      "t1_dfypo0e"
      "t1_dvbax6r"})


  *e)


(comment ;; distribution of presim-scores

  (->> (trn-db/all-labelled-data dataset-id)
    (filter #(-> % :labelled_data/datapoint_data ::sample-slice (= ::ordinary)))
    (map #(-> % :labelled_data/datapoint_data ::presim-score))
    (map #(-> % (* 10.) Math/floor (/ 10.)))
    frequencies
    (into (sorted-map)))
  {-1.0 294,
   0.0 13,
   0.1 71,
   0.2 197,
   0.3 410,
   0.4 906,
   0.5 1529,
   0.6 539,
   0.7 37,
   0.8 14,
   0.9 6,
   1.0 1}

  *e)


(def heuristic-positive?
  (let [patterns
        (mapv
          (fn [pattern]
            (cond-> pattern
              (string? pattern) (->
                                  (str/lower-case)
                                  (parsing-fr/normalize-french-diacritics))))
          ;; IMPROVEMENT use either WordNet or vector similarity (Val, 07 May 2020)
          ;; IMPROVEMENT make faster e.g via Regex compilation (Val, 07 May 2020)
          ["merci"

           "intéressant"
           "passionant"
           "enrichissant"
           "pertinent"
           #"constructi(f|ve)"
           #"instructi(f|ve)"
           "clair"
           "limpide"
           "détaillé"
           "fascinant"
           "sourcé"
           "expliqué"
           "pédagogique"
           "éclairant"
           "de qualité"

           "commentaire"
           "article"
           #"\Wpost\W"
           "remarque"
           "explication"
           "éclaircissement"
           "clarification"
           "argumentation"
           "argumentaire"
           "point de vue"
           ;#"infos?\W"
           ;"information"
           "bon sens"
           "recommandation"
           "sources"
           "référence"

           #"(bon|beau) (travail|boulot)"

           "superbe"
           "excellent"
           ;"bravo"
           "magnifique"
           "très bon"
           #"super\W"

           "AJA"
           "appris"
           "coucherai moins bête"
           "savais pas"
           "connaissais pas"
           "comprends mieux"
           "enfin compris"

           "mieux dit"
           "dit mieux"
           "bien dit"])]
    (fn heuristic-positive? [c]
      (boolean
        (when-some [body-normalized
                    (some-> (raw-body c)
                      (str/lower-case)
                      (parsing-fr/normalize-french-diacritics))]
          (and
            (-> body-normalized count (< 2000))
            (some
              (fn matches? [pattern]
                (if (string? pattern)
                  (str/includes? body-normalized pattern)
                  (some? (re-find pattern body-normalized))))
              patterns)))))))



(defn collect-heuristic-global-statistics
  [sc]
  (let [{N_h+ true N_h- false}
        (->> (dgds/comments-all-rdd sc)
          (spark/filter #(is-comment-of-interest? %))
          (spark/key-by #(heuristic-positive? %))
          (spark/map-values (constantly 1))
          (spark/reduce-by-key +)
          (spark/collect-map))]
    {:N_h+ N_h+ :N_h- N_h-}))

(defn sample-comments-by-heuristic
  [sc {n-take :n-take
       :or {n-take 30000}}]
  (letfn [(rand-from-comment [c]
            (-> c :name
              (str "_H")
              u/draw-random-from-string))]
    (let [all-comments (dgds/comments-all-rdd sc)]
      (->> all-comments
        (spark/filter #(is-comment-of-interest? %))
        (spark/filter #(heuristic-positive? %))
        (spark/key-by rand-from-comment)
        (spark/sort-by-key)
        (spark/values)
        (spark/take n-take)
        (into [])
        (enrich-with-parent-comments all-comments)))))

(comment ;; Building a dataset filtered by heuristic to label

  (def d_heuristic-dataset
    (uspark/run-local
      (fn [sc]
        (sample-comments-by-heuristic sc {:n-take 30000}))))

  (take 3 @d_heuristic-dataset)

  (def d_saved
    (mfd/chain d_heuristic-dataset
      (fn [_]
        (with-open [wtr
                    (uenc/fressian-writer
                      (GZIPOutputStream.
                        (io/output-stream
                          "../detecting-praise-comments_heuristic-dataset_v0.fressian.gz")))]
          (fressian/write-object wtr @d_heuristic-dataset)))))

  (def d_global-heuristic-stats
    (uspark/run-local
      (fn [sc]
        (collect-heuristic-global-statistics sc))))

  @d_global-heuristic-stats
  => {:N_h+ 264335, :N_h- 1428833}

  *e)


(def d_global-heuristic-stats
  (delay
    {:N_h+ 264335, :N_h- 1428833}))



(def d_heuristic-dataset
  (delay
    (with-open [rdr (uenc/fressian-reader
                      (GZIPInputStream.
                        (io/input-stream
                          "../detecting-praise-comments_heuristic-dataset_v0.fressian.gz")))]
      (fressian/read-object rdr))))




(comment ;; recurring negatives

  (->> @d_heuristic-dataset
    (filter is-comment-of-interest?)
    (filter heuristic-positive?)
    (map
      (fn [c]
        (boolean
          (when-some [body-normalized
                      (some-> (raw-body c)
                        (str/lower-case))]
            (re-matches #"(\s)*source(\s)*\?(\s)*"
              body-normalized)))))
    (frequencies))
  => {false 29928, true 72} ;; wow, disappointing

  (->> @d_heuristic-dataset
    (filter is-comment-of-interest?)
    (filter heuristic-positive?)
    (map
      (fn [c]
        (boolean
          (when-some [body-normalized
                      (some-> (raw-body c)
                        (str/lower-case))]
            (str/includes?
              body-normalized
              "ce commentaire a été supprimé")))))
    (frequencies))
  => {false 29879, true 121}

  *e)


(defn uniformly-sampled-comments
  []
  (->> @d_dataset-to-label
    :sampled-comments
    (filter #(and
               (is-comment-of-interest? %) ;; NOTE because of residual "Culture" flair comments
               (-> % ::sample-slice (= ::ordinary))))))


;; ------------------------------------------------------------------------------
;; 2nd heuristic 'h2': a linear split based on BoW and non-word features


(def h2-features-map
  (let [dag_features
        (merge discussion-gems.feature-engineering.reddit-markdown/dag_reddit-markdown-features
          {::words
           (mdg/step [:dgms_body_raw] parsing/split-words-fr)

           ;; NOTE focusing on the beginning and end of comment, (Val, 02 Jun 2020)
           ;; which is usually most indicative of sentiment
           ::zoned-words
           (mdg/step [::words]
             (fn [words]
               (u/take-first-and-last 30 30 words)))

           :zoned_tokens
           (mdg/step [::zoned-words]
             (fn [zoned-words]
               (mapv
                 (fn [w]
                   (-> w
                     (str/lower-case)
                     (parsing-fr/normalize-french-diacritics)))
                 zoned-words)))

           :n_words
           (mdg/step [::words] count)

           :is_post
           (mdg/step [:reddit_name]
             (fn [reddit-name]
               (if (some? reddit-name)
                 (str/starts-with? reddit-name "t3_")
                 false)))
           
           ::non-word-features
           (mdg/step [:is_post :n_words :dgms_n_quotes :dgms_n_hyperlinks]
             (fn [is_post n_words dgms_n_quotes dgms_n_hyperlinks]
               {:is_post is_post
                :n_words n_words
                :dgms_n_quotes dgms_n_quotes
                :dgms_n_hyperlinks dgms_n_hyperlinks}))

           ::features-map
           (mdg/step
             [::non-word-features :zoned_tokens]
             (fn [nw-feats zoned_tokens]
               (assoc nw-feats :zoned_tokens zoned_tokens)))})
        compiled-dag
        (mapdag.runtime.jvm-eval/compile-graph
          {:mapdag.run/input-keys [::reddit-md/md-txt :reddit_name]
           :mapdag.run/output-keys [::features-map]}
          dag_features)]
    (letfn [(compute-features-map [c]
              (let [md-txt (or
                             (:body c)
                             (:selftext c))]
                (::features-map
                  (compiled-dag
                    {:reddit_name (:name c)
                     ::reddit-md/md-txt md-txt}))))]
      (fn h2-features-map [c]
        {:dgms_comment (compute-features-map c)
         :dgms_parent (compute-features-map
                        (-> c :dgms_comment_parent))}))))

(defn h2-featmaps
  []
  (let [name->label (trn-db/all-labels dataset-id)
        unif-sampled-comments (uniformly-sampled-comments)]
    (->> @d_heuristic-dataset
      (filter is-comment-of-interest?)
      (filter heuristic-positive?)
      (remove
        (let [to-ignore-ids
              (into #{}
                (comp
                  (map :name)
                  (filter name->label))
                unif-sampled-comments)]
          (fn already-labelled-in-first-dataset? [c]
            (contains? to-ignore-ids (:name c)))))
      (map
        (fn [c]
          (when-some [lbl (get name->label (:name c))]
            [c lbl])))
      (take-while some?)
      (pmap (fn [[c lbl]]
              (assoc (h2-features-map c)
                :dgms_h2_label lbl))))))

(def h2_trainset_path
  "./resources/h2-train-set-0.json.gz")

(def h2_learned_model_path
  "./resources/h2-learned-model-0.json.gz")

(def d_h2-m-repr
  (delay
    (with-open [rdr (io/reader
                      (GZIPInputStream.
                        (io/input-stream h2_learned_model_path)))]
      (json/read-value rdr))))

(comment
  ;; Saving the training set to JSON-gz
  (def train-set
    (-> (h2-featmaps)
      ;; NOTE leaving one out of 10 as a test set. (Val, 04 Jun 2020)
      (->>
        (partition-all 10)
        (mapcat #(take 9 %)))
      vec))

  (with-open [os (GZIPOutputStream.
                   (io/output-stream h2_trainset_path))]
    (json/write-value os train-set))



  (count train-set)
  => 9486


  ;; Learning the h2 classifier
  (require-python '[discussion_gems_py.praise_comments_h2 :reload])

  (discussion_gems_py.praise_comments_h2/train_and_save_h2_linear_classifier_jsongz
    h2_trainset_path
    h2_learned_model_path
    :cv_k_folds 5)


  ;; Reading some self-documentation of the learned model.
  (-> @d_h2-m-repr
    (get "INFO"))
  =>
  {"kfold_cv_metrics"
   {"precision" [0.11956521739130435
                 0.1556122448979592
                 0.15777262180974477
                 0.16063348416289594
                 0.16981132075471697],
    "recall" [0.7857142857142857
              0.7261904761904762
              0.8095238095238095
              0.8452380952380952
              0.8571428571428571]}}

  *e)


(def h2-selected?
  (letfn
    [(apply-coeffs [coeffs-map m]
       (let [n2
             (Math/sqrt
               (reduce-kv
                 (fn [s w v]
                   (+ s
                     (if (contains? coeffs-map w)
                       (double (* v v))
                       0.)))
                 0. m))]
         (if (zero? n2)
           0.
           (/
             (reduce-kv
               (fn [s w v]
                 (+ s
                   (*
                     (double
                       (get coeffs-map w 0.))
                     v)))
               0. m)
             n2))))
     (BoW-map [x]
       (->> x
         :zoned_tokens
         frequencies))
     (nw-map [x]
       {"is_post" (if (:is_post x) 1. 0.)
        "log2_n_words" (/
                         (Math/log
                           (+ 1. (:n_words x)))
                         (Math/log 2.))
        "dgms_n_hyperlinks" (double (:dgms_n_hyperlinks x))
        "dgms_n_quotes" (double (:dgms_n_quotes x))})]
    (fn h2-selected? [h2_m_repr c]
      (let [fm (h2-features-map c)
            {cmt :dgms_comment prt :dgms_parent} fm
            {intercept "intercept",
             {cmt-bow "BoW",
              cmt-nw "non_word"} "dgms_comment"
             {parent-bow "BoW",
              parent-nw "non_word"} "dgms_parent"} h2_m_repr]
        (<= 0.
          (+ intercept
            (apply-coeffs cmt-bow
              (BoW-map cmt))
            (apply-coeffs parent-bow
              (BoW-map prt))
            (apply-coeffs cmt-nw
              (nw-map cmt))
            (apply-coeffs parent-nw
              (nw-map prt))))))))


(def d_h2-dataset
  (delay
    (into []
      ;; NOTE starting using h2 from "t1_egwbv7r" (Val, 04 Jun 2020)
      (filter
        (let [h2_m_repr @d_h2-m-repr]
          #(h2-selected? h2_m_repr %)))
      @d_heuristic-dataset)))

(comment

  (count @d_h2-dataset)
  => 6370

  *e)

;; ------------------------------------------------------------------------------
;; Counting positives


(defn heuristic-counts
  []
  (let [name->label (trn-db/all-labels dataset-id)
        unif-sampled-comments (uniformly-sampled-comments)]
    (merge
      @d_global-heuristic-stats
      (let [freqs
            (->> unif-sampled-comments
              (keep
                (fn [c]
                  (when-some [lbl (get name->label (:name c))]
                    [(heuristic-positive? c)
                     (> lbl 0.5)])))
              frequencies)]
        {:Nh+r+ (get freqs [true true] 0)
         :Nh-r+ (get freqs [false true] 0)
         :Nh+r- (get freqs [true false] 0)
         :Nh-r- (get freqs [false false] 0)})
      (let [{N1_h+r+ true N1_h+r- false, :or {N1_h+r+ 0 N1_h+r- 0}}
            (->> @d_heuristic-dataset
              (filter is-comment-of-interest?)
              (filter heuristic-positive?)
              (remove
                (let [to-ignore-ids
                      (into #{}
                        (comp
                          (map :name)
                          (filter name->label))
                        unif-sampled-comments)]
                  (fn already-labelled-in-first-dataset? [c]
                    (contains? to-ignore-ids (:name c)))))
              (keep
                (fn [c]
                  (when-some [lbl (get name->label (:name c))]
                    (> lbl 0.5))))
              frequencies)]
        {:N1_h+r+ N1_h+r+, :N1_h+r- N1_h+r-}))))



(require-python '[scipy.special])

(defn prob_heuristic-recall-is-below-threshold
  [recall-threshold {:as _observed, Nh+r+ :Nh+r+ Nh-r- :Nh-r+}]
  (scipy.special/betainc
    (+ 1. Nh+r+)
    (+ 1. Nh-r-)
    (double recall-threshold)))

(comment

  ;; Probability of heuristic recall to be below 80%, depending on observed false negatives.
  (let [Nr+ 55]
    (vec
      (for [Nh-r+ (range 10)]
        [Nh-r+
         (prob_heuristic-recall-is-below-threshold 0.8
           {:Nh+r+ (- Nr+ Nh-r+) :Nh-r+ Nh-r+})])))
  =>
  [[0 3.741444191567123E-6]
   [1 5.6121662873506845E-5]
   [2 4.162356663118419E-4]
   [3 0.002036748681784352]
   [4 0.007404698045537041]
   [5 0.02136136639129404]
   [6 0.05101928662602759]
   [7 0.10397985847376606]
   [8 0.18507573411561568]
   [9 0.29320356830474814]]

  *e)


(comment ;; Inference of Heuristic Recall (i.e: P(h+|r+))

  (heuristic-counts
    (->> (trn-db/all-labelled-data dataset-id)
      (filter #(-> % :labelled_data/datapoint_data ::sample-slice (= ::ordinary)))))
  => {:Nh+r+ 52, :Nh-r+ 8, :Nh+r- 854, :Nh-r- 4143}

  (def hcnts *1)

  (prob_heuristic-recall-is-below-threshold 0.8 hcnts)
  => 0.11482626027317401 ;; Ouch
  (prob_heuristic-recall-is-below-threshold 0.75 hcnts)
  => 0.018105735940211493 ;; OK, not so bad
  (prob_heuristic-recall-is-below-threshold 0.7 hcnts)
  => 0.001764201392905437

  ;; We might have a lot of noise samples from the "Culture" flair. What if we remove those?
  (heuristic-counts
    (->> (trn-db/all-labelled-data dataset-id)
      (remove #(-> % :labelled_data/datapoint_data
                 :dgms_comment_submission :link_flair_text (= "Culture")))
      (filter #(-> % :labelled_data/datapoint_data ::sample-slice (= ::ordinary)))))
  => {:Nh+r+ 42, :Nh-r+ 2, :Nh+r- 746, :Nh-r- 3564}

  (def hcnts *1)

  (prob_heuristic-recall-is-below-threshold 0.8 hcnts)
  => 0.0032285990973458683 ;; Excellent !
  (prob_heuristic-recall-is-below-threshold 0.9 hcnts)
  => 0.1590428916851537
  (prob_heuristic-recall-is-below-threshold 0.75 hcnts)
  => 3.006958246865396E-4
  (prob_heuristic-recall-is-below-threshold 0.7 hcnts)
  => 2.1628497579822695E-5

  *e)

(comment ;; Inference of Heuristic Precision (i.e: P(r+|h+))

  (def hcnts
    (heuristic-counts))


  (do
    (require-python '[discussion_gems_py.praise_comments #_:reload])
    (require-python '[pymc3])
    (require-python '[pymc3.stats])
    (require-python '[matplotlib.pyplot :as plt]))

  (def trace
    (discussion_gems_py.praise_comments/sample_heuristic_precision
      (py/->py-dict
        {"n1_Hp_Rp" (:N1_h+r+ hcnts 0),
         "n1_Hp" (+
                   (:N1_h+r+ hcnts 0)
                   (:N1_h+r- hcnts 0)),

         "P_Hp" (/ (:N_h+ hcnts 0)
                  (+
                    (:N_h+ hcnts 0)
                    (:N_h- hcnts 0)))

         "n_Hp_Rp" (:Nh+r+ hcnts)
         "n_Hn_Rp" (:Nh-r+ hcnts),
         "n_Hp_Rn" (:Nh+r- hcnts),
         "n_Hn_Rn" (:Nh-r- hcnts)})
      (py/->py-dict
        {"draws" 10000
         "tune" 100000})))


  (py/get-item
    (py/cfn pymc3.stats/summary trace :credible_interval 0.99)
    (py/->py-list ["hpd_0.5%" "mean" "hpd_99.5%"]))
  ;           hpd_0.5%       mean  hpd_99.5%
  ;M_1000p  11586.000  14026.430  16683.000
  ;p_R          0.009      0.011      0.013
  ;p_H          0.851      0.943      0.998
  ;q            0.057      0.067      0.077

  (pymc3/plot_posterior trace)
  (def plots
    (py/cfn pymc3/plot_posterior trace :credible_interval 0.99))

  (plt/show)

  *e)



;; ------------------------------------------------------------------------------
;; API  for labelling UI

(comment

  (count @d_heuristic-dataset)

  ;; How many are already labelled ?
  (->> @d_heuristic-dataset
    (filter
      (let [labelled-ids (trn-db/all-labeled-ids dataset-id)]
        (fn [c]
          (contains? labelled-ids
            (:name c)))))
    count)
  => 376

  *e)

(defn next-comment-to-label
  []
  (prepare-comment-for-labelling-ui
    #_(loop []
        (let [c (rand-nth
                  #_@d_ordinary-comments-to-label
                  @d_heuristic-dataset)]
          (if (trn-db/already-labeled? labelling-ui-dataset-id (:name c))
            (recur)
            c)))
    (-> @d_h2-dataset
      (->>
        (remove
          (let [labelled-ids (trn-db/all-labeled-ids dataset-id)]
            (fn [c]
              (contains? labelled-ids (:name c))))))
      first
      (or
        (throw
          (ex-info
            "No more comments to label!"
            {}))))))

(defn save-comment-label!
  [c-name c lbl]
  (trn-db/save-label!
    dataset-id
    c-name
    c
    (double lbl)))

(comment

  (next-comment-to-label)

  *e)