(ns video-note-taker.stripe-handlers
  (:require
   [ring.util.json-response :refer [json-response]]
   [clj-stripe.common :as common]
   [clj-stripe.checkouts :as checkouts]
   [video-note-taker.util :refer [get-body]]
   [video-note-taker.db :as db :refer [users-db]]))

(defn create-checkout-session-handler [req]
  (let [body (get-body req)
        secret-key (System/getenv "STRIPE_SECRET_KEY")
        plan (keyword (:plan body))]
    (println "Using STRIPE_SECRET_KEY: " secret-key)
    (println "plan: " plan)
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
         "http://localhost:3450/?cancel=true"))))))

(defn check-username-handler [req]
  (let [body (get-body req)]
    (println "check-username: " body)
    (let [resp (db/get-doc users-db nil (str "org.couchdb.user:" (:username body)) nil nil nil)]
      (if (empty? resp)
        (json-response {:status :available})
        (json-response {:status :taken})))))
