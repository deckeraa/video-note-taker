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
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn request-video-time [video-cursor time]
  (swap! video-cursor assoc :requested-time time))

(defn try-set-video-time [video-ref-atm video-cursor time]
  (println "video-ref-atm is " (str @video-ref-atm))
  (request-video-time video-cursor time)
  (when-let [video @video-ref-atm]
      (set! (.-currentTime video) time)))

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

(defn change-time-scrub [note-cursor notes-cursor video-ref-atm video-cursor scrub-timer-count-atm change]
  (let [new-time (+ (:time @note-cursor) change)]
    (try-set-video-time video-ref-atm video-cursor new-time)
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

(defn time-scrubber [note-cursor notes-cursor video-ref-atm video-cursor]
  (println "Re-rendering time-scrubber: " @note-cursor)
  (let [scrub-timer-count-atm (reagent/atom 0)]
    (fn [note-cursor notes-cursor video-ref-atm]
      [:div {:class "flex items-center"}
       [svg/chevron-left {:class "ma2 dim"
                          :on-click (partial change-time-scrub note-cursor notes-cursor video-ref-atm video-cursor scrub-timer-count-atm -0.1)}
        "gray" "32px"]
       [:div {:class "f3"}
        [:div (format-time (:time @note-cursor))]]
       [svg/chevron-right {:class "ma2 dim"
                           :on-click (partial change-time-scrub note-cursor notes-cursor video-ref-atm video-cursor scrub-timer-count-atm 0.1)}
        "gray" "32px"]])))

(defn note [note-cursor notes-cursor video-ref-atm video-cursor]
  [:div {:class "br3 ba b--black-10 pa2 ma2 flex justify-between items-center"}
                                        ;   [:button {}]
   [:div {:class "flex items-center"}
    [svg/media-play {:class "ml1 mr4 dim"
                     :on-click (fn []
                                 ;; (when-let [video @video-ref-atm]
                                 ;;   (set! (.-currentTime video) (:time @note-cursor)))
                                 (try-set-video-time video-ref-atm video-cursor (:time @note-cursor))
                                 )} "green" "32px"]
    [editable-field (:text @note-cursor)
     (fn [new-val done-fn]
       (db/put-doc (assoc @note-cursor :text new-val)
                (fn [new-doc]
                  (upsert-note! notes-cursor new-doc)
                  (done-fn))))]]
   [:div {:class "flex items-center ml3"}
    [time-scrubber note-cursor notes-cursor video-ref-atm video-cursor]
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

(defn notes [notes-cursor video-ref-atm video-cursor]
  [:div {:class "flex flex-column items-center"}
   [:div {:class "b--black-10 ba br3 pa2 ph4 flex items-center justify-center bg-green dim"
          :on-click (fn [e] 
                         (when-let [video @video-ref-atm]
                           (let [current-time (.-currentTime video)
                                 uuid (uuid/uuid-string (uuid/make-random-uuid))]
                             ;; Create a new note and save it ot CouchDB
                             (db/put-doc {:_id uuid
                                          :type :note
                                          :video (:_id @video-cursor)
                                          :video-display-name (:display-name @video-cursor) ; denormalized for speed while searching. This information is stored in the video's document.
                                          :users (:users @video-cursor) ; denormalize which users have access to this note, again for speed while searching. Sharing a video is a less frequent use case and can afford to be slower than searching.
                                          :time current-time
                                          :text (str "Note at " current-time)}
                                         (fn [doc]
                                           (swap! notes-cursor (fn [notes]
                                                                 (vec (concat [doc] notes))
                                                                 )))))))}
    [:div {:class "f2 b white"} "Add note"]]
   [:div {:class "flex flex-column"}
    (doall
     (map (fn [idx]
            (let [note-cursor (reagent/cursor notes-cursor [idx])]
              ^{:key (get-in @note-cursor [:_id])}
              [note note-cursor notes-cursor video-ref-atm video-cursor]))
          (range 0 (count @notes-cursor))))]
   [:a {:class "b--black-10 ba br3 pa3 dim link"
        :href (str (db/get-server-url) "/get-notes-spreadsheet?video-id=" (:_id @video-cursor))}
    "Download notes as spreadsheet"]
   ])

(defn load-notes [notes-cursor video-cursor]
  (go (let [resp (<! (http/post (db/resolve-endpoint "get-notes")
                                {:json-params {:video-key (:_id @video-cursor)}
                                 :with-credentials false}
                                ))]
        (db/toast-server-error-if-needed resp nil)
        (reset! notes-cursor (vec (sort-by :time (mapv :doc (:body resp))))))))
