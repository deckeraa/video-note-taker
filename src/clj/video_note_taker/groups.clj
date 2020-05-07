(ns video-note-taker.groups
  (:require
   [com.ashafa.clutch :as couch]
   [video-note-taker.auth :refer [db]]
   [ring.util.json-response :refer [json-response]]))

(defn get-groups-handler [req username roles]
  (let [groups (couch/get-view db "groups" "by_user"
                               {:key username :include_docs true})]
    (json-response (vec (map :doc groups)))))
