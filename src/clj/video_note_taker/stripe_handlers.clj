(ns video-note-taker.stripe-handlers
  (:require
   [clojure.data.json :as json]
   [clojure.walk :refer [keywordize-keys]]
   [ring.util.request :as request]
   [ring.util.json-response :refer [json-response]]
   [clj-stripe.common :as common]
   [clj-stripe.checkouts :as checkouts]
   [clj-stripe.subscriptions :as subscriptions]
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

(defn get-endpoint
  "Example output: http://localhost:3000/memories"
  [req endpoint]
  (str (name (:scheme req))
       "://"
       (get-in req [:headers "host"])
       "/"
       endpoint))

(defn get-stripe-secret-key []
  (let [stripe-mode (System/getenv "STRIPE_MODE")
        secret-key  (if (= "live" stripe-mode)
                     (System/getenv "STRIPE_SECRET_KEY_LIVE")
                     (System/getenv "STRIPE_SECRET_KEY_TEST"))]
    secret-key))

(defn price-lookup [plan]
  (case plan
    :a "price_HOOd5cGclxAn2v"
    :b "price_HOOdnszH3OSHgU"))

(defn create-checkout-session-handler [req]
  (let [body (get-body req)
        stripe-mode (System/getenv "STRIPE_MODE")
        secret-key (if (= "live" stripe-mode)
                     (System/getenv "STRIPE_SECRET_KEY_LIVE")
                     (System/getenv "STRIPE_SECRET_KEY_TEST"))
        plan (keyword (:plan body))
        username (:username body)
        password (:password body)]
    (println "In STRIPE_MODE: " stripe-mode)
    (println "Using STRIPE_SECRET_KEY: " secret-key)
    (println "plan: " plan)
    (println "username: " username)
    (println "password: " password)
    (println "success-url: " (get-endpoint req "memories"))
    (swap! temp-users-db assoc username password)
    (if stripe-mode
      (json-response 
       (common/with-token secret-key
         (common/execute
          (checkouts/create-checkout-session
           (if (= :a plan)
             [{:price-id "price_HOOd5cGclxAn2v"           :quantity 1}
              {:price-id "price_1GpxiCBo2Vr1t1SegLl0iU1K" :quantity 1}]
             [{:price-id "price_HOOdnszH3OSHgU"           :quantity 1}])
           "subscription"
           (get-endpoint req "memories")
           (get-endpoint req "?cancel=true")
           {"username" username}))))
      (json-response {:status "failed" :reason "STRIPE_MODE is not set."}))))

(defn get-user-doc [username]
  (db/get-doc users-db nil (str "org.couchdb.user:" username) nil nil nil))

(defn check-username-handler [req]
  (let [body (get-body req)
        username (:username body)]
    (println "check-username: " body)
    (if (re-matches #"^\w*$" username)
      (let [resp (get-user-doc username)]
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
          (com.stripe.net.Webhook/constructEvent
           body-str (str sig-header)
           (if (= "live" (System/getenv "STRIPE_MODE"))
             (System/getenv "STRIPE_WEBHOOK_SIGNING_SECRET_LIVE")
             (System/getenv "STRIPE_WEBHOOK_SIGNING_SECRET_TEST")))
          (println "Signature check passed!")
          (println "temp-users-db: " @temp-users-db)
          (let [data-object (get-in body [:data :object])
                metadata    (get-in body [:data :object :metadata])
                username    (get-in metadata [:username])
                password    (get-in @temp-users-db [username])]
            (println "username, password: " username password)
            (if (auth/create-user
                 username password ["family_lead"]
                 (merge
                  (select-keys data-object [:customer :subscription])
                  {:gb-limit 50}))
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

(defn add-subscription-handler [req username roles]
  (let [user (get-user-doc username)
        body (get-body req)
        price (price-lookup (keyword (:plan body)))
        stripe-resp
        ;(json/read-str)
        (common/with-token (get-stripe-secret-key)
          (common/execute (subscriptions/subscribe-customer
                           (common/plan price)
                           (common/customer (:customer user))
                           (subscriptions/do-not-prorate))))
        cancel-success? (nil? (get stripe-resp "error"))]
    (warn price)
    (warn cancel-success?)
    (warn stripe-resp)
    (json-response "foo")))

(defn cancel-subscription-handler [req username roles]
  (let [user (get-user-doc username)]
    (let [stripe-resp
          (json/read-str
           (common/with-token (get-stripe-secret-key)
             (common/execute (subscriptions/unsubscribe-customer
                              (common/customer (:customer user))
                              (subscriptions/immediately)))))
          cancel-success? (nil? (get stripe-resp "error"))]
      (warn (get stripe-resp "error"))
      ;; Set their upload limit to 0GB. This will leave existing videos in place. Those will need cleaned up manually.
      (when cancel-success?
        (db/put-doc users-db nil (assoc user :gb-limit 0) nil nil))
      (json-response cancel-success?))))

(defn- get-subscription-item [subscription]
  (let [items (get-in subscription [:items :data])
        item  (first (filter (fn [item]
                               (contains?
                                #{"price_HOOd5cGclxAn2v" "price_HOOdnszH3OSHgU"}
                                (get-in item [:plan :id])))
                             items))]
    item))

(defn update-quantity [subscription-id item-id quantity]
  (common/with-token (get-stripe-secret-key)
    (common/execute
     (subscriptions/set-subscription-items
      subscription-id
      {"items[0][id]" item-id
       "items[0][quantity]" quantity}))))

(defn modify-subscription-quantity [mutate-fn subscription-id]
  (let [item (get-subscription-item
              (common/with-token (get-stripe-secret-key)
                (common/execute
                 (subscriptions/get-subscription subscription-id))))]
    (update-quantity subscription-id (:id item) (mutate-fn (:quantity item)))))

(def inc-subscription-quantity (partial modify-subscription-quantity inc))
(def dec-subscription-quantity (partial modify-subscription-quantity dec))
