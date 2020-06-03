(ns video-note-taker.landing-page
  "Video Note Taker is a web-based collaborative video annotation app."
  (:require
   [reagent.core :as reagent]
   [reagent.dom]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
   [cljs.test :include-macros true :refer-macros [testing is]]
   [cljs-uuid-utils.core :as uuid]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.svg :as svg]
   [video-note-taker.db :as db]
   [video-note-taker.toaster-oven :as toaster-oven]
   [video-note-taker.editable-field :refer [editable-field]]
   [video-note-taker.video-notes :as notes]
   [video-note-taker.video-listing :as listing]
   [video-note-taker.settings :as settings]
   [video-note-taker.auth :as auth]
   [video-note-taker.auth-util :as auth-util]
   [video-note-taker.video :as video :refer [video]]
   [video-note-taker.search :as search]
   [video-note-taker.uploads :as uploads])
  (:require-macros
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(def stripe-public-key "pk_test_SXM3FqTZVdax1V17XeTEgvCJ003ySTXbMq")

(defn my-page []
  [:div
   [:h1 "Family Memory Stream"]
   [:p "Help your family reminisce together"]
   [:button {:on-click (fn [e]
                         (db/post-to-endpoint
                          "create-checkout-session"
                          {:plan :a}
                          (fn [resp]
                            (println "resp:" resp)
                            (let [id (:id resp)]
                              (when id
                                (let [stripe (js/Stripe. stripe-public-key)]
                                  (.redirectToCheckout stripe (js-obj "sessionId" id))))))
                          (fn [resp raw-resp]
                            (println "Stripe endpoint failed: " raw-resp))))}
    "Purchase at $5/mo"]
   [:button {} "Purchase at $55/year"]
   ])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialize App

(defn dev-setup []
  (when ^boolean js/goog.DEBUG
    (enable-console-print!)
    (println "dev mode")
    ))

(defn reload []
  (println "landing-page.cljs")
  (reagent.dom/render [my-page atoms/app-state]
                      (.getElementById js/document "app")))

(defn ^:export landing-page []
  (dev-setup)
  (reload))
