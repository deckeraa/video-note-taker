(ns video-note-taker.toaster-oven
  "Enables toast messages to be displayed in the application."
  (:require [reagent.core :as reagent]
            [devcards.core]
            [video-note-taker.svg :as svg]
            [video-note-taker.atoms :as atoms]
            [cljs-uuid-utils.core :as uuid]
            )
  (:require-macros
   [devcards.core :refer [defcard defcard-rg]]))

(def toasting-time (* 3 1000)) ; minimum time, in milliseconds, that toasts are shown.

(defn add-toast
  "Adds a toast that will disappear by itself. The time it disappears is controlled by the addition of
  other toast message and by the toasting-time variable."
  (
   ;; Adds a toast where:
   ;;   toast is a Hiccup form
   ;;   remove-delegate-atm is an atom that is called by the buttons in the 'toast' Hiccup form that need to close the toast
   ;;   toaster-cursor is the cursor that stores all of the toasts. Call the 2-arity version of this function to have it defaulted in.
   [toast remove-delegate-atm toaster-cursor] ; remove-delegate-atm is called in the button calling code, so that a toast with buttons can close itself
   (if remove-delegate-atm
     (let [uuid (keyword (str (uuid/make-random-uuid)))]
       (swap! toaster-cursor (fn [atm]
                                     (as-> atm $
                                       (assoc $ :static-toasts (assoc (:static-toasts $) uuid toast))
                                       (assoc $ :toast-count (inc (:toast-count $))))))
       (reset! remove-delegate-atm (fn []
                                     (swap! toaster-cursor (fn [v]
                                                             (update-in v [:static-toasts] dissoc uuid))))))
     (swap! toaster-cursor (fn [atm]
                                   (as-> atm $
                                     (assoc $ :toasts (conj (:toasts $) toast))
                                     (assoc $ :toast-count (inc (:toast-count $)))))))
   ;; add timeout
   (js/setTimeout (fn []
                    (swap! toaster-cursor
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
  (;; Adds a toast where:
   ;;   toast is a Hiccup form
   ;;   remove-delegate-atm is an atom that is called by the buttons in the 'toast' Hiccup form that need to close the toast
   [toast remove-delegate-atm]
   (add-toast toast remove-delegate-atm atoms/toaster-cursor))
  (;; Adds a toast where:
   ;;  toast-msg is a string with the toast message
   ;;  icon-fn is in the form of one of the functions from slide-stainer.atoms
   ;;  color is a string containing a css color (i.e. "green")
   ;;  buttons-fns is a map of functions for button presses, with keys of :ok-fn and :cancel-fn
   ;;  toaster-cursor is the cursor that stores all of the toasts. Call the 4-arity version of this function to have it defaulted in.
   [toast-msg icon-fn color button-fns toaster-cursor]
   (let [remove-delegate-atm (when button-fns
                               (atom (fn [] nil)))]
     (add-toast [:div {:style {:display :flex :flex-direction :column}}
                 [:div
                  (when icon-fn
                    [icon-fn {:style {:display :inline-block :padding "8px"}} color 32])
                  toast-msg]
                 (when button-fns
                   (let [cancel-fn (:cancel-fn button-fns)
                         ok-fn     (:ok-fn     button-fns)]
                     [:div {:style {:display :flex :justify-content :flex-end}}
                      (when cancel-fn [:div {:class "ba br3 b--black-10"
                                             :on-click (fn []
                                                            (@remove-delegate-atm)
                                                            (cancel-fn))} "Cancel"])
                      (when ok-fn     [:div {:class "br3 pa3 shadow-4 dim"
                                             :on-click (fn []
                                                            (@remove-delegate-atm)
                                                            (ok-fn))} "Ok"])]))]
                remove-delegate-atm ; if buttons are present, then the remove-delegate-atm is set and the toast doesn't disappear after a certain time.
                toaster-cursor
                )))
  ([toast-msg icon-fn color button-fns]
   (add-toast toast-msg icon-fn color button-fns atoms/toaster-cursor)))

(defn toaster-control
  "The Reagent control that displays the toast messages."
  ([]
   (toaster-control atoms/toaster-cursor))
  ([toaster-cursor]
   (fn []
     [:div {:class "toaster-oven"}
      (when (or
             (not (empty? (:static-toasts @toaster-cursor)))
             (not (empty? (:toasts @toaster-cursor))))
        [:div {:class "toaster-oven-card"}
         [:ul
          (map (fn [[k toast]]
                 ^{:key (str k)}
                 [:li {} toast])
               (:static-toasts @toaster-cursor))]
         [:ul 
          (map (fn [toast]
                 ^{:key (str toast)}
                 [:li {} toast])
               (:toasts @toaster-cursor))]])])))

(defcard-rg toaster-card
  (let [toaster-cursor (reagent/atom {:toasts [] :static-toasts {}})]
    (add-toast "Simple toast" nil nil nil toaster-cursor)
    (add-toast "Toast with icon" svg/check "green" nil toaster-cursor)
    (add-toast "Toast with buttons" nil nil {:ok-fn (fn [] (println "ok"))
                                             :cancel-fn (fn [] (println "cancel"))}
               toaster-cursor)
    (add-toast "Toast with buttons and icons" svg/check "green" {:ok-fn (fn [] (println "ok"))
                                                                 :cancel-fn (fn [] (println "cancel"))}
               toaster-cursor)
    (fn []
      [toaster-control toaster-cursor]
      )))
