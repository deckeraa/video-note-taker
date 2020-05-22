(ns video-note-taker.video
  "Reagent component for video display that allows seeking to a given time position."
  (:require
   [reagent.core :as reagent]
   [cljs.test :include-macros true :refer-macros [testing is]]
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

(defn get-file-extension [filename]
  (if (not (string? filename))
    ""
    (second (re-matches #".*\.(.*)" filename))))

(deftest test-get-file-extension
  (is (= (get-file-extension "foo.mp3") "mp3"))
  (is (= (get-file-extension "foo.mp3.mp4") "mp4")))

(defn video
  ([video-ref-atm video-cursor video-options-cursor]
   [video video-ref-atm video-cursor video-options-cursor {}])
  ([video-ref-atm video-cursor video-options-cursor options]
   "Reagent component that displays a video.
    video-ref-atm: Contains a reference to the <video> element. May be nil.
    video-cursor: Contains the video document from CouchDB.
       :file-name - filename in the ~/resources/private/ folder
    video-options-cursor: Contains additional options for the video
       :requested-time - time in seconds of where the video should seek to upon instantiation
    options: overrides used for testing
       :src-override - overrides the filename in the video-cursor"
   (when-let [filename (or (:src-override options) (:file-name @video-cursor))]
     (let [src (if (:src-override options)
                 (db/resolve-endpoint filename)
                 (let [presigned-url (:presigned-url @video-cursor)]
                   (if presigned-url
                     presigned-url ;; TODO should add some way to check for presigned URLs that have expired (unlikely) and re-sign if needed
                     (db/resolve-endpoint (str "videos/" filename))))
                 )]
       [:video {:id "main-video"
                :class "mb3"
                :controls true
                :src  src
                :width 620
                :type (str "video/" (get-file-extension filename))
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
        video-cursor  (reagent/atom {})
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
      "try-set-video-time to 5 minutes."]
     [:p "If the video doesn't show up, make sure you have a video named \"A Tale of Two Kitties (1942).mp4\" in ~/resources/public/."]]
    ))

(defcard-rg delayed-time-set-card
  "This card tests the behavior of setting a playback time via try-set-video-time when the video
   element does not yet exist. 
   The expected behavior is that the video will open and immediately seek to the correct playback time."
  (let [is-video-showing? (reagent/atom false)
        video-ref-atm (reagent/atom nil)
        video-cursor  (reagent/atom {})
        video-options-cursor (reagent/atom nil)
        options {:src-override "A Tale of Two Kitties (1942).mp4"}
        ]
    (fn []
      [:div
       (when @is-video-showing?
         [video video-ref-atm video-cursor video-options-cursor options])
       (if @is-video-showing?
         [:button {:on-click (fn [] (reset! is-video-showing? false))}
          "Hide video."]
         [:div
          [:button {:on-click (fn []
                                (try-set-video-time video-ref-atm video-options-cursor (* 1 60))
                                (js/setTimeout #(reset! is-video-showing? true) 1000))}
           "try-set-video-time to 1 minutes and show video after 1 seconds."]
          [:button {:on-click (fn []
                                (try-set-video-time video-ref-atm video-options-cursor (* 5 60))
                                (js/setTimeout #(reset! is-video-showing? true) 1000))}
           "try-set-video-time to 5 minutes and show video after 1 seconds."]]
         )
       ])))
