(ns video-note-taker.core
  (:require
   [clojure.test :refer [deftest is]]
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
   [ring.middleware.cookies :refer [wrap-cookies]]
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
   [clj-time.format :as format]
   [clojure.data.csv :refer [read-csv write-csv]])
  (:gen-class))

(def db (assoc (cemerick.url/url "http://localhost:5984/video-note-taker")
               :username "admin"
               :password "test"         ; TODO add real credential handling
               ))

(defn text-type [v]
  (content-type v "text/html"))

(defn json-type [v]
  (content-type v "application/json"))

(defn hello-handler [req]
  (text-type (response/response "hello")))

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

(defn put-doc-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (do
      (let [doc (get-body req)]
        (json-response (couch/put-document db doc))))))

(defn get-doc [id]
  (couch/get-document db id))

(defn get-doc-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (let [doc (get-body req)]
      (json-response (get-doc (:_id doc))))))

;; notes -> by_video
;; function(doc) {
;;   if ('video' in doc) {
;;       emit(doc.video, doc._id );
;;   }
;; }

(defn get-notes [video-key]
  (println "using key " video-key)
  (couch/get-view db "notes" "by_video" {:key video-key :include_docs true}))

(defn get-notes-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (let [doc (get-body req)]
      (json-response (get-notes (:video-key doc))))))

(defn delete-doc-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (let [doc (get-body req)]
      (json-response (couch/delete-document db doc)))))

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

(defn get-video-listing-handler [req]
  (let [cookie-check-val (cookie-check-from-req req)]
    (if (not cookie-check-val)
      (not-authorized-response)
      (do (let [username (get-in cookie-check-val [0 :name])
                videos   (couch/get-view db "videos" "by_user"
                                         {:key username :include_docs true})]
            (json-response (vec (map :doc videos))))))))

(defn get-notes-spreadsheet [video-src]
  nil)

(defn escape-csv-field [s]
  (str "\"" (clojure.string/escape s {\" "\"\""}) "\""))

(defn get-notes-spreadsheet-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
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
        (content-type $ "text/csv")))))

(defn import-note-spreadsheet-line [notes-by-video failed-imports video time-in-seconds note-text line]
    ;; if the video's notes haven't been loaded into our cache, go ahead and load them in
    (when (not (get-in @notes-by-video [video])) 
      (as-> (get-notes video) $
        (map (fn [{{time :time} :doc}]
               time) ; grab the time associated with the note returned by the view
             $)
        (swap! notes-by-video assoc video $)
        (println "Updated notes-by-video to: " @notes-by-video)))
    (if (empty? (filter #(< (Math/abs (- time-in-seconds %)) 1)
                        (get-in @notes-by-video [video])))
      (do (couch/put-document db {:type "note" :video video :time time-in-seconds :text note-text})
          (swap! notes-by-video #(assoc % video
                                        (conj (get % video) time-in-seconds)))
          (println "Updated2 notes-by-video to: " @notes-by-video))
      (swap! failed-imports conj {:line line :reason "A note within one second of that timestamp already exists."})))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-spreadsheet-handler" -F file=@my-spreadsheet.csv
(defn upload-spreadsheet-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (do
      (println "upload-spreadsheet-handler req: " req)
      (let [id (uuid/to-string (uuid/v4))
            notes-by-video (atom {})
            failed-imports (atom [])
            lines (read-csv (io/reader (get-in req [:params "file" :tempfile])))]
        (println "lines: " (type lines) (count lines) lines)
        ;; (io/copy (get-in req [:params "file" :tempfile])
        ;;          (io/file (str "./uploads/" id)))
        (doall (map (fn [[video time-in-seconds note-text :as line]]
                      (let [time-in-seconds (edn/read-string time-in-seconds)]
                                        ;                    (println "type: " time-in-seconds (type time-in-seconds))
                        (if (number? time-in-seconds) ; filter out the title row, if present
                          (import-note-spreadsheet-line notes-by-video failed-imports video time-in-seconds note-text line)
                          (swap! failed-imports conj {:line line :reason "time-in-seconds not a number"}))))
                    lines))
        (json-response {:didnt-import @failed-imports})))))

;; to test this via cURL, do something like:
;; curl -X POST "http://localhost:3000/upload-video-handler" -F file=@my-video.mp4
(defn upload-video-handler [req]
  (let [cookie-check-val (cookie-check-from-req req)]
    (if (not cookie-check-val)
      (not-authorized-response)
      (do
        (println (get-in req [:params]))
        (let [id (uuid/to-string (uuid/v4))
              user     (get-in cookie-check-val [0 :name])
              filename (get-in req [:params "file" :filename])
              file-ext (last (clojure.string/split filename #"\."))
              tempfile (get-in req [:params "file" :tempfile])
              new-short-filename (str id "." file-ext)]
          (println "filename: " filename)
          (println "cookie-check-val: " cookie-check-val)
          (println "user " user)
          ;; copy the file over -- it's going to get renamed to a uuid to avoid conflicts
          (io/copy (get-in req [:params "file" :tempfile])
                   (io/file (str "./resources/public/videos/" new-short-filename)))
          ;; put some video metadata into Couch
          (let [video-doc (couch/put-document db {:_id id
                                                  :type "video"
                                                  :display-name filename
                                                  :file-name new-short-filename
                                                  :users [user]
                                                  :uploaded-by user
                                                  :uploaded-datetime (.toString (new java.util.Date))})]
            (json-response video-doc))
          )))))

(defn get-cookie-handler [req]
  (try
    (let [params (get-body req)
          resp (http/post "http://localhost:5984/_session" {:as :json
                                                            :content-type :json
                                                            :form-params {:name (:user params)
                                                                          :password (:pass params)}})]

      (println params (type params))
      (println resp)
      (println (get-in resp [:body :ok]))
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
       :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies resp)) ;; set the CouchDB cookie on the ring response
       ))
    (catch Exception e
      (json-response false))))

(defn create-user-handler [req]
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

(defn change-password-handler [req]
  (let [cookie-check-val (cookie-check-from-req req)]
    (if (not cookie-check-val)
      (not-authorized-response)
      (try
        (let [params (get-body req)
              username (get-in cookie-check-val [0 :name])
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
          (json-response false))))))

(defn logout-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (try
      (println "logout-handler")
      (let [resp   (http/delete "http://localhost:5984/_session" {:as :json})]
        (println "resp: " resp)
        (assoc 
         (json-response {:logged-out true})
         :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies resp)) ;; set the CouchDB cookie on the ring response
         )
        )
      (catch Exception e
        (println "Logout exception e" e)
        (json-response {:logged-out false})))))

(defn get-session-handler [req]
  (let [;params (get-body req)
        cookie-value (get-in req [:cookies "AuthSession" :value])
        resp (http/get "http://localhost:5984/_session" {:as :json
                                                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                                                         :content-type :json
                                                         })
        ]
    (println cookie-value)
    (println resp)
    ;; (println params)
    ;; (println req)

    (json-response {:body (:body resp)})))

(defn construct-search-regex [text]
  (str ".*["
       (clojure.string/upper-case (first text))
       (clojure.string/lower-case (first text))
       "]"
       (subs text 1 (count text)) ; drop the first letter
       ".*"))

;; (deftest test-construct-search-regex
;;   (is (= (construct-search-regex "bravo") ".*[Bb]ravo")))

(defn search-text-handler [req]
  (let [cookie-check-val  (cookie-check-from-req req)]
    (if (not cookie-check-val)
      (not-authorized-response)
      (let [username (get-in cookie-check-val [0 :name])
            params (get-body req)
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
                                {"$regex" (construct-search-regex (:text params))}}]}
                      "execution_stats" true}
                     })]
          (println "stats for " (:text params)  " : "(get-in resp [:body :execution_stats]))
          (json-response (assoc (:body resp)
                                :search-string (:text params))))))))

(defn user-has-access-to-video [username video]
  (not (empty? (filter #(= username %) (:users video)))))

(defn update-video-permissions-handler [req]
  (let [cookie-check-val  (cookie-check-from-req req)]
    (if (not cookie-check-val)
      (not-authorized-response)
      (try
        (let [username (get-in cookie-check-val [0 :name])
              video (get-body req)
              current-video (get-doc (:_id video))]
          ;; make sure that the user is already on the document
          (println "current-video: " current-video)
          (if (user-has-access-to-video username current-video)
            (do
              (println "new video: " video)
              ;; update the document
              (let [updated-video (couch/put-document db video)]
                (println "updated video: " updated-video)
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
          (println "update-video-permissions-handler e: " e))))))

(defn get-connected-users-handler [req]
  (let [cookie-check-val  (cookie-check-from-req req)]
    (if (not cookie-check-val)
      (not-authorized-response)
      ;; TODO actually filter -- right now this just loads all suers
      (let [username (get-in cookie-check-val [0 :name])]
        (let [resp (couchdb-request :get (url/url db "/_users/_all_docs"))]
          (println "get-connected-users: " resp)
          (->> (map (fn [row] (second (re-matches #"org\.couchdb\.user\:(.*)" (:id row))))
                    (:rows resp))
               (remove nil?)
               (json-response)))))))

(def api-routes
  ["/" [["hello" hello-handler]
        ["get-doc" get-doc-handler]
        ["put-doc" put-doc-handler]
        ["get-notes" get-notes-handler]
        ["delete-doc" delete-doc-handler]
        ["get-video-listing" get-video-listing-handler]
        ["get-notes-spreadsheet" get-notes-spreadsheet-handler]
        ["upload-spreadsheet" upload-spreadsheet-handler]
        ["upload-video" upload-video-handler]
        ["get-cookie" get-cookie-handler]
        ["get-session" get-session-handler]
        ["login" login-handler]
        ["create-user" create-user-handler]
        ["change-password" change-password-handler]
        ["logout" logout-handler]
        ["cookie-check" cookie-check-handler]
        ["search-text" search-text-handler]
        ["update-video-permissions" update-video-permissions-handler]
        ["get-connected-users" get-connected-users-handler]
        [true (fn [req] (content-type (response/response "<h1>Default Page</h1>") "text/html"))]]])

(def app
  (-> (make-handler api-routes)
      (wrap-file "resources/public")
      (wrap-content-type)
      (wrap-params)
      (wrap-multipart-params)
      (wrap-cookies)
      (wrap-cors
       :access-control-allow-origin [#".*"]
       :access-control-allow-methods [:get :put :post :delete]
       :access-control-allow-credentials ["true"]
       :access-control-allow-headers ["X-Requested-With","Content-Type","Cache-Control"])))

(defn -main [& args]
  (let [port (try (Integer/parseInt (first args))
                  (catch Exception ex
                    3000))]
    (println port)
    (run-jetty app {:port port})))
