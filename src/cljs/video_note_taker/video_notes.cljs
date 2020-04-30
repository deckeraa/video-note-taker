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
   [video-note-taker.toaster-oven :as toaster-oven]
   [video-note-taker.editable-field :refer [editable-field]]
   [video-note-taker.auth])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn request-video-time [video-options-cursor time]
  (swap! video-options-cursor assoc :requested-time time))

(defn try-set-video-time [video-ref-atm video-options-cursor time]
  (println "video-ref-atm is " (str @video-ref-atm))
  (request-video-time video-options-cursor time)
  (when-let [video @video-ref-atm]
    (set! (.-currentTime video) time)))

(defn load-notes [notes-cursor video-cursor]
  (go (let [resp (<! (http/post (db/resolve-endpoint "get-notes")
                                {:json-params {:video-key (:_id @video-cursor)}
                                 :with-credentials false}
                                ))]
        (db/toast-server-error-if-needed resp nil)
        (reset! notes-cursor (vec (sort-by :time (mapv :doc (:body resp))))))))

(defn upsert-note! [notes-cursor doc]
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

(defn change-time-scrub [note-cursor notes-cursor video-ref-atm video-options-cursor scrub-timer-count-atm change]
  (let [new-time (+ (:time @note-cursor) change)]
    (try-set-video-time video-ref-atm video-options-cursor new-time)
    (swap! note-cursor assoc :time new-time)
    (swap! scrub-timer-count-atm inc)
    (js/setTimeout (fn []
                     (when (= 0 (swap! scrub-timer-count-atm dec))
                       (do
                         (println "Updating the time scrubbing")
                         (db/put-doc @note-cursor (fn [doc] (swap! note-cursor assoc :_rev (:_rev doc))))))) 2000)
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

;; (defn format-time-in-seconds [seconds]
;;   (let [min (Math/floor (/ seconds 60))
;;         sec (as-> (rem seconds 60) $
;;               (str $)
;;               (if (= 1 (count $)) (str "0" $) $))]
;;     (str min ":" sec)))

;; (deftest format-time-in-seconds-test
;;   (is (= "2:00" (format-time-in-seconds 120)))
;;   (is (= "0:12" (format-time-in-seconds  12)))
;;   (is (= "1:01" (format-time-in-seconds  61))))

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

(defn load-connected-users [user-list-atm]
  (go (let [resp (<! (http/get (db/resolve-endpoint "get-connected-users")
                               {}))]
        (reset! user-list-atm (set (:body resp)))
        (println "load-connected-users:" resp))))

(defn share-dialog [remove-delegate-atm video-cursor notes-cursor]
  (let [selected-users-atm (reagent/atom (set (:users @video-cursor)))
        user-input-atm (reagent/atom "")
        user-list-atm  (reagent/atom #{})
        _ (load-connected-users user-list-atm)
        ]
    (fn [remove-delegate-atm video-cursor notes-cursor]
      (println "rendering share dialog")
      [:div {:class "flex flex-column"}
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
       [:div {:class "flex br3"}
        [:select {:type :text
                  :class "bn"
                  :value @user-input-atm
                  :on-change (fn [e]
;                               (println (-> e .-target .-value))
                                        ;                               (reset! user-input-atm (-> e .-target .-value))
                               (swap! selected-users-atm conj (-> e .-target .-value))
                               (reset! user-input-atm "")
                               )}
         (doall (map (fn [name]
                       ^{:key name}
                       [:option {:value name} (if (= name "") "- Select user -" name)])
                     (conj (clojure.set/difference @user-list-atm @selected-users-atm) "")))]
        ;; [:button {:class "bn white bg-green b f2 br3 ma2"
        ;;           :on-click (fn []
        ;;                       (swap! selected-users-atm conj @user-input-atm)
        ;;                       (reset! user-input-atm ""))} "+"]
        ]
       [:div {:class "flex mt2 mh2"}
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e] (@remove-delegate-atm))}
         "Cancel"]
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e]
                              (println "updating permissions: " (vec @selected-users-atm))
                              (@remove-delegate-atm)
                              (go (let [resp (<! (http/post
                                                  (db/resolve-endpoint "update-video-permissions")
                                                  {:json-params (assoc @video-cursor
                                                                       :users (vec @selected-users-atm))
                                                   :with-credentials true}))]
                                    (println "share " resp)
                                    (db/toast-server-error-if-needed resp nil)
                                    (reset! video-cursor (:body resp))
                                    (toaster-oven/add-toast "Video sharing settings updated." svg/check "green" nil)
                                    (load-notes notes-cursor video-cursor)))
                              )}
         "Ok"]]])))

(defcard-rg test-share-dialog
  (let [remove-delegate-atm (reagent/atom (fn [] nil))
        video-cursor        (reagent/atom {:_id "abc123" :users ["alpha" "bravo" "charlie"]})
        notes-cursor        (reagent/atom [])]
    [:div {:class ""}
     [share-dialog remove-delegate-atm video-cursor notes-cursor]]))

(defn note [note-cursor notes-cursor video-ref-atm video-options-cursor]
  [:div {:class "br3 ba b--black-10 pa2 ma2 flex justify-between items-center"}
                                        ;   [:button {}]
   [:div {:class "flex items-center"}
    [svg/media-play {:class "ml1 mr4 dim"
                     :on-click (fn []
                                 (try-set-video-time video-ref-atm video-options-cursor (:time @note-cursor))
                                 )} "green" "32px"]
    [editable-field (:text @note-cursor)
     (fn [new-val done-fn]
       (db/put-doc (assoc @note-cursor :text new-val)
                (fn [new-doc]
                  (upsert-note! notes-cursor new-doc)
                  (done-fn))))]]
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
     "gray" "32px"]]
   ]
  )

(defn notes [notes-cursor video-ref-atm video-cursor video-options-cursor]
  [:div {:class "flex flex-column items-center"}
   [:div {:class "flex justify-between w-80"}
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
                                 (println "resp: " resp)
                                 (swap! notes-cursor
                                        (fn [notes]
                                          (vec (concat [(:body resp)] notes))))))
                           ;; (db/put-doc 
                           ;;             (fn [doc]
                           ;;               (swap! notes-cursor (fn [notes]
                           ;;                                     (vec (concat [doc] notes))
                           ;;                                     ))))
                           )))}
     [:div {:class "f2 b white"} "Add note"]]
    [:button {:class "bn pa3 br3 dim bg-gray"
              :title "Share"
              :on-click (fn [e]
                          (let [remove-delegate-atm (reagent/atom (fn []
                                                                    (println "remove-delegate-atm")))]
                            (println "Adding toast!")
                            (toaster-oven/add-toast [share-dialog remove-delegate-atm video-cursor notes-cursor] remove-delegate-atm atoms/toaster-cursor)
                            ;; (str "Server error: " resp doc) svg/x "red"
                            ;; {:ok-fn    (fn [] nil)}
                            
                            )
                          )}
     [svg/share-arrow {} "white" "28px"]]]
   [:div {:class "flex flex-column"}
    (doall
     (map (fn [idx]
            (let [note-cursor (reagent/cursor notes-cursor [idx])]
              ^{:key (get-in @note-cursor [:_id])}
              [note note-cursor notes-cursor video-ref-atm video-options-cursor]))
          (range 0 (count @notes-cursor))))]
   [:a {:class "b--black-10 ba br3 pa3 dim link"
        :href (str (db/get-server-url) "/get-notes-spreadsheet?video-id=" (:_id @video-cursor))}
    "Download notes as spreadsheet"]
   ])


