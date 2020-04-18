(ns video-note-taker.core
  (:require
   [reagent.core :as reagent]
   ))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vars

(defonce app-state
  (reagent/atom {:notes {}}))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page

(defn video [video-ref-atm]
  [:video {:id "main-video"
           :controls true
           :src "./big_buck_bunny_720p_surround.mp4"
           :width 620
           :ref (fn [el]
                 (reset! video-ref-atm el))}
   "Video not supported by your browser :("]
  )

(defn notes [notes-cursor video-ref-atm]
  (fn []
    [:div
     [:button {:on-click (fn [e] 
                           (when-let [video @video-ref-atm]
                             (let [current-time (.-currentTime video)]
                               (println "current time:" current-time)
                               (swap! notes-cursor assoc current-time
                                      {:time current-time
                                       :text (str "Note at " current-time)}))))}
      "Add note"]
     (map (fn [[key note]]
;            (println "looping over note: " key note)
            ^{:key key}
            [:div {:class "br3 ba b--black-10 pa2 ma2"} (str note)])
          @notes-cursor)]
    ))

(defn page [ratom]
  (let [video-ref-atm (clojure.core/atom nil)
        notes-cursor (reagent/cursor ratom [:notes])]
    (fn []
      [:div
       [:p "Video Note Taker v1.0"]
       [video video-ref-atm]
       [:button {:on-click (fn [e]
                             (when-let [video @video-ref-atm]
                               (if (.-paused video)
                                 (.play video)
                                 (.pause video))))}
        "Play/Pause"]
       [notes notes-cursor video-ref-atm]
       [:p (str @ratom)]
       ])))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialize App

(defn dev-setup []
  (when ^boolean js/goog.DEBUG
    (enable-console-print!)
    (println "dev mode")
    ))

(defn reload []
  (reagent/render [page app-state]
                  (.getElementById js/document "app")))

(defn ^:export main []
  (dev-setup)
  (reload))
