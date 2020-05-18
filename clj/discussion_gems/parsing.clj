(ns discussion-gems.parsing
  (:require [clojure.string :as str]
            [markdown.core]
            [crouton.html]
            [discussion-gems.utils.misc :as u]
            [clojure.java.io :as io]
            [discussion-gems.utils.encoding :as uenc])
  (:import (edu.stanford.nlp.ling CoreLabel)
           (edu.stanford.nlp.pipeline StanfordCoreNLP CoreDocument CoreSentence)
           (org.apache.lucene.analysis Analyzer)
           (java.util ArrayList)
           (org.apache.lucene.analysis.tokenattributes CharTermAttribute)
           (org.apache.lucene.analysis.fr FrenchAnalyzer)
           (edu.stanford.nlp.tagger.maxent MaxentTagger)))


;; TODO numbers ?
;; TODO unescape HTML entities, e.g with https://commons.apache.org/proper/commons-text/javadocs/api-release/org/apache/commons/text/StringEscapeUtils.html#escapeHtml4-java.lang.String- (Val, 18 May 2020)

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



(defn md->html-forest
  "Parses a Reddit markdown into a recursive data structure."
  [^String md-txt]
  (if (= md-txt "[deleted]")
    nil
    (get-in
      (crouton.html/parse-string
        (markdown.core/md-to-html-string
          (str/replace md-txt
            "&gt;" ">")))
      [:content 1 :content])))


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
;; French parsing / analysis


(defn lucene-tokenize
  [^Analyzer an, ^String txt]
  ;; Tutorial: https://www.baeldung.com/lucene-analyzers
  (let [tokens (ArrayList.)]
    (with-open [token-stream (.tokenStream an "dgms_tokens_field" txt)]
      (let [attr (.addAttribute token-stream CharTermAttribute)]
        (.reset token-stream)
        (loop []
          (when (.incrementToken token-stream)
            (.add tokens
              (.toString attr))
            (recur)))
        (vec tokens)))))

(comment

  (def fr-an (FrenchAnalyzer.))

  (lucene-tokenize fr-an
    "Salut, comment vas-tu ?")
  => ["salut" "coment" "vas"]

  (lucene-tokenize fr-an
    "Parlez-moi de la pluie, et non pas du beau temps")
  => ["parlez" "plu" "non" "beau" "temp"]


  (->> (io/resource "reddit-france-comments-dv-sample.json")
    (uenc/json-read)
    (keep :body)
    (keep trim-markdown)
    (take 20)
    (mapv
      (fn [src]
        {:text src
         :tokens (lucene-tokenize fr-an src)})))
  =>
  [{:text "Pour information, The Onion est un journal parodique.

         Que ce soit la réalité ou le journalisme -comme tu sembles le penser- qui rattrape la parodie, c'est assez effrayant.

         Quand les relations personnelles se reflètent sur les propos tenus auprès de journalistes ce n'est plus vraiment personnel, de plus si Obama ne veut pas être vu auprès de Sarkozy ça peut être politique.

         ",
    :tokens ["inform"
             "the"
             "onion"
             "journal"
             "parod"
             "realit"
             "journalism"
             "come"
             "sembl"
             "pens"
             "ratrap"
             "parod"
             "asez"
             "efrayant"
             "quand"
             "relation"
             "person"
             "refletent"
             "propo"
             "tenu"
             "aupr"
             "journalist"
             "plu"
             "vraiment"
             "personel"
             "plu"
             "si"
             "obama"
             "veut"
             "être"
             "vu"
             "aupr"
             "sarkozy"
             "ça"
             "peut"
             "être"
             "polit"]}
   {:text "So the French are arrogant about their health care system? Never heard that one before. They actually complain about it a lot because it's not good enough.

         ",
    :tokens ["so"
             "the"
             "french"
             "are"
             "arogant"
             "about"
             "thei"
             "health"
             "care"
             "system"
             "nev"
             "heard"
             "that"
             "one"
             "befor"
             "they"
             "actualy"
             "complain"
             "about"
             "it"
             "a"
             "lot"
             "becaus"
             "it'"
             "not"
             "good"
             "enough"]}
   {:text "C'est un peu dommage d'avoir lu les préludes avant les fondations mais bon ... :)

         Par contre, je t'invite par la suite à lire le cycle des robots. C'est un ensemble complet avec le cycle des fondations :)

         ",
    :tokens ["peu"
             "domag"
             "avoi"
             "lu"
             "prelud"
             "avant"
             "fond"
             "bon"
             "contr"
             "invit"
             "suit"
             "lire"
             "cycl"
             "robot"
             "ensembl"
             "complet"
             "cycl"
             "fond"]}
   {:text "Bah si tu aimes à ce point les exclus oui. Moi même j'ai hésité parce qu'il y a Bloodborne,Horizon:Zero Dawn surtout mais bon prendre une console pour 2 jeux...

         ",
    :tokens ["bah"
             "si"
             "aime"
             "point"
             "exclu"
             "oui"
             "hesit"
             "parc"
             "a"
             "blodborn"
             "horizon:zero"
             "dawn"
             "surtout"
             "bon"
             "prendr"
             "consol"
             "2"
             "jeu"]}
   {:text "Les réactions, mèmes et autres doivent être postés dans le méga-thread post-match.

         https://www.reddit.com/r/france/comments/8z336m/megathreadla franceremporte lacoupe du_monde/

         Please direct all WC related comments and posts to the above thread, thanks!

         ",
    :tokens ["reaction"
             "mème"
             "autr"
             "doivent"
             "être"
             "post"
             "méga"
             "thread"
             "post"
             "match"
             "http"
             "w.redit.com"
             "r"
             "franc"
             "coment"
             "8z336m"
             "megathreadla"
             "franceremport"
             "lacoup"
             "du_mond"
             "pleas"
             "direct"
             "all"
             "wc"
             "related"
             "coment"
             "and"
             "post"
             "to"
             "the"
             "abov"
             "thread"
             "thank"]}
   {:text "Je ne sais pas trop de quoi tu parles. Je faisais uniquement référence aux commentaires insultants ou désagréables que j'ai constatés et qui ne venaient pas de militants FN, et pas du tout à ton cas. Donc oui, absence de modération.

         Pour le reste, les downvotes d'opinion c'est pas bien et dans tous les camps on trouve des gens qui s'adonnent à ça. Mais franchement, de là à imaginer que le but ici c'est censure du FN, faut peut-être pas abuser.

         ",
    :tokens ["sai"
             "trop"
             "quoi"
             "parl"
             "faisai"
             "uniqu"
             "referenc"
             "comentair"
             "insultant"
             "desagreabl"
             "constat"
             "venaient"
             "militant"
             "fn"
             "tout"
             "cas"
             "donc"
             "oui"
             "absenc"
             "mod"
             "rest"
             "downvot"
             "opinion"
             "bien"
             "tou"
             "camp"
             "trouv"
             "gen"
             "adonent"
             "ça"
             "franch"
             "là"
             "imagin"
             "but"
             "censur"
             "fn"
             "faut"
             "peut"
             "être"
             "abus"]}
   {:text "Picketty a proposé une solution. Je suis pas fan de son analyse mais il propose au moins de rendre ça clair.

         ",
    :tokens ["pickety" "a" "propos" "solution" "fan" "analys" "propos" "moin" "rendr" "ça" "clai"]}
   {:text ">Ton article n'a rien à voir avec les 18% dont tu parles.

         https://oip.org/en-bref/pour-quels-types-de-delits-et-quelles-peines-les-personnes-detenues-sont-elles-incarcerees/

         ",
    :tokens ["articl"
             "a"
             "rien"
             "voir"
             "18"
             "dont"
             "parl"
             "http"
             "oip.org"
             "bref"
             "type"
             "delit"
             "pein"
             "person"
             "detenu"
             "elle"
             "incarcer"]}
   {:text ">j’adore les Québécoises

         Je vois qu'il n'y a pas que notre langue que tu as étudié, il y a aussi l'art français ancestral de la drague.

         ",
    :tokens ["ador" "quebecois" "voi" "a" "langu" "etud" "a" "ausi" "art" "francai" "ancestral" "dragu"]}
   {:text "C'est vraiment très vert, les pommes vertes.\n\n", :tokens ["vraiment" "trè" "vert" "pome" "vert"]}
   {:text "En effet. Woops.\n\n", :tokens ["efet" "woop"]}
   {:text "", :tokens []}
   {:text "First !\n\n\n\nDonnez moi du karma les rageux ! \n\nAllez bien bosser tous moi je vais dormir. \n\n",
    :tokens ["first" "donez" "karma" "rageu" "alez" "bien" "bos" "tou" "vai" "dormi"]}
   {:text "Les réactions, mèmes et autres doivent être postés dans le méga-thread post-match.

         https://www.reddit.com/r/france/comments/8z336m/megathreadla franceremporte lacoupe du_monde/

         Please direct all WC related comments and posts to the above thread, thanks!

         ",
    :tokens ["reaction"
             "mème"
             "autr"
             "doivent"
             "être"
             "post"
             "méga"
             "thread"
             "post"
             "match"
             "http"
             "w.redit.com"
             "r"
             "franc"
             "coment"
             "8z336m"
             "megathreadla"
             "franceremport"
             "lacoup"
             "du_mond"
             "pleas"
             "direct"
             "all"
             "wc"
             "related"
             "coment"
             "and"
             "post"
             "to"
             "the"
             "abov"
             "thread"
             "thank"]}
   {:text "Three points:

         This criminal act has no justification and I guess it is embarrassing for many muslims.
         Freedom of speech aside, the magazine's behavior is disrespectful and provocative. It's not funny and uncreative humor. (See point one.)
         If they had repeatedly offended Catholicism instead of Islam, something similar might have happened.


         Edit: Downvote me all you want, but this country has serious discriminatory issues towards muslims. Extremists are a minority.

         Edit 2: By continuing to look at the responses generated by this comment, it seems that I didn't express myself correctly. I admit I was wrong in my third point from above; this is very unlikely to happen. Still, I am just concerned about how these terrible and unacceptable acts of terrorism affect people's perception of religion and how this evolves into discrimination. If I could ask you to think about one thing: this problem is not rooted in religion but rather in the lack of education; take a look at history and you will find horrible things done in the name of Christ or other religious figures.

         ",
    :tokens ["thre"
             "point"
             "thi"
             "criminal"
             "act"
             "has"
             "no"
             "justific"
             "and"
             "i"
             "gues"
             "it"
             "is"
             "embarasing"
             "for"
             "many"
             "muslim"
             "fredom"
             "of"
             "spech"
             "asid"
             "the"
             "magazine'"
             "behavio"
             "is"
             "disrespectful"
             "and"
             "provocatif"
             "it'"
             "not"
             "funy"
             "and"
             "uncreatif"
             "humo"
             "see"
             "point"
             "one"
             "if"
             "they"
             "had"
             "repeatedly"
             "ofended"
             "catholicism"
             "instead"
             "of"
             "islam"
             "something"
             "simila"
             "might"
             "have"
             "hapened"
             "edit"
             "downvot"
             "all"
             "you"
             "want"
             "but"
             "thi"
             "country"
             "has"
             "seriou"
             "discriminatory"
             "isue"
             "toward"
             "muslim"
             "extremist"
             "are"
             "a"
             "minority"
             "edit"
             "2"
             "by"
             "continuing"
             "to"
             "look"
             "at"
             "the"
             "respons"
             "generated"
             "by"
             "thi"
             "coment"
             "it"
             "seem"
             "that"
             "i"
             "didn't"
             "expres"
             "myself"
             "corectly"
             "i"
             "admit"
             "i"
             "was"
             "wrong"
             "in"
             "my"
             "third"
             "point"
             "from"
             "abov"
             "thi"
             "is"
             "very"
             "unlikely"
             "to"
             "hapen"
             "stil"
             "i"
             "am"
             "just"
             "concerned"
             "about"
             "how"
             "thes"
             "teribl"
             "and"
             "unaceptabl"
             "act"
             "of"
             "terorism"
             "afect"
             "people'"
             "perception"
             "of"
             "religion"
             "and"
             "how"
             "thi"
             "evolv"
             "into"
             "discrimin"
             "if"
             "i"
             "could"
             "ask"
             "you"
             "to"
             "think"
             "about"
             "one"
             "thing"
             "thi"
             "problem"
             "is"
             "not"
             "roted"
             "in"
             "religion"
             "but"
             "rath"
             "in"
             "the"
             "lack"
             "of"
             "educ"
             "take"
             "a"
             "look"
             "at"
             "history"
             "and"
             "you"
             "will"
             "find"
             "horibl"
             "thing"
             "done"
             "in"
             "the"
             "name"
             "of"
             "christ"
             "or"
             "oth"
             "religiou"
             "figur"]}
   {:text "Non mais je veux dire, tu t'arrêtes ça c'est sur je suis d'accord. Mais ce que je voulais dire c'était une fois arrêter tu es en droit de demander qu'il te montre la plaque non ?

         En revanche je viens de penser que le \"pouvoir\" de demander aux gens de s'arrêter est peut normalement reserver aux policiers. Et dans ce cas c'est vrai qu'on peut rentrer dans le cas de confusion que tu citais.

         ",
    :tokens ["non"
             "veu"
             "dire"
             "aret"
             "ça"
             "acord"
             "voulai"
             "dire"
             "foi"
             "aret"
             "droit"
             "demand"
             "montr"
             "plaqu"
             "non"
             "revanch"
             "vien"
             "pens"
             "pouvoi"
             "demand"
             "gen"
             "aret"
             "peut"
             "normal"
             "reserv"
             "polici"
             "cas"
             "vrai"
             "peut"
             "rentr"
             "cas"
             "confusion"
             "citai"]}
   {:text "http://www.lepoint.fr/societe/bertrand-cantat-enquete-sur-une-omerta-29-11-2017-2176157_23.php\n\n",
    :tokens ["http" "w.lepoint.f" "societ" "bertrand" "cantat" "enquet" "omerta" "29" "11" "2017" "2176157_23" "php"]}
   {:text "Le jeu a l'air vraiment sympa, mais pourquoi /r/france ?\n\n",
    :tokens ["jeu" "a" "air" "vraiment" "sympa" "pourquoi" "r" "franc"]}
   {:text "C'est ce qu'il a fait et il a constaté l'usage des smartphones... Donc on devra faire le devoir dans la bibliothèque qui sera équipée de brouilleurs.

         ",
    :tokens ["a" "fait" "a" "constat" "usag" "smartphon" "donc" "devra" "fair" "devoi" "bibliothequ" "equip" "brouileu"]}
   {:text "Les Coréens du Nord sont décidés à un affrontement avec le reste du monde on dirait.\n\n",
    :tokens ["coren" "nord" "decid" "afront" "rest" "mond" "dirait"]}]


  *e)


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



(comment ;; performance comparison

  (require '[criterium.core :as bench])

  (def txts
    (->> (io/resource "reddit-france-comments-dv-sample.json") ;; CAVEAT using diversified sample, which may be highly skewed. (Val, 20 Apr 2020)
      (uenc/json-read)
      (keep :body)
      (keep trim-markdown)
      (take 100)
      vec))

  (def split-words-lucene-fr
    (let [fr-an (FrenchAnalyzer.)]
      #(lucene-tokenize fr-an %)))

  (bench/bench
    (run! split-words-lucene-fr txts))
  ;Evaluation count : 40200 in 60 samples of 670 calls.
  ;             Execution time mean : 1.496300 ms
  ;    Execution time std-deviation : 114.636285 µs
  ;   Execution time lower quantile : 1.273591 ms ( 2.5%)
  ;   Execution time upper quantile : 1.683144 ms (97.5%)
  ;                   Overhead used : 2.014804 ns

  (bench/bench
    (run! split-words-fr txts))
  ;Evaluation count : 14820 in 60 samples of 247 calls.
  ;             Execution time mean : 4.283518 ms
  ;    Execution time std-deviation : 261.806263 µs
  ;   Execution time lower quantile : 3.809658 ms ( 2.5%)
  ;   Execution time upper quantile : 4.739442 ms (97.5%)
  ;                   Overhead used : 2.014804 ns

  *e)



;; ------------------------------------------------------------------------------
;; Syntactic analysis

(def syntax-stats-fr
  (let [pos-pipeline
        (delay
          (StanfordCoreNLP.
            (u/as-java-props
              ;; https://stanfordnlp.github.io/CoreNLP/annotators.html
              {"annotators" (str/join "," ["tokenize" "ssplit" "pos"])
               "tokenize.language" "French"
               "tokenize.keepeol" "true"
               "pos.model" "french.tagger"})))]
    (fn syntax-stats-fr [^String raw-txt]
      (let [doc (doto
                  (CoreDocument. raw-txt)
                  (->> (.annotate ^StanfordCoreNLP @pos-pipeline)))]
        {:n-sentences (count (.sentences doc))
         :pos-freqs (->> doc
                      .tokens
                      (map
                        (fn [^CoreLabel lbl]
                          (.tag lbl)))
                      frequencies)}))))

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





;; ------------------------------------------------------------------------------
;; Reddit

(defn backfill-reddit-name
  [type-prefix {:as s, id :id, nm :name}]
  (cond-> s
    (nil? nm)
    (assoc :name (str type-prefix id))))


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