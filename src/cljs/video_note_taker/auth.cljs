(ns video-note-taker.auth
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
   [cljs.test :include-macros true :refer-macros [testing is]]
   [cljs-uuid-utils.core :as uuid]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.svg :as svg]
   [video-note-taker.db :as db]
   [video-note-taker.toaster-oven :as toaster-oven])
  (:require-macros
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn needs-auth-cookie []
  (println "Calling needs-auth-cookie with " (.-cookie js/document))
  (as-> js/document $
    (.-cookie $)
    (clojure.string/split $ "; ")
    (map #(clojure.string/split % "=") $)
    (filter #(and (= (first %) "AuthSession")
                  (not (nil? (second %)))) $)
    (empty? $)))

(defn run-cookie-renewer []
  (js/setTimeout (fn []
                   (go (let [resp (<! (http/post (db/resolve-endpoint "cookie-check")
                                                        {:json-params {}
                                                         :with-credentials true}))]
                         (run-cookie-renewer))))
                 (* 25 60 1000)))

(defn login [logged-in-atm]
  (let [user-atm (reagent/atom "alpha")
        pass-atm (reagent/atom "alpha")
        login-failed-atm (reagent/atom false)]
    (fn []
      [:div {:class "flex flex-column items-center justify-center"}
       [:div {:class "f1 blue b ma3"} "Video Note Taker"]
       [:div {:class "flex flex-column items-center justify-center w-80"}
        [:div {:class "flex items-center flex-wrap ma1"}
         [:div {:class ""} "Username"]
         [:input {:class "mh2" :type :text :value @user-atm :on-change #(reset! user-atm (-> % .-target .-value))}]]
        [:div {:class "flex items-center flex-wrap ma1"}
         [:div {:class ""} "Password"]
         [:input {:class "mh2" :type :text :value @pass-atm :on-change #(reset! pass-atm (-> % .-target .-value))}]]
        [:div {:class "f2 br3 white bg-blue pa3 mv2 dim tc w5"
               :on-click (fn []
                           (reset! login-failed-atm false)
                           (go (let [resp (<! (http/post (db/resolve-endpoint "login")
                                                         {:json-params {:user @user-atm
                                                                        :pass @pass-atm}
                                                          :with-credentials false} ;; no need to pass cookies while logging in -- the user shouldn't have our cookie at this point
                                                         ))]
                                 (if (:body resp)
                                   (js/setTimeout #(swap! logged-in-atm inc) 200)
                                   (reset! login-failed-atm true)))))}
         "Login"]
        (when @login-failed-atm
          [:div {:class "f3 br1 white bg-red b tc pa3 ma3"}
           "Login failed :("])]])))

(defn manage-identity [logged-in-atm]
  [:div
   [:h2 "Manage Identity"]
   [:div {:class "f3 br1 white bg-light-red b tc pa3 ma3 dim"
          :on-click (fn []
                      (go (let [resp (<! (http/post (db/resolve-endpoint "logout")
                                                    {:with-credentials true}))]
                            (println resp)
                            (swap! logged-in-atm inc))))}
    "Log out"]])
