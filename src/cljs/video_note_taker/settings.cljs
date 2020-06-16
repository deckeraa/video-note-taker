(ns video-note-taker.settings
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!]]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.db :as db]
   [video-note-taker.auth :as auth]
   [video-note-taker.video-notes :as video-notes]
   [video-note-taker.groups :as groups]
   [video-note-taker.listing :as listing]
   [video-note-taker.uploads :as uploads]
   [video-note-taker.toaster-oven :as toaster-oven])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn load-settings [settings-cursor]
  (println "Calling load-settings")
  (db/get-doc "settings"
              (fn [doc]
                (reset! settings-cursor doc)
                (println "Got settings: " doc))
              nil))

(defn highlight-str [full-str search-str]
  [:div {}
   (map (fn [piece]
          [:span (if (= piece search-str)
                   {:class "bg-light-yellow"}
                   {:class ""})
           piece])
    (interpose search-str (clojure.string/split full-str (re-pattern search-str))))])

(defcard-rg test-string-highlight
  [:div {}
   [highlight-str "Abby absolutely abhors slabs of drab tabs." "ab"]])

(defn usage-monitor [user-cursor]
  (fn []
    (if @atoms/usage-cursor
      [:div
       [:div {:class "f3"}
        "You are using "
        (/ (Math/round (/ @atoms/usage-cursor 10000000)) 100)
        " GB"
        (when-let [gb-limit (:gb-limit @user-cursor)]
          [:<>
           " of your " gb-limit " GB"])
        " of storage."]
       [:div {:class "f4 i"} "Recent uploads may not yet be reflected in the calculated total."]]
      [:div {:class "f3 i"} "Loading usage information."])))

(defn add-subscription-button [plan]
  [:button {:class "white bg-green br3 bn pa3 ma2 dim"
            :on-click
            (fn [e]
              (toaster-oven/add-toast
               "Purchase 50 additional GB of storage, and 15 additional users?" nil nil
               {:cancel-fn (fn [])
                :ok-fn (fn []
                         (db/post-to-endpoint
                          "add-subscription" {:plan plan}
                          (fn [resp]
                            (db/put-endpoint-in-atom "get-current-user" {} atoms/user-cursor)
                            (println "add-subscription: " resp))))}))}
   (case plan
     :a "Add 50 GB and 15 users for $5/month."
     :b "Add 50 GB and 15 users for $55/year.")])

(defn inc-subscription-button []
  [:button {:class "white bg-green br3 bn pa3 ma2 dim"
            :on-click
            (fn [e]
              (toaster-oven/add-toast
               "Purchase 50 additional GB of storage, and 15 additional users?" nil nil
               {:cancel-fn (fn [])
                :ok-fn (fn []
                         (db/post-to-endpoint
                          "inc-subscription" {}
                          (fn [resp]
                            (db/put-endpoint-in-atom "get-current-user" {} atoms/user-cursor)
                            (println "inc-subscription: " resp))))}))}
   "Add 50GB and 15 users"])

(defn dec-subscription-button []
  [:button {:class "white bg-red br3 bn pa3 ma2 dim"
            :on-click
            (fn [e]
              (toaster-oven/add-toast
               "Remove 50 additional GB of storage?" nil nil
               {:cancel-fn (fn [])
                :ok-fn (fn []
                         (db/post-to-endpoint
                          "dec-subscription" {}
                          (fn [resp]
                            (db/put-endpoint-in-atom "get-current-user" {} atoms/user-cursor)
                            (println "dec-subscription: " resp))))}))}
   "Remove 50GB of storage"])

(defn cancel-subscription-button []
  [:button {:class "white bg-red br3 bn pa3 ma2 dim"
            :on-click
            (fn [e]
              (toaster-oven/add-toast
               "Unsubscribe?" nil nil
               {:cancel-fn (fn [])
                :ok-fn (fn []
                         (db/post-to-endpoint
                          "cancel-subscription" {}
                          (fn [resp]
                            (db/put-endpoint-in-atom "get-current-user" {} atoms/user-cursor)
                            (println "cancel-subscription: " resp))))}))}
   "Cancel subscription"])

(defn settings [settings-cursor login-cursor notes-cursor video-listing-cursor video-cursor screen-cursor uploads-cursor user-cursor]
  (let [file-input-ref-el (reagent/atom nil)
        success-import-counter (reagent/atom nil)
        import-issues     (reagent/atom [])]
    (fn [settings-cursor]
      [:div {:class "w-100 pa3 flex flex-column items-start"}
       [auth/manage-identity login-cursor notes-cursor video-listing-cursor video-cursor screen-cursor uploads-cursor]
       [usage-monitor user-cursor]
       ;; [add-subscription-button :a]
       ;; [add-subscription-button :b]
       [inc-subscription-button]
       [dec-subscription-button]
       [cancel-subscription-button]
       [:h2 "Import & Export"]
       [:a (merge {:style {:text-align :center}}
                (if (uploads/uploads-in-progress?)
                  {:title "Cannot download spreadsheet of notes while upload is in progress."
                   :class "white bg-light-blue bn br3 pa3 link ma1 flex items-center"
                   }
                  {:title "Download spreadsheet of all notes"
                   :href (str (db/get-server-url) "get-notes-spreadsheet")
                   :class "white bg-blue bn br3 pa3 dim link ma1 flex items-center"}))
        [:img {:src "./spreadsheet-download.svg" :class "white" :color "white" :width "32px"}]
        [:div {:class "ml2"} "Download spreadsheet of all notes"]]
       (if (uploads/uploads-in-progress?)
         [:div {:class "white bg-light-blue bn br3 pa3 link ma1"
                :title "Cannot download spreadsheet while file upload is in progress."}
          "Download starter spreadsheet"]
         [:a {:class "white bg-blue bn br3 pa3 dim link ma1"
              :href (str (db/get-server-url) "download-starter-spreadsheet")}
          "Download starter spreadsheet"])
       [:label {:for "spreadsheet-upload"
                :class "white bg-blue bn br3 pa3 ma1"}
        "Import spreadsheet "]
       [:input {:id "spreadsheet-upload"
                :name "file"
                :type "file"
                :multiple false
                :class "dn"
                :ref (fn [el]
                       (reset! file-input-ref-el el))
                :on-change (fn [e]
                             (when-let [file-input @file-input-ref-el]
                               (go (let [resp (<! (http/post
                                                   (db/resolve-endpoint "upload-spreadsheet")
                                                   {:multipart-params
                                                    [["file" (aget (.-files file-input) 0)]]}))]
                                     (video-notes/load-notes notes-cursor video-cursor) ; reload notes
                                     (reset! import-issues (get-in resp [:body :didnt-import]))
                                     (reset! success-import-counter (get-in resp [:body :successfully-imported]))))))}]
       (when @success-import-counter
         [:div {:class "ma2"}
          (str "Successfully imported " @success-import-counter " notes.")])
       (when (not (empty? @import-issues))
         [:div {:class "ma2"}
          "The following lines were not imported: "
          [:ul {:class ""}
           (map (fn [issue]
                  ^{:key (str (:line issue))}
                  [:li (str (:line issue) ": " (:reason issue))])
                @import-issues)]])
       ;; [:a {:class "b--black-10 ba br3 pa3 mt4 dim w6 link"
       ;;      :href (str (db/get-server-url) "/get-notes-spreadsheet")}
       ;;  "Download all notes as spreadsheet"]
       (when (auth/is-admin?)
         (let [group-cursor (reagent/atom [])]
           [:div
            [:h2 "Manage Groups"]
            [groups/group-listing]
;            [groups/groups group-cursor]
            ]))
       (when (auth/is-admin?)
         [:div
          [:h2 "Developer settings"]
          [:div {:class "flex items-center"}
           [:input {:type :checkbox :class "ma2"
                    :checked (:show-app-state @settings-cursor)
                    :on-change (fn [e]
                                 (swap! settings-cursor assoc :show-app-state (-> e .-target .-checked))
                                 (db/put-doc @settings-cursor (fn [new-doc] (reset! settings-cursor new-doc))))}]
           [:div "Show app-state atom at the bottom of each page"]]
          [auth/user-creation]])])))
