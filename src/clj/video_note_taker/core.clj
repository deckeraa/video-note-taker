(ns video-note-taker.core
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [bidi.bidi :as bidi]
   [bidi.ring :refer [make-handler]]
   [ring.util.response :as response :refer [file-response content-type]]
   [ring.util.request :as request]
   [ring.util.json-response :refer [json-response]]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.file :refer [wrap-file]]
   [ring.middleware.cookies :refer [cookies-request wrap-cookies]]
   [ring.middleware.session.store :refer [read-session write-session]]
   [ring.middleware.session.memory]
   [ring.middleware.partial-content :refer [wrap-partial-content]]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.codec :as codec]
   [clojure.edn :as edn]
   [cemerick.url :as url]
   [clj-http.client :as http]
   [com.ashafa.clutch :as couch]
   [com.ashafa.clutch.utils :as utils]
   [com.ashafa.clutch.http-client :refer [couchdb-request]]
   [clojure.data.json :as json]
   [clojure.java.shell :as shell :refer [sh]]
   [clojure.walk :refer [keywordize-keys]]
   [clojure.java.io :as io]
   [clj-uuid :as uuid]
   [cljc.java-time.zoned-date-time :as zd]
   [cljc.java-time.duration :as dur]
   [clojure.data.csv :refer [read-csv write-csv]]
   [video-note-taker.search-shared :as search-shared :refer [construct-search-regex]])
  (:gen-class))

(def db
  (let [password (System/getenv "VNT_DB_PASSWORD")]
    (assoc (cemerick.url/url "http://localhost:5984/video-note-taker")
           :username "admin"
           :password (or password "test")
           )))

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

(defn- remove-cookie-attrs-not-supported-by-ring
  "CouchDB sends back cookie attributes like :version that ring can't handle. This removes those."
  [cookies]
  ;; CouchDB sends a cookies map that looks something like
  ;; {AuthSession {:discard false, :expires #inst "2020-04-21T19:51:08.000-00:00", :path /, :secure false, :value YWxwaGE6NUU5RjQ5RkM6MXHV10hKUXVSuaY8GcMOZ2wFfeA, :version 0}}
  (apply merge
         (map (fn [[cookie-name v]]
                (let [v (select-keys v [:value :domain :path :secure :http-only :max-age :same-site :expires])]
                  (if (:expires v)
                    {cookie-name (update v :expires #(.toString %))} ;; the :expires attr also needs changed frmo a java.util.Date to a string
                    {cookie-name v})))
              cookies)))

(defn- set-cookies-flag [cookies flag flag-value]
  (apply merge
         (map (fn [[cookie-name v]]
                {cookie-name (assoc v flag flag-value)})
              cookies)))

(deftest test-set-cookies-flag
  (is (= (set-cookies-flag {"AuthSession"
                                   {:value "YWxwaGE6NUVBQ0E1OUM6PNpD6s2El_yHqe2MNL-eOTGvkMQ"
                                    :path "/"
                                    :secure false
                                    :expires "Fri May 01 18:41:32 CDT 2020"}}
                                  :secure
                                  true)
         {"AuthSession"
                                   {:value "YWxwaGE6NUVBQ0E1OUM6PNpD6s2El_yHqe2MNL-eOTGvkMQ"
                                    :path "/"
                                    :secure true
                                    :expires "Fri May 01 18:41:32 CDT 2020"}}
         )))

(defn cookie-check
  "Checks the cookies in a request against CouchDB. Returns [{:name :roles} new_cookie] if it's valid, false otherwise.
  Note that
    1) A new cookie being issued does not invalidate old cookies.
    2) New cookies won't always be issued. It takes about a minute after getting a cookie before
       CouchDB will give you a new cookie."
  [cookie-value]
  (let [
        resp (http/get "http://localhost:5984/_session" {:as :json
                                                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                                                         :content-type :json
                                                         })]
    (if (nil? (get-in resp [:body :userCtx :name]))
      false
      [(get-in resp [:body :userCtx])
       (remove-cookie-attrs-not-supported-by-ring (:cookies resp))])))

(defn cookie-check-from-req [req]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (cookie-check cookie-value)))

(defn cookie-check-handler [req]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (if-let [[userCtxt new-cookie] (cookie-check cookie-value)]
      (assoc (json-response userCtxt) :cookies new-cookie)
      (json-response false))))

(defn put-doc-handler [req username]
  (let [doc (get-body req)]
    (println "last-edit: " (:last-edit doc))
    (println "dur: " (dur/minus-minutes
                      (dur/between (zd/parse (:last-edit doc)) (zd/now))
                      5))
    (println "true dur: " (dur/is-negative
                              (dur/minus-minutes
                               (dur/between (zd/parse (:last-edit doc)) (zd/now))
                               3)))
    (let [audited-doc
          ;; Here we look at the doc type and add in any extra fields we need for auditing
          (cond
            (= (:type doc) "note")
            (let [is-creator? (= username (:created-by doc))
                  is-last-editor? (if (:last-editor doc)
                                   (= username (:last-editor doc))
                                   (= username (:created-by doc)))
                  sufficiently-recent? (dur/is-negative
                                        (dur/minus-minutes
                                         (dur/between (zd/parse (:last-edit doc)) (zd/now))
                                         3))]
              (merge doc
                     {:last-edit (zd/format (zd/now) java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)}
                     ;; when a note is created, we'd expect the user to edit it write away,
                     ;; so give them a chance to edit it before we mark it as "edited".
                     (when (not (and is-creator? is-last-editor? sufficiently-recent?))
                       {:last-editor username})))
            :else doc)]
      (json-response (couch/put-document db audited-doc)))))

(defn get-doc [id]
  (couch/get-document db id))

(defn get-doc-handler [req username]
  (let [doc (get-body req)]
    (json-response (get-doc (:_id doc)))))

;; notes -> by_video
;; function(doc) {
;;   if ('video' in doc) {
;;       emit(doc.video, doc._id );
;;   }
;; }

(defn get-notes [video-key]
  (couch/get-view db "notes" "by_video" {:key video-key :include_docs true}))

(defn get-notes-handler [req username]
  (let [doc (get-body req)]
    (json-response (get-notes (:video-key doc)))))

(defn create-note-handler [req username]
  (let [doc (get-body req)
        video (get-doc (:video doc))]
    ;; TODO check user access and validate that the ID isn't already taken
    (json-response (couch/put-document db (merge doc {:created-by username
                                                      :last-edit (zd/format (zd/now) java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)})))))

(defn delete-doc-handler [req username]
  (let [doc (get-body req)]
    (json-response (couch/delete-document db doc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; _design/videos/_view/by_user
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; function (doc) {
;;   if(doc.type === "video") {
;;     for(var idx in doc.users) {
;;             emit(doc.users[idx], doc._id);
;;         }
;;   }
;; }
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-video-listing-handler [req username]
  (let [videos (couch/get-view db "videos" "by_user"
                               {:key username :include_docs true})]
    (json-response (vec (map :doc videos)))))

(defn escape-csv-field [s]
  (str "\"" (clojure.string/escape s {\" "\"\""}) "\""))

(defn get-notes-spreadsheet-handler [req username]
  (let [query-map (keywordize-keys (codec/form-decode (:query-string req)))
        notes     (couch/get-view db "notes" "by_video"
                                  {:key (:video-id query-map) :include_docs true})
        video     (get-doc (:video-id query-map))]
    (as-> notes $
      (map :doc $) ; pull out the docs
      (sort-by :time $) ; sort
      (map (fn [note]
             (str (escape-csv-field (:video note)) ","
                  (escape-csv-field (:display-name video)) ","
                  (float (/ (Math/round (* 100 (:time note))) 100)) ","
                  (escape-csv-field (:text note))))
           $)
      (conj $ "video key,video display name,time in seconds,note text")
      (clojure.string/join "\n" $)
      (response/response $)
      (content-type $ "text/csv"))))

(defn import-note-spreadsheet-line [notes-by-video success-imports-counter failed-imports video-key video-display-name time-in-seconds note-text line username]
    ;; if the video's notes haven't been loaded into our cache, go ahead and load them in
  (when (not (get-in @notes-by-video [video-key])) 
    (as-> (get-notes video-key) $
      (map (fn [{{time :time} :doc}]
             time) ; grab the time associated with the note returned by the view
           $)
      (swap! notes-by-video assoc video-key $)))
  (let [video (get-doc video-key)]
    (if (not (= (:display-name video)
                video-display-name))
      (swap! failed-imports conj {:line line :reason (str "The video display name " video-display-name " does not match with video key: " video-key ". Use the 'Download starter spreadsheet' button to download a spreadsheet with the mapping of video key to video display name.")})
                                        ; Check that the passed-in video-key matches an actual video. If the lookup failed in the previous step, then the video does not exist in the db.
      (if (empty? (get-in @notes-by-video [video-key]))
        (swap! failed-imports conj {:line line :reason (str "A video with the id: " video-key " is not in the database.")})
                                        ; Next, validate that there isn't another note already close to the timestamp
                                        ; This will prevent duplicate notes in case a spreadsheet is uploaded multiple times
        (if (empty? (filter #(< (Math/abs (- time-in-seconds %)) 0.25)
                            (get-in @notes-by-video [video-key])))
          (do (couch/put-document db {:type "note" :video video-key :video-display-name (:display-name video) :time time-in-seconds :text note-text  :users (:users video) :created-by username})
              (swap! notes-by-video #(assoc % video-key
                                            (conj (get % video-key) time-in-seconds)))
              (swap! success-imports-counter inc)
)
          (swap! failed-imports conj {:line line :reason "A note within one second of that timestamp already exists."}))))))

(defn wrap-cookie-auth [handler]
  (fn [req]
    (println "wrap-cookie-auth")
    (let [cookie-check-val (cookie-check-from-req req)]
      (if (not cookie-check-val)
        (not-authorized-response)
        (let [username (get-in cookie-check-val [0 :name])]
          (handler req username))))))

(defn download-starter-spreadsheet [req username]
  (println "download-starter-spreadsheet" username)
  (let [videos (couch/get-view db "videos" "by_user"
                               {:key username :include_docs true})]
    (as-> videos $
      (map :doc $) ; pull out the docs from the view response
      (sort-by :display-name $) ; sort
      (map (fn [video]
             (str (escape-csv-field (:_id video)) ","
                  (escape-csv-field (:display-name video)) ","
                  ","))
           $)
      (conj $ "video key,video display name,time in seconds,note text")
      (clojure.string/join "\n" $)
      (response/response $)
      (content-type $ "text/csv"))
    ))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-spreadsheet-handler" -F file=@my-spreadsheet.csv
(defn upload-spreadsheet-handler [req username]
  (let [notes-by-video (atom {})
        success-imports-counter (atom 0)
        failed-imports (atom [])
        lines (read-csv (io/reader (get-in req [:params "file" :tempfile])))]
    (doall (map (fn [[video-key video-display-name time-in-seconds note-text :as line]]
                  (let [time-in-seconds (edn/read-string time-in-seconds)]
                    (if (number? time-in-seconds) ; filter out the title row, if present
                      (import-note-spreadsheet-line notes-by-video success-imports-counter failed-imports video-key video-display-name time-in-seconds note-text line username)
                      (swap! failed-imports conj {:line line :reason "time-in-seconds not a number"}))))
                lines))
    (json-response {:didnt-import @failed-imports :successfully-imported @success-imports-counter})))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-video-handler" -F file=@my-video.mp4
(defn upload-video-handler [req username]
  (println (get-in req [:params]))
  (let [id (uuid/to-string (uuid/v4))
        filename (get-in req [:params "file" :filename])
        file-ext (last (clojure.string/split filename #"\."))
        tempfile (get-in req [:params "file" :tempfile])
        new-short-filename (str id "." file-ext)]
    (println "filename: " filename)
    (println "username " username)
    ;; copy the file over -- it's going to get renamed to a uuid to avoid conflicts
    (io/copy (get-in req [:params "file" :tempfile])
             (io/file (str "./resources/private/" new-short-filename)))
    ;; put some video metadata into Couch
    (let [video-doc (couch/put-document db {:_id id
                                            :type "video"
                                            :display-name filename
                                            :file-name new-short-filename
                                            :users [username]
                                            :uploaded-by username
                                            :uploaded-datetime (.toString (new java.util.Date))})]
      (json-response video-doc))))

(defonce file-upload-progress-atom (atom {}))

(defn upload-progress-fn [req bytes-read content-length item-count]
  (let [req (cookies-request req)
        cookie-value (get-in req [:cookies "AuthSession" :value])]
    ;; TODO this isn't quite correct since we're using cookie-value as a session key, but the cookie-value gets refreshed mid-session on a regular basis.
    (swap! file-upload-progress-atom assoc cookie-value {:bytes-read bytes-read :content-length content-length})))

(defn get-upload-progress [req username]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (json-response (get @file-upload-progress-atom cookie-value))))

(defn delete-video-handler [req username]
  (let [doc (get-body req) ; the doc should be a video CouchDB document
        video-id (get-in doc [:_id])
        video (get-doc video-id)]
    (cond
      ;; check that the user has access to the video
      (not (= username (:uploaded-by video)))
      (assoc (json-response {:success false :reason "You cannot delete a video that you did not upload."})
             :status 403)
      ;; otherwise, do the delete
      :else
      (do
        ;; delete all notes related to the video
        (couch/bulk-update
         db
         (vec (map
               (fn [view-result]
                 (let [v (:doc view-result)]
                   (assoc v :_deleted true)))
               (get-notes (:_id video)))))
        ;; Currently this doesn't handle bulk update conflicts.
        ;; Notes not deleted because of a conflict will be rare and  won't cause a problem,
        ;; but they will be left sitting around, so either we need to do something here
        ;; or make a view that can clean up orphaned notes periodically.
        
        ;; delete the video document
        (couch/delete-document db video)
        ;; delete the actual video file
        (io/delete-file (str "./resources/private/" (:file-name video)))
        ;; return the response
        (json-response {:success true})))
    ))

(defn get-cookie-handler [req]
  (try
    (let [params (get-body req)
          resp (http/post "http://localhost:5984/_session" {:as :json
                                                            :content-type :json
                                                            :form-params {:name (:user params)
                                                                          :password (:pass params)}})]
      (let [ring-resp
            (assoc 
             (json-response {:body (:body resp) :cookies (:cookies resp)})
             :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies resp)) ;; set the CouchDB cookie on the ring response
             ;; :cookies {"secret" {:value "foobar", :secure true, :max-age 3600}}
             )]
                                        ;      (println ring-resp)
        ring-resp))
    (catch Exception e
      (json-response {:result "login failed"}))))

(defn login-handler [req]
  (try
    (let [params (get-body req)
          resp (http/post "http://localhost:5984/_session" {:as :json
                                                            :content-type :json
                                                            :form-params {:name     (:user params)
                                                                          :password (:pass params)}})]
      (println "Login params: " params)
      (println "Login resp: " resp)
      (assoc 
       (json-response true)
       :cookies (as-> (:cookies resp) $
                  (remove-cookie-attrs-not-supported-by-ring $)
                  ;; (set-cookies-flag $ :secure true)
                  ;; (set-cookies-flag $ :same-site :strict)
                  (do (println $) $)
                 )))
    (catch Exception e
      (json-response false))))

(defn create-user-handler [req username]
  (try
    (let [params (get-body req)
          name (:user params)]
      (println "create-user-handler: " params)
      (if (nil? (re-find #"^\w+$" name)) ; sanitize the name
        (assoc (json-response :invalid-user-name) :status 400)
        (let [resp (http/put
                    (str "http://localhost:5984/_users/org.couchdb.user:" name)
                    {:as :json
                     :basic-auth [(:username db) (:password db)]
                     :content-type :json
                     :form-params {:name     name
                                   :password (:pass params)
                                   :roles []
                                   :type :user}})]
          (println "create-user resp: " resp)
          (if (= 201 (:status resp))
            (do
              (let [login-resp (http/post "http://localhost:5984/_session" {:as :json
                                                            :content-type :json
                                                            :form-params {:name     (:user params)
                                                                          :password (:pass params)}})]
                (assoc 
                 (json-response true)
                 :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies login-resp)) ;; set the CouchDB cookie on the ring response
                 )))
            (assoc (json-response false) :status 400) ;; don't want to leak any info useful to attackers, no keeping this very non-descript
            ))))
    (catch Exception e
      (println "create-user-handler exception: " e)
      (assoc (json-response false) :status 400))))

(defn change-password-handler [req username]
  (try
    (let [params (get-body req)
          cookie-value (get-in req [:cookies "AuthSession" :value])]
      (let [old-user
            (:body (http/get (str "http://localhost:5984/_users/org.couchdb.user:" username)
                             {:as :json
                              :headers {"Cookie" (str "AuthSession=" cookie-value)}}))
            new-user (as-> old-user $
                       (assoc $ :password (:pass params)))]
        ;; change the password and then re-authenticate since the old cookie is no longer considered valid by CouchDB
        (let [change-resp
              (http/put (str "http://localhost:5984/_users/org.couchdb.user:" username)
                        {:as :json
                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                         :content-type :json
                         :form-params new-user})
              new-login (http/post "http://localhost:5984/_session"
                                   {:as :json
                                    :content-type :json
                                    :form-params {:name     username
                                                  :password (:pass params)}})]
          (assoc 
           (json-response true)
           :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies new-login)) ;; set the CouchDB cookie on the ring response
           ))
        ))
    (catch Exception e
      (json-response false))))

(defn logout-handler [req username]
  (try
    (let [resp (http/delete "http://localhost:5984/_session" {:as :json})]
      (assoc 
       (json-response {:logged-out true})
       :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies resp)) ;; set the CouchDB cookie on the ring response
       )
      )
    (catch Exception e
      (json-response {:logged-out false}))))

(defn get-session-handler [req]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])
        resp (http/get "http://localhost:5984/_session" {:as :json
                                                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                                                         :content-type :json
                                                         })
        ]
    (json-response {:body (:body resp)})))

(defn search-text-handler [req username]
  (let [params (get-body req)
        cookie-value (get-in req [:cookies "AuthSession" :value])]
    (let [resp (http/post
                "http://localhost:5984/video-note-taker/_find"
                {:as :json
                 :content-type :json
                 :headers {"Cookie" (str "AuthSession=" cookie-value)}
                 :form-params
                 {"selector"
                  {"$and" [{"type"
                            {"$eq" "note"}}
                           {"users"
                            {"$elemMatch"
                             {"$eq" username}}},
                           {"text"
                            {"$regex" (construct-search-regex (:text params) true)}}]}
                  "execution_stats" true}
                 })]
      (println "search stats for " (:text params)  " : "(get-in resp [:body :execution_stats]))
      (json-response (assoc (:body resp)
                            :search-string (:text params))))))

(defn user-has-access-to-video [username video]
  (not (empty? (filter #(= username %) (:users video)))))

(defn update-video-permissions-handler [req username]
  (try
    (let [video (get-body req)
          current-video (get-doc (:_id video))]
      ;; make sure that the user is already on the document
      (if (user-has-access-to-video username current-video)
        (do
          ;; update the document
          (let [updated-video (couch/put-document db video)]
            ;; now update the denormalized user permissions stored on the notes
            (couch/bulk-update
             db
             (vec (map
                   (fn [view-result]
                     (let [v (:doc view-result)]
                       (println "updating" v)
                       (assoc v :users (:users video))))
                   (get-notes (:_id video)))))
            ;; TODO the bulk update could fail to update certain notes.
            ;; They will still appear in below the video, but won't show up
            ;; in the notes search. I should either handle them here or create a search
            ;; to validate the denormalized 'index' :users field on notes.
            (json-response updated-video)))
        (not-authorized-response))
      )
    (catch Exception e
      (println "update-video-permissions-handler e: " e))))

(defn get-connected-users-handler [req username]
  ;; TODO implement an actual connected-users concept -- right now this returns all users.
  (let [resp (couchdb-request :get (url/url db "/_users/_all_docs"))]
    (->> (map (fn [row] (second (re-matches #"org\.couchdb\.user\:(.*)" (:id row))))
              (:rows resp))
         (remove nil?)
         (json-response))))

(defn videos-handler [req username]
  (let [video-id (second (re-matches #"/videos/(.*)\..*" (:uri req)))
        video (get-doc video-id)
        filename (:file-name video)]
    (if (user-has-access-to-video username video)
      (file-response filename {:root "resources/private/"})
      (not-authorized-response))))

(def api-routes
  ["/" [[["videos/" :id]  (wrap-cookie-auth videos-handler)]
        ["get-doc" (wrap-cookie-auth get-doc-handler)]
        ["put-doc" (wrap-cookie-auth put-doc-handler)]
        ["get-notes" (wrap-cookie-auth get-notes-handler)]
        ["create-note" (wrap-cookie-auth create-note-handler)]
        ["delete-doc" (wrap-cookie-auth delete-doc-handler)]
        ["get-video-listing" (wrap-cookie-auth get-video-listing-handler)]
        ["download-starter-spreadsheet" (wrap-cookie-auth download-starter-spreadsheet)]
        ["get-notes-spreadsheet" (wrap-cookie-auth get-notes-spreadsheet-handler)]
        ["upload-spreadsheet" (wrap-cookie-auth upload-spreadsheet-handler)]
        ["upload-video" (wrap-cookie-auth upload-video-handler)]
        ["get-upload-progress" (wrap-cookie-auth get-upload-progress)]
        ["delete-video" (wrap-cookie-auth delete-video-handler)]
        ["get-cookie" get-cookie-handler]
        ["get-session" get-session-handler]
        ["login" login-handler]
        ["create-user" (wrap-cookie-auth create-user-handler)]
        ["change-password" (wrap-cookie-auth change-password-handler)]
        ["logout" (wrap-cookie-auth logout-handler)]
        ["cookie-check" cookie-check-handler]
        ["search-text" (wrap-cookie-auth search-text-handler)]
        ["update-video-permissions" (wrap-cookie-auth update-video-permissions-handler)]
        ["get-connected-users" (wrap-cookie-auth get-connected-users-handler)]
        ]])

(defn wrap-index
  "Serves up index.html for the '/' route."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (if resp
        resp
        (if (= (:uri req) "/")
          (content-type (file-response "index.html" {:root "resources/public"}) "text/html")
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
      (println caption req)
      resp)))

(def app
  (-> (make-handler api-routes)
      (wrap-index)
      (wrap-file "resources/public" {:prefer-handler? true})
      (wrap-content-type)
      (wrap-params)
      (wrap-cookies)
      (wrap-partial-content)
      (wrap-multipart-params {:progress-fn upload-progress-fn})
      (wrap-cors
       :access-control-allow-origin [#".*"]
       :access-control-allow-methods [:get :put :post :delete]
       :access-control-allow-credentials ["true"]
       :access-control-allow-headers ["X-Requested-With","Content-Type","Cache-Control"])))

(defn -main [& args]
  (let [http-port (try (Integer/parseInt (first args))
                       (catch Exception ex
                         80))
        https-port (try (Integer/parseInt (second args))
                       (catch Exception ex
                         443))
        use-ssl? (.exists (io/file "./keystore"))]
    (println http-port " " https-port)
    (println "use-ssl? " use-ssl?)
    (run-jetty
     app
     (merge {:port http-port
             }
            (if use-ssl?
              {:ssl? true
               :ssl-port https-port
               :keystore "./keystore"
               :key-password (or (System/getenv "VNT_KEYSTORE_PASSWORD") "storep")}
              {})))))
