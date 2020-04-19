(ns video-note-taker.toaster-oven
  "Enables toast messages to be displayed in the application."
  (:require [reagent.core :as reagent]
            [devcards.core]
            [video-note-taker.svg :as svg]
            [video-note-taker.atoms :as atoms]
            )
  (:require-macros
   [devcards.core :refer [defcard defcard-rg]]))

(def toasting-time (* 5 1000)) ; minimum time, in milliseconds, that toasts are shown.

(defn add-toast
  "Adds a toast that will disappear by itself. The time it disappears is controlled by the addition of
  other toast message and by the toasting-time variable."
  ([toast]
   (swap! atoms/toaster-cursor (fn [atm]
                                 (as-> atm $
                                   (assoc $ :toasts (conj (:toasts $) toast))
                                   (assoc $ :toast-count (inc (:toast-count $))))))
   ;; add timeout
   (js/setTimeout (fn []
                    (swap! atoms/toaster-cursor
                           (fn [atm]
                             (as-> atm $
                                        ; decrement the counter, which is used to make it so that everytime a toast is
                                        ; added, the timer is "reset" without needing to do any js timer shenaningans
                               (assoc $ :toast-count (dec (:toast-count $)))
                                        ; if we're the last timer out there, go ahead and clear out the current toasts
                               (if (= 0 (:toast-count $))
                                 (do
                                   (assoc $ :old-toasts (conj (:old-toasts $) toast))
                                   (assoc $ :toasts []))
                                 $)))))
                  toasting-time))
  ; icon-fn should be in the form of one of the functions from slide-stainer.atoms
  ([toast-msg icon-fn color]
   (add-toast [:div {}
               [icon-fn {:style {:display :inline-block :padding "8px"}} color 32]
               toast-msg])))

(defn get-toasts []
  (:toasts @atoms/toaster-cursor))

(defn toaster-control
  "The Reagent control that displays the toast messages."
  ([]
   (toaster-control atoms/toaster-cursor))
  ([toaster-cursor]
   (fn []

     [:div {:class "toaster-oven"}
      (when (not (empty? (:toasts @toaster-cursor)))
        [:div {:class "toaster-oven-card"}
         [:ul 
          (map (fn [toast]
                 ^{:key (str toast)}
                 [:li {} toast])
               (:toasts @toaster-cursor))]])])))

(defcard-rg toaster-card
  (let [toaster-cursor (reagent/atom {:toasts ["Simple toast"
                                               [:div {}
                                                [svg/check {:style {:display :inline-block :padding "8px"}} "green" 32]
                                                "Stylized toast"]]})]
    (fn []
      [toaster-control toaster-cursor])))
