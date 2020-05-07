(ns video-note-taker.groups
  (:require
   [com.ashafa.clutch :as couch]
   [video-note-taker.auth :refer [db]]
   [ring.util.json-response :refer [json-response]]
   [video-note-taker.util :as util]))

(defn get-groups-handler [req username roles]
  (let [groups (couch/get-view db "groups" "by_user"
                               {:key username :include_docs true})]
    (json-response (vec (map :doc groups)))))

(defn group-handler [req username roles]
  (let [req-group   (util/get-body req)
        saved-group (couch/get-document db (:_id req-group))]
    (if saved-group
      (if (= (:created-by saved-group) username)
        (couch/put-document db req-group)
        (util/not-authorized-response))
      (couch/put-document db req-group))))
