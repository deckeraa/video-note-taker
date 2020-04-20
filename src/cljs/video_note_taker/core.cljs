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
   [video-note-taker.video-notes :as notes])
  (:require-macros
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn video [video-ref-atm video-src]
  [:video {:id "main-video"
           :class "mb3"
           :controls true
           :src video-src
           :width 620
           :ref (fn [el]
                 (reset! video-ref-atm el))}
   "Video not supported by your browser :("]
  )

(defn header [screen-cursor video-cursor]
  [:div {:class "flex items-center f3 justify-between w-100"}
   [:div {:class "flex items-center"}                             ; left side
    [:div {:class "f3 ma2 dim"
           :on-click (fn [] (swap! screen-cursor pop)) } "Video Note Taker"]
    (when (= :video (peek @screen-cursor))
      [svg/chevron-right {} "black" "24px"])
    (when (= :video (peek @screen-cursor))
      [:div {:class ""} (:src @video-cursor)])]
   ])


(defn page [ratom]
  (let [video-ref-atm (clojure.core/atom nil)
        video-src "big_buck_bunny_720p_surround.mp4"
        notes-cursor atoms/notes-cursor
        _auto-load (notes/load-notes notes-cursor video-src)]
    (fn []
      [:div {:class "flex flex-column items-center"}
       ;; [:p {:class "f3"} "Video Note Taker"]
       [header atoms/screen-cursor atoms/video-cursor]
       (when (= :video-selection (peek @atoms/screen-cursor))
         [:div {:class ""
                :on-click (fn []
                            (reset! atoms/video-cursor {:src "big_buck_bunny_720p_surround.mp4"})
                            (swap! atoms/screen-cursor conj :video))}
          "Big Buck Bunny"])
       (when (= :video (peek @atoms/screen-cursor))
         [:div
          [video video-ref-atm video-src]
          [notes/notes notes-cursor video-ref-atm video-src]])
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
