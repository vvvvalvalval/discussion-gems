(ns discussion-gems.feature-engineering.reddit-markdown
  "Extraction of various features from hypertext encoded in Reddit-style Markdown."
  (:require [clojure.string :as str]
            [crouton.html]
            [markdown.core]
            [mapdag.step :as mdg]
            [mapdag.runtime.jvm-eval]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.utils.misc :as u])
  (:import (edu.stanford.nlp.pipeline StanfordCoreNLP CoreDocument)
           (edu.stanford.nlp.ling CoreLabel)))

;; ------------------------------------------------------------------------------
;; Markdown parsing

(comment ;; What HTML tags to we observe upon parsing?

  (require '[discussion-gems.utils.encoding :as uenc])
  (require '[clojure.java.io :as io])


  (markdown.core/md-to-html-string
    "> Ceci est une citation

Ceci n'est pas une citation.")

  (->>
    (uenc/json-read
      (io/resource "reddit-france-comments-dv-sample.json"))
    (keep :body)
    (map (fn [body]
           (crouton.html/parse-string
             (markdown.core/md-to-html-string body))))
    (mapcat
      (fn [html-tree]
        (->> html-tree
          (tree-seq map? :content)
          (into []
            (comp
              (filter map?)
              (map :tag))))))
    frequencies
    (sort-by val u/decreasing)
    vec)
  =>
  [[:p 5961]
   [:head 3172]
   [:body 3172]
   [:html 3172]
   [:em 422]
   [:a 415]
   [:br 227]
   [:li 164]
   [:strong 106]
   [:i 90]
   [:ul 45]
   [:sup 34]
   [:del 13]
   [:h2 13]
   [:pre 9]
   [:ol 9]
   [:code 9]
   [:h1 7]
   [:hr 6]
   [:h3 5]
   [:b 4]
   [:h5 1]
   [:h4 1]
   [:h6 1]]

  ;; <del> ???
  (->>
    (uenc/json-read
      (io/resource "reddit-france-comments-dv-sample.json"))
    (keep :body)
    (map (fn [body]
           (crouton.html/parse-string
             (markdown.core/md-to-html-string body))))
    (mapcat
      (fn [html-tree]
        (->> html-tree
          (tree-seq map? :content)
          (into []
            (comp
              (filter map?)
              (filter #(-> % :tag (= :del))))))))
    vec)
  =>
  [{:tag :del, :attrs nil, :content ["contenta"]}
   {:tag :del, :attrs nil, :content ["Police fédérale"]}
   {:tag :del, :attrs nil, :content ["J'ai raté un truc?"]}
   {:tag :del, :attrs nil, :content ["C'est l'inverse !"]}
   {:tag :del, :attrs nil, :content ["la Floride"]}
   {:tag :del, :attrs nil, :content ["bossé"]}
   {:tag :del, :attrs nil, :content ["employé"]}
   {:tag :del, :attrs nil, :content ["patrons"]}
   {:tag :del, :attrs nil, :content ["en un instant"]}
   {:tag :del, :attrs nil, :content ["avec hardiesse et résolution"]}
   {:tag :del, :attrs nil, :content ["ajoutons l'un pour l'autre"]}
   {:tag :del, :attrs nil, :content ["charcuté"]}
   {:tag :del, :attrs nil, :content ["assumer"]}]

  ;; <code> ??
  (->>
    (uenc/json-read
      (io/resource "reddit-france-comments-dv-sample.json"))
    (keep :body)
    (map (fn [body]
           (crouton.html/parse-string
             (markdown.core/md-to-html-string body))))
    (mapcat
      (fn [html-tree]
        (->> html-tree
          (tree-seq map? :content)
          (into []
            (comp
              (filter map?)
              (filter #(-> % :tag (= :code))))))))
    vec)

  (->>
    (uenc/json-read
      (io/resource "reddit-france-comments-dv-sample.json"))
    (keep :body)
    (filter
      (fn [txt]

        (re-find #"^&gt" txt)))
    (take 10)
    vec)
  => [{:tag :code, :attrs nil, :content ["Sections"]}
      {:tag :code, :attrs nil, :content ["Get this offer Sign In"]}
      {:tag :code, :attrs nil, :content ["Accessibility for screenreader"]}
      {:tag :code,
       :attrs nil,
       :content ["j'alla dans mon bain et le get fort de la douche m'exiter je le meta contre mon clito j'aimer cela justin arriva dans la salle de bain en caleçon il me disa "]}
      {:tag :code,
       :attrs nil,
       :content ["Plusieurs militantes relatent le « jeu des étoiles » lors du camp d’été 2016. Les filles sont notées avec des étoiles en fonction de leur disponibilité sexuelle : une pour un baiser, deux pour une fellation… « Le summum étant la sodomie. Les filles mineures ont un bonus »"]}
      {:tag :code, :attrs nil, :content [" СМИ: Макрон и Ле Пен лидируют на выборах президента Франции"]}
      {:tag :code,
       :attrs nil,
       :content [" Подробнее: https://t.co/yF6acxYMux#JeVoteMarine pic.twitter.com/P5HjRalMBs — Телеканал ЗВЕЗДА (@zvezdanews) 23 avril 2017"]}
      {:tag :code,
       :attrs nil,
       :content ["&gt;se plaint du nombre de juifs dans la classe politique Française &gt;traite les autres d'antisémites &gt;okay.jpg"]}
      {:tag :code,
       :attrs nil,
       :content ["&lt;tag&gt; Ouroboros: lets play Pong &lt;Ouroboros&gt; Ok. &lt;tag&gt; | . &lt;Ouroboros&gt; . | &lt;tag&gt; | . &lt;Ouroboros&gt; . | &lt;tag&gt; | . &lt;Ouroboros&gt; | . &lt;Ouroboros&gt; Whoops"]}]


  *e)


(defn quote-paragraph?
  [node]
  (and
    (map? node)
    (let [cont (:content node)]
      (and
        (not (empty? cont))
        (let [b (first cont)]
          (and
            (string? b)
            (str/starts-with? b ">")))))))

(comment
  (quote-paragraph?
    {:tag :p, :attrs nil, :content ["> Bah sachant que plus de securitaire c'est les minorités qui vont morfler ...."]})
  => true

  *e)


(defn clean-special-characters
  [^String txt]
  (str/replace txt
    #"(\&\#x\d{3}[a-zA-Z];|https?://(www\.)?)"
    ""))

(comment
  (clean-special-characters
    "Voir cet article: https://www.lemonde.fr/url-de/l-article")
  => "Voir cet article: lemonde.fr/url-de/l-article"

  *e)


(defn non-empty-md-txt
  [^String src-md-txt]
  (when-not (or
              (nil? src-md-txt)
              (contains?
                #{"[deleted]"
                  "[removed]"
                  "[supprimé]"}
                src-md-txt)
              (str/blank? src-md-txt))
    src-md-txt))


(defn md->html-forest
  "Parses a Reddit markdown into a recursive data structure."
  [^String md-txt]
  (some-> md-txt
    (non-empty-md-txt)
    (as-> md-txt
      (get-in
        (crouton.html/parse-string
          (markdown.core/md-to-html-string
            (str/replace md-txt
              "&gt;" ">")))
        [:content 1 :content]))))


(defn raw-text-contents
  [{:as _opts, remove-quotes? ::remove-quotes remove-code? ::remove-code}
   md-html-forest]
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

             (:p)
             (when-not (and remove-quotes? (quote-paragraph? node))
               (run! aux (:content node))
               (.append sb "\n\n"))

             (:blockquote)
             (when-not remove-quotes?
               (run! aux (:content node))
               (.append sb "\n\n"))

             (:code)
             (when-not remove-code?
               (run! aux (:content node))
               (.append sb "\n\n"))

             (:h1 :h2 :h3 :h4 :h5 :h6 :ol :ul :hr :br)
             (do
               (run! aux (:content node))
               (.append sb "\n\n"))

             (:li)
             (do
               (run! aux (:content node))
               (.append sb "\n"))

             (:a :i :em :strong)
             (do
               (run! aux (:content node))
               (.append sb " "))

             (run! aux (:content node)))))]
      (run! aux md-html-forest))
    (-> (.toString sb)
      clean-special-characters)))


(defn trim-markdown
  ([md-txt] (trim-markdown {} md-txt))
  ([opts md-txt]
   (if (= md-txt "[deleted]")
     nil
     (let [html-forest (md->html-forest md-txt)]
       (raw-text-contents opts html-forest)))))


(comment



  (->> (io/resource "reddit-france-comments-dv-sample.json")
    (uenc/json-read)
    (keep :body)
    (filter trim-markdown)
    (filter
      (fn [body]
        (re-seq #"\&\#x\d{3}[a-zA-Z];"
          (trim-markdown
            {::remove-quotes true ::remove-code true}
            body))))
    (keep trim-markdown)
    (take 10)
    vec)

  *e)

(defn hyperlinks
  [md-html-forest]
  (letfn
    [(find-links [node]
       (if (string? node)
         (mapv first
           (re-seq
             ;; NOTE for URLs in the text, a / is mandatory.
             #"((https?://)?[\w\-]+(\.[\w\-]+)+/[-a-zA-Z0-9+&@#/%?=~_|!:,;.]*[-a-zA-Z0-9+&@#/%=~_|])"
             node))
         (let [tag (:tag node)]
           (if (= tag :a)
             (when-some [href (some-> node :attrs :href
                                (as-> href
                                  (when-not (str/blank? href)
                                    href)))]
               [href])
             (mapcat find-links (:content node))))))]
    (into []
      (mapcat find-links)
      md-html-forest)))


(comment

  (hyperlinks
    (md->html-forest
      "Voir [ce site](https://news.ycombinator.com)

      Tu peux aussi regarder sur service-public.fr/mon-renseignement/coucou323/mythe.htm?q=324d&z=44lfdsfs%32#xyz990."))
  => ["https://news.ycombinator.com" "service-public.fr/mon-renseignement/coucou323/mythe.htm?q=324d&z=44lfdsfs%32#xyz990"]

  *e)


(defn formatting-count
  [md-html-forest]
  (->> {:content md-html-forest}
    (tree-seq map? :content)
    (filter map?)
    (map :tag)
    (filter #{:code :h1 :h2 :h3 :h4 :h5 :h6 :ol :ul :hr :a :i :em :strong})
    count))



;; ------------------------------------------------------------------------------
;; Syntactic analysis

(def d_default-corenlp-pipeline
  (delay
    (StanfordCoreNLP.
      (u/as-java-props
        ;; https://stanfordnlp.github.io/CoreNLP/annotators.html
        {"annotators" (str/join "," ["tokenize" "ssplit" "pos"])
         "tokenize.language" "French"
         "tokenize.keepeol" "true"
         "pos.model" "french.tagger"}))))


(defn annotated-corenlp-doc
  ^CoreDocument
  [^StanfordCoreNLP pos-pipeline, ^String raw-txt]
  (doto
    (CoreDocument. raw-txt)
    (->> (.annotate ^StanfordCoreNLP pos-pipeline))))


(defn syntax-stats-fr
  [^CoreDocument doc]
  {:n-sentences (count (.sentences doc))
   :pos-freqs (->> doc
                .tokens
                (map
                  (fn [^CoreLabel lbl]
                    (.tag lbl)))
                frequencies)})

(comment

  (def txts
    (->> (io/resource "reddit-france-comments-dv-sample.json") ;; CAVEAT using diversified sample, which may be highly skewed. (Val, 20 Apr 2020)
      (uenc/json-read)
      (keep :body)
      (keep trim-markdown)
      (take 100)
      vec))

  (def pos-pipeline
    (delay
      (StanfordCoreNLP.
        (u/as-java-props
          {"annotators" (str/join "," ["tokenize" "ssplit" "pos"])
           "tokenize.language" "French"
           "tokenize.keepeol" "true"
           "pos.model" "french.tagger"}))))

  @pos-pipeline

  (defn decompose-sentences
    [txt]
    (let [doc (doto
                (CoreDocument. txt)
                (->> (.annotate ^StanfordCoreNLP @pos-pipeline)))]
      (->> (.sentences doc)
        (mapv
          (fn [^CoreSentence sentence]
            [(.text sentence)
             (->> (.tokens sentence)
               (mapv
                 (fn [^CoreLabel lbl]
                   [(.value lbl) (.tag lbl)])))])))))

  (def sntc1 "Le rapide renard brun saute par dessus le chien paresseux de Balkany. Alors, qu'est-ce que t'en dis ?")

  (decompose-sentences sntc1)
  =>
  [["Le rapide renard brun saute par dessus le chien paresseux de Balkany."
    [["Le" "DET"]
     ["rapide" "ADJ"]
     ["renard" "NC"]
     ["brun" "ADJ"]
     ["saute" "V"]
     ["par" "P"]
     ["dessus" "ADV"]
     ["le" "DET"]
     ["chien" "NC"]
     ["paresseux" "ADJ"]
     ["de" "P"]
     ["Balkany" "NPP"]
     ["." "PUNC"]]]
   ["Alors, qu'est-ce que t'en dis ?"
    [["Alors" "ADV"]
     ["," "PUNC"]
     ["qu'" "CS"]
     ["est" "V"]
     ["-ce" "CLS"]
     ["que" "CS"]
     ["t'" "CLS"]
     ["en" "CLO"]
     ["dis" "V"]
     ["?" "PUNC"]]]]

  (syntax-stats-fr sntc1)
  =>
  {:n-sentences 2,
   :pos-freqs {"ADV" 2, "ADJ" 3, "CLO" 1, "CLS" 2, "CS" 2, "PUNC" 3, "P" 2, "V" 3, "DET" 2, "NC" 2, "NPP" 1}}


  (->> txts (str/join "\n") syntax-stats-fr)
  =>
  {:n-sentences 276,
   :pos-freqs {"ADV" 465,
               "ADJ" 360,
               "VPP" 137,
               "PROREL" 78,
               "CLO" 90,
               "CLS" 304,
               "CS" 109,
               "VPR" 18,
               "DETWH" 1,
               "PUNC" 641,
               "VIMP" 5,
               "C" 25,
               "CC" 164,
               "P" 619,
               "V" 541,
               "ET" 245,
               "DET" 720,
               "CL" 8,
               "N" 142,
               "VS" 14,
               "PRO" 81,
               "ADJWH" 2,
               "NC" 798,
               "I" 9,
               "ADVWH" 9,
               "NPP" 153,
               "VINF" 189,
               "CLR" 28}}


  *e)


(defn count-quotes
  [html-forest]
  (letfn [(aux-count [s node]
            (+ s
              (if (map? node)
                (let [{tag :tag children :content} node]
                  (if (= tag :blockquote)
                    1
                    (reduce aux-count 0 children)))
                0)))]
    (reduce aux-count 0 html-forest)))


(def dag_reddit-markdown-features
  {::html-forest
   (mdg/step [::md-txt] md->html-forest)

   :dgms_body_raw
   (mdg/step
     [::html-forest]
     (fn [md-html-forest]
       (when (some? md-html-forest)
         (raw-text-contents
           {::remove-code true
            ::remove-quotes true}
           md-html-forest))))

   :standford.corenlp/pipeline
   (mdg/step []
     (fn [] @d_default-corenlp-pipeline))

   :standford.corenlp/annotated-document
   (mdg/step [:standford.corenlp/pipeline :dgms_body_raw]
     annotated-corenlp-doc)

   :dgms_syntax_stats_fr
   (mdg/step [:standford.corenlp/annotated-document]
     syntax-stats-fr)

   :dgms_n_sentences
   (mdg/step [:dgms_syntax_stats_fr] #(get % :n-sentences))

   :dgms_n_words
   (mdg/step [:dgms_syntax_stats_fr]
     (letfn [(add-val [s _k n] (+ s n))]
       (fn [{pos-freqs :pos-freqs}]
         (reduce-kv add-val 0 pos-freqs))))

   ;; IMPROVEMENT remove low-information domains such as giphy, imgur etc. (Val, 28 May 2020)
   :dgms_hyperlinks
   (mdg/step [::html-forest]
     (fn [md-html-forest]
       (when (some? md-html-forest)
         (hyperlinks md-html-forest))))

   :dgms_n_hyperlinks
   (mdg/step [:dgms_hyperlinks] count)

   :dgms_n_quotes
   (mdg/step [::html-forest] count-quotes)

   :dgms_n_formatting
   (mdg/step [::html-forest]
     (fn [md-html-forest]
       (when (some? md-html-forest)
         (formatting-count md-html-forest))))})


