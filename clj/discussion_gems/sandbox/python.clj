(ns discussion-gems.sandbox.python
  (:require [discussion-gems.config.libpython]
            [libpython-clj.require :refer [require-python]]
            [libpython-clj.python :refer [py. py.. py.-] :as py]
            [tech.v2.datatype :as dtype]
            [clojure.repl :refer :all]))

(require-python '[numpy :as np])

(comment

  (def test-ary (np/array [[1 2][3 4]]))

  (float-array (first test-ary))
  (seq *1)

  *e)

(comment
  (require-python '[discussion_gems_py.test_module])

  (discussion_gems_py.test_module/addition 3. 4.)

  *e)

(comment
  (require-python '[sklearn.metrics.pairwise])
  (require-python '[sentence_transformers])
  (require-python '[sentence_transformers.models])


  (doc sentence_transformers.models/CamemBERT)
  (def cmb
    (sentence_transformers.models/CamemBERT "camembert-base"))

  (def pooling-model
    (sentence_transformers.models/Pooling
      (py. cmb get_word_embedding_dimension)
      :pooling_mode_mean_tokens true,
      :pooling_mode_cls_token false,
      :pooling_mode_max_tokens false))

  (def model
    (sentence_transformers/SentenceTransformer :modules [cmb pooling-model]))

  (def sentences
    ["Merci pour ta réponse, j'ai appris plein de choses!"
     "Très intéressant, merci."
     "Je suis complètement d'accord avec toi"
     "Je pense que tu fais une faute de raisonnement fondamentale."
     "Je ne suis pas du tout d'accord."
     "Le crocodile ça cuit lentement, c'est comme ça."
     "Ce film n'est pas un film sur le cyclisme."])

  (def encoded
    (py. model encode sentences))

  (sklearn.metrics.pairwise/cosine_similarity
    (py. model encode sentences))

  (sklearn.metrics.pairwise/cosine_similarity
    (->> sentences
      (mapv (fn [s]
              (first (py. model encode [s]))))))

  (first encoded)
  (mapv identity encoded)
  (ancestors (type encoded))

  (sklearn.metrics.pairwise/euclidean_distances
    (py. model encode sentences))

  *e)
