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

(def pageinfo-atom (reagent/atom {}))

(defn purchase-handler [loading-stripe-atom validated-username-atom password-atom coupon-code-atom plan e]
  (reset! loading-stripe-atom plan)
  (db/post-to-endpoint
   "create-checkout-session"
   {:plan plan
    :username @validated-username-atom
    :password @password-atom
    :coupon @coupon-code-atom}
   (fn [resp]
     (println "resp:" resp)
     (let [id (:id resp)]
       (when id
         (let [stripe (js/Stripe. (:stripe-public-key @pageinfo-atom))]
           (.redirectToCheckout stripe (js-obj "sessionId" id))))))
   (fn [resp raw-resp]
     (println "Stripe endpoint failed: " raw-resp))))

(defn get-prompt-message [needs-username needs-password needs-tos]
  (if (and (not needs-username) (not needs-password) (not needs-tos))
    ""
    (str
     "Please "
     (cond (and needs-username needs-password)
           "pick a username and password"
           needs-username
           "pick a username"
           needs-password
           "pick a password")
     (when (and (or needs-username needs-password) needs-tos)
       " and ")
     (when needs-tos
       "check the Terms of Service box")
     ".")))

(deftest test-get-prompt-message
  (is (= (get-prompt-message true true true)
         "Please pick a username and password and check the Terms of Service box."))
  (is (= (get-prompt-message true false true)
         "Please pick a username and check the Terms of Service box."))
  (is (= (get-prompt-message false true true)
         "Please pick a password and check the Terms of Service box."))
  (is (= (get-prompt-message false false true)
         "Please check the Terms of Service box.")))

(defn payment-button [loading-stripe-atom validated-username-atom password-atom tos-atom coupon-code-atom plan]
  (let [show-click-message? (reagent/atom false)]
    (fn []
      (let [payment-button-inactive (or @loading-stripe-atom
                                        (or (empty? @validated-username-atom)
                                            (empty? @password-atom)
                                            (not @tos-atom)))]
        [:<>
         [:button {:class (str "bn white pa3 ma2 flex items-center justify-between "
                               (if payment-button-inactive
                                 "bg-light-green"
                                 "bg-green dim"))
                   :on-click (fn [e]
                               (if (not payment-button-inactive)
                                 (purchase-handler loading-stripe-atom validated-username-atom password-atom coupon-code-atom plan e)
                                 (reset! show-click-message? true)))}
          (if (= plan @loading-stripe-atom)
            [:div
             [:img {:src "tapefish_animation.gif" :width "200px"}]
             [:p {:class "f3 b"} "Loading Stripe payment page..."]]
            [:<>
             [:div {:class "mr1 tl"}
              [:p {:class "f3 b"}
               (case plan
                 :a "$15 first month, then $5/month afterwards"
                 :b "$55/year"
                 "Undefined payment plan")]
              [:p {:class "f4"} "50 GB total, shared with up to 15 family members"]]
             [svg/chevron-right {} "white" "64px"]])]
         (when @show-click-message?
           [:p {:class ""}
            (get-prompt-message (nil? @validated-username-atom) (empty? @password-atom) (not @tos-atom))
            ])]))))

(defn my-page []
  (let [loading-stripe (reagent/atom false)
        username-atom (reagent/atom "")
        password-atom (reagent/atom "")
        validated-username-atom (reagent/atom nil)
        tos-checked-atom (reagent/atom false)
        show-tos (reagent/atom false)
        coupon-code-atom (reagent/atom "")]
    (fn []
      [:<>
       (if (:stripe-mode @pageinfo-atom)
         [:<>
           [:h2 {:class ""} "Get Started"]
          [:div {:class "flex flex-column"}
           [auth/user-name-picker username-atom validated-username-atom]
           [auth/password-picker password-atom]]
          [:div {:class "ma1"}
           [:input {:type :checkbox :name "TOS" :class "mr1"
                    :checked @tos-checked-atom
                    :on-change (fn [e]
                                 (reset! tos-checked-atom (-> e .-target .-checked)))}]
           [:label {:for "TOS" }
            "I agree with the "
            [:a {:class "link underline blue hover-orange"
                 :href "./tos.html"
                 :target "_blank"}
             "Terms of Service"]
            "."]]
          [:div {:class "ma1"}
           [:label {:for "coupon-code"} "Optional coupon code: "]
           [:input {:type :text :value @coupon-code-atom
                    :id "coupon-code"
                    :on-change (fn [e]
                                 (reset! coupon-code-atom (-> e .-target .-value)))}]]
          [:div {:class "flex flex-column"}
           [payment-button loading-stripe validated-username-atom password-atom tos-checked-atom coupon-code-atom :a]
           "or"
           [payment-button loading-stripe validated-username-atom password-atom tos-checked-atom coupon-code-atom :b]
           ]
          [:p {:class "ma1 f5 i"}
           "Additional storage and users can be purchased in-app in groups of 50GB and 15 family members."
           [:br]
           "Example: if you want to host 100GB of videos shared with up to 30 others and pay monthly, that would be an
extra $5 a month,"
           [:br]
           " so you would pay $20 the first month and $10/month afterwards."
           ]]
         [:p "Contact familymemorystreamsupport@stronganchortech.com to get started."])
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
  (reset! pageinfo-atom (cljs.reader/read-string (.-textContent (.getElementById js/document "pageinfo"))))
  (reagent.dom/render [my-page atoms/app-state]
                      (.getElementById js/document "sign-up")))

(defn ^:export landing-page []
  (dev-setup)
  (reload))
