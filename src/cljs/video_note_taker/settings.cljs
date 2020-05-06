(ns video-note-taker.settings
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!]]
   [video-note-taker.db :as db]
   [video-note-taker.auth :as auth]
   [video-note-taker.video-notes :as video-notes]
   [video-note-taker.groups :as groups])
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

(defn settings [settings-cursor login-cursor notes-cursor video-listing-cursor video-cursor screen-cursor]
  (let [file-input-ref-el (reagent/atom nil)
        success-import-counter (reagent/atom nil)
        import-issues     (reagent/atom [])]
    (fn [settings-cursor]
      [:div {:class "w-100 pa3 flex flex-column items-start"}
       [auth/manage-identity login-cursor notes-cursor video-listing-cursor video-cursor screen-cursor]
       [:h2 "Import & Export"]
       [:a {:class "b--black-10 ba br3 pa3 dim link ma1"
        :href (str (db/get-server-url) "download-starter-spreadsheet")}
        "Download starter spreadsheet"]
       [:label {:for "spreadsheet-upload"
                :class "b--black-10 ba br3 pa3 ma1"}
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
            [groups/groups group-cursor]]))
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
