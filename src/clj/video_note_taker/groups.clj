(ns video-note-taker.groups
  (:require
   [ring.util.json-response :refer [json-response]]
   [taoensso.timbre :as timbre
      :refer [log  trace  debug  info  warn  error  fatal  report
              logf tracef debugf infof warnf errorf fatalf reportf
              spy get-env]]
   [video-note-taker.db :as db :refer [db]]
   [video-note-taker.access :as access]
   [video-note-taker.util :as util :refer [get-body]]))

(defn get-groups-handler [req username roles]
  (let [body (get-body req)
        username-for-view (if (and (contains? roles "business_user") (:username body))
                            (:username body)
                            username)
        groups (db/get-view db access/get-hook-fn
                            "groups" "by_user"
                            {:key username-for-view :include_docs true}
                            username roles (db/get-auth-cookie req))]
    (json-response (vec (set groups)))))

(defn delete-group-handler [req username roles]
  (let [req-group   (util/get-body req)
        saved-group (db/get-doc db access/get-hook-fn (:_id req-group)
                                username roles (db/get-auth-cookie req))]
    (if (= (:created-by saved-group) username)
      (db/delete-doc db access/delete-hook-fn saved-group
                     username roles (db/get-auth-cookie req))
      (util/not-authorized-response))))

(defn get-users-from-groups [groups]
  (let [group-docs
        (db/bulk-get
         db nil
         {:docs (vec (map (fn [group] {:id group}) groups))}
         nil nil nil)
        ]
    (apply clojure.set/union (map (comp set :users) group-docs)))
  )

;; copied from core.clj and changed to run as admin
(defn get-notes [video-key]
  (db/get-view db nil "notes" "by_video" {:key video-key :include_docs true} nil nil nil)
  )

(defn denormalize-users-on-video [video]
  (let [listed-users (set (:users video))
        group-users  (get-users-from-groups (:groups video))
        all-users    (clojure.set/union listed-users group-users)
        affected-notes (get-notes (:_id video))]
    ;; TODO bulk update could fail on certain notes (for example, due to a document conflict),
    ;; so we'll need a search we can find periodically to validate the "index".
    (info "Updating affected-notes via groups/denormalize-users-on-video: " affected-notes video)
    (db/bulk-update
     db nil
     (vec (map #(assoc % :users (vec all-users)) affected-notes))
     nil nil nil)))

(defn group-handler [req username roles]
  (let [req-group   (util/get-body req)
        saved-group (db/get-doc db access/get-hook-fn (:_id req-group)
                                username roles (db/get-auth-cookie req))]
    (if saved-group
      (if (= (:created-by saved-group) username)
        (let [updated-group (db/put-doc db access/put-hook-fn req-group
                                        username roles (db/get-auth-cookie req))
              videos-query {"selector"
               {"$and" [{"type"
                         {"$eq" "video"}}
                        {"groups"
                         {"$elemMatch"
                          {"$eq" (:_id updated-group)}}}]}
               "execution_stats" true
               "limit" 125}
              affected-videos (:docs (db/run-mango-query videos-query (db/get-auth-cookie req)))
              ]
          (doall (map denormalize-users-on-video affected-videos))
          (json-response updated-group))
        (util/not-authorized-response))
      (json-response (db/put-doc db access/put-hook-fn (merge req-group {:type "group" :created-by username :users []})
                                 username roles (db/get-auth-cookie req))))))
