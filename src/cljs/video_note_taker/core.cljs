(ns video-note-taker.core
  (:require
   [reagent.core :as reagent]
   ))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vars

(defonce app-state
  (reagent/atom {}))



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

(defn page [ratom]
  (let [video-ref-atm (clojure.core/atom nil)]
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
       [:button {:on-click (fn [e] (println "clicked!")
                             (when-let [video @video-ref-atm]
                               (println "current time:" (.-currentTime video))))}
        "Add note"]
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
