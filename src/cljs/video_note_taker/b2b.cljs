(ns video-note-taker.b2b
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.svg :as svg]
   [video-note-taker.auth :as auth]
   [video-note-taker.db :as db]
   [video-note-taker.listing :as listing]
   [video-note-taker.editable-field :as editable-field]
   [video-note-taker.groups :as groups]
   [video-note-taker.video-listing :as video-listing]
   [video-note-taker.video-notes :as video-notes]
   [video-note-taker.toaster-oven :as toaster-oven])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(defn xform-username [id]
  (if id
    (second (re-matches #"org\.couchdb\.user\:(.*)" id))
    ""))

(defn load-in-progress-users
  ([atm selected-user-atom selected-end-user-update-set]
   (db/post-to-endpoint "get-in-progress-users" {}
                        (fn [vals]
                          (println "vals: " vals)
                          (reset! atm vals)
                          (reset! selected-user-atom (xform-username (:_id (first vals))))
                          (doall (map (fn [reactive-fn] (reactive-fn val))
                                      @selected-end-user-update-set))
                          )))
  ([atm]
   (db/put-endpoint-in-atom "get-in-progress-users" {} atm)))

(defn load-all-business-users-users
  ([atm selected-user-atom selected-end-user-update-set]
)
  ([atm]
   (db/put-endpoint-in-atom "get-in-progress-users" {} atm)))

(defn new-end-user-creation [selected-end-user-atom selected-end-user-update-set]
  (let [username-atom (reagent/atom "")
        validated-username-atom (reagent/atom "")
        in-progress-users-atom (reagent/atom nil)
        all-users-atom (reagent/atom nil)
        family-members (reagent/atom nil)
        _ (load-in-progress-users in-progress-users-atom selected-end-user-atom selected-end-user-update-set)
        _ (db/put-endpoint-in-atom "by-business-user" {} all-users-atom)
        ]
    (fn []
      [:div {:class "flex flex-wrap"}
       [:div
        [:h2 {} "Create a new end-user"]
        [:div {:class ""}
         [auth/user-name-picker username-atom validated-username-atom true]]
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
         "Create new end-user"]]
       [:div 
        [:h2 {} "Or select an existing user"]
        [:div {:class ""}
         [:label {:for "inprogresselect"} "In progress:"]
         [:select {:name "inprogresselect" :value @selected-end-user-atom
                   :on-change (fn [e]
                                (let [val (-> e .-target .-value)]
                                  (reset! selected-end-user-atom val)
                                  (doall (map (fn [reactive-fn] (reactive-fn val))
                                              @selected-end-user-update-set)))) }
          (map (fn [{:keys [_id key value]}]
                 (let [username (xform-username _id)]
                   ^{:key username}
                   [:option {:value username} username]))
               @in-progress-users-atom
               )]]
        [:div {:class ""}
         [:label {:for "allusersselect"} "All users you've created:"]
         [:select {:name "allusersselect" :value @selected-end-user-atom
                   :on-change (fn [e]
                                (let [val (-> e .-target .-value)]
                                  (reset! selected-end-user-atom val)
                                  (doall (map (fn [reactive-fn] (reactive-fn val))
                                              @selected-end-user-update-set)))) }
          (map (fn [{:keys [_id key value]}]
                 (let [username (xform-username _id)]
                   ^{:key username}
                   [:option {:value username} username]))
               @all-users-atom
               )]]]
       ;; [:div
       ;;  [:h2 {} "Family Members"]
       ;;  [:p @selected-end-user-atom]
       ;;  [listing/listing {:data-cursor family-members
       ;;                   ;; :load-fn (fn [atm] (db/put-endpoint-in-atom
       ;;                   ;;                     "get-family-members-of-users"
       ;;                   ;;                     {:family-lead @selected-end-user-atom}
       ;;                   ;;                     family-members))
       ;;                   ;;:card-fn (fn [foo] [:p "foo"])
       ;;                   ;;:new-async-fn (fn [] "foo")
       ;;                    }]
       ;;  ]
       ;; [:button {:on-click (fn [] (load-in-progress-users in-progress-users-atom))}
       ;;  "Load in-progress-users"]
       ;;[:p (str "the atom: " @in-progress-users-atom)]
       ])))

(defn family-member-listing-card [family-member-cursor options]
  (let [family-member @family-member-cursor]
    ^{:key (:_id family-member)}
    [:div {:class "flex items-center br3 shadow-4 ma2"}
     [:p {:class "ma2 mr4"} (xform-username (:_id family-member))]
     ;;[:p {:class "ma2"} (:email family-member)]
     [editable-field/editable-field
      (:email family-member)
      (fn [val done-fn]
        (db/post-to-endpoint "put-user-doc" (assoc family-member :email val)
                             (fn []
                               (done-fn)
                               (listing/reload options)
                               )))]
     ]))

(defn family-member-listing [selected-end-user-atom selected-end-user-update-set]
  (let [family-members (reagent/atom nil)
        family-listing-options {:data-cursor family-members
                                :load-fn (fn [atm] (db/put-endpoint-in-atom
                                             "get-family-members-of-users"
                                             {:family-lead @selected-end-user-atom}
                                             family-members))
                                :card-fn family-member-listing-card
                                }
        family-member-username (reagent/atom nil)
        family-member-validated-username (reagent/atom nil)
        email-atom (reagent/atom nil)]
    (swap! selected-end-user-update-set conj (fn [] (listing/reload family-listing-options)))
    (fn []
      [:div
       [:h2 {} "Family Members"]
       [:p @selected-end-user-atom]
       [listing/listing family-listing-options]
       [:h3 {} "Create a new family member"]
       [auth/user-name-picker family-member-username family-member-validated-username]
       [:label {:for "email"} "Email: "]
       [:input {:id "email" :type :text :value @email-atom :on-change #(reset! email-atom (-> % .-target .-value))}]
       [:button {:class (str "br3 white bn pa3 "
                             (if (empty? @family-member-validated-username)
                               "bg-light-green"
                               "bg-green dim"))
                 :on-click (fn []
                             (let [username @family-member-validated-username]
                               (when (not (empty? username))
                                 ;; user creation goes here
                                 (db/post-to-endpoint
                                  "create-user"
                                  {:user username
                                   :req-role "family_member"
                                   :family-lead @selected-end-user-atom
                                   :metadata {:email @email-atom}}
                                  (fn [] (listing/reload family-listing-options))))))}
        "Create new end-user"]
       ])))

(defn load-connected-users
  "Loads the list of connects users. Used to populate the list of options for the share dialog.
  At present, each user is connected to each other user in the Alpha Deploy.
  This will change in the future when a user connection workflow is implemented."
  ([selected-end-user-atom user-list-atm]
   (load-connected-users selected-end-user-atom user-list-atm identity))
  ([selected-end-user-atom user-list-atm xform-fn]
   (println "In load-connected-users " selected-end-user-atom user-list-atm xform-fn)
   (go (let [resp (<! (http/post (db/resolve-endpoint "get-connected-users")
                                {:json-params {:username @selected-end-user-atom}
                                                :with-credentials true}))
             users (set (:body resp))]
         (println "new load-connected-users resp: " resp)
         (println "users: " users (xform-fn users))
         (reset! user-list-atm (xform-fn users))))))

(defn group-listing [groups-cursor selected-end-user-atom selected-end-user-update-set]
  ;; copied from groups.cljs
  (let [;;data-cursor (reagent/atom [])
        group-listing-options
        {:data-cursor groups-cursor
         :card-fn (fn [group-cursor options]
                    [groups/group-card group-cursor options
                     (partial load-connected-users selected-end-user-atom)])
         :load-fn (fn [] ;; (db/put-endpoint-in-atom
                         ;;  "get-groups"
                         ;;  {:username @selected-end-user-atom}
                    ;;  groups-cursor)
                    (println "Calling get-groups with " @selected-end-user-atom)
                    (db/post-to-endpoint
                     "get-groups"
                     {:username @selected-end-user-atom}
                     (fn [resp]
                       (println "Loaded from get-groups: " resp)
                       (reset! groups-cursor resp))))
         :new-async-fn (fn [call-with-new-data-fn]
                         ;; (let [uuid (uuid/uuid-string (uuid/make-random-uuid))]
                         ;;   {:_id uuid :name "My Untitled Group"})
                         (db/post-to-endpoint "group" {:name "My Untitled Group"
                                                       :created-by @selected-end-user-atom}
                                              (fn [doc]
                                                (println "doc from post-to-endpoint: " doc)
                                                (call-with-new-data-fn doc))))
         :add-caption "Create new group"}]
    (swap! selected-end-user-update-set conj (fn []
                                               (println "Re-loading group-listing: " @selected-end-user-atom)
                                               (listing/reload group-listing-options)))
    (fn []
      [listing/listing
       group-listing-options])))

(defn video-listing [groups-cursor selected-end-user-atom selected-end-user-update-set]
  (let [data-cursor (reagent/atom [])
        load-fn (fn []
                    (db/post-to-endpoint
                     "get-video-listing"
                     {:username @selected-end-user-atom}
                     (fn [result]
                       (reset! data-cursor result))))
        video-listing-options
        {:data-cursor data-cursor
         :card-fn (fn [video-cursor options]
                    (let [hover-atm (reagent/atom false)]
                      (fn []
                        (let [video @video-cursor]
                          [:div {:class "br3 shadow-4 ma2 pa2 flex justify-between items-center"
                                 :title (:_id video)
                                 :on-mouse-over (fn [e] (reset! hover-atm true))
                                 :on-mouse-out  (fn [e] (reset! hover-atm false))}
                           [:div {} (:display-name video)]
                           [:button {:class "bn pa2 ma2 br3 dim bg-white"
                                     :title "Share"
                                     :on-click (fn [e]
                                                 (let [remove-delegate-atm (reagent/atom (fn [] nil))]
                                                   (toaster-oven/add-toast
                                                    [video-notes/share-dialog remove-delegate-atm video-cursor (fn [] (println "Reloading the video listing") (load-fn)) selected-end-user-atom]
                                                    remove-delegate-atm atoms/toaster-cursor)))}
                            [svg/share-graph {:class "bg-white"} "gray" "32px"]]
                           [:div {:class "ma2 flex flex-column em"} "Users"
                            [:ul {:class "list pl0"}
                             (map (fn [user] ^{:key user} [:li user])
                                  (:users video))]
                            ]
                           [:div {:class "ma2"} "Groups: "
                            [:ul {:class "list pl0"}
                             (map (fn [user] ^{:key user} [:li user])
                                  (map
                                   (fn [group-id]
                                     (:name
                                      (first
                                       (get
                                        (group-by :_id @groups-cursor)
                                        group-id))))
                                   (:groups video)))]]
                           (if @hover-atm
                             [svg/trash {:on-click
                                         (fn [e]
                                           (.stopPropagation e) ;; prevent this click from registing as a click on the video
                                           (toaster-oven/add-toast
                                            "Delete video permanently?" nil nil
                                            {:cancel-fn (fn [] nil)
                                             :ok-fn (fn []
                                                      (go (let [resp (<! (http/post (db/resolve-endpoint "delete-video")
                                                                                    {:json-params video
                                                                                     :with-credentials true}))]
                                                            (if (= 200 (:status resp))
                                                              (do
                                                                (toaster-oven/add-toast "Video deleted" svg/check "green" nil)
                                                                (db/put-endpoint-in-atom "get-user-usage" {} atoms/usage-cursor)
                                                                (load-fn))
                                                              (toaster-oven/add-toast (str "Couldn't delete video. " (get-in resp [:body :reason])) svg/x "red" nil)
                                                              ))))}))}
                              "gray" "24px"]
                             [:div {:style {:width "24px"}}
                              ;; empty div to reserve space for the trash can on hover
                              ])
                           ]))))
         :load-fn load-fn
         ;; (partial db/put-endpoint-in-atom "get-video-listing"
         ;;          {:username @selected-end-user-atom} data-cursor)
         ;; :new-async-fn (fn [call-with-new-data-fn]
         ;;                 ;; (let [uuid (uuid/uuid-string (uuid/make-random-uuid))]
         ;;                 ;;   {:_id uuid :name "My Untitled Group"})
         ;;                 (db/post-to-endpoint "group" {:name "My Untitled Group"}
         ;;                                      (fn [doc]
         ;;                                        (println "doc from post-to-endpoint: " doc)
         ;;                                        (call-with-new-data-fn doc))))
         ;; :add-caption "Upload video"
         }]
    (swap! selected-end-user-update-set conj (fn [] (listing/reload video-listing-options)))
    (fn []
      [:<>
       [listing/listing
        video-listing-options]
       [video-listing/upload-card selected-end-user-atom data-cursor]]
      )))

(defn activate-and-email [selected-end-user-atom selected-end-user-update-set]
  [:button {:class "white bg-green br3 pa4 ma4 f2"
            :on-click (fn [e]
                        (db/post-to-endpoint
                         "set-passwords-and-email"
                         {:username @selected-end-user-atom}))}
   "Send user activation email"])

(defn business-view []
  (let [selected-end-user-atom (reagent/atom "")
        selected-end-user-update-set (reagent/atom #{})
        groups-cursor (reagent/atom [])
        ]
    (fn []
      [:div {}
       [:h1 {} "1) Select a family lead"]
       ;; [:button {:on-click
       ;;           (fn [e]
       ;;             (go (let [resp (<! (http/post (db/resolve-endpoint "get-connected-users")
       ;;                                           {:json-params {:username @selected-end-user-atom}
       ;;                                            :with-credentials true}))]
       ;;                   (println "resp " resp))))} "load-connected-users"]
       [new-end-user-creation selected-end-user-atom selected-end-user-update-set]
       [:h1 {} "2) Create family members"]
       [family-member-listing selected-end-user-atom selected-end-user-update-set]
       [:h1 {} "3) Create a group to grant access to videos"]
       [group-listing groups-cursor selected-end-user-atom selected-end-user-update-set]
       ;;[:p {} (str (group-by :_id @groups-cursor))]
       [:h1 {} "4) Upload videos"]
       [video-listing groups-cursor selected-end-user-atom selected-end-user-update-set]
       [:h1 {} "5) Activate"]
       [activate-and-email selected-end-user-atom selected-end-user-update-set]
       ])))
