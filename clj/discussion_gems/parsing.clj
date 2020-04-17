(ns discussion-gems.parsing
  (:require [clojure.string :as str]
            [markdown.core]
            [crouton.html]
            [discussion-gems.utils.misc :as u])
  (:import (edu.stanford.nlp.ling CoreLabel)
           (edu.stanford.nlp.pipeline StanfordCoreNLP CoreDocument)))


(defn backfill-reddit-name
  [type-prefix {:as s, id :id, nm :name}]
  (cond-> s
    (nil? nm)
    (assoc :name (str type-prefix id))))


(comment ;; What HTML tags to we observe upon parsing?

  (require '[discussion-gems.utils.encoding :as uenc])
  (require '[clojure.java.io :as io])

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
            (str/starts-with? ">" b)))))))


(defn trim-markdown
  ([txt] (trim-markdown {} txt))
  ([{:as _opts, remove-quotes? ::remove-quotes remove-code? ::remove-code}
    ^String txt]
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

                (:p)
                (when-not (and remove-quotes? (quote-paragraph? node))
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
         (let [html-forest
               (get-in
                 (crouton.html/parse-string
                   (markdown.core/md-to-html-string txt))
                 [:content 1 :content])]
           (run! aux html-forest)))
       (.toString sb)))))


(def split-words-fr
  (let [tokens-pipeline
        (delay
          (StanfordCoreNLP.
            (u/as-java-props
              {"annotators" (str/join "," ["tokenize"])
               "tokenize.language" "French"
               "tokenize.keepeol" "true"})))]
    (fn split-words [^String txt]
      (into []
        (comp
          (map
            (fn [^CoreLabel lbl]
              (.value lbl)))
          (filter
            (fn word-token? [^String lbl]
              (re-find #"\w" lbl))))
        (.tokens
          (doto
            (CoreDocument. txt)
            (->> (.annotate ^StanfordCoreNLP @tokens-pipeline))))))))



(comment

  (->>
    (uenc/json-read
      (io/resource "reddit-france-submissions-dv-sample.json"))
    (keep :title)
    (take 100)
    vec)

  (->>
    (uenc/json-read
      (io/resource "reddit-france-submissions-dv-sample.json"))
    (keep :selftext)
    (take 10)
    vec)

  *e)