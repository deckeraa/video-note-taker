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
                 :screen-stack [:video-selection]
                 :settings {}
                 :toaster {:toasts [] :old-toasts []}}))

(defonce screen-cursor   (reagent/cursor app-state [:screen-stack]))
(defonce settings-cursor (reagent/cursor app-state [:settings]))
(defonce toaster-cursor  (reagent/cursor app-state [:toaster]))
(defonce notes-cursor    (reagent/cursor app-state [:notes]))
