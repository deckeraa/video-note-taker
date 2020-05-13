(ns video-note-taker.access
  (:require
   [cljc.java-time.zoned-date-time :as zd]
   [cljc.java-time.duration :as dur]
   [video-note-taker.db :as db]))

(def couch-url "http://localhost:5984/video-note-taker")

(def db
  (let [password (System/getenv "VNT_DB_PASSWORD")]
    {:url couch-url
     :username "admin"
     :password (or password "test")}))

(defn load-groups-for-user
  "Returns a list of group IDs, e.g.
  [\"6ad12c0291d9f043fb092d076a000cc1\" \"6ad12c0291d9f043fb092d076a006c04\"]"
  [username]
  (vec (map :id (db/get-view db nil "groups" "by_user" {:key username} nil nil nil))))

(defn user-has-access-to-video [username video]
  (let [groups (load-groups-for-user username)]
    (or
     ; Are they listed in the :users key?
     (not (empty? (filter #(= username %) (:users video))))
     ; Is one of the groups of which they are part listed in the :groups key?
     (not (empty? (clojure.set/intersection (set groups) (set (:groups video))))))))

(defn user-has-access-to-note [username note]
  (contains? (set (:users note)) username))

(defn user-has-access-to-group [username group]
  (or
   (= username (:created-by group))
   (contains? (set (:users group)) username)))

(defn get-hook-fn [real-doc username roles]
  (case (:type real-doc)
    "video" (user-has-access-to-video username real-doc)
    "note"  (user-has-access-to-note  username real-doc)
    "group" (user-has-access-to-group username real-doc)
    "settings" true
    false))

(defn note-put-hook [real-doc req-doc username roles]
  (let [is-creator? (= username (:created-by req-doc))
        is-last-editor? (if (:last-editor req-doc)
                          (= username (:last-editor req-doc))
                          (= username (:created-req-by req-doc)))
        sufficiently-recent? (dur/is-negative
                              (dur/minus-minutes
                               (dur/between (zd/parse (:last-edit req-doc)) (zd/now))
                               3))]
    (merge req-doc
           {:last-edit (zd/format (zd/now) java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)}
           ;; when a note is created, we'd expect the user to edit it write away,
           ;; so give them a chance to edit it before we mark it as "edited".
           (when (not (and is-creator? is-last-editor? sufficiently-recent?))
             {:last-editor username}))))

(defn video-put-hook [real-doc req-doc username roles]
  (let [users (apply clojure.set/union
                     (map :users (db/get-view
                                  db get-hook-fn "groups" "by_user"
                                  {:key username :include_docs true}
                                  username roles
                                  nil)))]
    (println "video-put-hook users: " users)
    ;;pull out any users that the user isn't allowed to share with. Only do this for non-admins.
    (if (contains? (set roles) "_admin")
      req-doc
      (update-in req-doc [:users] (fn [req-users]
                                    ;; TODO avoid stripping off already-existing users in :users that the user putting the doc doesn't have rights to add or remove.
                                    (println "req-users " (set req-users))
                                    (println "users" (set (conj users username)))
                                    (println "intersection "
                                             (clojure.set/intersection
                                              (set (conj users username))
                                              (set (conj req-users username))))
                                    (vec (clojure.set/intersection
                                          (set (conj users username))
                                          (set (conj req-users username))))
                                    )))
    ))

(defn put-hook-fn
  "When a CouchDB call is made using a db.clj function that uses the hooks,
  put-hook-fn will be called so that you can modify the document (for example, adding timestamps)
  before it gets sent to CouchDB. Returns nil if the user doesn't have permission to modify the doc."
  [doc username roles]
  (let [real-doc (try
                   (db/couch-request db :get (:_id doc) {} {} nil)
                   (catch Exception e
                     {}))]
    ;; TODO make sure types match
    (case (:type real-doc)
      "note" (note-put-hook real-doc doc username roles)
      "video" (video-put-hook real-doc doc username roles)
      doc)))

(defn delete-hook-fn [real-doc req-doc username roles]
  (case (:type req-doc)
    "video" (= username (:uploaded-by real-doc))
    "note"  (contains? (set (:users real-doc)) username)
    "group" (= username (:created-by real-doc))
    false))
