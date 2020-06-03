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

(defn purchase-handler [loading-stripe-atom plan e]
  (reset! loading-stripe-atom plan)
  (db/post-to-endpoint
   "create-checkout-session"
   {:plan plan}
   (fn [resp]
     (println "resp:" resp)
     (let [id (:id resp)]
       (when id
         (let [stripe (js/Stripe. stripe-public-key)]
           (.redirectToCheckout stripe (js-obj "sessionId" id))))))
   (fn [resp raw-resp]
     (println "Stripe endpoint failed: " raw-resp))))

(defn payment-button [loading-stripe-atom plan]
  [:button {:class (str "bn white pa3 ma2 flex items-center "
                        (if @loading-stripe-atom "bg-light-green" "bg-green dim" ))
            :on-click (partial purchase-handler loading-stripe-atom plan)}
         (if (= plan @loading-stripe-atom)
           [:div
            [:p {:class "f3 b"} "Loading Stripe payment page."]]
           [:<>
            [:div {:class "mr1"}
             [:p {:class "f3 b"}
              (case plan
                :a "$15 first month, then $5/month afterwards"
                :b "$55/year"
                "Undefined payment plan")]
             [:p {:class "f4"} "50 GB, up to 15 family members"]]
            [svg/chevron-right {} "white" "64px"]])])

(defn my-page []
  (let [loading-stripe (reagent/atom false)]
    (fn []
      [:div {:class "h-100 flex flex-column"}
       [:div {:class "white bg-blue pl2-ns"}
        [:p  {:class "f1 ma0"} "Family" [:wbr] "Memory" [:wbr] "Stream"]
        [:p  {:class "f3 i ma1"} "Helping your family reminisce together"]]
       [:p  {:class "f4 ml1"} "When you host your family videos with FamilyMemoryStream, your family can:"]
       [:ul {:class "f4"}
        [:li
         [:p {:class "f4"} "Stream the videos on-demand to their computer or mobile device."]
         [:p {:class "f5 i"} "No need to pass around massive video files or DVDs."]]
        [:li
         [:p {:class "f4"} "Highlight their favorite memories by adding notes."]
         [:p {:class "f5 i"} "Notes can be added by any family member and correspond to a timestamp in a video, making it easy to jump to their favorite memories."]]
        [:li
         [:p {:class "f4"} "Find memories with a built-in notes search."]
         [:p {:class "f5 i"} "Got a wedding coming up and looking for some cute/awkward baby videos? The search capability shows you all relevant clips."]]]
       [:h2 {:class "f2 ml1"} "Get started"]
       [:h3 {:class "f3 ml1"} "Pick a user name"]
       [:p {:class "ml1"} "TODO username picker goes here"]
       [:div
        [:input {:type :checkbox :name "TOS" :class "mr1"}]
        [:label {:for "TOS" } "I agree with the TODO TOS."]]
       [:div {:class "flex flex-row"}
        [payment-button loading-stripe :a]
        [payment-button loading-stripe :b]
        ]
       [:p {:class "f5 i"} "Additional storage and users can be purchased in-app in blocks of 50GB and 15 family members. Example: if you want to host 100GB of videos and pay monthly, that would be an
extra $5 a month, so you would pay $20 the first month and $10/month afterwards."]
       ])))

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
