(ns discussion-gems.feature-engineering.protocols)

(defprotocol ISubmissionDb
  (submdb-submission [submdb])
  (submdb-comments [submdb])
  (submdb-thing-by-reddit-name [submdb rdt-name])
  (submdb-children-by-reddit-name [submdb rdt-name]))
