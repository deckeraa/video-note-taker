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
   [me.raynes.conch :refer [programs with-programs let-programs]]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]))

(programs mail echo) ;; this is a macro that makes mail and echo callable via conch

(defn get-in-progress-users-handler [req username roles]
  (println "b2b username: " username)
  (json-response (db/get-view db/users-db access/get-hook-fn "users" "in_progress_end_users_by_business_user" {:key username :include_docs true} nil nil nil)))

(defn by-business-user-handler [req username roles]
  (json-response (db/get-view db/users-db access/get-hook-fn "users" "by_business_user" {:key username :include_docs true} nil nil nil)))

(defn get-family-members-of-users [req username roles]
  (let [body (get-body req)
        family-lead (:family-lead body) ;; TODO check that the business user hass access to the family lead
        ]
    (json-response (db/get-view db/users-db access/get-hook-fn "users" "by_creating_user" {:key family-lead :include_docs true} nil nil nil))))

(defn gen-password [length]
  (let [usable-letters (vec "0123456789abcdefghijklmnopqrstuvwxyz")        
        ]
    (apply str (take length (repeatedly (fn [] (rand-nth usable-letters)))))))

(defn set-password! [user-doc]
  (let [password (gen-password 10)]
    (db/put-doc users-db nil (assoc user-doc :password password) nil nil)
    password))

(defn load-user [username]
  (db/get-doc
   users-db
   nil
   (str "org.couchdb.user:" username)
   nil nil nil))

(defn set-passwords-and-email-handler [req username roles]
  (let [body (get-body req)
        subject "Link to family videos at FamilyMemoryStream"
        family-lead-username (:username body)
        ;;family-lead-user (load-user family-lead-username) ;; (db/get-doc
                         ;;  users-db
                         ;;  access/get-hook-fn
                         ;;  (str "org.couchdb.user:" family-lead-username)
                         ;;  nil nil nil)
        family-members (set (access/get-connected-users family-lead-username roles))]
    (println "family-members: " family-members)
    (let [passwords-to-email (mapv (fn [username]
                                     (let [user (load-user username)]
                                       (if (empty? user)
                                         (do
                                           (warn "Loaded empty document for user: " username)
                                           nil)
                                         {:name username :email (:email user) :password (set-password! user)})))
                                   (conj family-members family-lead-username))]
      (println "passwords-to-email: " passwords-to-email)
      (doall (map (fn [user]
                    
                    (when (:email user)
                      (do
                        (info "Emailing activation email to " (:email user))
                        (mail "-s" subject (:email user) (echo (str "Your username is:" (:name user) "\nYour password is:" (:password user)) {:seq true})))))
                  passwords-to-email))
      )
    (json-response true)))
