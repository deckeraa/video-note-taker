(ns video-note-taker.stripe-handlers
  (:require
   [ring.util.json-response :refer [json-response]]
   [clj-stripe.common :as common]
   [clj-stripe.checkouts :as checkouts]))

(defn create-checkout-session-handler [req]
  (let [secret-key (System/getenv "STRIPE_SECRET_KEY")]
    (println "Using STRIPE_SECRET_KEY: " secret-key)
    (json-response 
     (common/with-token secret-key
       (common/execute
        (checkouts/create-checkout-session
         [{:price-id "price_HOOdnszH3OSHgU" :quantity 1}]
         "subscription"
         "http://localhost:3450/memories.html"
         "http://localhost:3450/?cancel=true"))))))
