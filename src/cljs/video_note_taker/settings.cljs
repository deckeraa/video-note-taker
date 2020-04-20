(ns video-note-taker.settings
  (:require
   [reagent.core :as reagent]
   [video-note-taker.db :as db]))

(defn load-settings [settings-cursor]
  (println "Calling load-settings")
  (db/get-doc "settings"
              (fn [doc]
                (reset! settings-cursor doc)
                (println "Got settings: " doc))
              nil))

(defn settings [settings-cursor]
  [:div {:class ""}
   [:h2 "Developer settings"]
   [:div {:class "flex items-center"}
    [:input {:type :checkbox :class "ma2"
             :checked (:show-app-state @settings-cursor)
             :on-change (fn [e]
                          (swap! settings-cursor assoc :show-app-state (-> e .-target .-checked))
                          (db/put-doc @settings-cursor (fn [new-doc] (reset! settings-cursor new-doc))))}]
    [:div "Show app-state atom at the bottom of each page"]]])
