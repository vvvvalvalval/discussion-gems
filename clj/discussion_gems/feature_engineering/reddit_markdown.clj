(ns discussion-gems.feature-engineering.reddit-markdown
  "Extraction of various features from hypertext encoded in Reddit-style Markdown."
  (:require [discussion-gems.parsing :as parsing]
            [mapdag.step :as mdg]
            [mapdag.runtime.jvm-eval]))

(def dag_reddit-markdown-features
  {::parsing/html-forest
   (mdg/step [::parsing/md-txt] parsing/md->html-forest)

   :dgms_body_raw
   (mdg/step
     [::parsing/html-forest]
     (fn [md-html-forest]
       (when (some? md-html-forest)
         (parsing/raw-text-contents
           {::parsing/remove-code true
            ::parsing/remove-quotes true}
           md-html-forest))))

   :dgms_syntax_stats_fr
   (mdg/step [:dgms_body_raw]
     (fn [body-raw]
       (when (some? body-raw)
         (parsing/syntax-stats-fr body-raw))))

   :dgms_n_sentences
   (mdg/step [:dgms_syntax_stats_fr] #(get % :n-sentences))

   :dgms_n_words
   (mdg/step [:dgms_syntax_stats_fr]
     (letfn [(add-val [s _k n] (+ s n))]
       (fn [{pos-freqs :pos-freqs}]
         (reduce-kv add-val 0 pos-freqs))))

   ;; IMPROVEMENT remove low-information domains such as giphy, imgur etc. (Val, 28 May 2020)
   :dgms_hyperlinks
   (mdg/step [::parsing/html-forest]
     (fn [md-html-forest]
       (when (some? md-html-forest)
         (parsing/hyperlinks md-html-forest))))

   :dgms_n_hyperlinks
   (mdg/step [:dgms_hyperlinks] count)

   :dgms_n_formatting
   (mdg/step [::parsing/html-forest]
     (fn [md-html-forest]
       (when (some? md-html-forest)
         (parsing/formatting-count md-html-forest))))})


