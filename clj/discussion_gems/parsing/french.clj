(ns discussion-gems.parsing.french
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(def lucene-french-stopwords
  "A common list of French stopwords, lowercase but with diacritics."
  (into #{}
    (keep
      (fn [l]
        (when-some [m (re-matches #"^([^\s\|]+).*" l)]
          (let [[_ w] m]
            w))))
    (str/split-lines
      (slurp
        (io/resource
          "lucene_french_stop.txt")))))


(comment

  (into (sorted-set)
    lucene-french-stopwords)

  *e)


(defn- replace-fr-diacritics
  [m]
  (case m
    ("à" "â") "a"
    ("é" "è" "ê" "ë") "e"
    ("î" "ï") "i"
    ("ô" "ö") "o"
    ("ù" "ü") "u"
    ("ç") "c"))

(defn normalize-french-diacritics
  [^String txt-lowercase]
  (str/replace txt-lowercase
    #"[àâéèêëîïôöùüç]"
    replace-fr-diacritics))

(comment

  (normalize-french-diacritics
    (str/lower-case
      "Salut chères amies, comment ça va? Le Père Noël sera-t'il notre hôte en cette belle journée à neige, où l'âme se plaît à rêver ?"))
  => "salut cheres amies, comment ca va? le pere noel sera-t'il notre hote en cette belle journee a neige, ou l'ame se plait a rever ?"

  *e)





