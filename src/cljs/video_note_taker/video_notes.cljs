(ns video-note-taker.video-notes
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
   [cljs.test :include-macros true :refer-macros [testing is]]
   [cljs-uuid-utils.core :as uuid]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.svg :as svg]
   [video-note-taker.db :as db]
   [video-note-taker.video :as video :refer [try-set-video-time]]
   [video-note-taker.toaster-oven :as toaster-oven]
   [video-note-taker.editable-field :refer [editable-field]]
   [video-note-taker.auth])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn load-notes [notes-cursor video-cursor]
  (go (let [resp (<! (http/post (db/resolve-endpoint "get-notes")
                                {:json-params {:video-key (:_id @video-cursor)}
                                 :with-credentials false}
                                ))]
        (db/toast-server-error-if-needed resp nil)
        (when (= 200 (:status resp))
          (reset! notes-cursor (vec (sort-by :time (mapv :doc (:body resp)))))))))

(defn update-note!
  "Updates a given note in the list of notes. Sorts the list by time."
  [notes-cursor doc]
  (swap! notes-cursor
         (fn [notes]
           (let [new-notes
                 (mapv (fn [note]
                         (if (= (:_id note) (:_id doc))
                           doc ; update if they are the same
                           note ; otherwise leave as is
                           ))
                       notes)]
             (vec (sort-by :time new-notes)))))
  )

(defn change-time-scrub
  "Updates the time scrubber value by the change given.
   Batches updates to wait until 2 seconds after the last change is made."
  [note-cursor notes-cursor video-ref-atm video-options-cursor scrub-timer-count-atm change]
  (let [new-time (+ (:time @note-cursor) change)]
    (try-set-video-time video-ref-atm video-options-cursor new-time)
    (swap! note-cursor assoc :time new-time)
    (swap! scrub-timer-count-atm inc) ;; this is the count used to batch scrub changes together
    (js/setTimeout (fn []
                     (when (= 0 (swap! scrub-timer-count-atm dec))
                       (do
                         (db/put-doc @note-cursor
                                     ;; Update the _rev, we already updated the note-cursor above
                                     ;; Only the _rev is updated, since the note-cursor could have mutated during server call and, if so, will result in another call down.
                                     ;; This means that the note-cursor will be updated and saved corretly, but it's important that we update the _rev before that second call happens, so that CouchDB accepts the change.
                                     (fn [doc] (swap! note-cursor assoc :_rev (:_rev doc)))
                                     )))) 2000)
    ))

(defn format-time [time-in-seconds]
  (let [minutes (Math/floor (/ time-in-seconds 60)) ; floor instead of round since the remainder of minutes is dplayed in seconds
        seconds (/ (Math/round (* (mod time-in-seconds 60) 10)) 10)
        ;; pad to two digits if needed
        padded-seconds (if (< seconds 10)
                         (str "0" seconds)
                         (str seconds))
        ;; make sure there's a decimal point on the end
        padded-seconds (if (clojure.string/includes? padded-seconds ".")
                         padded-seconds
                         (str padded-seconds ".0"))]
    (str minutes ":" padded-seconds)))

(deftest format-time-test
  (is (= (format-time 40.4583330000001) "0:40.5"))
  (is (= (format-time 95.553641) "1:35.6"))
  (is (= (format-time 95.00001) "1:35.0")))

(defn time-scrubber [note-cursor notes-cursor video-ref-atm video-options-cursor]
  (let [scrub-timer-count-atm (reagent/atom 0)]
    (fn [note-cursor notes-cursor video-ref-atm]
      [:div {:class "flex items-center"}
       [svg/chevron-left {:class "ma2 dim"
                          :on-click (partial change-time-scrub note-cursor notes-cursor video-ref-atm video-options-cursor scrub-timer-count-atm -0.1)}
        "gray" "32px"]
       [:div {:class "f3"}
        [:div (format-time (:time @note-cursor))]]
       [svg/chevron-right {:class "ma2 dim"
                           :on-click (partial change-time-scrub note-cursor notes-cursor video-ref-atm video-options-cursor scrub-timer-count-atm 0.1)}
        "gray" "32px"]])))

(defcard-rg time-scrubber-card
  "Controls not hooked up, time should read 12:45.2"
  [time-scrubber (reagent/atom {:time 765.2})])

(defn load-connected-users
  "Loads the list of connects users. Used to populate the list of options for the share dialog.
  At present, each user is connected to each other user in the Alpha Deploy.
  This will change in the future when a user connection workflow is implemented."
  [user-list-atm]
  (go (let [resp (<! (http/get (db/resolve-endpoint "get-connected-users")
                               {}))]
        (reset! user-list-atm (set (:body resp))))))

(defn share-dialog
  "Dialog that allows a user to share the video with other users."
  [remove-delegate-atm video-cursor notes-cursor]
  (let [selected-users-atm (reagent/atom (set (:users @video-cursor)))
        user-input-atm (reagent/atom "")
        user-list-atm  (reagent/atom #{})
        _ (load-connected-users user-list-atm)
        ]
    (fn [remove-delegate-atm video-cursor notes-cursor]
      [:div {:class "flex flex-column"}
       ;; List out the current usres who selected to be on the video
       [:div {} "Share with:"]
       [:ul
        (doall (map (fn [user]
                      ^{:key user}
                      [:li {:class "flex items-center justify-center"}
                       user
                       (when (not (= user (:uploaded-by @video-cursor)))
                         [svg/x {:class "ma2 dim"
                                 :on-click (fn []
                                             (swap! selected-users-atm disj user))}
                          "red" "12px"])])
                    @selected-users-atm))]
       ;; A selection box for adding new users
       [:div {:class "flex br3"}
        [:select {:type :text
                  :class "bn"
                  :value @user-input-atm
                  :on-change (fn [e]
                               (swap! selected-users-atm conj (-> e .-target .-value))
                               (reset! user-input-atm "")
                               )}
         (doall (map (fn [name]
                       ^{:key name}
                       [:option {:value name} (if (= name "") "- Select user -" name)])
                     (conj (clojure.set/difference @user-list-atm @selected-users-atm) "")))]]
       ;; Cancel and OK buttons
       [:div {:class "flex mt2 mh2"}
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e] (@remove-delegate-atm))} ; closes the dialog
         "Cancel"]
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e]
                              (@remove-delegate-atm) ; closes the dialog
                              (go (let [resp (<! (http/post
                                                  (db/resolve-endpoint "update-video-permissions")
                                                  {:json-params (assoc @video-cursor
                                                                       :users (vec @selected-users-atm))
                                                   :with-credentials true}))]
                                    (db/toast-server-error-if-needed resp nil)
                                    (reset! video-cursor (:body resp))
                                    (toaster-oven/add-toast "Video sharing settings updated." svg/check "green" nil)
                                    (load-notes notes-cursor video-cursor))))}
         "Ok"]]])))

(defcard-rg test-share-dialog
  (let [remove-delegate-atm (reagent/atom (fn [] nil))
        video-cursor        (reagent/atom {:_id "abc123" :users ["alpha" "bravo" "charlie"]})
        notes-cursor        (reagent/atom [])]
    [:div {:class ""}
     [share-dialog remove-delegate-atm video-cursor notes-cursor]]))

(defn note [note-cursor notes-cursor video-ref-atm video-options-cursor]
  [:div {:class "br3 ba b--black-10 pa2 ma2 flex justify-between items-center"}
   [:div {:class "flex items-center"}
    [svg/media-play {:class "ml1 mr4 dim"
                     :on-click (fn []
                                 (try-set-video-time video-ref-atm video-options-cursor (:time @note-cursor))
                                 )} "green" "32px"]
    [:div
     [editable-field (:text @note-cursor)
      (fn [new-val done-fn]
        (db/put-doc (assoc @note-cursor :text new-val)
                    (fn [new-doc]
                      (update-note! notes-cursor new-doc)
                      (done-fn))))]
     [:div {:class "i mid-gray"} (str "By " (:created-by @note-cursor)
                                      (when-let [last-edit (:last-edit @note-cursor)]
                                        (str " on " last-edit)))]]]
   [:div {:class "flex items-center ml3"}
    [time-scrubber note-cursor notes-cursor video-ref-atm video-options-cursor]
    [svg/trash {:class "dim ml3"
                :on-click (fn []
                            (toaster-oven/add-toast
                             "Delete note?" nil nil
                             {:cancel-fn (fn [] nil)
                              :ok-fn (fn [] 
                                       (db/delete-doc @note-cursor
                                                   (fn [resp]
                                                     (swap! notes-cursor (fn [notes]
                                                                           (vec (filter #(not (= (:_id @note-cursor) (:_id %)))
                                                                                                               notes)))))))}))}
     "gray" "32px"]]])

(defcard-rg note-card
  "Controls not hooked up."
  [note
   (reagent/atom {:time 123.4
                  :text "Sample note text <em>here</em>. <blink>HTML tags</blink> should be properly escaped.<script>alert(document.cookie)</script>"})])

(defn notes [notes-cursor video-ref-atm video-cursor video-options-cursor]
  [:div {:class "flex flex-column items-center w-100"}
   [:div {:class "flex justify-between w-100 pa2"}
    ;; The Add Note button
    [:div {:class "b--black-10 ba br3 pa2 ph4 flex items-center justify-center bg-green dim"
           :on-click (fn [e] 
                       (when-let [video @video-ref-atm]
                         (let [current-time (.-currentTime video)
                               uuid (uuid/uuid-string (uuid/make-random-uuid))]
                           ;; Create a new note and save it ot CouchDB
                           (go (let [resp (<! (http/post
                                               (db/resolve-endpoint "create-note")
                                               {:json-params
                                                {:_id uuid
                                                 :type :note
                                                 :video (:_id @video-cursor)
                                                 :time current-time
                                                 :text (str "Note at " current-time)
                                                 :video-display-name (:display-name @video-cursor) ; denormalized for speed while searching. This information is stored in the video's document.
                                                 :users (:users @video-cursor) ; denormalize which users have access to this note, again for speed while searching. Sharing a video is a less frequent use case and can afford to be slower than searching.
                                                 }
                                                :with-credentials true}))]
                                 ;; Add the new note to the top of the notes list.
                                 (swap! notes-cursor
                                        (fn [notes]
                                          (vec (concat [(:body resp)] notes)))))))))}
     [:div {:class "f2 b white"} "Add note"]]
    ;; The "Share" button
    [:button {:class "bn pa3 br3 dim bg-gray"
              :title "Share"
              :on-click (fn [e]
                          (let [remove-delegate-atm (reagent/atom (fn [] nil))]
                            (toaster-oven/add-toast [share-dialog remove-delegate-atm video-cursor notes-cursor] remove-delegate-atm atoms/toaster-cursor)))}
     [svg/share-arrow {} "white" "28px"]]]
   ;; List out the notes
   [:div {:class "flex flex-column"}
    (doall
     (map (fn [idx]
            (let [note-cursor (reagent/cursor notes-cursor [idx])]
              ^{:key (get-in @note-cursor [:_id])}
              [note note-cursor notes-cursor video-ref-atm video-options-cursor]))
          (range 0 (count @notes-cursor))))]
   ;; Link to download notes as a spreadsheet
   [:a {:class "b--black-10 ba br3 ma2 pa3 dim link"
        :href (str (db/get-server-url) "get-notes-spreadsheet?video-id=" (:_id @video-cursor))}
    "Download notes as spreadsheet"]])


