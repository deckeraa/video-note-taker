(ns video-note-taker.core
  (:require
   [reagent.core :as reagent]
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
   [video-note-taker.video-listing :as listing])
  (:require-macros
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn video [video-ref-atm video-src]
  [:video {:id "main-video"
           :class "mb3"
           :controls true
           :src (str "videos/" video-src)
           :width 620
           :ref (fn [el]
                 (reset! video-ref-atm el))}
   "Video not supported by your browser :("]
  )

(defn header [screen-cursor video-cursor]
  [:div {:class "flex items-center f4 justify-between w-100 bg-blue white mb1"}
   [:div {:class "flex items-center"}                             ; left side
    (if (= :video-selection (peek @screen-cursor))
      [:div {:class "b ma2"} "Video Note Taker"]
      [:div {:class "bg-white br-100 pa1 ma1 dim"}
       [svg/chevron-left {:on-click (fn [] (swap! screen-cursor pop))} "#357edd" "24px"]])
    ;; (when (= :video (peek @screen-cursor))
    ;;   )
    (when (= :video (peek @screen-cursor))
      [:div {:class ""} (:src @video-cursor)])
    (when (= :settings (peek @screen-cursor))
      [:div {:class "b ma2"} "Settings"])]
   [svg/cog {:class "ma2 dim"
             :on-click (fn [] (swap! screen-cursor conj :settings))} "white" "26px"]
   ])


(defn page [ratom]
  (let [video-ref-atm (clojure.core/atom nil)
        notes-cursor atoms/notes-cursor
        _auto-load (listing/load-video-listing atoms/video-listing-cursor)
        ]
    (fn []
      [:div {:class "flex flex-column items-center"}
       [header atoms/screen-cursor atoms/video-cursor]
       (when (= :video-selection (peek @atoms/screen-cursor))
         [listing/video-listing atoms/video-listing-cursor atoms/video-cursor atoms/notes-cursor atoms/screen-cursor] ;; TODO that's a lot of cursors. Maybe decouple this a bit.
         )
       (when (= :video (peek @atoms/screen-cursor))
         [:div
          [video video-ref-atm (:src @atoms/video-cursor)]
          [notes/notes notes-cursor video-ref-atm (:src @atoms/video-cursor)]])
       [:p (str @ratom)]
       [toaster-oven/toaster-control]
       ])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialize App

(defn dev-setup []
  (when ^boolean js/goog.DEBUG
    (enable-console-print!)
    (println "dev mode")
    ))

(defn reload []
  (reagent/render [page atoms/app-state]
                  (.getElementById js/document "app")))

(defn ^:export main []
  (dev-setup)
  (reload))
