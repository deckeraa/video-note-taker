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
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn load-video-listing [video-listing-cursor]
  (println "calling load-video-listing")
  (go (let [resp (<! (http/post (db/resolve-endpoint "get-video-listing")
                                {:json-params {}
                                 :with-credentials false}
                                ))]
        (db/toast-server-error-if-needed resp nil)
        (reset! video-listing-cursor (:body resp)))))

(defn single-video-listing [video video-cursor notes-cursor screen-cursor video-listing-cursor]
  (let [hover-atm (reagent/atom false)]
    (fn [video video-cursor notes-cursor screen-cursor video-listing-cursor]
      [:div {:class "br3 shadow-4 pa2 flex justify-between items-center"
             :on-click (fn []
                         ;; clear out the notes cursor if a different video was selected than before
                         (when (not (= (:_id @video-cursor) (:_id video)))
                           (reset! notes-cursor []))
                         ;; update the video cursor and ...
                         (reset! video-cursor video)
                         ;; ... auto-load notes if needed
                         (when (empty? @notes-cursor)
                           (notes/load-notes notes-cursor video-cursor)) ;; TODO update with the new src from the video
                         ;; update the screen cursor to go to the new screen
                         (swap! atoms/screen-cursor conj :video)
                         )
             :on-mouse-over (fn [e] (reset! hover-atm true))
             :on-mouse-out  (fn [e] (reset! hover-atm false))}
       [:p (when @hover-atm {:class "b"}) (str (:display-name video))]
       (when @hover-atm
         [svg/trash {:on-click
                     (fn [e]
                       (.stopPropagation e) ;; prevent this click from registing as a click on the video
                       (toaster-oven/add-toast
                        "Delete video permanently?" nil nil
                        {:cancel-fn (fn [] nil)
                         :ok-fn (fn []
                                  (go (let [resp (<! (http/post (db/resolve-endpoint "delete-video")
                                                                {:json-params video
                                                                 :with-credentials true}))]
                                        (println "Delete resp: " resp)
                                        (if (= 200 (:status resp))
                                          (do
                                            (toaster-oven/add-toast "Video deleted" svg/check "green" nil)
                                            (load-video-listing video-listing-cursor))
                                          (toaster-oven/add-toast (str "Couldn't delete video. " (get-in resp [:body :reason])) svg/x "red" nil)
                                          ))))}))}
          "gray" "24px"]
         )
       ]))
  )

(defn upload-progress-updater [progress-atm]
  (go (let [resp (<! (http/get (db/resolve-endpoint "get-upload-progress")))
            progress (:body resp)]
        (reset! progress-atm progress)
        (when (and progress
                   (< (:bytes-read progress)
                      (:content-length progress)))
          (js/setTimeout (partial upload-progress-updater progress-atm) 1500)
          ))))

(defn- display-in-megabytes [bytes]
  (if (>= bytes 1000000)
    (str (Math/round (/ bytes 1000000)) " MB")
    "<1 MB"))

(deftest test-display-in-megabytes
  (is (= (display-in-megabytes 50) "<1 MB"))
  (is (= (display-in-megabytes 1000000) "1 MB"))
  (is (= (display-in-megabytes 1500000) "2 MB")))

(defn upload-toast
  ([remote-delegate-atom upload-progress]
   (fn [remove-delegate-atm]
     [:div {}
      (if (and (:bytes-read upload-progress)
               (:content-length upload-progress))
        (str "Uploading: "
             (let [percent
                   (Math/round (*
                                (/ (:bytes-read upload-progress)
                                   (:content-length upload-progress))
                                100))]
               (if (number? percent)
                 percent
                 0))
             "%"
             (str
              " ("
              (display-in-megabytes (:bytes-read upload-progress))
              " of "
              (display-in-megabytes (:content-length upload-progress))
              ")"))
        (str "Uploading..."))
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e] (@remove-delegate-atm))}
         "Ok"]]))
  ([remove-delegate-atm]
   (let [upload-progress-atom (reagent/atom {})
         _timer (upload-progress-updater upload-progress-atom)]
     [upload-toast remove-delegate-atm @upload-progress-atom]
     )))

(defcard-rg upload-toast-card
  [:div
   [upload-toast (fn [] nil) {}]
   [upload-toast (fn [] nil) {:bytes-read 1000 :content-length 2000}]
   [upload-toast (fn [] nil) {:bytes-read 1000000 :content-length 3000000}]])

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
                                        ;                             (toaster-oven/add-toast "Uploading video..." nil nil nil)
                             (let [remove-delegate-atm (reagent/atom (fn [] nil))]
                               (toaster-oven/add-toast
                                [upload-toast remove-delegate-atm]
                                remove-delegate-atm
                                atoms/toaster-cursor))
                             (when-let [file-input @file-input-ref-el]
                               (go (let [resp (<! (http/post
                                                   (db/resolve-endpoint "upload-video")
                                                   {:multipart-params
                                                    [["file" (aget (.-files file-input) 0)]]
                                                    }))]
                                     (reset! import-results resp)

                                     (if (= 200 (:status resp))
                                       (do
                                         (toaster-oven/add-toast "Video uploaded" svg/check "green" nil)
                                         (load-video-listing video-listing-cursor))
                                       (toaster-oven/add-toast "Couldn't upload video :(" svg/x "red" nil))
                                     ))))}]])))

(defn video-listing [video-listing-cursor video-cursor notes-cursor screen-cursor]
  [:div
   (map (fn [video]
          ^{:key (:_id video)}
          [single-video-listing video video-cursor notes-cursor screen-cursor video-listing-cursor])
        @video-listing-cursor)
   [upload-card video-listing-cursor]])
