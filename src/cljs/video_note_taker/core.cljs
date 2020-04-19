(ns video-note-taker.core
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
   [cljs-uuid-utils.core :as uuid]
   [video-note-taker.svg :as svg])
  (:require-macros
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vars

(defonce app-state
  (reagent/atom {:notes []}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page

(defn video [video-ref-atm video-src]
  [:video {:id "main-video"
           :controls true
           :src video-src
           :width 620
           :ref (fn [el]
                 (reset! video-ref-atm el))}
   "Video not supported by your browser :("]
  )

(defn put-doc [doc handler-fn]
  (go (let [resp (<! (http/post "http://localhost:3000/put-doc"
                                {:json-params doc
                                 :with-credentials false}
                                ))]
        (println resp)
        (println (:body resp))
        (handler-fn (:body resp) resp)))
  )

(defn delete-doc [doc handler-fn]
  (go (let [resp (<! (http/post "http://localhost:3000/delete-doc"
                                {:json-params doc
                                 :with-credentials false}
                                ))]
        (println resp)
        (println (:body resp))
        (handler-fn (:body resp) resp)))
  )

(defn editable-field [initial-val save-fn]
  (let [editing? (reagent/atom false)
        restore-val-atm (reagent/atom initial-val)
        val-atm (reagent/atom initial-val)]
    (fn []
      (if @editing?
        [:div {:class ""}
         [:input {:type :text :value @val-atm
                  :on-change (fn [e]
                               (reset! val-atm (-> e .-target .-value)))}]
         [:button {:on-click (fn []
                               (reset! val-atm @restore-val-atm)
                               (reset! editing? false))}
          "Cancel"]
         [:button {:on-click (fn []
                               (save-fn @val-atm #(reset! editing? false)))}
          "Save"]]
        [:div {:class ""}
         [:p {:class ""} @val-atm]
         [:button {:on-click #(do (reset! editing? true)
                                  (reset! restore-val-atm @val-atm))} "edit"]]
        ))))

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
             (sort-by :time new-notes))))
  )

(defn change-time-scrub [note-cursor notes-cursor video-ref-atm scrub-timer-count-atm change]
  (let [new-time (+ (:time @note-cursor) change)]
    (when-let [video @video-ref-atm]
      (set! (.-currentTime video) new-time))
    (swap! note-cursor assoc :time new-time)
    (swap! scrub-timer-count-atm inc)
    (js/setTimeout (fn []
                     (when (= 0 (swap! scrub-timer-count-atm dec))
                       (do
                         (println "Updating the time scrubbing")
                         (put-doc @note-cursor (fn [] nil))))) 2000)
    ))

(defn format-time [time-in-seconds]
  (let [minutes (Math/round (/ time-in-seconds 60))
        seconds (/ (Math/round (* (mod time-in-seconds 60) 10)) 10)]
    (str minutes ":" seconds)))

(defn time-scrubber [note-cursor notes-cursor video-ref-atm]
  (let [scrub-timer-count-atm (reagent/atom 0)]
    (fn []
      [:div {:class "flex items-center"}
       [svg/chevron-left {:class "ma2"
                          :on-click (partial change-time-scrub note-cursor notes-cursor video-ref-atm scrub-timer-count-atm -0.1)}
        "black" "32px"]
       [:div {:class "f3"}
        [:div (format-time (:time @note-cursor))]]
       [svg/chevron-right {:class "ma2"
                           :on-click (partial change-time-scrub note-cursor notes-cursor video-ref-atm scrub-timer-count-atm 0.1)}
        "black" "32px"]])))

(defn note [note-cursor notes-cursor video-ref-atm]
  (fn []
    [:div {:class "br3 ba b--black-10 pa2 ma2 flex justify-between"}
     [:button {:on-click (fn []
                           (when-let [video @video-ref-atm]
                             (set! (.-currentTime video) (:time @note-cursor))))}
      "Go"]
     [editable-field (:text @note-cursor)
      (fn [new-val done-fn]
        (put-doc (assoc @note-cursor :text new-val)
                 (fn [new-doc]
                   (println "new-doc" new-doc)
                   (upsert-note! notes-cursor new-doc)
                   (done-fn))))]
     ;; [:div (str @note-cursor)]
     [time-scrubber note-cursor notes-cursor video-ref-atm]
     [svg/trash {:on-click (fn []
                             (delete-doc @note-cursor
                                         (fn [resp]
                                           (swap! notes-cursor (fn [notes]
                                                                 (vec (filter #(not (= (:_id @note-cursor) (:_id %)))
                                                                              notes)))))))}
      "gray" "32px"]
     ])
  )

(defn notes [notes-cursor video-ref-atm video-src]
  (fn []
    [:div
     [:button {:on-click (fn [e] 
                           (when-let [video @video-ref-atm]
                             (let [current-time (.-currentTime video)
                                   uuid (uuid/uuid-string (uuid/make-random-uuid))]
                               (put-doc {:_id uuid
                                         :type :note
                                         :video video-src
                                         :time current-time
                                         :text (str "Note at " current-time)}
                                        (fn [doc]
                                          (swap! notes-cursor (fn [notes]
;                                                                (vec (conj notes doc))
                                                                (vec (concat [doc] notes))
                                                                )))))))}
      "Add note"]
     (doall
      (map (fn [idx]
             (let [note-cursor (reagent/cursor notes-cursor [idx])]
               ^{:key (get-in @note-cursor [:_id])}
               [note note-cursor notes-cursor video-ref-atm]
               ))
           (range 0 (count @notes-cursor))))
     ]
    ))

(defn load-notes [notes-cursor video-key]
  (go (let [resp (<! (http/post "http://localhost:3000/get-notes"
                                {:json-params {:video-key video-key}
                                 :with-credentials false}
                                ))]
        (reset! notes-cursor (vec (sort-by :time (mapv :doc (:body resp))))))))

(defn page [ratom]
  (let [video-ref-atm (clojure.core/atom nil)
        video-src "big_buck_bunny_720p_surround.mp4"
        notes-cursor (reagent/cursor ratom [:notes])
        _auto-load (load-notes notes-cursor video-src)]
    (fn []
      [:div
       [:p "Video Note Taker v1.0"]
       [video video-ref-atm video-src]
       [notes notes-cursor video-ref-atm video-src]
       [:button {:on-click (fn [] (when-let [video @video-ref-atm]
                                    (println (.-src video))
                                    (println @notes-cursor)))}
        "Print video source"]
       [:p (str @ratom)]
       ])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialize App

(defn dev-setup []
  (when ^boolean js/goog.DEBUG
    (enable-console-print!)
    (println "dev mode")
    ))

(defn reload []
  (reagent/render [page app-state]
                  (.getElementById js/document "app")))

(defn ^:export main []
  (dev-setup)
  (reload))
