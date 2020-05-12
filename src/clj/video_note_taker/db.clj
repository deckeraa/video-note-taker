(ns video-note-taker.db
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.walk :refer [keywordize-keys]]
            [cemerick.url :as url]
            [ring.util.request :as request]
            [ring.util.response :as response]
            [ring.util.json-response :refer [json-response]]
            [clj-http.client :as http]
            [com.ashafa.clutch :as couch]
            [com.ashafa.clutch.utils :as utils]
            [com.ashafa.clutch.http-client :refer [couchdb-request]]))

(def couch-url "http://localhost:5984/video-note-taker")

(def db
  (let [password (System/getenv "VNT_DB_PASSWORD")]
    {:url couch-url
     :username "admin"
     :password (or password "test")}))

(defn get-body [req]
  (-> req
      (request/body-string)
      (json/read-str)
      (keywordize-keys)))

(defn get-auth-cookie [req]
  (get-in req [:cookies "AuthSession" :value]))

(defn couch-request
  ([db method endpoint form-params]
   (couch-request db method endpoint form-params {}))
  ([db method endpoint form-params http-options]
   (couch-request db method endpoint form-params http-options nil))
  ([db method endpoint form-params http-options auth-cookie]
   (:body ((case method
             :get clj-http.client/get
             :delete clj-http.client/delete
             clj-http.client/post)
           (str (:url db) "/" endpoint)
           (merge
            {:as :json
             :content-type :json
             :form-params form-params
             }
            (if auth-cookie
                                        ; if we have a user cookie, use the user cookie
              {:headers {"Cookie" (str "AuthSession=" auth-cookie)}}
                                        ; otherwise, run as admin
              {:basic-auth [(:username db) (:password db)]})
            http-options)))))

(defn put-doc
  ([db put-hook-fn doc username roles]
   (put-doc db put-hook-fn doc username roles nil))
  ([db put-hook-fn doc username roles auth-cookie]
   (let [audited-doc
         (if put-hook-fn
           (put-hook-fn doc username roles)
           doc)
         couch-resp
         (couch-request db :post "" audited-doc {} auth-cookie)]
     (when (not (:ok couch-resp))
       (println "Putting " couch-resp " failed: " couch-resp "."))
     ;; couch-resp will be in the form {:ok true, :id ..., :rev ...}
     ;; Thus we need to lookup the created doc
     (couch-request db :get (:id couch-resp) {} {} auth-cookie))))

(defn put-doc-handler [db put-hook-fn req username roles]
  (let [doc (get-body req)
        cookie (get-auth-cookie req)]
    (json-response (put-doc db put-hook-fn doc username roles cookie))))

(defn delete-doc [db delete-hook-fn doc username roles auth-cookie]
  (if (and (:_id doc) (delete-hook-fn doc username roles))
    (couch-request db :delete (:_id doc) nil {:query-params {:rev (:_rev doc)}} auth-cookie)
    false))

(defn delete-doc-handler [db delete-hook-fn req username roles]
  (let [doc (get-body req)
        auth-cookie (get-auth-cookie req)
        resp (delete-doc db delete-hook-fn doc username roles auth-cookie)]x
    (json-response resp)))

(defn run-mango-query [query auth-cookie]
  (let [results (couch-request db :post "_find" query {} auth-cookie)]
    (println "search stats for " query  " : "(get-in results [:execution_stats]))
    results))

(defn install-views [db req username roles]
  (if (contains? (set roles) "_admin")
    (do
      (try
        (put-doc db (fn [doc _ _] doc)
                 {:_id "_design/videos"
                  :views
                  {:by_user
                   {:map "function (doc) {
                      if(doc.type === \"video\") {
                         for(var idx in doc.users) {
                            emit(doc.users[idx],doc._id);
                         }
                      }
                    }"}}
                  :language "javascript"}
                 username roles)
        (catch Exception e
          (println "Didn't install _design/videos")))
      (try
        (put-doc db (fn [doc _ _] doc)
                 {:_id "_design/groups"
                  :views
                  {:by_user
                   {:map "function (doc) {
                      if(doc.type === \"group\") {
                         emit(doc[\"created-by\"],doc._id);
                         for(var idx in doc.users) {
                            emit(doc.users[idx],doc._id);
                         }
                      }
                    }"}}
                  :language "javascript"}
                 username roles)
        (catch Exception e
          (println "Didn't install _design/groups")))
      (try
        (put-doc db (fn [doc _ _] doc)
                 {:_id "_design/notes"
                  :views
                  {:by_video
                   {:map "function (doc) {
                      if(doc.type === \"note\" && \"video\" in doc) {
                         emit(doc.video, doc._id);
                      }
                    }"}}
                  :language "javascript"}
                 username roles)
        (catch Exception e
          (println "Didn't install _design/notes")))
      (json-response true))
    (json-response false)))
