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

(defn upload-card [video-listing-cursor]
  (let [file-input-ref-el (reagent/atom nil)
        import-results    (reagent/atom nil)]
    (fn [video-listing-cursor]
      [:div {:class "br3 shadow-4 dim mt3 flex"
             :style {:position :relative}}
                                        ;   [svg/cloud-upload {:class "relative"} "white" "32px"]
       [:label {:for "file-upload"
                :class "f2 bg-green white b br3 dib pa2 w-100 tc"}
        "Upload video"]
       [:input {:id "file-upload"
                :name "file"
                :type "file"
                :multiple false
                :class "dn"
                :ref (fn [el]
                       (reset! file-input-ref-el el))
                :on-change (fn [e]
                             ;; cancelling out of the browser-supplied file upload dialog doesn't trigger this event
                             (println "file-upload event: " e)
                             (when-let [file-input @file-input-ref-el]
                               (go (let [resp (<! (http/post
                                                   (db/resolve-endpoint "upload-video")
                                                   {:multipart-params
                                                    [["file" (aget (.-files file-input) 0)]]
                                                    }))]
                                     (reset! import-results resp)
                                     (when (= 200 (:status resp))
                                       (load-video-listing video-listing-cursor))
                                     ))))}]])))

(defn video-listing [video-listing-cursor video-cursor notes-cursor screen-cursor]
  [:div
   (map (fn [video-src]
          ^{:key video-src}
          [single-video-listing video-src video-cursor notes-cursor screen-cursor])
        @video-listing-cursor)
   [upload-card video-listing-cursor]])
