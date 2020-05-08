(ns video-note-taker.groups
  (:require
   [com.ashafa.clutch :as couch]
   [video-note-taker.auth :refer [db]]
   [ring.util.json-response :refer [json-response]]
   [video-note-taker.util :as util]))

;; groups -> by_user
;; function (doc) {
;;   if(doc.type === "group") {
;;     emit(doc["created-by"],doc._id)
;;     for(var idx in doc.users) {
;;             emit(doc.users[idx], doc._id)
;;     }
;;   }
;; }

(defn get-groups-handler [req username roles]
  (let [groups (couch/get-view db "groups" "by_user"
                               {:key username :include_docs true})]
    (println "groups: " groups)
    (println "map :doc " (map :dooc groups))
    (json-response (vec (map :doc groups)))))

(defn group-handler [req username roles]
  (let [req-group   (util/get-body req)
        saved-group (couch/get-document db (:_id req-group))]
    (println "group-handler: " req-group)
    (if saved-group
      (if (= (:created-by saved-group) username)
        (couch/put-document db req-group)
        (util/not-authorized-response))
      (couch/put-document db (merge req-group {:type "group" :created-by username})))))
