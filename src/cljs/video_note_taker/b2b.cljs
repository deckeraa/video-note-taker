(ns video-note-taker.b2b
  (:require
   [reagent.core :as reagent]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.svg :as svg]
   [video-note-taker.auth :as auth]))

(defn new-end-user-creation []
  (let [username-atom (reagent/atom "")
        validated-username-atom (reagent/atom "")]
    (fn []
      [:div
       [:h2 {} "Create a new end-user"]
       [auth/user-name-picker username-atom validated-username-atom]
       [:button {:class (str "br3 white bn pa3 "
                             (if (empty? @validated-username-atom)
                               "bg-light-green"
                               "bg-green dim"))
                 :on-click (fn []
                             (when (not (empty? @validated-username-atom))
                               ;; user creation goes here
                               ))}
        "Create new end-user"]])))

(defn business-view []
  [:<>
   [:div "This is the business view."]
   [new-end-user-creation]])
