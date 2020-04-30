(ns discussion-gems.training.db
  (:require [next.jdbc :as jdbc]
            [discussion-gems.utils.encoding :as uenc]))


(def db
  {:jdbcUrl "jdbc:sqlite:./resources/unversioned/training-db.db"})

(def ds
  (jdbc/get-datasource db))

(defn install-schema! []
  (jdbc/execute! ds
    ["
create table labelled_data (
  dataset_id TEXT,
  datapoint_id TEXT,
  datapoint_data__fressian BLOB,
  datapoint_label__fressian BLOB,
  PRIMARY KEY(dataset_id, datapoint_id)
    )"]))

(comment

  (install-schema!)

  *e)

(defn drop-schema! []
  (jdbc/execute! ds ["DROP TABLE labelled_data"]))


(defn save-label!
  [dataset-id datapoint-id datapoint-data datapoint-label]
  (jdbc/execute! ds
    ["
    INSERT OR REPLACE INTO labelled_data(dataset_id, datapoint_id, datapoint_data__fressian, datapoint_label__fressian)
    VALUES (?, ?, ?, ?)"
     dataset-id
     datapoint-id
     (uenc/fressian-encode datapoint-data)
     (uenc/fressian-encode datapoint-label)]))

(defn already-labeled?
  [dataset-id datapoint-id]
  (= 1
    (get-in
      (jdbc/execute! ds
        ["SELECT EXISTS(
  SELECT datapoint_id FROM labelled_data WHERE dataset_id = ? AND datapoint_id = ?
  ) AS ret"
         dataset-id datapoint-id])
      [0 :ret])))


(comment

  (save-label! "myexp0" "point-id1" {:id "point-id1"} 0.59)

  (already-labeled? "myexp0" "point-id1")
  (already-labeled? "myexp0" "point-id-that-does-not-exist")
  *e)
