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

(defn upsert-note [notes-cursor doc]
  (swap! notes-cursor
         (fn [notes]
           (let [new-notes
                 (mapv (fn [note]
                         (if (= (:id note) (:id doc))
                           doc ; update if they are the same
                           note ; otherwise leave as is
                           ))
                       notes)]
             (sort-by :time new-notes))))
  )
  

(defn note [note notes-cursor video-ref-atm]
  [:div {:class "br3 ba b--black-10 pa2 ma2 flex justify-between"}
   [:div (:type note)]
   [editable-field (:text note)
    (fn [new-val done-fn]
      (put-doc (assoc note :text new-val)
               (fn [new-doc]
                 (println "new-doc" new-doc)
                 (upsert-note notes-cursor new-doc)
                 (done-fn))))]
   [:button {:on-click (fn []
                         (when-let [video @video-ref-atm]
                           (set! (.-currentTime video) (:time note))))}
    "Go"]
   [svg/trash {:on-click (fn []
                           (delete-doc note
                                       (fn [resp]
                                         (swap! notes-cursor (fn [notes]
                                                               (filter #(not (= (:id note) (:id %)))
                                                                       notes))))))}
    "gray" "32px"]
   ]
  )

(defn notes [notes-cursor video-ref-atm video-src]
  (fn []
    [:div
     [:button {:on-click (fn [e] 
                           (when-let [video @video-ref-atm]
                             (let [current-time (.-currentTime video)
                                   uuid (uuid/uuid-string (uuid/make-random-uuid))]
                               (println "current time:" current-time)
                               (put-doc {:id uuid
                                         :type :note
                                         :video video-src
                                         :time current-time
                                         :text (str "Note at " current-time)}
                                        (fn [doc]
                                          (println "handler-fn's doc: " doc)
                                          (swap! notes-cursor conj doc))))))}
      "Add note"]
     (map (fn [note-val]
            ^{:key (:id note-val)}
            [note note-val notes-cursor video-ref-atm])
          @notes-cursor)]
    ))

(defn load-notes [notes-cursor video-key]
  (go (let [resp (<! (http/post "http://localhost:3000/get-notes"
                                {:json-params {:video-key video-key}
                                 :with-credentials false}
                                ))]
        (reset! notes-cursor (sort-by :time (mapv :doc (:body resp)))))))

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
