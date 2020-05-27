(ns video-note-taker.groups
  (:require
   [ring.util.json-response :refer [json-response]]
   [video-note-taker.db :as db :refer [db]]
   [video-note-taker.access :as access]
   [video-note-taker.util :as util]))

(defn get-groups-handler [req username roles]
  (let [groups (db/get-view db access/get-hook-fn
                            "groups" "by_user"
                            {:key username :include_docs true}
                            username roles (db/get-auth-cookie req))]
    (json-response groups)))

(defn delete-group-handler [req username roles]
  (let [req-group   (util/get-body req)
        saved-group (db/get-doc db access/get-hook-fn (:_id req-group)
                                username roles (db/get-auth-cookie req))]
    (if (= (:created-by saved-group) username)
      (db/delete-doc db access/delete-hook-fn saved-group
                     username roles (db/get-auth-cookie req))
      (util/not-authorized-response))))

(defn group-handler [req username roles]
  (let [req-group   (util/get-body req)
        saved-group (db/get-doc db access/get-hook-fn (:_id req-group)
                                username roles (db/get-auth-cookie req))]
    (if saved-group
      (if (= (:created-by saved-group) username)
        (json-response (db/put-doc db access/put-hook-fn req-group
                                   username roles (db/get-auth-cookie req)))
        (util/not-authorized-response))
      (json-response (db/put-doc db access/put-hook-fn (merge req-group {:type "group" :created-by username :users []})
                                 username roles (db/get-auth-cookie req))))))
