(ns discussion-gems.training.db
  (:require [next.jdbc :as jdbc]
            [discussion-gems.utils.encoding :as uenc]
            [vvvvalvalval.supdate.api :as supd]
            [discussion-gems.utils.misc :as u]))


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
  label_time INTEGER,
  PRIMARY KEY(dataset_id, datapoint_id)
    )"]))


(defn drop-schema! []
  (jdbc/execute! ds ["DROP TABLE labelled_data"]))

(comment

  (install-schema!)

  (drop-schema!)

  *e)


(defn save-label!
  [dataset-id datapoint-id datapoint-data datapoint-label]
  (jdbc/execute! ds
    ["
    INSERT OR REPLACE INTO labelled_data(dataset_id, datapoint_id, datapoint_data__fressian, datapoint_label__fressian, label_time)
    VALUES (?, ?, ?, ?, ?)"
     dataset-id
     datapoint-id
     (uenc/fressian-encode datapoint-data)
     (uenc/fressian-encode datapoint-label)
     (.getTime (java.util.Date.))]))

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


(defn all-labels
  [dataset-id]
  (->>
    (jdbc/execute! ds
      ["SELECT * FROM labelled_data WHERE dataset_id = ?"
       dataset-id])
    (mapv
      (fn [row]
        (-> row
          (supd/supdate
            {:labelled_data/datapoint_data__fressian uenc/fressian-decode
             :labelled_data/datapoint_label__fressian uenc/fressian-decode})
          (u/rename-keys {:labelled_data/datapoint_data__fressian :labelled_data/datapoint_data
                          :labelled_data/datapoint_label__fressian :labelled_data/datapoint_label}))))))

(defn count-labeled
  [dataset-id]
  (->
    (jdbc/execute! ds
      ["SELECT COUNT(*) AS n FROM labelled_data WHERE dataset_id = ?"
       dataset-id])
    (get-in [0 :n])))

(defn clear-labels!
  [dataset-id]
  (jdbc/execute! ds
    ["DELETE FROM labelled_data WHERE dataset_id = ?"
     dataset-id]))


(comment

  (save-label! "myexp0" "point-id1" {:id "point-id1"} 0.59)

  (already-labeled? "myexp0" "point-id1")
  (already-labeled? "myexp0" "point-id-that-does-not-exist")

  (all-labels "discussion-gems.experiments.detecting-praise-comments--label")
  (count-labeled "discussion-gems.experiments.detecting-praise-comments--label")

  (comment
    (clear-labels! "discussion-gems.experiments.detecting-praise-comments--label"))
  *e)
