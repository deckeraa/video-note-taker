(ns video-note-taker.atoms
  "Defines the global state atom of the app as
  well as several cursors used to access various elements."
  (:require
   [reagent.core :as reagent]
   [devcards.core])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [devcards.core :refer [defcard defcard-rg]]))

(defonce app-state
  (reagent/atom {:notes []
                 :video {}
                 :video-options {}
                 :video-ref nil
                 :video-listing nil
                 :screen-stack [:video-selection]
                 :settings {:_id "settings"}
                 :toaster {:toasts [] :old-toasts []}
                 :login-atm 0
                 :user nil
                 :usage nil
                 }))

(defonce screen-cursor    (reagent/cursor app-state [:screen-stack]))
(defonce settings-cursor  (reagent/cursor app-state [:settings]))
(defonce toaster-cursor   (reagent/cursor app-state [:toaster]))
(defonce notes-cursor     (reagent/cursor app-state [:notes]))
(defonce video-cursor     (reagent/cursor app-state [:video]))
(defonce video-ref-cursor (reagent/cursor app-state [:video-ref]))
(defonce video-listing-cursor    (reagent/cursor app-state [:video-listing]))
(defonce video-options-cursor    (reagent/cursor app-state [:video-options]))
(defonce login-cursor     (reagent/cursor app-state [:login-atm]))
(defonce user-cursor      (reagent/cursor app-state [:user]))
(defonce uploads-cursor   (reagent/cursor app-state [:uploads]))
(defonce usage-cursor     (reagent/cursor app-state [:usage]))
