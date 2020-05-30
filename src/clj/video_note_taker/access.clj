(ns video-note-taker.access
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [cljc.java-time.zoned-date-time :as zd]
   [cljc.java-time.duration :as dur]
   [amazonica.aws.s3 :as s3]
   [amazonica.aws.s3transfer]
   [clj-time.core :as time]
   [clj-time.coerce :as coerce]
   [taoensso.timbre :as timbre
      :refer [log  trace  debug  info  warn  error  fatal  report
              logf tracef debugf infof warnf errorf fatalf reportf
              spy get-env]]
   [video-note-taker.db :as db :refer [db]]
   [video-note-taker.access-shared :as access-shared]))

(defn load-groups-for-user
  "Returns a list of group IDs, e.g.
  [\"6ad12c0291d9f043fb092d076a000cc1\" \"6ad12c0291d9f043fb092d076a006c04\"]"
  [username]
  (vec (map :id (db/get-view db nil "groups" "by_user" {:key username} nil nil nil))))

(defn user-has-access-to-video [username video]
  (if (nil? username) ;; since username is derived from the server-side of things, we can establish the convention that nil means to skip checks
    true
    (let [groups (load-groups-for-user username)]
      (or
                                        ; Are they listed in the :users key?
       (not (empty? (filter #(= username %) (:users video))))
                                        ; Is one of the groups of which they are part listed in the :groups key?
       (not (empty? (clojure.set/intersection (set groups) (set (:groups video)))))))))

(defn user-has-access-to-note [username note]
  (contains? (set (:users note)) username))

(defn user-has-access-to-group [username group]
  (or
   (= username (:created-by group))
   (contains? (set (:users group)) username)))

(defn is-video-stored-in-spaces? [video]
  (and (not (nil? (:storage-location video)))
       (not (=    (:storage-location video) "local"))))

(defn insert-presigned-url-into-video [video]
  (warn "is-video-stored-in-spaces?" (is-video-stored-in-spaces? video) video)
  (if (is-video-stored-in-spaces? video)
    (assoc video
           :presigned-url
           (.toString
            (s3/generate-presigned-url
             :bucket-name (:storage-location video)
             :key (:file-name video)
             :expiration (coerce/to-date (-> 3 time/days time/from-now))
             :method "GET")))
    video))

(defn insert-upload-setting [settings-doc]
  (assoc settings-doc :upload-location?
         (case (System/getenv "VNT_UPLOAD_TO_SPACES")
           "true" :spaces
           :local)))

(defn get-hook-fn [real-doc username roles]
  (warn "get-hook-fn " real-doc)
  (case (:type real-doc)
    "video" (if (user-has-access-to-video username real-doc)
              (-> real-doc
                  (insert-presigned-url-into-video))
              nil)
    "note"  (if (user-has-access-to-note  username real-doc) real-doc nil)
    "group" (if (user-has-access-to-group username real-doc) real-doc nil)
    "settings" (insert-upload-setting real-doc)
    nil))

(defn note-put-hook [real-doc req-doc username roles]
  (let [is-creator? (= username (:created-by req-doc))
        is-last-editor? (if (:last-editor req-doc)
                          (= username (:last-editor req-doc))
                          (= username (:created-req-by req-doc)))
        sufficiently-recent? (and
                              (:last-edit req-doc) ;; some really old docs don't have a :last-edit, causing a NullPointerException on zd/parse. This will avoid that.
                              (dur/is-negative
                               (dur/minus-minutes
                                (dur/between (zd/parse (:last-edit req-doc)) (zd/now))
                                3)))]
    (if (not (or is-creator? (access-shared/can-edit-others-notes roles)))
      real-doc ;; user is not allowed to edit the note
      (merge req-doc
             {:last-edit (zd/format (zd/now) java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)}
             ;; when a note is created, we'd expect the user to edit it write away,
             ;; so give them a chance to edit it before we mark it as "edited".
             (when (not (and is-creator? is-last-editor? sufficiently-recent?))
               {:last-editor username})))))

(defn update-users
  "Used to update :users on an incoming video to avoid users adding or removing users that they
  shouldn't be."
  [real-users req-users users-users username roles]
  (let [real-users  (set real-users) ;; users on the current document
        req-users   (set req-users)  ;; users on the requested update to the document
        users-users (set users-users) ;; users that the given user is allow to add or remove
        existing-users-they-cant-remove (clojure.set/difference real-users users-users)
        requested-users-they-can-add (clojure.set/intersection req-users users-users)]
    (clojure.set/union #{username} requested-users-they-can-add existing-users-they-cant-remove)))

(deftest test-update-users
  (is (= (update-users ["charlie" "golf" "hotel" "foxtrot"] ["echo" "india" "foxtrot"] ["charlie" "golf" "echo" "foxtrot"] "charlie" "")
         #{"charlie" "hotel" "echo" "foxtrot"})))

(defn audit-video-key-display-name
  "Put-hook middleware that audits the :display-name key. Returns an updated version of req-doc."
  [real-doc req-doc username roles]
  (if (access-shared/can-change-video-display-name roles)
    req-doc
    (assoc req-doc :display-name (:display-name real-doc))))

(defn audit-video-key-users
  [real-doc req-doc username roles users]
  ;;pull out any users that the user isn't allowed to share with. Only do this for non-admins.
  (if (contains? (set roles) "_admin")
      req-doc
      (update-in req-doc [:users] (fn [req-users]
                                    (vec (update-users (:users real-doc) req-users users username roles))
                                    )))
  )

(defn video-put-hook [real-doc req-doc username roles]
  (let [users (apply clojure.set/union
                     (map :users (db/get-view
                                  db get-hook-fn "groups" "by_user"
                                  {:key username :include_docs true}
                                  username roles
                                  nil)))]
    (as-> req-doc $
      (audit-video-key-display-name real-doc $ username roles)
      (audit-video-key-users real-doc $ username roles users))))

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
