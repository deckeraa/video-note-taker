(ns video-note-taker.groups
  (:require [reagent.core :as reagent]
            [cljs-uuid-utils.core :as uuid]
            [video-note-taker.svg :as svg]
            [video-note-taker.atoms :as atoms]
            [video-note-taker.video-notes :refer [load-connected-users]]
            [video-note-taker.pick-list :refer [pick-list]]
            [video-note-taker.listing :as listing]
            [video-note-taker.editable-field :as editable-field]
            [video-note-taker.db :as db])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]))

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

(defn group-card [group-cursor]
  (let [users-cursor (reagent/cursor group-cursor [:users])
        is-editing? (reagent/atom false)]
    (fn []
      [:div
       (if @is-editing?
         [:div
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
            :ok-fn (fn [] (db/post-to-endpoint "group" @group-cursor #(reset! is-editing? false)))
            }]]
         [:div {:class "flex flex-columns items-center justify-between br3 shadow-4 pv3 pl3"}
                                        ;          [:div (str @group-cursor)]
          [:div
           [:div {:class "f3"} (:name @group-cursor)]
           [:div {:class "f4"} (clojure.string/join " " (:users @group-cursor))]]
          [svg/pencil {:class "ma3" :on-click #(reset! is-editing? true)} "grey" "18px"]])])))

(defn group-listing []
  (let [data-cursor (reagent/atom [])]
    (fn []
      [listing/listing
       {:data-cursor data-cursor
        :card-fn group-card;(fn [group-cursor] [:div {} (str "group-cursor " @group-cursor)])
        :load-fn (partial db/put-endpoint-in-atom "get-groups" {} data-cursor)
        :new-async-fn (fn [call-with-new-data-fn]
                  ;; (let [uuid (uuid/uuid-string (uuid/make-random-uuid))]
                  ;;   {:_id uuid :name "My Untitled Group"})
                        (db/post-to-endpoint "group" {:name "My Untitled Group"}
                                             (fn [doc] (call-with-new-data-fn doc))))
        :add-caption "Create new group"}])))
