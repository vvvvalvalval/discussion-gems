(ns discussion-gems.algs.protocols)

(defprotocol WordEmbeddingIndex
  (word-embedding-dim [this])
  (token-vector [this token])
  (all-word-vectors [this]))
