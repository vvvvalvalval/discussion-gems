(ns discussion-gems.sandbox.search-queries
  (:require [discussion-gems.wrappers.elasticsearch-client :as es]))

(comment ;; Experimenting with dgms-search-0
  ;; Can we find interesting results based on the given features ?

  (def es-url
    (let [es-instance-domain "ip-172-31-70-82.ec2.internal"]
      (str "http://" es-instance-domain ":9200")))

  (def es-index-name "dgms-search-0--0")

  (def esc (es/basic-client es-url))


  ;; matching on text content
  (:body
    @(es/request esc
       {:method :post :url [es-index-name :_search]
        :body
        {:size 20
         :query
         {:bool
          {:must [{:match {:dgms_text_contents
                           "voiture électrique empreinte métaux climat"}}]
           :filter
           [{:match {:dgms_flair "Écologie"}}
            {:range
             {:dgms_n_words {:gte 50}}}
            {:range
             {:dgms_n_hyperlinks {:gte 1}}}
            {:range
             {:dgms_n_formatting {:gte 5}}}]}}}}))


  (:body
    @(es/request esc
       {:method :post :url [es-index-name :_search]
        :body
        {:size 20
         :query
         {:bool
          {:must [{:match {:dgms_text_contents
                           "Immigration intégration place territoire problèmes"}}]
           :filter
           [{:match {:dgms_flair "Société"}}
            {:range
             {:dgms_n_words {:gte 50}}}
            {:range
             {:dgms_n_hyperlinks {:gte 1}}}
            {:range
             {:dgms_n_formatting {:gte 5}}}]}}}}))


  ;; matching on parent's content
  (:body
    @(es/request esc
       {:method :post :url [es-index-name :_search]
        :body
        {:size 20
         :query
         {:bool
          {:must [{:match {:dgms_parent_text_contents
                           "Quelqu'un peut m'expliquer en quoi l'UE est un frein à la transition écologique ?"}}]
           :filter
           [#_{:match {:dgms_flair "Société"}}
            {:range
             {:dgms_n_words {:gte 50}}}
            {:range
             {:dgms_n_hyperlinks {:gte 1}}}
            {:range
             {:dgms_n_formatting {:gte 5}}}]}}}}))


  ;; TODO try changing the scoring using the quality predictor features

  *e)
