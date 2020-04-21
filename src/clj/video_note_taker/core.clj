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

(defn get-body [req]
  (-> req
      (request/body-string)
      (json/read-str)
      (keywordize-keys)))

(defn put-doc-handler [req]
  (println req)
  (let [doc (get-body req)]
    (json-response (couch/put-document db doc)))
  )

(defn get-doc [id]
  (couch/get-document db id))

(defn get-doc-handler [req]
  (let [doc (get-body req)]
    (json-response (get-doc (:_id doc)))))

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
  (let [doc (get-body req)]
    (json-response (get-notes (:video-key doc)))))

(defn delete-doc-handler [req]
  (let [doc (get-body req)]
    (println "deleting doc: " doc)
    (json-response (couch/delete-document db doc))))

(defn get-video-listing-handler [req]
  (json-response
   (as-> (shell/with-sh-dir "./resources/public/videos"
           (sh "ls" "-1")) %
     (:out %)
     (clojure.string/split % #"\n")
     )))

(defn get-notes-spreadsheet [video-src]
  nil
  )

(defn escape-csv-field [s]
  (str "\"" (clojure.string/escape s {\" "\"\""}) "\""))

(defn get-notes-spreadsheet-handler [req]
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
      (content-type $ "text/csv"))
    ))

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
    (json-response {:didnt-import @failed-imports})))

(defn- remove-cookie-attrs-not-supported-by-ring
  "CouchDB sends back cookie attributes like :version that ring can't handle. This removes those."
  [cookies]
  ;; CouchDB sends a cookies map that looks something like
  ;; {AuthSession {:discard false, :expires #inst "2020-04-21T19:51:08.000-00:00", :path /, :secure false, :value YWxwaGE6NUU5RjQ5RkM6MXHV10hKUXVSuaY8GcMOZ2wFfeA, :version 0}}
  (apply merge
         (map (fn [[cookie-name v]]
                (let [v (select-keys v [:value :domain :path :secure :http-only :max-age :same-site :expires])]
                  {cookie-name (update v :expires #(.toString %))} ;; the :expires attr also needs changed frmo a java.util.Date to a string
                  ))
              cookies)))

(defn get-cookie-handler [req]
  (let [params (get-body req)
        resp (http/post "http://localhost:5984/_session" {:as :json
                                                         :content-type :json
                                                         :form-params {:name (:user params)
                                                                       :password (:pass params)}})]
    (println params (type params))
    (let [ring-resp
          (assoc 
           (json-response {:body (:body resp) :cookies (:cookies resp)})
           :cookies (remove-cookie-attrs-not-supported-by-ring (:cookies resp)) ;; set the CouchDB cookie on the ring response
           ;; :cookies {"secret" {:value "foobar", :secure true, :max-age 3600}}
           )]
;      (println ring-resp)
      ring-resp)))

(def api-routes
  ["/" [["hello" hello-handler]
        ["get-doc" get-doc-handler]
        ["put-doc" put-doc-handler]
        ["get-notes" get-notes-handler]
        ["delete-doc" delete-doc-handler]
        ["get-video-listing" get-video-listing-handler]
        ["get-notes-spreadsheet" get-notes-spreadsheet-handler]
        ["upload-spreadsheet" upload-spreadsheet-handler]
        ["get-cookie" get-cookie-handler]
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
