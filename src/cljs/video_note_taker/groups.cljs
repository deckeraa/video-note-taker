(ns video-note-taker.groups
  (:require [reagent.core :as reagent]
            [cljs-uuid-utils.core :as uuid]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
            [video-note-taker.svg :as svg]
            [video-note-taker.atoms :as atoms]
            [video-note-taker.pick-list :refer [pick-list]]
            [video-note-taker.listing :as listing]
            [video-note-taker.editable-field :as editable-field]
            [video-note-taker.toaster-oven :as toaster-oven]
            [video-note-taker.db :as db])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go]]))

;; (defn groups [group-cursor]
;;   (fn []
;;     [pick-list
;;      {
;;       :data-cursor           group-cursor
;;       :option-load-fn        load-connected-users
;;       :can-delete-option-fn  (fn [option] (not (= option (:name @atoms/user-cursor))))
;;       :caption               "Select a user:"
;;       :remove-delegate-atom  (reagent/atom (fn [] nil))
;;       }]
;;     ))

;; (defcard-rg groups-card
;;   (let [group-cursor (reagent/atom {})]
;;     [groups group-cursor]))

(defn load-connected-users
  "Loads the list of connects users. Used to populate the list of options for the share dialog.
  At present, each user is connected to each other user in the Alpha Deploy.
  This will change in the future when a user connection workflow is implemented."
  [user-list-atm]
  (go (let [resp (<! (http/get (db/resolve-endpoint "get-connected-users")
                               {}))
            users (set (:body resp))]
        (reset! user-list-atm users))))

(defn load-groups [data-cursor]
  (db/put-endpoint-in-atom "get-groups" {} data-cursor))

(defn group-card [group-cursor options]
  (let [users-cursor (reagent/cursor group-cursor [:users])
        is-editing? (reagent/atom false)]
    (fn []
      [:div
       (if @is-editing?
         [:div {:class "br3 shadow-4 pv3 pl3"}
          [editable-field/editable-field
           (:name @group-cursor)
           (fn [v close-fn]
             (swap! group-cursor assoc :name v)
             (close-fn)
;             (db/post-to-endpoint "group" @group-cursor close-fn)
             )]
          [pick-list
           {
            :data-cursor           users-cursor
            :option-load-fn        load-connected-users
            :can-delete-option-fn  (fn [option] (not (= option (:name @atoms/user-cursor))))
            :caption               "Select a user:"
            :remove-delegate-atom  (reagent/atom (fn [] nil))
            :cancel-fn #(reset! is-editing? false)
            :ok-fn (fn [] (db/post-to-endpoint "group" @group-cursor
                                               (fn []
                                                 (listing/reload options)
                                                 (reset! is-editing? false))))
            }]]
         [:div {:class "flex flex-columns items-center justify-between br3 shadow-4 pv3 pl3"}
          ;;          [:div (str @group-cursor)]
          [:div
           [:div {:class "f3"} (:name @group-cursor)]
           [:div {:class "f4"} (clojure.string/join " " (:users @group-cursor))]]
          [:div {:class "flex flex-columns"}
           [svg/pencil {:class "mh3" :on-click #(reset! is-editing? true)} "grey" "18px"]
           (when (= (:created-by @group-cursor) (:name @atoms/user-cursor))
             [svg/trash {:class "mr2"
                         :on-click
                         (fn []
                           (toaster-oven/add-toast
                            "Delete group permanently?" nil nil
                            {:cancel-fn (fn [] nil)
                             :ok-fn (fn []
                                      (db/post-to-endpoint
                                       "delete-group"
                                       @group-cursor
                                       (fn []
                                         (toaster-oven/add-toast "Group deleted." svg/check "green" nil)
                                         ; Reload the list after deletion
                                         (listing/reload options))))}))}
              "grey" "18px"])]])])))

(defn group-listing []
  (let [data-cursor (reagent/atom [])]
    (fn []
      [listing/listing
       {:data-cursor data-cursor
        :card-fn group-card
        :load-fn (partial db/put-endpoint-in-atom "get-groups" {} data-cursor)
        :new-async-fn (fn [call-with-new-data-fn]
                  ;; (let [uuid (uuid/uuid-string (uuid/make-random-uuid))]
                  ;;   {:_id uuid :name "My Untitled Group"})
                        (db/post-to-endpoint "group" {:name "My Untitled Group"}
                                             (fn [doc]
                                               (println "doc from post-to-endpoint: " doc)
                                               (call-with-new-data-fn doc))))
        :add-caption "Create new group"}])))
