(ns discussion-gems.sandbox.praise-comments
  (:require [discussion-gems.config.libpython]
            [libpython-clj.require :refer [require-python]]
            [libpython-clj.python :refer [py. py.. py.-] :as py]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [markdown.core]
            [crouton.html]
            [discussion-gems.utils.misc :as u]
            [clojure.string :as str]
            [clojure.repl :refer :all]
            [sparkling.core :as spark]
            [discussion-gems.utils.spark :as uspark]
            [discussion-gems.utils.encoding :as uenc]
            [sparkling.conf :as conf]
            [sparkling.destructuring :as s-de]
            [clojure.pprint :as pp]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.data-sources :as dgds]
            [oz.core :as oz])
  (:import (edu.stanford.nlp.pipeline StanfordCoreNLP CoreDocument CoreSentence)
           (edu.stanford.nlp.ling CoreLabel)))

(require 'sc.api)

(defn trim-markdown
  [^String txt]
  (if (= txt "[deleted]")
    nil
    (let [sb (StringBuilder.)]
      (letfn
        [(aux [node]
           (cond
             (string? node)
             (.append sb ^String node)

             (map? node)
             (case (:tag node)
               (:img)
               (do nil)

               (:p :blockquote :h1 :h2 :h3 :h4 :h5 :h6)
               (do
                 (run! aux (:content node))
                 (.append sb "\n\n"))

               (:a :i :em :strong)
               (do
                 (run! aux (:content node))
                 (.append sb " "))

               (run! aux (:content node)))))]
        (let [html-forest
              (get-in
                (crouton.html/parse-string
                  (markdown.core/md-to-html-string txt))
                [:content 1 :content])]
          (run! aux html-forest)))
      (.toString sb))))


(def ssplit-pipeline
  (StanfordCoreNLP.
    (u/as-java-props
      {"annotators" (str/join "," ["tokenize" "ssplit"])
       "tokenize.language" "French"
       "tokenize.keepeol" "true"})))

(defn sentences
  [^String txt-trimmed]
  (->>
    (.sentences
      (doto
        (CoreDocument.                                      ;; https://nlp.stanford.edu/nlp/javadoc/javanlp/edu/stanford/nlp/pipeline/CoreDocument.html
          txt-trimmed)
        (->> (.annotate ssplit-pipeline))))
    (mapv
      (fn [^CoreSentence sntc]
        (.text sntc)))))

(comment
  (def comment-bodies
    (-> (slurp (io/resource "reddit-france-comments-dv-sample.json"))
      (json/read-value
        (json/object-mapper
          {:decode-key-fn true}))
      (->> (mapv :body))))

  (->> comment-bodies
    (mapv trim-markdown)
    count time)
  ;"Elapsed time: 5536.942 msecs"
  => 3172

  (->> comment-bodies
    (take 100)
    (mapv trim-markdown))

  (-> comment-bodies
    (->>
      (keep trim-markdown)
      (mapv sentences))
    count time)
  ;"Elapsed time: 6237.501 msecs"
  => 2786
  *e)

(comment
  (require-python '[discussion_gems_py.sentence_embeddings])

  (require-python '[numpy])
  (require-python '[sklearn.metrics.pairwise])
  (require-python '[sentence_transformers])
  (require-python '[sentence_transformers.models])

  *e)

#_
(def sentence-embedding-model
  (let [cmb (sentence_transformers.models/CamemBERT "camembert-base")
        pooling-model
        (sentence_transformers.models/Pooling
          (py. cmb get_word_embedding_dimension)
          :pooling_mode_mean_tokens true,
          :pooling_mode_cls_token false,
          :pooling_mode_max_tokens false)]
    (sentence_transformers/SentenceTransformer :modules [cmb pooling-model])))


(def json-mpr
  (json/object-mapper
    {:decode-key-fn true}))

(comment

  (defn doc-sentence-similarity
    [base-sentence-encoded comment-body]
    (time
      (let [comment-sentences (-> comment-body trim-markdown sentences)]
        (double
          (discussion_gems_py.sentence_embeddings/doc_sentence_sim
            base-sentence-encoded
            sentences)))))

  (def base-sentence "Merci pour ta réponse, j'ai appris plein de choses.")

  (time
    (dotimes [_ 10]
      (doc-sentence-similarity
        base-sentence
        (str/join "\n\n"
          (repeat 5
            "Salut, ça va, tu vas bien ?

            On se présente - mais non tu nous connaît.

            On est là pour te pomper, t'imposer sans répit et sans repos, pour te sucer ton flouze, ton oseil, ton pognon, to pez, ton fric, ton blé...")))))


  @(uspark/run-local
     (fn [sc]
       (->>
         (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
         (spark/map
           (fn [^String l]
             (json/read-value l json-mpr)))
         (spark/filter
           (fn [c]
             (not
               (or
                 (-> c :body (= "[deleted]"))
                 (-> c :body count (> 500))))))
         spark/count)))
  => 5106480

  (def d_sample
    (uspark/run-local
      (fn [sc]
        (->>
          (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/filter
            (fn [c]
              (not
                (or
                  (-> c :body (= "[deleted]"))
                  (-> c :body count (> 500))))))
          (spark/sample false (/ 1e3 6e6) 2309892)
          spark/collect
          shuffle vec))))

  (count @d_sample)
  => 863

  (->> @d_sample
    (take 100)
    (map
      (let [bse (discussion_gems_py.sentence_embeddings/sentences_embeddings [base-sentence])]
        (fn [c]
          (spark/tuple
            (doc-sentence-similarity bse (:body c))
            [(:permalink c)
             (:body c)]))))
    count time)
  ;"Elapsed time: 8505.344833 msecs"
  => 100
  (/ 8505.344833 100) => 85.05344833 ;; ms/comment


  (/
    (* 5106480 85.05344833)
    (* 1000 60 60))
  => 120.64548134116065 ;; ouch, 164 hours at best

  (def d_top-similar-comments
    (uspark/run-local
      (fn [sc]
        (->>
          (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/filter
            (fn [c]
              (not
                (or
                  (-> c :body (= "[deleted]"))
                  (-> c :body count (> 500))))))
          (spark/map-to-pair
            (fn [c]
              (spark/tuple
                (doc-sentence-similarity base-sentence (:body c))
                [(:permalink c)
                 (:body c)])))
          (spark/sort-by-key compare false)
          (spark/take 1000)))))

  *e)


(comment ;; trying with Spacy

  (require-python '[spacy])

  (def nlp (spacy/load "fr_core_news_md"))
  (def nlp (spacy/load "../models/fastttext_fr"))

  (def c0
    (nth @d_sample 1))

  (def doc0
    (nlp (trim-markdown (:body c0))))

  (py.- doc0 vector)

  (time
    (py. (nlp "Merci beaucoup, intéressant !")
      similarity
      (nlp "Super réponse, très instructif, merci.")))

  (py. (nlp "Super réponse, très instructif, merci.")
    similarity
      (nlp "Excellent commentaire, j'ai appris plein de choses, merci."))

  (py. (nlp "Merci beaucoup, intéressant !")
    similarity
    (nlp "Nul à chier."))

  (py. (nlp "Les Gilets Jaunes demandent des choses budgétairement impossibles")
    similarity
    (nlp "Il n'y a pas d'opposition entre solidarité et économie"))


  (->>
    (let [docs (->> @d_sample
                 (take 100)
                 (mapv
                   (fn [c]
                     (nlp (trim-markdown (:body c))))))]
      (for [doc1 docs
            doc2 docs]
        (py. doc1 similarity doc2)))
    (apply +)
    (/ 10000))

  (->> @d_sample
    (take 100)
    (map
      (let [bsd (nlp base-sentence)]
        (fn [c]
          (spark/tuple
            (py. bsd
              similarity
              (nlp (trim-markdown (:body c))))
            [(:permalink c)
             (:body c)]))))
    count time)

  *e)

(require-python '[spacy])

(defonce fr_pipeline
  (delay
    (spacy/load "fr_core_news_md")))

(defonce fr_pipeline-fasttext
  (delay
    (spacy/load "../models/fastttext_fr/")))

(defn enrich-comment
  [c]
  (merge c
    (when-some [md-html-forest (parsing/md->html-forest (:body c))]
      (let [body-raw (parsing/raw-text-contents
                       {::parsing/remove-code true
                        ::parsing/remove-quotes true}
                       md-html-forest)]
        {:dgms_body_raw body-raw
         #_#_
         :dgms_body_vector
         (py/with-gil
           (float-array
             (let [parsed (@fr_pipeline body-raw)]
               (py.- parsed vector))))
         :dgms_syntax_stats_fr
         (parsing/syntax-stats-fr body-raw)
         :dgms_hyperlinks
         (parsing/hyperlinks md-html-forest)
         :dgms_n_formatting
         (parsing/formatting-count md-html-forest)}))))

(comment

  (-> (io/resource "reddit-france-comments-dv-sample.json")
    (json/read-value json-mpr)
    (->>
      (take 10)
      (mapv enrich-comment)))

  (-> (io/resource "reddit-france-comments-dv-sample.json")
    (json/read-value json-mpr)
    count #_
    (->>
      (run! enrich-comment)
      time))

  (/ 150470.826249 3172)

  ;; Saving enriched comments
  (def d_saved-enriched
    (uspark/run-local
      (fn [cnf]
        (conf/master cnf "local[4]"))
      (fn [sc]
        (->>
          (uspark/from-hadoop-fressian-sequence-file sc "../derived-data/reddit-france/comments/RC-enriched_v1.seqfile")
          #_#_#_
          (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile") ;; FIXME
          (spark/repartition 1000) ;; FIXME
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/map-to-pair (fn [c] (spark/tuple "" (enrich-comment c))))
          (uspark/save-to-hadoop-text+fressian-seqfile
            "../derived-data/reddit-france/comments/RC-enriched_v2.seqfile")))))

  *e)

(defn vec-dot-product
  [^floats x, ^floats y]
  (areduce x i acc
    (float 0.)
    (+ (float acc)
      (* (aget x i) (aget y i)))))

(defn vec-norm2
  [x]
  (Math/sqrt
    (vec-dot-product x x)))

(defn vec-cosine-sim
  [x y]
  (double
    (let [nx (vec-norm2 x)
          ny (vec-norm2 y)]
      (if (or (zero? nx) (zero? ny))
        0.
        (/ (vec-dot-product x y)
          (* nx ny))))))


(defn phrase-vector
  [phrase]
  (float-array
    (py/with-gil
      (vec
        (let [parsed (#_@fr_pipeline @fr_pipeline-fasttext phrase)]
          (py.- parsed vector))))))


(comment ;; Experiments with words similarity using the SpaCy word embeddings.

  (def candidate-words
    ["merci" "super" "excellent" "excellente" "intéressant" "intéressante" "pertinent"])

  (def candidate-words
    ;; That's rather disappointing: all these words have a rather low (< 0.5) similarity to each other. (Val, 28 Apr 2020)
    ["intéressant" "instructif" "enrichissant" "mauvais" "nul" "mouais" "bof"])

  (def candidate-words
    ;; low similarity as well. (< 0.2)
    ["réponse" "commentaire"])

  (def candidate-words
    ;; somewhat orthogonal, except that sim('réponse', 'intéressant') is rather high (~ 0.4)
    ["réponse" "commentaire" "intéressant" "merci"])

  (oz/start-server! 10666)

  (oz/view!
    (let [size (* 30 (count candidate-words))]
      {:encoding {:x {:field "word_1", :type "nominal"}
                  :y {:field "word_2", :type "nominal"},
                  :size {:field "cosine_sim", :type "quantitative", :aggregate "mean"},},
       :description "A bubble plot of the cosine similarity of various word vectors."
       :mark "circle",
       :$schema "https://vega.github.io/schema/vega-lite/v4.json",
       :width size :height size
       :data {:values
              (for [w1 candidate-words
                    w2 candidate-words]
                (let [sim (vec-cosine-sim
                            (phrase-vector w1)
                            (phrase-vector w2))]
                  {:word_1 w1
                   :word_2 w2
                   :cosine_sim sim}))}}))

  ;; Much better results with Fasttext !

  *e)


(comment ;; Finding comments expressing praise based on vector similarity

  (def query-sentence "merci intéressant instructif informatif clair merci appris éclairant logique merci excellent super merci beaucoup")

  (def query-sentence "Je n'aurais pas dit mieux, excellente réponse.")
  (def query-sentence "Excellente réponse intéressant merci")

  (def query-vec
    (phrase-vector query-sentence))

  (def d_top-similar
    (uspark/run-local
      (fn [sc]
        (->> (dgds/comments-all-rdd sc)
          (spark/filter :dgms_body_vector)
          (spark/map-to-pair
           (fn [c]
             (let [doc-vec (:dgms_body_vector c)
                   sim (vec-cosine-sim query-vec doc-vec)]
               (spark/tuple
                 (double sim)
                 (select-keys c [:id :permalink :body])))))
          (spark/sort-by-key compare false)
          (spark/map
            (fn [sim+c]
              (assoc (s-de/value sim+c)
                :vec-sim (s-de/key sim+c))))
          (spark/take 10000)
          vec))))

  (count @d_top-similar)

  (->> @d_top-similar
    (drop 7000)
    (take 10))

  (oz/start-server! 10666)

  ;; What do these most-similar comments look like?
  (oz/view!
    (into [:div {}]
      (->> @d_top-similar
        (drop (* 1000 6))
        (take 20)
        (map
          (fn [c]
            [:div
             [:h2
              [:code (:id c)]
              (let [url (str "https://reddit.com" (:permalink c) "?context=8&depth=9")]
                [:a {:href url} (:permalink c "MISSING PERMALINK")])]
             [:div (:body c)]
             [:div "sim = " [:strong (format "%.5f" (:vec-sim c))]]]))
        (interpose [:hr]))))

  ;; Hard to set a precise limit, bad results are mixed at every level...
  ;; The vec-similarity is good for recall. It could be used to build a
  ;; preliminary dataset to label, on which other features might be used for improving precision.
  ;; we get in particular some false negative, like "Hors-Sujet".


  ;; TODO Statistics on :vec-sim (e.g mean and std-dev)

  *e)



(comment ;; Some statistics

  (split-words "Salut, est-ce-que ça va, Mr. Je-Sais-Tout à 234,5% ?")
  => ["Salut" "est-ce-que" "ça" "va" "Mr." "Je-Sais-Tout" "234,5"] ;; NOTE 'à' was elided, must be considered a stop-word (Val, 14 Apr 2020)


  (-> (io/resource "reddit-france-comments-dv-sample.json")
    (json/read-value json-mpr)
    (->>
      (take 100)
      (map enrich-comment)
      (keep :dgms_body_raw)
      (mapcat split-words)
      frequencies
      (sort-by key)))

  (def d_wc-buckets
    (uspark/run-local 2
      (fn [sc]
        (->> (uspark/from-hadoop-fressian-sequence-file sc "../derived-data/reddit-france/comments/RC-enriched_v1.seqfile")
          (spark/flat-map-to-pair
            (fn [c]
              (vec
                (when-some [body-raw (:dgms_body_raw c)]
                  (let [body-length (count body-raw)
                        body-wc (count (split-words body-raw))]
                    [(spark/tuple
                       (Long/highestOneBit body-wc)
                       [1.
                        (double body-length)
                        (double body-wc)])])))))
          (spark/reduce-by-key
            (fn [v1 v2]
              (mapv + v1 v2)))
          (spark/map
            (fn [k+v]
              (let [wc-h1b (s-de/key k+v)
                    [n-docs w-chars w-words] (s-de/value k+v)]
                {:wc-h1b wc-h1b
                 :n-docs n-docs
                 :w-chars w-chars
                 :w-words w-words})))
          (spark/collect)
          (sort-by :wc-h1b u/decreasing)
          vec))))

  @d_wc-buckets
  =>
  [{:wc-h1b 2048, :n-docs 4.0, :w-chars 63099.0, :w-words 10907.0}
   {:wc-h1b 1024, :n-docs 3721.0, :w-chars 2.815548E7, :w-words 4839476.0}
   {:wc-h1b 512, :n-docs 15836.0, :w-chars 6.1395982E7, :w-words 1.0745725E7}
   {:wc-h1b 256, :n-docs 74088.0, :w-chars 1.40642212E8, :w-words 2.5070473E7}
   {:wc-h1b 128, :n-docs 269042.0, :w-chars 2.58724998E8, :w-words 4.64702E7}
   {:wc-h1b 64, :n-docs 654343.0, :w-chars 3.1860127E8, :w-words 5.7612013E7}
   {:wc-h1b 32, :n-docs 1121395.0, :w-chars 2.76258227E8, :w-words 5.0126927E7}
   {:wc-h1b 16, :n-docs 1365720.0, :w-chars 1.69813207E8, :w-words 3.0779518E7}
   {:wc-h1b 8, :n-docs 1151543.0, :w-chars 7.3606422E7, :w-words 1.3009331E7}
   {:wc-h1b 4, :n-docs 624909.0, :w-chars 2.1317905E7, :w-words 3455762.0}
   {:wc-h1b 2, :n-docs 268680.0, :w-chars 5278910.0, :w-words 669335.0}
   {:wc-h1b 1, :n-docs 195998.0, :w-chars 2946508.0, :w-words 195998.0}
   {:wc-h1b 0, :n-docs 12914.0, :w-chars 187111.0, :w-words 0.0}]

  (->> @d_wc-buckets
    (sort-by :wc-h1b u/decreasing)
    (reductions
      (fn [acc row]
        (into row
          (for [k [:n-docs :w-chars :w-words]]
            (let [cum-k (keyword (str "Σ-" (name k)))]
              [cum-k
               (+
                 (get acc cum-k 0.)
                 (get row k))]))))
      {}) rest
    vec
    (pp/print-table))

  ;| :wc-h1b |   :n-docs |     :w-chars |    :w-words | :Σ-n-docs |    :Σ-w-chars |   :Σ-w-words |
  ;|---------+-----------+--------------+-------------+-----------+---------------+--------------|
  ;|    2048 |       4.0 |      63099.0 |     10907.0 |       4.0 |       63099.0 |      10907.0 |
  ;|    1024 |    3721.0 |   2.815548E7 |   4839476.0 |    3725.0 |   2.8218579E7 |    4850383.0 |
  ;|     512 |   15836.0 |  6.1395982E7 | 1.0745725E7 |   19561.0 |   8.9614561E7 |  1.5596108E7 |
  ;|     256 |   74088.0 | 1.40642212E8 | 2.5070473E7 |   93649.0 |  2.30256773E8 |  4.0666581E7 |
  ;|     128 |  269042.0 | 2.58724998E8 |   4.64702E7 |  362691.0 |  4.88981771E8 |  8.7136781E7 |
  ;|      64 |  654343.0 |  3.1860127E8 | 5.7612013E7 | 1017034.0 |  8.07583041E8 | 1.44748794E8 |
  ;|      32 | 1121395.0 | 2.76258227E8 | 5.0126927E7 | 2138429.0 | 1.083841268E9 | 1.94875721E8 |
  ;|      16 | 1365720.0 | 1.69813207E8 | 3.0779518E7 | 3504149.0 | 1.253654475E9 | 2.25655239E8 |
  ;|       8 | 1151543.0 |  7.3606422E7 | 1.3009331E7 | 4655692.0 | 1.327260897E9 |  2.3866457E8 |
  ;|       4 |  624909.0 |  2.1317905E7 |   3455762.0 | 5280601.0 | 1.348578802E9 | 2.42120332E8 |
  ;|       2 |  268680.0 |    5278910.0 |    669335.0 | 5549281.0 | 1.353857712E9 | 2.42789667E8 |
  ;|       1 |  195998.0 |    2946508.0 |    195998.0 | 5745279.0 |  1.35680422E9 | 2.42985665E8 |
  ;|       0 |   12914.0 |     187111.0 |         0.0 | 5758193.0 | 1.356991331E9 | 2.42985665E8 |

  *e)