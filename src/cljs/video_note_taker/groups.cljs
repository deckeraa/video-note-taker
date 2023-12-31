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
  ([user-list-atm]
   (load-connected-users user-list-atm identity))
  ([user-list-atm xform-fn]
   (go (let [resp (<! (http/post (db/resolve-endpoint "get-connected-users")
                                {:json-params {}
                                 :with-credentials true}))
             users (set (:body resp))]
         (println "users: " users (xform-fn users))
         (reset! user-list-atm (xform-fn users))))))

(defn load-groups [data-cursor]
  (db/put-endpoint-in-atom "get-groups" {} data-cursor))

(defn load-groups-into-map
  ([data-cursor]
   (load-groups-into-map data-cursor {}))
  ([data-cursor get-groups-options]
   (db/post-to-endpoint
    "get-groups" get-groups-options
    (fn [groups]
      ;; take out _id from each doc and make a map of that
      (reset! data-cursor
              (apply merge (map (fn [v] {(str (:_id v)) v}) groups))))))
  )

(defn group-card
  ([group-cursor options]
   [group-card group-cursor options nil nil])
  ([group-cursor options connected-users-fn]
   [group-card group-cursor options connected-users-fn nil])
  ([group-cursor options connected-users-fn show-b2b-controls?]
   (let [users-cursor (reagent/cursor group-cursor [:users])
         is-editing? (reagent/atom false)
         b2b-auto-add-atom (reagent/atom false)]
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
           [:input {:id "b2b-auto-add"
                    :type :checkbox
                    :checked (:b2b-auto-add @group-cursor)
                    :on-change #(swap! group-cursor assoc :b2b-auto-add (-> % .-target .-checked))}]
           [:label {:for "b2b-auto-add"} "Auto-add this group to uploaded videos."]
           [pick-list
            {
             :data-cursor           users-cursor
             :option-load-fn        (or connected-users-fn load-connected-users)
             :can-delete-option-fn  (fn [option] (not (= option (:name @atoms/user-cursor))))
             :caption               "Select a user:"
             :remove-delegate-atom  (reagent/atom (fn [] nil))
             :cancel-fn #(reset! is-editing? false)
             :ok-fn (fn [] (db/post-to-endpoint "group" @group-cursor
                                                (fn []
                                                  (listing/reload options)
                                                  (reset! is-editing? false))))
             }]
           ]
          [:div {:class "flex flex-columns items-center justify-between br3 shadow-4 pv3 pl3"}
           ;;          [:div (str @group-cursor)]
           [:div
            [:div {:class "f3"} (:name @group-cursor)]
            [:div {:class "f4"} (clojure.string/join " " (:users @group-cursor))]
            (when (:b2b-auto-add @group-cursor)
              [:div {:class "f4"} "Auto-adds to uploaded videos." ])]
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
               "grey" "18px"])]])]))))

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

(defcard-rg bulk-docs-devcards
  (let [resp-atom (reagent/atom "")]
    (fn []
      [:div
       [:button {:on-click
                 #(db/put-endpoint-in-atom
                   "bulk-get-doc"
                   {:docs [{:id "6ad12c0291d9f043fb092d076a000cc1"}
                           {:id "6ad12c0291d9f043fb092d076a006c04"}]} resp-atom)} "bulk-get-doc"]
       [:p (str @resp-atom)]])))
 

(defcard-rg bulk-lookup-to-atom-devcard
  (let [resp-atom (reagent/atom "")]
    (db/bulk-lookup-to-atom ["6ad12c0291d9f043fb092d076a000cc1"
                             "6ad12c0291d9f043fb092d076a006c04"]
                            resp-atom)
    (fn []
      [:p (str @resp-atom)])))
