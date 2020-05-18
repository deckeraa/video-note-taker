(ns video-note-taker.core
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

(defn header [screen-cursor video-cursor]
  [:div {:class "flex items-center f4 justify-between w-100 bg-blue white mb1"}
   ;; left side
   [:div {:class "flex items-center"}                             
    (if (= :video-selection (peek @screen-cursor))
      [:div {:class "b ma2"} "Video Note Taker"]
      [:div {:class "bg-white br-100 pa1 ma1 dim"
             :on-click (fn []
                         (swap! screen-cursor pop)
                         ;; screen-based logic can go here
                         (cond (= :video-selection (peek @screen-cursor))
                               (listing/load-video-listing atoms/video-listing-cursor)))}
       [svg/chevron-left {} "#357edd" "24px"]])
    (when (= :video (peek @screen-cursor))
      [:div {:class ""} (:display-name @video-cursor)]
      )
    (when (= :settings (peek @screen-cursor))
      [:div {:class "b ma2"} "Settings"])]
   ;; right side
   [svg/cog {:class "ma2 dim" 
             :on-click (fn [] (swap! screen-cursor conj :settings))} "white" "26px"]
   ])


(defn page [ratom]
  (let [notes-cursor atoms/notes-cursor
        _auto-loaded-settings      (reagent/atom false)
        ]
    (fn []
      @atoms/login-cursor ; referenced so that this component refreshes when the login-cursor changes
      (if (auth-util/needs-auth-cookie)
        ;; Show login screen if needed
        [auth/login atoms/login-cursor]
        ;; Otherwise, render the app
        (do
          ;; Auto-load data if needed
          (when (compare-and-set! atoms/user-cursor nil {})
            (auth/load-user-cursor atoms/user-cursor))
          (when (compare-and-set! atoms/video-listing-cursor nil [])
            (listing/load-video-listing atoms/video-listing-cursor))
          (when (not @_auto-loaded-settings)
            (settings/load-settings atoms/settings-cursor)
            (reset! _auto-loaded-settings true))
          ;; Draw thte page
          [:div {:class "flex flex-column items-center"}
           [header atoms/screen-cursor atoms/video-cursor]
           (when (= :video-selection (peek @atoms/screen-cursor))
             [:div {:class "mh3"}
              [search/search atoms/video-cursor atoms/screen-cursor]
              [:h2 {:class "mh3"} "Videos"]
              [listing/video-listing atoms/video-listing-cursor atoms/video-cursor atoms/notes-cursor atoms/screen-cursor]] ;; TODO that's a lot of cursors. Maybe decouple this a bit.
             )
           (when (= :video (peek @atoms/screen-cursor))
             [:div {:class "flex flex-column items-center"}
              [video atoms/video-ref-cursor atoms/video-cursor atoms/video-options-cursor]
              [notes/notes notes-cursor atoms/video-ref-cursor atoms/video-cursor atoms/video-options-cursor]])
           (when (= :settings (peek @atoms/screen-cursor))
             [settings/settings atoms/settings-cursor atoms/login-cursor atoms/notes-cursor atoms/video-listing-cursor atoms/video-cursor atoms/screen-cursor])
           (when (:show-app-state @atoms/settings-cursor)
             [:p (str @ratom)])
           [toaster-oven/toaster-control]
           [uploads/upload-display atoms/uploads-cursor]
           ])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialize App

(auth/run-cookie-renewer) ;; set up the auto-renewer

(defn dev-setup []
  (when ^boolean js/goog.DEBUG
    (enable-console-print!)
    (println "dev mode")
    ))

(defn reload []
  (reagent.dom/render [page atoms/app-state]
                  (.getElementById js/document "app")))

(defn ^:export main []
  (dev-setup)
  (reload))
