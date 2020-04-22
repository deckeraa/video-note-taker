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

(defn note-finder []
  (let [input-atm   (reagent/atom "")
        results-atm (reagent/atom "")
        search-fn (fn []
                          (go (let [resp (<! (http/post (db/resolve-endpoint "search-text")
                                                        {:json-params {:text @input-atm}
                                                         :with-credentials true}))]
                                (reset! results-atm resp))))
        search-as-you-type true]
    (fn []
      [:div {:class ""}
       [:input {:type :text :value @input-atm :on-change (fn [e]
                                                           (reset! input-atm (-> e .-target .-value))
                                                           (when (and search-as-you-type
                                                                      (not (empty? @input-atm)))
                                                             (search-fn)))}]
       [:div {:class "br3 ba b--black-10 pa3 mv2 dim"
              :on-click search-fn}
        "Run search"]
       ;[:div (str (:body @results-atm))]
       (when (not (empty? @input-atm))
         [:div {:class ""}
          (map (fn [note]
                 [:div {:class "br3 ba b--black-10 pa3 mv2"}
                  [:div {:class "f2"}
                   [highlight-str (:text note) @input-atm]]
                  [:div {:class "f3"} (str (video-notes/format-time (:time note)) "  " (:video note) )]])
               (get-in @results-atm [:body :docs]))])])))


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
