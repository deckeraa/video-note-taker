(ns video-note-taker.b2b
  (:require
   [clojure.data.json :as json]
   [clojure.walk :refer [keywordize-keys]]
   [ring.util.request :as request]
   [ring.util.json-response :refer [json-response]]
   [com.stronganchortech.couchdb-auth-for-ring :as auth :refer [wrap-cookie-auth]]
   [video-note-taker.util :refer [get-body not-authorized-response]]
   [video-note-taker.db :as db :refer [users-db]]
   [video-note-taker.access :as access]
   [video-note-taker.util :refer [get-body]]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]))

(defn get-in-progress-users-handler [req username roles]
  (println "b2b username: " username)
  (json-response (db/get-view db/users-db access/get-hook-fn "users" "in_progress_end_users_by_business_user" {:key username} nil nil nil)))

(defn get-family-members-of-users [req username roles]
  (let [body (get-body req)
        family-lead (:family-lead body) ;; TODO check that the business user hass access to the family lead
        ]
    (json-response (db/get-view db/users-db access/get-hook-fn "users" "by_creating_user" {:key family-lead} nil nil nil))))
