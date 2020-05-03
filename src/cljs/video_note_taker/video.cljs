(ns video-note-taker.video
  (:require
   [reagent.core :as reagent]
   [video-note-taker.db :as db])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn try-set-video-time
  "Sets the video's current playbock time, if the video exists.
  Otherwise it saves off the time into video-options-cursor and seeks to that time when the
  video becomes non-nil."
  [video-ref-atm video-options-cursor time]
  ;; This will work in Chrome by virtue of the server implementing partial content requests:
  ;; https://github.com/remvee/ring-partial-content
  ;; https://stackoverflow.com/questions/8088364/html5-video-will-not-loop
  (swap! video-options-cursor assoc :requested-time time)
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
                                  ;; when the time updates, check to see if we made it to the requested time
                                  (let [current-time   (.-currentTime (-> e .-target))
                                        requested-time (:requested-time @video-options-cursor)]
                                    (when requested-time
                                      (if (< (Math/abs (- requested-time current-time)) 1)
                                        ;; If so, dissoc the :requested-time
                                        ;; Previously, this contained logic to continue making
                                        ;; calls to set currentTime until it reached the requested time.
                                        ;; However, after implementing partial content requests (using wrap-partial-content), all tested browsers appear to be seeking directly to the requested time in a single set.
                                        (swap! video-options-cursor dissoc :requested-time)))))
                :ref (fn [el]
                       (when el
                         (do
                           ;; Save off the video reference
                           (reset! video-ref-atm el)
                           ;; If we have a requested time in the video, seek to that time.
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
      "Set video.currentTime 1m"]
     [:button {:on-click (fn [] (when-let [video @video-ref-atm]
                                  (set! (.-currentTime video) (* 5 60))))}
      "Set video.currentTime 5m"]
     [:button {:on-click (fn []
                           (try-set-video-time video-ref-atm video-options-cursor (* 1 60)))}
      "try-set-video-time to 1 minutes."]
     [:button {:on-click (fn []
                           (try-set-video-time video-ref-atm video-options-cursor (* 5 60)))}
      "try-set-video-time to 5 minutes."]]
    ))
