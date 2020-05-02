(ns video-note-taker.video
  (:require
   [reagent.core :as reagent]
   [video-note-taker.db :as db]))

(defn video [video-ref-atm video-cursor video-options-cursor]
  (let [file-ext (second (re-matches #".*\.(.*)" (:file-name @video-cursor)))]
    [:video {:id "main-video"
             :class "mb3"
             :controls true
             :src  (db/resolve-endpoint (str "videos/" (:file-name @video-cursor)))
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
     "Video not supported by your browser :("]))
