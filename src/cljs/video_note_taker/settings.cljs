(ns video-note-taker.settings
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!]]
   [video-note-taker.db :as db]
   [video-note-taker.auth :as auth])
  (:require-macros
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn load-settings [settings-cursor]
  (println "Calling load-settings")
  (db/get-doc "settings"
              (fn [doc]
                (reset! settings-cursor doc)
                (println "Got settings: " doc))
              nil))

(defn cookie-retriever []
  (let [user-atm (reagent/atom "alpha")
        pass-atm (reagent/atom "alpha")
        cookie-atm (reagent/atom {})
        cookie-check-atm (reagent/atom {})]
    (fn []
      [:div
       [:h2 "Cookie Retriever"]
       [:input {:type :text :value @user-atm :on-change #(reset! user-atm (-> % .-target .-value))}]
       [:input {:type :text :value @pass-atm :on-change #(reset! pass-atm (-> % .-target .-value))}]
       [:div {:class "br3 ba b--black-10 pa3 mv2 dim"
              :on-click (fn []
                          (go (let [resp (<! (http/post (db/resolve-endpoint "get-cookie")
                                                        {:json-params {:user @user-atm
                                                                       :pass @pass-atm}
                                                         :with-credentials false}))]
                                (reset! cookie-atm resp))))}
        "Retrieve Cookie"]
       [:div (str (:body @cookie-atm))]
       [:div {:class "br3 ba b--black-10 pa3 mv2 dim"
              :on-click (fn []
                          (go (let [resp (<! (http/post (db/resolve-endpoint "cookie-check")
                                                        {:json-params {}
                                                         :with-credentials true}))]
                                (reset! cookie-check-atm resp))))}
        "Check cookie"]
       [:div (str (:body @cookie-check-atm))]])))

(defn note-finder []
  (let [input-atm   (reagent/atom "")
        results-atm (reagent/atom "")]
    (fn []
      [:div {:class ""}
       [:input {:type :text :value @input-atm :on-change #(reset! input-atm (-> % .-target .-value))}]
       [:div {:class "br3 ba b--black-10 pa3 mv2 dim"
              :on-click (fn []
                          (go (let [resp (<! (http/post (db/resolve-endpoint "search-text")
                                                        {:json-params {:text @input-atm}
                                                         :with-credentials true}))]
                                (reset! results-atm resp))))}
        "Run search"]
       [:div (str (:body @results-atm))]])))


(defn settings [settings-cursor login-cursor]
  (let [file-input-ref-el (reagent/atom nil)
        import-issues     (reagent/atom [])]
    (fn [settings-cursor]
      [:div {:class "w-100 pa3 flex flex-column items-start"}
       [auth/manage-identity login-cursor]
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
       [note-finder]
       [cookie-retriever]
       [:div {:class "flex items-center"}
        [:input {:type :checkbox :class "ma2"
                 :checked (:show-app-state @settings-cursor)
                 :on-change (fn [e]
                              (swap! settings-cursor assoc :show-app-state (-> e .-target .-checked))
                              (db/put-doc @settings-cursor (fn [new-doc] (reset! settings-cursor new-doc))))}]
        [:div "Show app-state atom at the bottom of each page"]]])))
