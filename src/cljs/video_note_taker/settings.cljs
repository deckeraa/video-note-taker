(ns video-note-taker.settings
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!]]
   [video-note-taker.db :as db]
   [video-note-taker.auth :as auth]
   [video-note-taker.video-notes :as video-notes])
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
        import-issues     (reagent/atom [])]
    (fn [settings-cursor]
      [:div {:class "w-100 pa3 flex flex-column items-start"}
       [auth/manage-identity login-cursor notes-cursor video-listing-cursor video-cursor screen-cursor]
       [:h2 "Import & Export"]
       [:form {:id "upload-form" :action (str (db/get-server-url) "/upload-spreadsheet") :method "post" :enctype "multipart/form-data"
               }
        [:input {:name "file" :type "file" :size "20" :multiple false
                 :ref (fn [el]
                      (reset! file-input-ref-el el))}]]
       [:div {:class "br3 ba b--black-10 pa3 mv2 dim"
              :on-click (fn []
                          (when-let [file-input @file-input-ref-el]
                            (go (let [resp (<! (http/post
                                                (db/resolve-endpoint "upload-spreadsheet")
                                                {:multipart-params
                                                 [["file" (aget (.-files file-input) 0)]]}))]
                                  (reset! import-issues (get-in resp [:body :didnt-import]))
                                  ))
                              ))}
        "Import spreadsheet"]
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
       [:h2 "Developer settings"]
       [:div {:class "flex items-center"}
        [:input {:type :checkbox :class "ma2"
                 :checked (:show-app-state @settings-cursor)
                 :on-change (fn [e]
                              (swap! settings-cursor assoc :show-app-state (-> e .-target .-checked))
                              (db/put-doc @settings-cursor (fn [new-doc] (reset! settings-cursor new-doc))))}]
        [:div "Show app-state atom at the bottom of each page"]]])))
