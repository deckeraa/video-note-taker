(ns video-note-taker.core
  (:require
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
  (println "checking cookie-value: " cookie-value)
  (let [
        resp (http/get "http://localhost:5984/_session" {:as :json
                                                         :headers {"Cookie" (str "AuthSession=" cookie-value)}
                                                         :content-type :json
                                                         })]
    (println resp)
    (println "cookies: " (:cookies resp))
    (println "processed cookies: " (remove-cookie-attrs-not-supported-by-ring (:cookies resp)))
    (if (nil? (get-in resp [:body :userCtx :name]))
      false
      [(get-in resp [:body :userCtx])
       (remove-cookie-attrs-not-supported-by-ring (:cookies resp))])))

(defn cookie-check-from-req [req]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (cookie-check cookie-value)))

(defn cookie-check-handler [req]
  (println "cookie-check-handler: " (get-in req [:cookies "AuthSession" :value]))
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (if-let [[userCtxt new-cookie] (cookie-check cookie-value)]
      (assoc (json-response userCtxt) :cookies new-cookie)
      (json-response false))))

(defn put-doc-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (do
      (println req)
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
      (println "deleting doc: " doc)
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

;; (defn get-video-listing-handler [req]
;;   (if (not (cookie-check-from-req req))
;;     (not-authorized-response)
;;     (json-response
;;      (as-> (shell/with-sh-dir "./resources/public/videos"
;;              (sh "ls" "-1")) %
;;        (:out %)
;;        (clojure.string/split % #"\n")
;;        ))))

(defn get-video-listing-handler [req]
  (let [cookie-check-val (cookie-check-from-req req)]
    (if (not cookie-check-val)
      (not-authorized-response)
      (do (let [username (get-in cookie-check-val [0 :name])
                videos   (couch/get-view db "videos" "by_user"
                                         {:key username :include_docs true})]
            (json-response videos))))))

(defn get-notes-spreadsheet [video-src]
  nil
  )

(defn escape-csv-field [s]
  (str "\"" (clojure.string/escape s {\" "\"\""}) "\""))

(defn get-notes-spreadsheet-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (let [query-map (keywordize-keys (codec/form-decode (:query-string req)))
          notes     (couch/get-view db "notes" "by_video"
                                    {:key (:video_src query-map) :include_docs true})]
      (as-> notes $
        (map :doc $) ; pull out the docs
        (sort-by :time $) ; sort
        (map (fn [note]
               (str (escape-csv-field (:video note)) ","
                    (float (/ (Math/round (* 100 (:time note))) 100)) ","
                    (escape-csv-field (:text note))))
             $)
        (conj $ "video,time in seconds,note text")
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
              tempfile (get-in req [:params "file" :tempfile])]
          (println "filename: " filename)
          (println "cookie-check-val: " cookie-check-val)
          (println "user " user)
          ;; copy the file over -- it's going to get renamed to a uuid to avoid conflicts
          (io/copy (get-in req [:params "file" :tempfile])
                   (io/file (str "./resources/public/videos/" id "." file-ext)))
          ;; put some video metadata into Couch
          (let [video-doc (couch/put-document db {:_id id
                                                  :type "video"
                                                  :display-name filename
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

      (if (nil? (re-find #"^\w+$" name)) ; sanitize the name
        (assoc (json-response :invalid-user-name) :status 400)
        (let [resp (http/put
                    (str "http://localhost:5984/_users/org.couchdb.user:" name)
                    {:as :json
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
      (assoc (json-response false) :status 400))))

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

(defn search-text-handler [req]
  (if (not (cookie-check-from-req req))
    (not-authorized-response)
    (let [params (get-body req)
          resp (http/post
                "http://localhost:5984/video-note-taker/_find"
                {:as :json
                 :content-type :json
                 :form-params
                 {:selector
                  {:text
                   {"$regex" (str ".*" (:text params) ".*")}}}})]
      (println "search-text resp: " resp)
      (json-response (:body resp)))))

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
        ["logout" logout-handler]
        ["cookie-check" cookie-check-handler]
        ["search-text" search-text-handler]
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
