(ns video-note-taker.settings
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async :refer-macros [go]]
   [video-note-taker.db :as db]))

(defn load-settings [settings-cursor]
  (println "Calling load-settings")
  (db/get-doc "settings"
              (fn [doc]
                (reset! settings-cursor doc)
                (println "Got settings: " doc))
              nil))

(defn settings [settings-cursor]
  (let [file-input-ref-el (reagent/atom nil)
        import-issues     (reagent/atom [])]
    (fn [settings-cursor]
      [:div {:class "w-100 pa3"}
       [:h2 "Import & Export"]
       [:form {:id "upload-form" :action (str (db/get-server-url) "/upload-spreadsheet") :method "post" :enctype "multipart/form-data"
               }
        [:input {:name "file" :type "file" :size "20" :multiple false
                 :ref (fn [el]
                      (reset! file-input-ref-el el))}]]
       [:div {:class "br3 ba b--black-10 pa2 dim w5"
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
       [:h2 "Developer settings"]
       [:div {:class "flex items-center"}
        [:input {:type :checkbox :class "ma2"
                 :checked (:show-app-state @settings-cursor)
                 :on-change (fn [e]
                              (swap! settings-cursor assoc :show-app-state (-> e .-target .-checked))
                              (db/put-doc @settings-cursor (fn [new-doc] (reset! settings-cursor new-doc))))}]
        [:div "Show app-state atom at the bottom of each page"]]])))
