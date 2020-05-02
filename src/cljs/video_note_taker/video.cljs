(ns video-note-taker.video
  (:require
   [reagent.core :as reagent]
   [video-note-taker.db :as db])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn request-video-time [video-options-cursor time]
  (swap! video-options-cursor assoc :requested-time time))

(defn try-set-video-time [video-ref-atm video-options-cursor time]
  ;; This will work in Chrome by virtue of the server implementing partial content requests:
  ;; https://github.com/remvee/ring-partial-content
  ;; https://stackoverflow.com/questions/8088364/html5-video-will-not-loop
  (println "video-ref-atm is " (str @video-ref-atm))
  (request-video-time video-options-cursor time)
  (when-let [video @video-ref-atm]
    (set! (.-currentTime video) time)))

(defn video
  ([video-ref-atm video-cursor video-options-cursor]
   [video video-ref-atm video-cursor video-options-cursor {}])
  ([video-ref-atm video-cursor video-options-cursor options]
   (when (:file-name @video-cursor)
     (let [file-ext (second (re-matches #".*\.(.*)" (:file-name @video-cursor)))
           src (if (:src-override options)
                 (db/resolve-endpoint (:src-override options))
                 (db/resolve-endpoint (str "videos/" (:file-name @video-cursor))))]
       [:video {:id "main-video"
                :class "mb3"
                :controls true
                :src  src
                :width 620
                :type (str "video/" file-ext)
                :on-time-update (fn [e]
                                  (let [current-time   (.-currentTime (-> e .-target))
                                        requested-time (:requested-time @video-options-cursor)]
                                    (println (:display-name @video-cursor)
                                             " Time update event fired: current time"
                                             current-time requested-time)
                                    (when requested-time
                                      (if (< (Math/abs (- requested-time current-time)) 1)
                                        (swap! video-options-cursor dissoc :requested-time)
                                        (when-let [video @video-ref-atm]
                                          (set! (.-currentTime video) requested-time))))))
                :ref (fn [el]
                       (when el
                         (do
                           (reset! video-ref-atm el)
                           (when-let [requested-time (:requested-time @video-options-cursor)]
                             (set! (.-currentTime el) requested-time)))))}
        "Video not supported by your browser :("]))))

(defcard-rg video-card
  (let [video-ref-atm (reagent/atom nil)
        ; TODO check the test video into source
        video-cursor  (reagent/atom {:file-name "foo.mp4"})
        video-options-cursor (reagent/atom nil)
        options {:src-override "A Tale of Two Kitties (1942).mp4"}]
    [:div
     [video video-ref-atm video-cursor video-options-cursor options]
     [:button {:on-click (fn [] (when-let [video @video-ref-atm]
                                  (set! (.-currentTime video) (* 1 60))))}
      "Quick 1m"]
     [:button {:on-click (fn [] (when-let [video @video-ref-atm]
                                  (set! (.-currentTime video) (* 5 60))))}
      "Quick 5m"]
     [:button {:on-click (fn []
                           (try-set-video-time video-ref-atm video-options-cursor (* 5 60)))}
      "Jump to 5 minutes."]
     [:button {:on-click (fn []
                           (try-set-video-time video-ref-atm video-options-cursor (* 1 60)))}
      "Jump to 1 minutes."]]
    ))
