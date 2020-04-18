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

(defn video [video-ref-atm]
  [:video {:id "main-video"
           :controls true
           :src "./big_buck_bunny_720p_surround.mp4"
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

(defn notes [notes-cursor video-ref-atm]
  (fn []
    [:div
     [:button {:on-click (fn [e] 
                           (when-let [video @video-ref-atm]
                             (let [current-time (.-currentTime video)
                                   uuid (uuid/uuid-string (uuid/make-random-uuid))]
                               (println "current time:" current-time)
                               (put-doc {:id uuid
                                         :type :note
                                         :video "big-buck-bunny" ;; TODO use actual video URL
                                         :time current-time
                                         :text (str "Note at " current-time)}
                                        (fn [doc]
                                          (println "handler-fn's doc: " doc)
                                          (swap! notes-cursor conj doc)))
                               )))}
      "Add note"]
     (map (fn [note]
            ^{:key (:id note)}
            [:div {:class "br3 ba b--black-10 pa2 ma2 flex justify-between"}
             [:div (str note)]
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
             ])
          @notes-cursor)]
    ))

(defn page [ratom]
  (let [video-ref-atm (clojure.core/atom nil)
        notes-cursor (reagent/cursor ratom [:notes])]
    (fn []
      [:div
       [:p "Video Note Taker v1.0"]
       [video video-ref-atm]
       [notes notes-cursor video-ref-atm]
       [:button {:on-click (fn []
                             (go (let [resp (<! (http/post "http://localhost:3000/get-notes"
                                                           {:json-params {:video-key "big-buck-bunny"}
                                                            :with-credentials false}
                                                           ))]
                                   
                                   (reset! notes-cursor (sort-by :time (mapv :doc (:body resp))))

                                   )))}
        "Load notes"]
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
