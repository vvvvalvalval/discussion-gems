(ns discussion-gems.indexing.elasticsearch-schema)


(def es-mapping_dgms-raw-content
  "A (cumbersome) index, that won't be updated frequently,
  acting as a KV-store for retrieving the original Reddit documents."
  {:mappings
   {:properties
    {:reddit_name {:type :keyword}
     :reddit_doc__json {:type :text :index false}}}}) ;; INTRO json-encoded original map.


(def es-mapping_dgms-search-0
  "An index for running structured search."
  {:mappings
   {:properties
    {:reddit_name {:type :keyword}
     :reddit_type {:type :keyword} ;; INTRO either "reddit_type_submission" or "reddit_type_comment"

     :parent_reddit_name {:type :keyword}
     :subm_reddit_name {:type :keyword}

     ;; NOTE Norms are ignored here, because we postulate that concise documents won't be more relevant. (Val, 30 May 2020)
     ;; https://www.elastic.co/guide/en/elasticsearch/reference/current/norms.html
     ;; NOTE Analysis is performed in French:
     ;; https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-lang-analyzer.html#french-analyzer
     :subm_title {:type :text, :norms false
                  :analyzer :french}
     :dgms_text_contents {:type :text, :norms false
                          :analyzer :french}
     :dgms_parent_text_contents {:type :text, :norms false
                                 :analyzer :french}

     :reddit_created {:type :date}

     :dgms_flair {:type :keyword}

     :reddit_n_ups {:type :integer}
     :reddit_n_downs {:type :integer}
     :reddit_score {:type :integer}

     ;; TODO number of quotes
     ;; TODO number of figures (and dates?)
     :dgms_n_sentences {:type :integer}
     :dgms_n_words {:type :integer}
     :dgms_n_hyperlinks {:type :integer}
     :dgms_n_formatting {:type :integer}}}})

