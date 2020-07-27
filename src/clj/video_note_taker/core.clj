(ns video-note-taker.core
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [bidi.bidi :as bidi]
   [bidi.ring :refer [make-handler]]
   [ring.util.response :as response :refer [file-response content-type response]]
   [ring.util.request :as request]
   [ring.util.json-response :refer [json-response]]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.multipart-params.temp-file :refer [temp-file-store]]
   [ring.middleware.file :refer [wrap-file]]
   [ring.middleware.cookies :refer [cookies-request wrap-cookies]]
   [ring.middleware.session.store :refer [read-session write-session]]
   [ring.middleware.session.memory]
   [ring.middleware.partial-content :refer [wrap-partial-content]]
   [ring.middleware.ssl :refer [wrap-ssl-redirect]]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.codec :as codec]
   [ring.logger :as logger]
   [taoensso.timbre :as timbre
      :refer [log  trace  debug  info  warn  error  fatal  report
              logf tracef debugf infof warnf errorf fatalf reportf
              spy get-env]]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre.appenders.3rd-party.syslog-appender :as syslog-appender]
   [clojure.edn :as edn]
   [clojure.walk :refer [keywordize-keys]]
   [cemerick.url :as url]
   [clj-http.client :as http]
   [clojure.data.json :as json]
   [clojure.java.shell :as shell :refer [sh]]
   [clojure.java.io :as io]
   [clj-uuid :as uuid]
   [cljc.java-time.zoned-date-time :as zd]
   [cljc.java-time.duration :as dur]
   [clojure.data.csv :refer [read-csv write-csv]]
   [video-note-taker.db :as db :refer [db users-db]]
   [video-note-taker.search-shared :as search-shared :refer [construct-search-regex]]
   [video-note-taker.upload-progress :as upload-progress]
   [com.stronganchortech.couchdb-auth-for-ring :as auth :refer [wrap-cookie-auth]]
   [video-note-taker.groups :as groups]
   [video-note-taker.access :as access]
   [video-note-taker.access-shared :as access-shared]
   [s3-beam.handler :as s3b]
   [amazonica.core :refer [with-credential defcredential]]
   [amazonica.aws.s3 :as s3]
   [amazonica.aws.s3transfer]
   [clj-time.core :as time]
   [clj-time.coerce :as coerce]
   [video-note-taker.stripe-handlers :as stripe-handlers]
   [video-note-taker.b2b :as b2b]
   [clojure.core.async :as async :refer [<! go go-loop timeout]]
   [clojure.math.numeric-tower :as math :refer [expt]]
   )
  (:gen-class))

(defonce timbre-syslogger
  (timbre/merge-config!
   {:appenders
    {:syslog-appender
     (taoensso.timbre.appenders.3rd-party.syslog-appender/syslog-appender
      {:ident "video-note-taker"
       :syslog-options (byte 0x03)
       :facility :log-user})}}))

(timbre/set-level! :warn)

(def bucket (System/getenv "VNT_BUCKET"))
(def access-key (System/getenv "AWS_ACCESS_KEY_ID"))
(def secret-key (System/getenv "AWS_SECRET_ACCESS_KEY"))
(defcredential access-key secret-key "https://nyc3.digitaloceanspaces.com")

(defn text-type [v]
  (content-type v "text/html"))

(defn json-type [v]
  (content-type v "application/json"))

(defn not-authorized-response []
  (assoc 
   (text-type (response/response "Not authorized"))
   :status 401))

(defn get-body [req]
  (-> req
      (request/body-string)
      (json/read-str)
      (keywordize-keys)))

(def get-doc (partial db/get-doc db access/get-hook-fn))

(defn get-notes [video-key username roles auth-cookie]
  (db/get-view db access/get-hook-fn "notes" "by_video" {:key video-key :include_docs true} username roles auth-cookie)
  )

(defn get-notes-handler [req username roles]
  (let [doc (get-body req)]
    (json-response (get-notes (:video-key doc) username roles (db/get-auth-cookie req)))))

(defn create-note-handler [req username roles]
  (let [doc (merge (get-body req)
                   {:created-by username
                    :last-edit (zd/format
                                (zd/now)
                                java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)})
        couch-resp (db/put-doc db access/put-hook-fn doc username roles (db/get-auth-cookie req))]
    (json-response couch-resp)))

(defn load-groups-for-user
  "Returns a list of group IDs, e.g.
  [\"6ad12c0291d9f043fb092d076a000cc1\" \"6ad12c0291d9f043fb092d076a006c04\"]"
  [username]
  (vec (map :id (db/get-view db nil "groups" "by_user_in_group" {:key username} nil nil nil))))

(defn get-video-listing-handler [req username roles]
  (let [body (get-body req)
        username (or (:username body) username)
        groups (load-groups-for-user username)
        query {"selector"
               {"$and" [{"type"
                         {"$eq" "video"}}
                        {"$or"
                         [{"users"
                           {"$elemMatch"
                            {"$eq" username}}}
                          (when (not (empty? groups))
                            {"groups"
                             {"$elemMatch"
                              {"$in" groups}}})]}]}
               "execution_stats" true
               "limit" 125}
        videos (db/run-mango-query query (db/get-auth-cookie req))]
    (as-> (:docs videos) $
        (map (fn [video] (access/get-hook-fn video username roles)) $)
        (vec $)
        (json-response $))
;    (json-response (vec (:docs videos)))
    ))

(defn escape-csv-field [s]
  (if s ;; null check to prevent java.lang.NullPointerException from clojure.string/escape
    (str "\"" (clojure.string/escape s {\" "\"\""}) "\"")
    nil))

(defn generate-spreadsheet-line [note]
  (str (escape-csv-field (:video note)) ","
       (escape-csv-field (:video-display-name note)) ","
       (float (/ (Math/round (float (* 100 (:time note)))) 100)) ","
       (escape-csv-field (:text note)) ","
       (escape-csv-field (:created-by note)) ","
       (escape-csv-field (:last-editor note))))

(defn get-notes-spreadsheet-handler
  "Generates a spreadsheet of video notes. Pass ?video-id=<your video id> to get notes for a specific
  video, omit the query string altogether to get all of your user's notes."
  [req username roles]
  (let [query-map (as-> (:query-string req) $
                    (if $
                      (keywordize-keys (codec/form-decode $))
                      $))
        notes     (if (:video-id query-map)
                    (db/get-view db access/get-hook-fn "notes" "by_video"
                                 {:key (:video-id query-map) :include_docs true}
                                 username roles (db/get-auth-cookie req))
                    (db/get-view db access/get-hook-fn "notes" "by_user"
                                 {:key username :include_docs true}
                                 username roles (db/get-auth-cookie req)))
        file-name (str
                   (if (:video-id query-map) ;; if they requested a specific video ...
                     (:video-display-name (first notes)) ;; name it after the video
                     "all" ;; otherwise name it "all"
                     )
                   "_notes.csv"
                   )]
    (as-> notes $
      (sort-by :time $) ; sort
      (map generate-spreadsheet-line $)
      (conj $ "video key,video display name,time in seconds,note text,created by,last editor")
      (clojure.string/join "\n" $)
      (response/response $)
      (content-type $ "text/csv")
      (response/header $ "Content-Disposition"
                       (str  "attachment; filename=\"" file-name  "\"")))))

(defn ensure-file-extension [file-name file-ext]
  (let [file-name-without-extension (or (second (re-matches #"(.*)\.\w*" file-name))
                                        file-name)]
    (str file-name-without-extension "." file-ext)))

(deftest test-ensure-file-extension
  (is (= (ensure-file-extension "foo.mp4" "mp4") "foo.mp4"))
  (is (= (ensure-file-extension "foo" "mp4") "foo.mp4"))
  (is (= (ensure-file-extension "evil.txt" "exe") "evil.exe")))

(defn download-video-handler [req username roles]
  (let [query-map (keywordize-keys (codec/form-decode (:query-string req)))
        video (get-doc (:video-id query-map) username roles (db/get-auth-cookie req))
        file-name (:file-name video)
        file-ext  (last (clojure.string/split file-name #"\."))
        display-name (ensure-file-extension (:display-name video) file-ext)]
    (-> (file-response (str "./resources/private/" file-name))
        (response/header "Content-Disposition" (str  "attachment; filename=\"" display-name "\"")))))

(defn import-note-spreadsheet-line [notes-by-video success-imports-counter failed-imports video-key video-display-name time-in-seconds note-text line username roles auth-cookie]
    ;; if the video's notes haven't been loaded into our cache, go ahead and load them in
  (when (not (get-in @notes-by-video [video-key])) 
    (as-> (get-notes video-key username roles auth-cookie) $
      (map (fn [{time :time}]
             time) ; grab the time associated with the note returned by the view
           $)
      (swap! notes-by-video assoc video-key $)))
  (let [video (get-doc video-key username roles auth-cookie)]
    (if (not (= (:display-name video)
                video-display-name))
      (swap! failed-imports conj {:line line :reason (str "The video display name " video-display-name " does not match with video key: " video-key ". Use the 'Download starter spreadsheet' button to download a spreadsheet with the mapping of video key to video display name.")})
                                        ; Check that the passed-in video-key matches an actual video. If the lookup failed in the previous step, then the video does not exist in the db.
      (if (nil? (get-in @notes-by-video [video-key]))
        (swap! failed-imports conj {:line line :reason (str "A video with the id: " video-key " is not in the database.")})
                                        ; Next, validate that there isn't another note already close to the timestamp
                                        ; This will prevent duplicate notes in case a spreadsheet is uploaded multiple times
        (if (empty? (filter #(< (Math/abs (- time-in-seconds %)) 0.25)
                            (get-in @notes-by-video [video-key])))
          (do (db/put-doc db access/put-hook-fn
                          {:type "note"
                           :video video-key
                           :video-display-name (:display-name video)
                           :time time-in-seconds
                           :text note-text
                           :users (:users video)
                           :created-by username
                           :last-edit (zd/format
                                       (zd/now)
                                       java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)}
                          username roles auth-cookie)
              (swap! notes-by-video #(assoc % video-key
                                            (conj (get % video-key) time-in-seconds)))
              (swap! success-imports-counter inc)
)
          (swap! failed-imports conj {:line line :reason "A note within one second of that timestamp already exists."}))))))

(defn download-starter-spreadsheet [req username roles]
  (if (not (access-shared/can-import-spreadsheet roles))
    (not-authorized-response)
    (let [videos (db/get-view db access/get-hook-fn "videos" "by_user"
                              {:key username :include_docs true}
                              username roles (db/get-auth-cookie req))]
      (as-> videos $
        (sort-by :display-name $) ; sort
        (map (fn [video]
               (str (escape-csv-field (:_id video)) ","
                    (escape-csv-field (:display-name video)) ","
                    ","))
             $)
        (conj $ "video key,video display name,time in seconds,note text")
        (clojure.string/join "\n" $)
        (response/response $)
        (content-type $ "text/csv")
        (response/header $ "Content-Disposition" "attachment; filename=\"starter_spreadsheet.csv\"")))))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-spreadsheet-handler" -F file=@my-spreadsheet.csv
(defn upload-spreadsheet-handler [req username roles]
  (if (not (access-shared/can-import-spreadsheet roles))
    (not-authorized-response)
    (let [notes-by-video (atom {})
          success-imports-counter (atom 0)
          failed-imports (atom [])
          lines (read-csv (io/reader (get-in req [:params "file" :tempfile])))]
      (doall (map (fn [[video-key video-display-name time-in-seconds note-text :as line]]
                    (let [time-in-seconds (edn/read-string time-in-seconds)]
                      (if (number? time-in-seconds) ; filter out the title row, if present
                        (import-note-spreadsheet-line notes-by-video success-imports-counter failed-imports video-key video-display-name time-in-seconds note-text line username roles (db/get-auth-cookie req))
                        (swap! failed-imports conj {:line line :reason "time-in-seconds not a number"}))))
                  lines))
      (json-response {:didnt-import @failed-imports :successfully-imported @success-imports-counter}))))

(defn single-video-upload [req username roles {:keys [filename tempfile] :as file}]
  (let [uploaded-by-username (or (and (contains? (set roles) "business_user")
                                      (get-in req [:params "username"]))
                                 username)
        auto-add-groups (get-in req [:params "auto-add-groups"])
        id (uuid/to-string (uuid/v4))
        file-ext (last (clojure.string/split filename #"\."))
        new-short-filename (str id "." file-ext)
        file-loc (str "./resources/private/" new-short-filename)
        ]
    (info "filename: " filename)
    (info "username: " username uploaded-by-username)
    ;; copy the file over -- it's going to get renamed to a uuid to avoid conflicts
    (io/copy tempfile
             (io/file file-loc))
    ;; delete the temp file -- this happens automatically by Ring, but takes an hour, so this frees up space sooner
    (io/delete-file tempfile)
    ;; put some video metadata into Couch
    (let [content-length (-> (sh "du" "--bytes" file-loc)
                             (:out)
                             (clojure.string/split #"\t")
                             (first)
                             (Integer.))
          video-doc (db/put-doc
                     db access/put-hook-fn
                     (merge
                      {:_id id
                       :type "video"
                       :display-name filename
                       :file-name new-short-filename
                       :users [uploaded-by-username]
                       :content-length content-length
                       :uploaded-by uploaded-by-username
                       :uploaded-datetime (.toString (new java.util.Date))}
                      (if (= uploaded-by-username username)
                        {}
                        {:b2b-user username})
                      (when auto-add-groups
                        {:groups auto-add-groups})
                      )
                     username
                     roles (db/get-auth-cookie req))]
      video-doc)))

(defn local-upload-handler [req username roles]
  (info req)
  (info (get-in req [:params]))
  ;;; Sometimes params looks like
  ;; {file [{:filename foo.mp3,
  ;;         :content-type audio/mpeg,
  ;;         :tempfile #object[java.io.File 0x3736570e /tmp/ring-multipart-8331678626413497023.tmp],
  ;;         :size 62220623}
  ;;        {:filename bar.mp3,
  ;;         :content-type audio/mpeg,
  ;;         :tempfile #object[java.io.File 0x78d9a256 /tmp/ring-multipart-3113659076537655850.tmp],
  ;;         :size 52311320}]}
  ;;;; Other times it looks like
  ;; {file {:filename foo.mp3,
  ;;        :content-type application/octet-stream,
  ;;        :tempfile #object[java.io.File 0x7d710f13 /tmp/ring-multipart-6750941438243826845.tmp],
  ;;        :size 225667}}
  ;;;; Therefore, we'll ensure that it's a vector.
  (let [file-array (if (vector? (get-in req [:params "file"]))
                     (get-in req [:params "file"])
                     [(get-in req [:params "file"])])]
    (info "(:params req)" (:params req))
    (info "file-array: " (remove nil? file-array) file-array)
    (json-response (map (partial single-video-upload req username roles) (remove nil? file-array)))))

(defn get-user-handler [req username roles]
  (if (= username "admin")
    (json-response {:name "admin" :roles ["_admin"]})
    (let [user-doc (db/get-doc users-db access/get-hook-fn (str "org.couchdb.user:" username) username roles (db/get-auth-cookie req))]
      (json-response user-doc))))

(defn get-user-usage [username]
  (let [view-resp (db/get-view db nil "videos" "content_length_by_user"
                               {:key username} nil nil nil)]
    (or (-> view-resp
            (first)
            (:value))
        0)))

(defn get-user-usage-handler
  "Returns the user's storage usage in bytes"
  [req username roles]
  (json-response (get-user-usage username)))

(defn has-user-exceeded-limits? [username]
  (let [limit (:gb-limit (db/get-doc users-db nil (str "org.couchdb.user:" username) nil nil nil))
        usage (/ (get-user-usage username) 1000000000)]
    (and limit
         (> usage limit))))

(defn spaces-upload-handler [req username roles]
  (info "s3 req:" req)
  ;; (warn "extraction: " (get-in req [:headers "auto-add-groups"]))
  ;; (warn (get-in req [:headers]))
  (let [params (keywordize-keys (:params req))
        filename (:file-name params)
        id (uuid/to-string (uuid/v4))
        file-ext (last (clojure.string/split filename #"\."))
        new-short-filename (str id "." file-ext)
        params (assoc params :file-name new-short-filename)
        uploaded-by-username (or (and (contains? (set roles) "business_user")
                                      (get-in req [:headers "username"]))
                                 username)
        auto-add-groups (edn/read-string (get-in req [:headers "auto-add-groups"]))
        ;;params (assoc params "Content-Disposition" (str "attachment; filename=\"" filename "\""))
        ]
    (info "auto-add-groups: " auto-add-groups)
    (info "filename: " filename)
    ;; TODO GB limit check could go here
    (if (has-user-exceeded-limits? username)
      {:status 402 ; payment required
       :body "Storage usage limit has been exceeded."
       :headers {"Content-Type" "application/json"}
       }
      (do
        ;; put the video metadata into Couch
        (let [video-doc (db/put-doc
                         db access/put-hook-fn
                         (merge
                          {:_id id
                           :type "video"
                           :display-name filename
                           :file-name new-short-filename
                           :users [uploaded-by-username]
                           :uploaded-by uploaded-by-username
                           :uploaded-datetime (.toString (new java.util.Date))
                           :storage-location bucket}
                          (when (not (= username uploaded-by-username))
                            {:b2b-user username})
                          (when auto-add-groups
                            {:groups auto-add-groups}
                            ))
                         username
                         roles
                         (db/get-auth-cookie req)
                         )])
        ;; Do an exponential backoff to query Spaces to get the content length of the uploaded video.
        ;; Content length isn't available until the upload is entirely complete.
        (go-loop [retry 0]
          (<! (timeout (* 1000 (expt 10 (+ retry 1)))))
          (let [success?
                (try
                  (warn "Looking up: " new-short-filename)
                  (let [metadata (s3/get-object-metadata :bucket-name "vnt-spaces-0" :key new-short-filename)
                        content-length (:content-length metadata)
                        doc (db/get-doc db nil id nil nil nil)]
                    (warn "Got content-length of " content-length " for id " id " " metadata)
                    (when content-length
                      (db/put-doc db nil (assoc doc :content-length content-length) nil nil)
                      true)
                    false)
                  (catch Exception ex
                    (warn new-short-filename " not found.")
                    false))]
            (when (and (not success?)
                       (< retry 5))
              (recur (inc retry)))))
        ;; Return the response with the pre-signed url for client uploading
        {:status 200
         :body (pr-str
                (s3b/sign-upload
                 params
                 {:bucket bucket
                  :aws-access-key access-key
                  :aws-secret-key secret-key
                  :acl "private"
                  :upload-url "https://vnt-spaces-0.nyc3.digitaloceanspaces.com"}))
         :headers {"Content-Type" "application/edn"}}))
    ))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-video-handler" -F file=@my-video.mp4
(defn upload-video-handler [req username roles]
  (info req)
  (info (get-in req [:params]))
  (if (access-shared/can-upload roles)
    (if (= (System/getenv "VNT_UPLOAD_TO_SPACES") "true")
      (spaces-upload-handler req username roles)
      (local-upload-handler req username roles))
    (not-authorized-response)))

(defn delete-video-handler [req username roles]
  (let [doc (get-body req) ; the doc should be a video CouchDB document
        video-id (get-in doc [:_id])
        video (get-doc video-id nil nil nil)]
    (if (access-shared/can-delete-videos roles)
      (if (not (db/delete-doc db access/delete-hook-fn doc username roles (db/get-auth-cookie req)))
        (assoc (json-response {:success false :reason "You cannot delete a video that you did not upload."})
               :status 403)
        (do
          ;; delete all notes related to the video
          (db/bulk-update
           db access/put-hook-fn
           (vec (map
                 #(assoc % :_deleted true)
                 (get-notes (:_id video) username roles (db/get-auth-cookie req))))
           username roles (db/get-auth-cookie req))
          ;; Currently this doesn't handle bulk update conflicts.
          ;; Notes not deleted because of a conflict will be rare and  won't cause a problem,
          ;; but they will be left sitting around, so either we need to do something here
          ;; or make a view that can clean up orphaned notes periodically.
          
          ;; delete the actual video file
          (let [bucket (:storage-location video)]
            (info "deleting: " video bucket (nil? bucket) (= :local bucket) (= :local bucket))
            (if (or (nil? bucket) (= :local bucket))
              (io/delete-file (str "./resources/private/" (:file-name video)))
              (s3/delete-object :bucket-name bucket :key (:file-name video))
              ;; (s3/delete-object {:endpoint "https://nyc3.digitaloceanspaces.com"} :bucket-name bucket :key (:file-name video))
              ))
          ;; return the response
          (json-response {:success true}))
        )
      (not-authorized-response))))

(defn search-text-handler [req username roles]
  (let [params (get-body req)
        cookie-value (get-in req [:cookies "AuthSession" :value])]
    (let [query {"selector"
                 {"$and" [{"type"
                           {"$eq" "note"}}
                          {"users"
                           {"$elemMatch"
                            {"$eq" username}}},
                          {"text"
                           {"$regex" (construct-search-regex (:text params) true)}}]}
                 "execution_stats" true}
          resp (db/run-mango-query query (db/get-auth-cookie req))
          ]
      (json-response (assoc resp
                            :search-string (:text params))))))

;; (deftest test-user-has-access-to-video
;;   (let [video {:_id 123 :display-name "my_video.mp4" :file-name "123.mp4"
;;                :users ["alpha" "bravo"] :groups ["my_family"]}]))

(defn get-users-from-groups [req groups username roles]
  (let [group-docs
        (db/bulk-get
         db access/get-hook-fn
         {:docs (vec (map (fn [group] {:id group}) groups))}
         username roles (db/get-auth-cookie req))
        ]
    (apply clojure.set/union (map (comp set :users) group-docs)))
  )

(defn update-video-permissions-handler [req username roles]
  (try
    (println "Running update-video-permissions-handler" username roles)
    (let [video (get-body req)
          current-video (get-doc (:_id video) nil nil nil)
          listed-users (set (:users video))
          group-users (get-users-from-groups req (:groups video) username roles)
          all-users (clojure.set/union listed-users group-users)]
      ;; make sure that the user is already on the document
      (if (and (access-shared/can-change-video-display-name roles)
           (access/user-has-access-to-video username current-video))
        (do
          ;; update the document
          (let [updated-video (db/put-doc db access/put-hook-fn video
                                          username roles (db/get-auth-cookie req))
                affected-notes (get-notes (:_id video) username roles (db/get-auth-cookie req))]
            ;; now update the denormalized user permissions stored on the notes
            (db/bulk-update
             db nil
             (vec (map #(assoc % :users (vec all-users)) affected-notes))
             username roles (db/get-auth-cookie req))
            ;; TODO the bulk update could fail to update certain notes.
            ;; They will still appear in below the video, but won't show up
            ;; in the notes search. I should either handle them here or create a search
            ;; to validate the denormalized 'index' :users field on notes.
            (json-response updated-video)))
        (not-authorized-response))
      )
    (catch Exception e
      (error "update-video-permissions-handler e: " e))))

(defn get-connected-users-handler [req username roles]
  (println "get-connected-users-handler req: " req)
  (let [body (get-body req)
        username (if (and (contains? (set roles) "business_user") (:username body))
                   (:username body)
                   username)]
    (json-response (access/get-connected-users username roles)))
  ;; TODO implement an actual connected-users concept -- right now this returns all users.
  ;; (if (contains? (set roles) "_admin")
  ;;   ;; admins get all users
  ;;   (let [resp (db/couch-request users-db :get "_all_docs" {} {} (db/get-auth-cookie req))]
  ;;     (->> (map (fn [row] (second (re-matches #"org\.couchdb\.user\:(.*)" (:id row))))
  ;;               (:rows resp))
  ;;          (remove nil?)
  ;;          (json-response)))
  ;;   ;; non-admins get all users that they are in a group with and any users they created
  ;;   ;; (db/get-view users-db nil "users" "by_creating_user" {:key "dawn" :include_docs true} nil nil nil)
  ;;   (let [groups (db/get-view db access/get-hook-fn "groups" "by_user" {:key username :include_docs true}
  ;;                             username roles (db/get-auth-cookie req))
  ;;         created-users (set (map :name
  ;;                                 (db/get-view users-db nil "users" "by_creating_user" {:key username :include_docs true} nil nil nil)))
  ;;         groups-users (apply clojure.set/union (map :users groups))
  ;;         users (clojure.set/union groups-users created-users)]
  ;;     (warn "created-users: " created-users)
  ;;     (warn "groups-users: " groups-users)
  ;;     (warn "users: " users)
  ;;     (json-response (vec users))))
  )

(defn videos-handler [req username roles]
  (let [video-id (second (re-matches #"/videos/(.*)\..*" (:uri req)))
        video (get-doc video-id username roles (db/get-auth-cookie req))
        filename (:file-name video)]
    (if (and video filename) ;; get-doc does the acess check for us
      (file-response filename {:root "resources/private/"})
      (not-authorized-response))))

(defn create-user-handler [req username roles]
  (warn "User is being created by: " username)
  (if (access-shared/can-create-family-member-users roles)
    (let [user-doc (db/get-doc users-db access/get-hook-fn (str "org.couchdb.user:" username) username roles (db/get-auth-cookie req))
          users (access/get-connected-users username roles)]
      (if (< (count users) (:user-limit user-doc))
        (let [params (get-body req)
              name  (:user params)
              pass  (:pass params)
              business-user? (contains? (set roles) "business_user")
              req-role (:req-role params)
              family-lead (:family-lead params)
              metadata (dissoc (or (:metadata params) {}) :password)]
          (json-response (auth/create-user
                          name pass
                          (if business-user?
                            (if (= req-role "family_lead")
                              ["family_lead"]
                              ["family_member"])
                            ["family_member"])
                          (merge metadata
                                 (if business-user?
                                   {:created-by (or family-lead username)
                                    :b2b-user username}
                                   {:created-by username})))))
        (not-authorized-response)))
    (not-authorized-response)))

(defn report-error-handler [req username roles]
  (warn "report-error-handler: " (get-body req))
  (json-response true))

(defn wrap-login [handler]
  (fn [req]
    (let [resp (handler req)]
      (if (= (:body resp) "false")
        (warn "Unsuccessful login attempt from" (:remote-addr req))
        (info "Successful login for" (get-in resp [:body]) "at" (:remote-addr req))
        )
      resp)))

(def api-routes
  ["/" [[["videos/" :id]  (wrap-cookie-auth videos-handler)]
        ["memories" (fn [req]
                      (println "req: " req)
                      (content-type
                       (file-response "memories.html" {:root "resources/public/"})
                       "text/html"))]
        ["get-doc" (wrap-cookie-auth (partial db/get-doc-handler db access/get-hook-fn))]
        ["bulk-get-doc" (wrap-cookie-auth (partial db/bulk-get-doc-handler db access/get-hook-fn))]
        ["put-doc" (wrap-cookie-auth (partial db/put-doc-handler db access/put-hook-fn))]
        ["put-user-doc" (wrap-cookie-auth (partial db/put-doc-handler users-db access/users-put-hook-fn))]
        ["get-notes" (wrap-cookie-auth get-notes-handler)]
        ["create-note" (wrap-cookie-auth create-note-handler)]
        ["delete-doc" (wrap-cookie-auth (partial db/delete-doc-handler db access/delete-hook-fn))]
        ["get-video-listing" (wrap-cookie-auth get-video-listing-handler)]
        ["download-starter-spreadsheet" (wrap-cookie-auth download-starter-spreadsheet)]
        ["get-notes-spreadsheet" (wrap-cookie-auth get-notes-spreadsheet-handler)]
        ["download-video" (wrap-cookie-auth download-video-handler)]
        ["upload-spreadsheet" (wrap-cookie-auth upload-spreadsheet-handler)]
        ["upload-video" (wrap-cookie-auth upload-video-handler)]
        ["get-upload-progress" (wrap-cookie-auth upload-progress/get-upload-progress)]
        ["delete-video" (wrap-cookie-auth delete-video-handler)]
        ["login" (wrap-login auth/login-handler)]
        ["create-user" (wrap-cookie-auth create-user-handler)]
        ["change-password" (wrap-cookie-auth auth/change-password-handler)]
        ["logout" (wrap-cookie-auth auth/logout-handler)]
        ["cookie-check" auth/cookie-check-handler]
        ["search-text" (wrap-cookie-auth search-text-handler)]
        ["update-video-permissions" (wrap-cookie-auth update-video-permissions-handler)]
        ["get-connected-users" (wrap-cookie-auth get-connected-users-handler)]
        ["get-groups" (wrap-cookie-auth groups/get-groups-handler)]
        ["group" (wrap-cookie-auth groups/group-handler)]
        ["delete-group" (wrap-cookie-auth groups/delete-group-handler)]
        ["install-views" (wrap-cookie-auth (partial db/install-views db))]
        ["spaces-upload" (wrap-cookie-auth spaces-upload-handler)]
        ["create-checkout-session" stripe-handlers/create-checkout-session-handler]
        ["check-username" stripe-handlers/check-username-handler]
        ["hooks" stripe-handlers/hooks]
        ["get-temp-users" (wrap-cookie-auth stripe-handlers/get-temp-users-handler)]
        ["get-current-user" (wrap-cookie-auth get-user-handler)]
        ["get-user-usage" (wrap-cookie-auth get-user-usage-handler)]
        ["get-subscription-info" (wrap-cookie-auth stripe-handlers/get-subscription-info-handler)]
        ["inc-subscription" (wrap-cookie-auth stripe-handlers/inc-subscription-handler)]
        ["dec-subscription" (wrap-cookie-auth stripe-handlers/dec-subscription-handler)]
        ["cancel-subscription" (wrap-cookie-auth stripe-handlers/cancel-subscription-handler)]
        ["report-error" (wrap-cookie-auth report-error-handler)]
        ["get-in-progress-users" (wrap-cookie-auth b2b/get-in-progress-users-handler)]
        ["by-business-user" (wrap-cookie-auth b2b/by-business-user-handler)]
        ["get-family-members-of-users" (wrap-cookie-auth b2b/get-family-members-of-users)]
        ["set-passwords-and-email" (wrap-cookie-auth b2b/set-passwords-and-email-handler)]
        ;; ["hello" (fn [req]
        ;;            (let [id "62df5602-91c5-4b7e-964a-29379190483f.mp3"
        ;;                  metadata (s3/get-object-metadata :bucket-name "vnt-spaces-0" :key id)]
        ;;              (warn metadata)
        ;;              (json-response (:content-length metadata))))]
        ]])

(defn wrap-index
  "Serves up index.html for the '/' route."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (if resp
        resp
        (if (= (:uri req) "/")
          (content-type
           (response
            (clojure.string/replace
             (slurp "./resources/public/index.html")
             "PAGEINFO"
             (pr-str {:stripe-mode (System/getenv "STRIPE_MODE")
                      :stripe-public-key (stripe-handlers/get-stripe-public-key)
                      :landing-page-video-url (System/getenv "LANDING_PAGE_VIDEO_URL")})
             ))
           "text/html")
          (response/not-found "Not found"))))))

(defn wrap-println
  "Middleware that prints the response being built."
  [handler caption]
  (fn [req]
    (let [resp (handler req)]
      (println caption resp)
      resp)))

(defn wrap-print-req
  "Middleware that prints the ring request."
  [handler caption]
  (fn [req]
    (let [resp (handler req)]
      (info caption req)
      resp)))

(defn wrap-not-found [handler]
  (fn [req]
    (let [resp (handler req)]
      (if resp
        resp
        (response/not-found "Not found")))))

(defn wrap-log-abnormal-responses [handler]
  (fn [req]
    (let [resp (handler req)]
      (when (not (= 200 (:status resp)))
        (warn "Sending" (:status resp) "to" (:remote-addr req) "for" (:uri req)))
      resp)))

(defn wrap-ssl-redirect-sometimes [handler use-ssl?]
  (if use-ssl?
    (wrap-ssl-redirect handler)
    handler))

(defn app [use-ssl?]
  (-> (make-handler api-routes)
      (wrap-index)
      (wrap-file "resources/public" {:prefer-handler? true})
      (wrap-content-type)
      (wrap-not-found)
      (wrap-log-abnormal-responses)
      (wrap-params)
      (wrap-cookies)
      (wrap-partial-content)
      (wrap-multipart-params {:store (temp-file-store {:expires-in (* 24 3600)})
                              :progress-fn (partial upload-progress/upload-progress-fn db)})
      (wrap-cors
       :access-control-allow-origin [#".*"]
       :access-control-allow-methods [:get :put :post :delete]
       :access-control-allow-credentials ["true"]
       :access-control-allow-headers ["X-Requested-With","Content-Type","Cache-Control"])
      (wrap-ssl-redirect-sometimes use-ssl?)
      (logger/wrap-with-logger {:log-fn (fn [{:keys [level throwable message]}]
                                          (timbre/log level throwable message))})))

(def hook-for-lein-ring-plugin (app false))

(defn -main [& args]
  (let [http-port (try (Integer/parseInt (first args))
                       (catch Exception ex
                         80))
        https-port (try (Integer/parseInt (second args))
                       (catch Exception ex
                         443))
        use-ssl? (.exists (io/file "./keystore"))]
    (info http-port " " https-port)
    (info "use-ssl? " use-ssl?)
    (run-jetty
     (app use-ssl?)
     (merge {:port http-port
             }
            (if use-ssl?
              {:ssl? true
               :ssl-port https-port
               :keystore "./keystore"
               :key-password (or (System/getenv "VNT_KEYSTORE_PASSWORD") "storep")}
              {})))))
