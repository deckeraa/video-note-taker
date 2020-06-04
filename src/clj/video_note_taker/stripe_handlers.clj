(ns video-note-taker.stripe-handlers
  (:require
   [clojure.data.json :as json]
   [clojure.walk :refer [keywordize-keys]]
   [ring.util.request :as request]
   [ring.util.json-response :refer [json-response]]
   [clj-stripe.common :as common]
   [clj-stripe.checkouts :as checkouts]
   [video-note-taker.util :refer [get-body]]
   [video-note-taker.db :as db :refer [users-db]])
  (:import
   com.stripe.net.ApiResource))

(defn create-checkout-session-handler [req]
  (let [body (get-body req)
        secret-key (System/getenv "STRIPE_SECRET_KEY")
        plan (keyword (:plan body))
        username (:username body)]
    (println "Using STRIPE_SECRET_KEY: " secret-key)
    (println "plan: " plan)
    (println "username: " username)
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
    
    (if (or (= type "checkout.session.completed")
            (= type "payment_intent.succeeded"))
      (do
        (println "hooks body: " body)
        (println "sig-header: " sig-header)
        (println "extracted username: " (get-in body [:data :object :metadata :username]))
        (try
          (println "Checking signature")
          (com.stripe.net.Webhook/constructEvent body-str (str sig-header) (System/getenv "STRIPE_SIGNING_SECRET"))
          (println "Signature check passed!")
          (json-response {:status "Web hook succeeded!"})
          (catch com.stripe.exception.SignatureVerificationException e
            (println "The signature was bad.")
            {:status 500 :body "Bad request"})))
      (json-response {:status "Web hook not needed."}))))
