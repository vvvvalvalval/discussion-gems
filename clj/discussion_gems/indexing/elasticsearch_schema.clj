(ns discussion-gems.indexing.elasticsearch-schema)


(def mapping-raw-content
  "A (cumbersome) index, that won't be updated frequently,
  acting as a KV-store for retrieving the original documents."
  {:mappings
   {:properties
    {:reddit_name {:type :keyword}
     :reddit_doc__json {:type :text :index false}}}}) ;; INTRO json-encoded original map.


(def mapping-search-docs
  "An index for running structured search."
  {:mappings
   {:properties
    {:reddit_name {:type :keyword}

     :dgms_text_contents {:type :text}

     :reddit_created {:type :date}

     :dgms_flair {:type :keyword}

     :reddit_n_ups {:type :integer}
     :reddit_n_downs {:type :integer}
     :reddit_score {:type :integer}

     :dgms_n_sentences {:type :integer}
     :dgms_n_words {:type :integer}
     :dgms_n_hyperlinks {:type :integer}
     :dgms_n_formatting {:type :integer}}}})

