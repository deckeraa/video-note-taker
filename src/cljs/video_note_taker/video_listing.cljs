(ns video-note-taker.video-listing
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
   [video-note-taker.video-notes :as notes]
   [video-note-taker.editable-field :refer [editable-field]])
  (:require-macros
   [devcards.core :refer [defcard deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn load-video-listing [video-listing-cursor]
  (println "calling load-video-listing")
  (go (let [resp (<! (http/post (db/resolve-endpoint "get-video-listing")
                                {:json-params {}
                                 :with-credentials false}
                                ))]
        (db/toast-server-error-if-needed resp nil)
        (reset! video-listing-cursor (:body resp)))))

(defn single-video-listing [video-src video-cursor notes-cursor screen-cursor]
  [:div {:class "br3 shadow-4 pa3 dim"
         :on-click (fn []
                     ;; clear out the notes cursor if a different video was selected than before
                     (when (not (= (:src @video-cursor) video-src))
                       (reset! notes-cursor []))
                     ;; update the video cursor and 
                     (reset! video-cursor {:src video-src})
                     ;; auto-load notes if needed
                     (when (empty? @notes-cursor)
                       (notes/load-notes notes-cursor (:src @video-cursor)))
                     ;; update the screen cursor to go to the new screen
                     (swap! atoms/screen-cursor conj :video)
                     )}
   (str video-src)]
  )

(defn video-listing [video-listing-cursor video-cursor notes-cursor screen-cursor]
  [:div
   (map (fn [video-src]
          ^{:key video-src}
          [single-video-listing video-src video-cursor notes-cursor screen-cursor])
        @video-listing-cursor)])
