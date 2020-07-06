(ns video-note-taker.b2b
  (:require
   [reagent.core :as reagent]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.svg :as svg]
   [video-note-taker.auth :as auth]
   [video-note-taker.db :as db]
   [video-note-taker.listing :as listing]))

(defn load-in-progress-users [atm]
  (db/put-endpoint-in-atom "get-in-progress-users" {} atm))

(defn new-end-user-creation [selected-end-user-atom]
  (let [username-atom (reagent/atom "")
        validated-username-atom (reagent/atom "")
        in-progress-users-atom (reagent/atom nil)
        _ (load-in-progress-users in-progress-users-atom)
        ]
    (fn []
      [:div
       [:h2 {} "Create a new end-user"]
       [auth/user-name-picker username-atom validated-username-atom]
       [:button {:class (str "br3 white bn pa3 "
                             (if (empty? @validated-username-atom)
                               "bg-light-green"
                               "bg-green dim"))
                 :on-click (fn []
                             (let [username @validated-username-atom]
                               (when (not (empty? username))
                                 ;; user creation goes here
                                 (db/post-to-endpoint
                                  "create-user"
                                  {:user username
                                   :req-role "family_lead"}))))}
        "Create new end-user"]
       [:h2 {} "Or select an existing user"]
       [:select {:name "foo" :value @selected-end-user-atom
                 :on-change (fn [e] (reset! selected-end-user-atom (-> e .-target .-value))) }
        (map (fn [{:keys [id key value]}]
               (let [username (second (re-matches #"org\.couchdb\.user\:(.*)" id))]
                 ^{:key username}
                 [:option {:value username} username]))
             @in-progress-users-atom
         )
        ]
       ;; [:button {:on-click (fn [] (load-in-progress-users in-progress-users-atom))}
       ;;  "Load in-progress-users"]
       ;;[:p (str "the atom: " @in-progress-users-atom)]
       ])))

(defn family-member-listing [selected-end-user-atom]
  (let [family-members (reagent/atom nil)]
    (fn []
      [:div
       [:h2 {} "Family Members"]
       ;[listing/listing ]
       ])))

(defn business-view []
  (let [selected-end-user-atom (reagent/atom nil)]
    [:<>
     [:div "This is the business view."]
     [new-end-user-creation selected-end-user-atom]
     [family-member-listing]]))
