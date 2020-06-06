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

(defn purchase-handler [loading-stripe-atom validated-username-atom password-atom plan e]
  (reset! loading-stripe-atom plan)
  (db/post-to-endpoint
   "create-checkout-session"
   {:plan plan
    :username @validated-username-atom
    :password @password-atom}
   (fn [resp]
     (println "resp:" resp)
     (let [id (:id resp)]
       (when id
         (let [stripe (js/Stripe. stripe-public-key)]
           (.redirectToCheckout stripe (js-obj "sessionId" id))))))
   (fn [resp raw-resp]
     (println "Stripe endpoint failed: " raw-resp))))

(defn payment-button [loading-stripe-atom validated-username-atom password-atom tos-atom plan]
  (let [payment-button-inactive (or @loading-stripe-atom
                                (or (empty? @validated-username-atom)
                                    (empty? @password-atom)
                                    (not @tos-atom)))]
    [:button {:class (str "bn white pa3 ma2 flex items-center "
                          (if payment-button-inactive
                            "bg-light-green"
                            "bg-green dim"))
              :on-click (fn [e]
                          (when (not payment-button-inactive)
                            (purchase-handler loading-stripe-atom validated-username-atom password-atom plan e)))}
     (if (= plan @loading-stripe-atom)
       [:div
        [:img {:src "tapefish_animation.gif" :width "200px"}]
        [:p {:class "f3 b"} "Loading Stripe payment page..."]]
       [:<>
        [:div {:class "mr1"}
         [:p {:class "f3 b"}
          (case plan
            :a "$15 first month, then $5/month afterwards"
            :b "$55/year"
            "Undefined payment plan")]
         [:p {:class "f4"} "50 GB, up to 15 family members"]]
        [svg/chevron-right {} "white" "64px"]])]))

(defn user-name-picker [username-atom validated-username-atom]
  (let [check-ctr (reagent/atom 0)
        username-status (reagent/atom :none)]
    (fn []
      [:div {:class "flex items-center"}
       [:div {:class "flex"}
        [:p {:class "ma1"} "Username: "]
        [:input {:type :text :value @username-atom
                 :on-change
                 (fn [e]
                   (let [username (-> e .-target .-value)]
                     (if (empty? username)
                       (reset! username-status :none)
                       (reset! username-status nil))
                     (reset! username-atom username)
                     (reset! validated-username-atom nil)
                     (swap! check-ctr inc)
                     (js/setTimeout
                      (fn []
                        (let [username @username-atom]
                          (when (and
                                 (= 0 (swap! check-ctr dec))
                                 (not (empty? username)))
                            (do
                              (reset! username-status :checking)
                              (println "username check TODO" username)
                              (db/post-to-endpoint
                               "check-username" {:username username}
                               (fn [resp]
                                 (case (keyword (:status resp))
                                   :available
                                   (do
                                     (reset! username-status :validated)
                                     (reset! validated-username-atom username))
                                   :taken
                                   (reset! username-status :taken)
                                   :invalid
                                   (reset! username-status :invalid))))))))
                      350)))}]]
       (case @username-status
         :none      [:p {:class ""}      "Please pick a username"]
         :checking  [:p {:class ""}      "Checking username..."]
         :taken     [:p {:class "red"}   "Username is taken."]
         :invalid   [:p {:class "red"}   "Username can only contain letters, numbers, and underscores."]
         :validated [:p {:class "green"} "Username is available."]
         nil)])))

(defn password-picker [password-atom]
  (let [input-atm (reagent/atom "")]
    (fn []
      [:div {:class "flex items-center"}
       [:div {:class "flex"}
        [:p {:class "ma1"} "Password: "]
        [:input {:type :password :value @input-atm
                 :on-change
                 (fn [e]
                   (let [value (-> e .-target .-value)]
                     (reset! input-atm value)
                     (if (> (count value) 4)
                       (reset! password-atom value)
                       (reset! password-atom nil))))}]]
       (cond
         (empty? @input-atm) [:p {:class ""} "Please pick a password"]
         (empty? @password-atom) [:p {:class "red"} "Password must be at least four characters long"]
         :default [svg/check {:class "ma1"} "green" "24px"])])))

(defn my-page []
  (let [loading-stripe (reagent/atom false)
        username-atom (reagent/atom "")
        password-atom (reagent/atom "")
        validated-username-atom (reagent/atom nil)
        tos-atom (reagent/atom false)]
    (fn []
      [:div {:class "h-100 flex flex-column"}
       [:div {:class "white bg-blue pl2-ns"}
        [:p  {:class "f1 ma0"} "Family" [:wbr] "Memory" [:wbr] "Stream"]
        [:p  {:class "f3 i ma1"} "Helping your family reminisce together"]]
       [:p  {:class "f4 ml1 tc"} "When you host your family videos with FamilyMemoryStream, your family can:"]
       [:div {:class "flex flex-column flex-row-ns justify-between-ns"}
        [:div {:class "flex flex-column items-center pa3"}
         [:img {:src "streaming_icon.svg" :width "64px" :height "64px"}]
         [:p {:class "f4 ma1"} "Stream videos to their computer or mobile device."]
         [:p {:class "f5 ma1 i"} "No need to pass around massive video files or DVDs."]]
        [:div {:class "flex flex-column items-center pa3"}
         [:img {:src "note_taking_icon.svg" :width "64px" :height "64px"}]
         [:p {:class "f4 ma1"} "Highlight their favorite memories by adding notes."]
         [:p {:class "f5 ma1 i"} "Notes can be added by any family member and correspond to a timestamp in a video, making it easy to jump to their favorite memories."]]
        [:div {:class "flex flex-column items-center pa3"}
         [:img {:src "magnifying_glass.svg" :width "64px" :height "64px"}]
         [:p {:class "f4 ma1"} "Find memories with a built-in notes search."]
         [:p {:class "f5 ma1 i"} "Got a wedding coming up and looking for some cute/awkward baby videos? The search capability shows you all relevant clips."]]]
       [:h2 {:class "f2 ml1 tc"} "Get started sharing memories"]
       [user-name-picker username-atom validated-username-atom]
       [password-picker password-atom]
       [:div {:class "ma1"}
        [:input {:type :checkbox :name "TOS" :class "mr1"
                 :checked @tos-atom
                 :on-change (fn [e]
                              (reset! tos-atom (-> e .-target .-checked)))}]
        [:label {:for "TOS" } "I agree with the TODO TOS."]]
       [:div {:class "flex flex-row flex-wrap"}
        [payment-button loading-stripe validated-username-atom password-atom tos-atom :a]
        [payment-button loading-stripe validated-username-atom password-atom tos-atom :b]
        ]
       [:p {:class "ma1 f5 i"} "Additional storage and users can be purchased in-app in blocks of 50GB and 15 family members. Example: if you want to host 100GB of videos and pay monthly, that would be an
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
