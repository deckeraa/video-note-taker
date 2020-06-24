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
   [video-note-taker.auth-util :as auth-util]
   [video-note-taker.toaster-oven :as toaster-oven]
   [video-note-taker.access-shared :as access])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn run-cookie-renewer
  "Creates a recurring call to the server to refresh the authorization cookie."
  []
  (js/setTimeout (fn []
                   (go (let [resp (<! (http/post (db/resolve-endpoint "cookie-check")
                                                        {:json-params {}
                                                         :with-credentials true}))]
                         (run-cookie-renewer))))
                 (* 25 60 1000)))

(defn load-user-cursor
  ([atm]
   (db/put-endpoint-in-atom "get-current-user" {} atm)))

(defcard-rg load-user-cursor
  "Don't forget to login to set the auth cookie before running this."
  (let [resp-atom (reagent/atom nil)]
    (fn []
      [:div
       [:button {:on-click (fn [] (load-user-cursor resp-atom)) } "load-user-cursor"]
       [:p (str @resp-atom)]])))

(defn login
  "Reagent control that allows a user to log in (or optionally create new users).
   At present the new user creation functionality is behind wrap-cookie-auth on the server;
   a better security model for new user creation will be needed to turn it back on client-side."
  [logged-in-atm]
  (let [user-atm (reagent/atom "")
        pass-atm (reagent/atom "")
        pass-rpt-atm (reagent/atom "")
        login-failed-atm (reagent/atom false)
        can't-set-cookie-atm (reagent/atom false)
        creating-new-user? (reagent/atom false)
        allow-new-user-creation? false]
    (fn []
      [:div {:class "flex flex-column items-center justify-center bg-white ma4 pa1 pa3-ns mt4 br3"
             :style {:background-color "#ffffffd0"}}
       [:div {:class "f1 blue b tc"}
        "Family" [:wbr] "Memory" [:wbr] "Stream"]
       [:div {:class "flex flex-column items-center justify-center w-80"}
        [:div {:class "flex items-center flex-wrap ma1"}
         [:div {:class ""} "Username"]
         [:input {:class "mh2" :type :text :value @user-atm :on-change #(reset! user-atm (-> % .-target .-value))}]]
        [:div {:class "flex items-center flex-wrap ma1"}
         [:div {:class ""} "Password"]
         [:input {:class "mh2" :type :password :value @pass-atm :on-change #(reset! pass-atm (-> % .-target .-value))}]]
        (when (and @creating-new-user? allow-new-user-creation?)
          [:div {:class "flex items-center flex-wrap ma1"}
           [:div {:class ""} "Repeat password"]
           [:input {:class "mh2" :type :password :value @pass-rpt-atm :on-change #(reset! pass-rpt-atm (-> % .-target .-value))}]])
        (when (and (and @creating-new-user? allow-new-user-creation?)
;                   (not (empty? @pass-rpt-atm))
                   (not (= @pass-atm @pass-rpt-atm)))
          [:div {:class "f5 red"}
           "Passwords do not match."])
        (if (and @creating-new-user? allow-new-user-creation?)
          [:button {:class "f2 br3 white bg-blue bn pa3 mv2 dim tc w5"
               :on-click (fn []
                           (reset! login-failed-atm false)
                           (if (= @pass-atm @pass-rpt-atm)
                             (go (let [resp (<! (http/post (db/resolve-endpoint "create-user")
                                                           {:json-params {:user @user-atm
                                                                          :pass @pass-atm}
                                                            :with-credentials false} ;; no need to pass cookies while logging in -- the user shouldn't have our cookie at this point
                                                           ))]
                                   (if (and (:body resp) (= 200 (:status resp)))
                                     (js/setTimeout #(swap! logged-in-atm inc) 200)
                                     (reset! login-failed-atm true))))
                             (reset! login-failed-atm true)))}
         "Create user"]
          [:button {:class "f2 br3 white bg-blue bn pa3 mv2 dim tc w5"
                 :on-click (fn []
                             (reset! login-failed-atm false)
                             (reset! can't-set-cookie-atm false)
                             (go (let [resp (<! (http/post (db/resolve-endpoint "login")
                                                           {:json-params {:user @user-atm
                                                                          :pass @pass-atm}
                                                            :with-credentials false} ;; no need to pass cookies while creating a user -- the user shouldn't have our cookie at this point
                                                           ))]
                                   (println "login response: " resp)
                                   (swap! logged-in-atm inc)
                                   (if (and (= 200 (:status resp))
                                            (:body resp))
                                     (if (auth-util/needs-auth-cookie)
                                         (reset! can't-set-cookie-atm true)
                                         (do
                                           (load-user-cursor atoms/user-cursor)
                                           ;(reset! atoms/user-cursor (:body resp))
                                           (js/setTimeout #(swap! logged-in-atm inc) 200)))
                                     (reset! login-failed-atm true)))))}
           "Login"])
        (when allow-new-user-creation?
          (if @creating-new-user? 
            [:div {:class "f4 br3 ba b--black-10 pa3 mv4 dim tc w5"
                   :on-click (fn [] (reset! creating-new-user? false))}
             "... or use existing user"]
            [:div {:class "f4 br3 ba b--black-10 pa3 mv4 dim tc w5"
                   :on-click (fn [] (reset! creating-new-user? true))}
             "... or create a new user"]))
        (when @login-failed-atm
          [:div {:class "f3 br1 white bg-red b tc pa3 ma3"}
           (if @creating-new-user?
             "Couldn't create new user :("
             "Login failed or user not yet created :(")])
        (when @can't-set-cookie-atm
          [:div {:class "f3 br1 white bg-red b tc pa3 ma3"}
           "Sucessfully logged in but could not set the authorization cookie.
            Please enable cookies."])]])))

(defcard-rg user-creation-card
  "Can be used to set the cookie."
  [login (reagent/atom {})])

(defn user-creation []
  (let [user-atm (reagent/atom "")
        pass-atm (reagent/atom "")
        pass-rpt-atm (reagent/atom "")
        creation-report-atm (reagent/atom nil)]
    (fn []
      [:div {:class "flex flex-column items-center justify-center"}
       [:div {:class "flex flex-column items-center justify-center w-80"}
        [:div {:class "flex items-center flex-wrap ma1"}
         [:div {:class ""} "Username"]
         [:input {:class "mh2" :type :text :value @user-atm :on-change #(reset! user-atm (-> % .-target .-value))}]]
        [:div {:class "flex items-center flex-wrap ma1"}
         [:div {:class ""} "Password"]
         [:input {:class "mh2" :type :password :value @pass-atm :on-change #(reset! pass-atm (-> % .-target .-value))}]]
        [:div {:class "flex items-center flex-wrap ma1"}
         [:div {:class ""} "Repeat password"]
         [:input {:class "mh2" :type :password :value @pass-rpt-atm :on-change #(reset! pass-rpt-atm (-> % .-target .-value))}]]
        (when (not (= @pass-atm @pass-rpt-atm))
          [:div {:class "f5 red"}
           "Passwords do not match."])
        [:button {:class  (str "f2 br3 white bn pa3 mv2 tc w5 "
                               (if (and (= @pass-atm @pass-rpt-atm)
                                        (not (empty? @pass-atm)))
                                 "bg-blue dim"
                                 "bg-light-blue"
                                 ))
                  :on-click (fn []
                              (reset! creation-report-atm nil)
                              (when (= @pass-atm @pass-rpt-atm)
                                (db/post-to-endpoint
                                 "create-user"
                                 {:user @user-atm
                                  :pass @pass-atm}
                                 (fn [body]
                                   (println "User created!")
                                   (reset! creation-report-atm true))
                                 (fn [body raw]
                                   (println "Couldn't create user:" raw)
                                   (reset! creation-report-atm false)
                                   ))
                                ))}
         "Create user"]
        (case @creation-report-atm
          true
          [:div {:class "f3 br1 white bg-green b tc pa3 ma3"}
           "Created new user :)"]
          false
          [:div {:class "f3 br1 white bg-red b tc pa3 ma3"}
           "Couldn't create new user :("]
          [:div])]])))

(defcard-rg user-creation-card
  "If you have the cookie set this will actually create new users."
  [user-creation])

(defn password-changer
  "Reagent component to change a users password."
  []
  (let [pass-atm     (reagent/atom "")
        pass-rpt-atm (reagent/atom "")]
    (fn []
      [:div {:class ""}
       [:h3 "Change password"]
       [:div {:class "flex items-center justify-between"}
        [:label "Password: "]
        [:input {:class "mh2" :type :password :value @pass-atm :on-change #(reset! pass-atm (-> % .-target .-value))}]]
       [:div {:class "flex items-center justify-between"}
        [:label "Repeat: "]
        [:input {:class "mh2" :type :password :value @pass-rpt-atm :on-change #(reset! pass-rpt-atm (-> % .-target .-value))}]]
       [:button {:class (str "bn br3 white pa2 ma2 "
                             (if (and (= @pass-atm @pass-rpt-atm)
                                      (not (empty? @pass-atm)))
                               "bg-green dim" "bg-light-green"))
                 :on-click (fn []
                             (go (let [resp (<! (http/post (db/resolve-endpoint "change-password")
                                                           {:json-params {:pass @pass-atm}
                                                            :with-credentials true}))]
                                        ; check the result here
                                   (if (true? (get-in resp [:body]))
                                     (toaster-oven/add-toast "Password changed." svg/check "green" nil)
                                     (toaster-oven/add-toast (str "Couldn't update password :(") svg/x "red" nil))
                                   )))}
        "Change password"]])))

(defn user-name-picker [username-atom validated-username-atom]
  (let [check-ctr (reagent/atom 0)
        username-status (reagent/atom :none)]
    (fn []
      [:div {:class "flex items-center flex-wrap flex-nowrap-ns h2"}
       [:div {:class "flex"}
        [:p {:class "ma1" :style {:width "5em"}} "Username: "]
        [:input {:type :text :value @username-atom
                 :style {:width "15em"}
                 :class "mr2"
                 :on-change
                 (fn [e]
                   (let [username (-> e .-target .-value)]
                     (if (empty? username)
                       (reset! username-status :none)
                       (reset! username-status nil))
                     (reset! username-atom username)
                     (reset! validated-username-atom nil)
                     (swap! check-ctr inc)
                     (js/setTimeout
                      (fn []
                        (let [username @username-atom]
                          (when (and
                                 (= 0 (swap! check-ctr dec))
                                 (not (empty? username)))
                            (do
                              (reset! username-status :checking)
                              (println "username check TODO" username)
                              (db/post-to-endpoint
                               "check-username" {:username username}
                               (fn [resp]
                                 (case (keyword (:status resp))
                                   :available
                                   (do
                                     (reset! username-status :validated)
                                     (reset! validated-username-atom username))
                                   :taken
                                   (reset! username-status :taken)
                                   :invalid
                                   (reset! username-status :invalid))))))))
                      350)))}]]
       (case @username-status
         :none      [:p {:class ""}      "Please pick a username"]
         :checking  [:p {:class ""}      "Checking username...  "]
         :taken     [:p {:class "red"}   "Username is taken.    "]
         :invalid   [:p {:class "red"}   "Username can only contain letters, numbers, and underscores."]
         :validated [:p {:class "green "} "Username is available."]
         [:p {:class "" :style {:width "12em"}}])])))

(defn password-picker [password-atom]
  (let [input-atm (reagent/atom "")]
    (fn []
      [:div {:class "flex items-center flex-wrap flex-nowrap-ns mv2 h2"}
       [:div {:class "flex"}
        [:p {:class "ma1" :style {:width "5em"}} "Password: "]
        [:input {:type :password :value @input-atm
                 :style {:width "15em"}
                 :class "mr2"
                 :on-change
                 (fn [e]
                   (let [value (-> e .-target .-value)]
                     (reset! input-atm value)
                     (if (>= (count value) 4)
                       (reset! password-atom value)
                       (reset! password-atom nil))))}]]
       (cond
         (empty? @input-atm) [:p {:class ""}         "Please pick a password                              "]
         (empty? @password-atom) [:p {:class "red"} "Password must be at " [:br] "least four characters long"]
         :default [svg/check {:class "ma1"} "green" "24px"])])))

(defn is-admin?
  ([] (is-admin? atoms/user-cursor))
  ([user-cursor] (contains? (set (:roles @atoms/user-cursor)) "_admin")))

(defn manage-identity
  "Reagent component used in the Settings screen that allows a user do various profile edit activites such as changing a password or logging out."
  [logged-in-atm notes-cursor video-listing-cursor video-cursor screen-cursor uploads-cursor]
  [:div
   [:h2 "Manage Identity"]
   [:div {} "Hello " (:name @atoms/user-cursor) "."]
   (when (is-admin?)
     [:div {} "You are an admin."])
   [:h3 "Log out"]
   [:div {:class "f3 br1 white bg-light-red b tc pa3 ma3 dim"
          :on-click (fn []
                      (go (let [resp (<! (http/post (db/resolve-endpoint "logout")
                                                    {:with-credentials true}))]
                            (println resp)
                            ;; clear out the current app state
                            (reset! notes-cursor [])
                            (reset! video-listing-cursor nil)
                            (reset! video-cursor {})
                            (reset! screen-cursor [:video-selection])
                            (reset! uploads-cursor {})
                            ;; redraw core page
                            (swap! logged-in-atm inc))))}
    "Log out"]
   [password-changer]])

(defn can-upload []         (access/can-upload        (set (:roles @atoms/user-cursor))))
(defn can-delete-videos [] (access/can-delete-videos (set (:roles @atoms/user-cursor))))
(defn can-change-video-display-name [] (access/can-change-video-display-name (set (:roles @atoms/user-cursor))))
(defn can-change-video-share-settings [] (access/can-change-video-share-settings (set (:roles @atoms/user-cursor))))
(defn can-edit-others-notes [] (access/can-edit-others-notes (set (:roles @atoms/user-cursor))))
(defn can-import-spreadsheet [] (access/can-import-spreadsheet (set (:roles @atoms/user-cursor))))
(defn can-create-family-member-users [] (access/can-create-family-member-users (set (:roles @atoms/user-cursor))))
(defn can-create-groups [] (access/can-create-groups (set (:roles @atoms/user-cursor))))
(defn can-modify-subscription [] (access/can-modify-subscription (set (:roles @atoms/user-cursor))))

(defn is-users-note [note] (= (:created-by note) (:name @atoms/user-cursor)))


