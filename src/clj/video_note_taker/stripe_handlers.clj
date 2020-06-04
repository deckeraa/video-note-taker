(ns video-note-taker.stripe-handlers
  (:require
   [clojure.data.json :as json]
   [clojure.walk :refer [keywordize-keys]]
   [ring.util.request :as request]
   [ring.util.json-response :refer [json-response]]
   [clj-stripe.common :as common]
   [clj-stripe.checkouts :as checkouts]
   [com.stronganchortech.couchdb-auth-for-ring :as auth :refer [wrap-cookie-auth]]
   [video-note-taker.util :refer [get-body not-authorized-response]]
   [video-note-taker.db :as db :refer [users-db]]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]])
  (:import
   com.stripe.net.ApiResource))

(defonce temp-users-db (atom {}))

(defn get-temp-users-handler [req username roles]
  (println "get-temp-users-handler" @temp-users-db)
  (if (contains? (set roles) "_admin")
    (json-response (keys @temp-users-db))
    (not-authorized-response)))

(defn create-checkout-session-handler [req]
  (let [body (get-body req)
        secret-key (System/getenv "STRIPE_SECRET_KEY")
        plan (keyword (:plan body))
        username (:username body)
        password (:password body)]
    (println "Using STRIPE_SECRET_KEY: " secret-key)
    (println "plan: " plan)
    (println "username: " username)
    (println "password: " password)
    (swap! temp-users-db assoc username password)
    (json-response 
     (common/with-token secret-key
       (common/execute
        (checkouts/create-checkout-session
         (if (= :a plan)
           [{:price-id "price_HOOd5cGclxAn2v"           :quantity 1}
            {:price-id "price_1GpxiCBo2Vr1t1SegLl0iU1K" :quantity 1}]
           [{:price-id "price_HOOdnszH3OSHgU"           :quantity 1}])
         "subscription"
         "http://localhost:3450/memories.html"
         "http://localhost:3450/?cancel=true"
         {"username" username}))))))

(defn check-username-handler [req]
  (let [body (get-body req)
        username (:username body)]
    (println "check-username: " body)
    (if (re-matches #"^\w*$" username)
      (let [resp (db/get-doc users-db nil (str "org.couchdb.user:" username) nil nil nil)]
        (if (empty? resp)
          (json-response {:status :available})
          (json-response {:status :taken})))
      (json-response {:status :invalid}))))

(defn hooks [req]
  ;;(println "hooks: " req)
  (let [body-str (request/body-string req)
        body (keywordize-keys (json/read-str body-str))
        sig-header (get-in req [:headers "stripe-signature"])
        type (get body :type)]
    (println "hooks type:" type)
    
    (if (= type "checkout.session.completed")
      (do
        (println "hooks body: " body)
        (println "sig-header: " sig-header)
;;        (println "extracted username: " )
        (try
          (println "Checking signature")
          (com.stripe.net.Webhook/constructEvent body-str (str sig-header) (System/getenv "STRIPE_SIGNING_SECRET"))
          (println "Signature check passed!")
          (println "temp-users-db: " @temp-users-db)
          (let [username (get-in body [:data :object :metadata :username])
                password (get-in @temp-users-db [username])]
            (println "username, password: " username password)
            (if (auth/create-user username password)
              (do
                (swap! temp-users-db dissoc username)
                (json-response {:status "Web hook succeeded!"}))
              (do
                (error "Couldn't create user: " username)
                (json-response {:status "Couldn't create user."}))))
          (catch com.stripe.exception.SignatureVerificationException e
            (println "The signature was bad.")
            {:status 500 :body "Bad request"})))
      (json-response {:status "Web hook not needed."}))))
