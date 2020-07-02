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

(defn stripe-live? []
  (= "live" (System/getenv "STRIPE_MODE")))

(defn plan-a-one-time-price []
  (if (stripe-live?)
    (System/getenv "STRIPE_PLAN_A_ONE_TIME_PRICE_LIVE")
    (System/getenv "STRIPE_PLAN_A_ONE_TIME_PRICE_TEST")))

(defn plan-a-recurring-price []
  (if (stripe-live?)
    (System/getenv "STRIPE_PLAN_A_RECURRING_PRICE_LIVE")
    (System/getenv "STRIPE_PLAN_A_RECURRING_PRICE_TEST")))

(defn plan-b-recurring-price []
  (if (stripe-live?)
    (System/getenv "STRIPE_PLAN_B_RECURRING_PRICE_LIVE")
    (System/getenv "STRIPE_PLAN_B_RECURRING_PRICE_TEST")))

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

(defn get-stripe-public-key []
  (let [secret-key  (if (stripe-live?)
                      (System/getenv "STRIPE_PUBLIC_KEY_LIVE")
                      (System/getenv "STRIPE_PUBLIC_KEY_TEST"))]
    secret-key))

(defn get-stripe-secret-key []
  (let [secret-key  (if (stripe-live?)
                      (System/getenv "STRIPE_SECRET_KEY_LIVE")
                      (System/getenv "STRIPE_SECRET_KEY_TEST"))]
    secret-key))

(defn coupon-id-from-coupon-code [coupon-code]
  (let [coupons (clojure.edn/read-string (System/getenv "VNT_COUPONS"))
        coupon-code (if (string? coupon-code)
                      (clojure.string/lower-case coupon-code)
                      nil)]
    (when (map? coupons)
          (get coupons coupon-code))))

(defn create-checkout-session-handler [req]
  (let [body (get-body req)
        stripe-mode (stripe-live?)
        secret-key (get-stripe-secret-key)
        plan (keyword (:plan body))
        username (:username body)
        password (:password body)
        coupon-code (:coupon body)]
    (println "In STRIPE_MODE: " stripe-mode)
    (println "Using STRIPE_SECRET_KEY: " secret-key)
    (println "plan: " plan)
    (println "username: " username)
    (println "password: " password)
    (println "success-url: " (get-endpoint req "memories"))
    (println "plan-a-recurring-price: " (plan-a-recurring-price))
    (println "plan-a-one-time-price: " (plan-a-one-time-price))
    (swap! temp-users-db assoc username password)
    (if (nil? stripe-mode)
      (json-response {:status "failed" :reason "STRIPE_MODE is not set."})
      (json-response
       (common/with-token secret-key
         (common/execute
          (checkouts/create-checkout-session
           (if (= :a plan)
             [{:price-id (plan-a-recurring-price) :quantity 1}
              {:price-id (plan-a-one-time-price)  :quantity 1}]
             [{:price-id (plan-b-recurring-price) :quantity 1}])
           "subscription"
           (get-endpoint req "memories")
           (get-endpoint req "?cancel=true")
           {"username" username}
           (coupon-id-from-coupon-code coupon-code)
           ))))
      )))

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
           (if (stripe-live?)
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
                  {:gb-limit 50
                   :user-limit 15}))
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
        (db/put-doc users-db nil (merge user {:gb-limit 0 :user-limit 0}) nil nil))
      (json-response cancel-success?))))

(defn- get-subscription-item [subscription]
  (let [items (get-in subscription [:items :data])
        item  (first (filter (fn [item]
                               (contains?
                                #{(plan-a-recurring-price) (plan-b-recurring-price)}
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

(defn get-subscription-info [subscription-id]
  (let [subscription (common/with-token (get-stripe-secret-key)
                       (common/execute
                        (subscriptions/get-subscription subscription-id)))
        item (get-subscription-item subscription)]
    (info "subscription: " subscription)
    (info "item: " item)
    (or item {}) ;; sometimes item ends up being nil, so return an empty object
    ))

(defn get-subscription-info-handler [req username roles]
  (let [user (get-user-doc username)]
    ;(json-response user)
    (json-response (get-subscription-info (:subscription user)))))

(defn modify-subscription-quantity [mutate-fn subscription-id]
  (let [subscription (common/with-token (get-stripe-secret-key)
                       (common/execute
                        (subscriptions/get-subscription subscription-id)))
        item (get-subscription-item subscription)]
    (warn "subscription: " subscription)
    (warn "item: " item)
    (update-quantity subscription-id (:id item) (mutate-fn (:quantity item)))))

(def inc-subscription-quantity (partial modify-subscription-quantity inc))
(def dec-subscription-quantity (partial modify-subscription-quantity dec))

(defn inc-subscription-handler [req username roles]
  (let [user (get-user-doc username)]
    (warn "inc-subscription-handler: " user)
    (if (not (:subscription user))
      (json-response {:status "false" :reason ":subscription not present in user record."})
      (let [stripe-resp (inc-subscription-quantity (:subscription user))
            success? (nil? (get stripe-resp "error"))]
        (when success?
          (db/put-doc users-db nil (update (update user :gb-limit + 50)
                                           :user-limit + 15) nil nil))
        (json-response success?)))))

(defn dec-subscription-handler [req username roles]
  (let [user (get-user-doc username)]
    (warn "dec-subscription-handler: " user)
    (if (<= (:gb-limit user) 50)
      (json-response {:status false :reason "Don't have enough GB to remove storage. Use the cancel button to cancel the service all together."})
      (if (not (:subscription user))
        (json-response {:status false :reason ":subcription not present in user record."})
        (let [stripe-resp (dec-subscription-quantity (:subscription user))
              success? (nil? (get stripe-resp "error"))]
          (when success?
            (db/put-doc users-db nil (update (update user :gb-limit - 50)
                                             :user-limit - 15) nil nil))
          (json-response {:status success?}))))))
