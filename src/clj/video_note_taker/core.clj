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
   [video-note-taker.db :as db]
   [video-note-taker.search-shared :as search-shared :refer [construct-search-regex]]
   [video-note-taker.upload-progress :as upload-progress]
   [video-note-taker.auth :as auth :refer [wrap-cookie-auth]]
   [video-note-taker.groups :as groups]
   [video-note-taker.access :as access])
  (:gen-class))

(def couch-url "http://localhost:5984/video-note-taker")

(def db
  (let [password (System/getenv "VNT_DB_PASSWORD")]
    {:url couch-url
     :username "admin"
     :password (or password "test")}))

(def users-url "http://localhost:5984/_users")
(def users-db
  (let [password (System/getenv "VNT_DB_PASSWORD")]
    {:url users-url
     :username "admin"
     :password (or password "test")}))

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
  (vec (map :id (db/get-view db nil "groups" "by_user" {:key username} nil nil nil))))

(defn get-video-listing-handler [req username roles]
  (let [groups (load-groups-for-user username)
        query {"selector"
               {"$and" [{"type"
                         {"$eq" "video"}}
                        {"$or"
                         [{"users"
                           {"$elemMatch"
                            {"$eq" username}}}
                          {"groups" ;; TODO $in yields true when groups is empty, but only on CouchDB 3.0.0? More investigation needed.
                           {"$elemMatch"
                            {"$in" groups}}}]}]}
               "execution_stats" true}
        videos (db/run-mango-query query (db/get-auth-cookie req))]
    (json-response (vec (:docs videos)))))

(defn escape-csv-field [s]
  (if s ;; null check to prevent java.lang.NullPointerException from clojure.string/escape
    (str "\"" (clojure.string/escape s {\" "\"\""}) "\"")
    nil))

(defn get-notes-spreadsheet-handler [req username roles]
  (let [query-map (keywordize-keys (codec/form-decode (:query-string req)))
        notes     (db/get-view db access/get-hook-fn "notes" "by_video"
                               {:key (:video-id query-map) :include_docs true}
                               username roles (db/get-auth-cookie req))
        video     (get-doc (:video-id query-map) username roles (db/get-auth-cookie req))]
    (as-> notes $
      (sort-by :time $) ; sort
      (map (fn [note]
             (str (escape-csv-field (:video note)) ","
                  (escape-csv-field (:display-name video)) ","
                  (float (/ (Math/round (float (* 100 (:time note)))) 100)) ","
                  (escape-csv-field (:text note))))
           $)
      (conj $ "video key,video display name,time in seconds,note text")
      (clojure.string/join "\n" $)
      (response/response $)
      (content-type $ "text/csv"))))

(defn import-note-spreadsheet-line [notes-by-video success-imports-counter failed-imports video-key video-display-name time-in-seconds note-text line username roles auth-cookie]
    ;; if the video's notes haven't been loaded into our cache, go ahead and load them in
  (when (not (get-in @notes-by-video [video-key])) 
    (as-> (get-notes video-key) $
      (map (fn [{{time :time} :doc}]
             time) ; grab the time associated with the note returned by the view
           $)
      (swap! notes-by-video assoc video-key $)))
  (let [video (get-doc video-key username roles auth-cookie)]
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
          (do (db/put-doc db access/put-hook-fn
                          {:type "note"
                           :video video-key
                           :video-display-name (:display-name video)
                           :time time-in-seconds
                           :text note-text
                           :users (:users video)
                           :created-by username}
                          username roles auth-cookie)
              (swap! notes-by-video #(assoc % video-key
                                            (conj (get % video-key) time-in-seconds)))
              (swap! success-imports-counter inc)
)
          (swap! failed-imports conj {:line line :reason "A note within one second of that timestamp already exists."}))))))

(defn download-starter-spreadsheet [req username roles]
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
      (content-type $ "text/csv"))
    ))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-spreadsheet-handler" -F file=@my-spreadsheet.csv
(defn upload-spreadsheet-handler [req username roles]
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
    (json-response {:didnt-import @failed-imports :successfully-imported @success-imports-counter})))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-video-handler" -F file=@my-video.mp4
(defn upload-video-handler [req username roles]
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
    ;; delete the temp file -- this happens automatically by Ring, but takes an hour, so this frees up space sooner
    (io/delete-file (get-in req [:params "file" :tempfile]))
    ;; put some video metadata into Couch
    (let [video-doc (db/put-doc
                     db access/put-hook-fn
                     {:_id id
                      :type "video"
                      :display-name filename
                      :file-name new-short-filename
                      :users [username]
                      :uploaded-by username
                      :uploaded-datetime (.toString (new java.util.Date))}
                     username
                     roles (db/get-auth-cookie req))]
      (json-response video-doc))))

(defn delete-video-handler [req username roles]
  (let [doc (get-body req) ; the doc should be a video CouchDB document
        video-id (get-in doc [:_id])
        video (get-doc video-id nil nil nil)]
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
        (io/delete-file (str "./resources/private/" (:file-name video)))
        ;; return the response
        (json-response {:success true}))
      )))

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
      (println "search stats for " (:text params)  " : "(get-in resp [:execution_stats]))
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
    (let [video (get-body req)
          current-video (get-doc (:_id video) nil nil nil)
          listed-users (set (:users video))
          group-users (get-users-from-groups req (:groups video) username roles)
          all-users (clojure.set/union listed-users group-users)]
      ;; make sure that the user is already on the document
      (if (access/user-has-access-to-video username current-video)
        (do
          ;; update the document
          (let [updated-video (db/put-doc db access/put-hook-fn video
                                          username roles (db/get-auth-cookie req))
                affected-notes (get-notes (:_id video) username roles (db/get-auth-cookie req))]
            ;; now update the denormalized user permissions stored on the notes
            (db/bulk-update
             db access/put-hook-fn
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
      (println "update-video-permissions-handler e: " e))))

(defn get-connected-users-handler [req username roles]
  ;; TODO implement an actual connected-users concept -- right now this returns all users.
  (if (contains? (set roles) "_admin")
    ;; admins get all users
    (let [resp (db/couch-request users-db :get "_all_docs" {} {} (db/get-auth-cookie req))]
      (->> (map (fn [row] (second (re-matches #"org\.couchdb\.user\:(.*)" (:id row))))
                (:rows resp))
           (remove nil?)
           (json-response)))
    ;; non-admins get all users that they are in a group with
    (let [groups (db/get-view db access/get-hook-fn "groups" "by_user" {:key username :include_docs true}
                              username roles (db/get-auth-cookie req))
          users (apply clojure.set/union (map :users groups))]
      (println "groups: " groups)
      (println "users: " users)
      (json-response (vec users)))))

(defn videos-handler [req username roles]
  (let [video-id (second (re-matches #"/videos/(.*)\..*" (:uri req)))
        video (get-doc video-id username roles (db/get-auth-cookie req))
        filename (:file-name video)]
    (if (and video filename) ;; get-doc does the acess check for us
      (file-response filename {:root "resources/private/"})
      (not-authorized-response))))

(def api-routes
  ["/" [[["videos/" :id]  (wrap-cookie-auth videos-handler)]
        ["get-doc" (wrap-cookie-auth (partial db/get-doc-handler db access/get-hook-fn))]
        ["bulk-get-doc" (wrap-cookie-auth (partial db/bulk-get-doc-handler db access/get-hook-fn))]
        ["put-doc" (wrap-cookie-auth (partial db/put-doc-handler db access/put-hook-fn))]
        ["get-notes" (wrap-cookie-auth get-notes-handler)]
        ["create-note" (wrap-cookie-auth create-note-handler)]
        ["delete-doc" (wrap-cookie-auth (partial db/delete-doc-handler db access/delete-hook-fn))]
        ["get-video-listing" (wrap-cookie-auth get-video-listing-handler)]
        ["download-starter-spreadsheet" (wrap-cookie-auth download-starter-spreadsheet)]
        ["get-notes-spreadsheet" (wrap-cookie-auth get-notes-spreadsheet-handler)]
        ["upload-spreadsheet" (wrap-cookie-auth upload-spreadsheet-handler)]
        ["upload-video" (wrap-cookie-auth upload-video-handler)]
        ["get-upload-progress" (wrap-cookie-auth upload-progress/get-upload-progress)]
        ["delete-video" (wrap-cookie-auth delete-video-handler)]
        ["login" auth/login-handler]
        ["create-user" (wrap-cookie-auth auth/create-user-handler)]
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

(defn wrap-not-found [handler]
  (fn [req]
    (let [resp (handler req)]
      (if resp
        resp
        (do
          (println "Sending 404 to " (:remote-addr req) " for " (:uri req))
          (response/not-found "Not found"))))))

(def app
  (-> (make-handler api-routes)
      (wrap-index)
      (wrap-file "resources/public" {:prefer-handler? true})
      (wrap-content-type)
      (wrap-not-found)
      (wrap-params)
      (wrap-cookies)
      (wrap-partial-content)
      (wrap-multipart-params {:progress-fn upload-progress/upload-progress-fn})
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
