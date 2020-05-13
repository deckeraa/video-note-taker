(ns video-note-taker.video-listing
  "Reagent component that displays a user's videos, as well as allow them to upload videos."
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
  (go (let [resp (<! (http/post (db/resolve-endpoint "get-video-listing")
                                {:json-params {}
                                 :with-credentials false}
                                ))]
        (db/toast-server-error-if-needed resp nil)
        (when (= 200 (:status resp))
          (reset! video-listing-cursor (sort-by :display-name (:body resp)))))))

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
                           (notes/load-notes notes-cursor video-cursor))
                         ;; update the screen cursor to go to the new screen
                         (swap! atoms/screen-cursor conj :video))
             :on-mouse-over (fn [e] (reset! hover-atm true))
             :on-mouse-out  (fn [e] (reset! hover-atm false))}
                                        ;[:p (when @hover-atm {:class "b"}) (str (:display-name video))]
       [editable-field (:display-name video)
        (fn [new-val done-fn]
          (swap! video-cursor assoc :display-name new-val)
          (db/put-doc @video-cursor (fn [new-val]
                                      (done-fn)
                                      (load-video-listing video-listing-cursor))))]
       (when @hover-atm ;; portion of the card that only appears on hover (such as the trash can)
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
                                        (if (= 200 (:status resp))
                                          (do
                                            (toaster-oven/add-toast "Video deleted" svg/check "green" nil)
                                            (load-video-listing video-listing-cursor))
                                          (toaster-oven/add-toast (str "Couldn't delete video. " (get-in resp [:body :reason])) svg/x "red" nil)
                                          ))))}))}
          "gray" "24px"]
         )
       ])))

(defcard-rg single-video-listing-card
  "The card for a video listing. Controls are not hooked up on this devcard."
  [:div
   [single-video-listing {:display-name "Video_display_name_here.mp4"}]])

(defn upload-progress-updater
  "Gets the current status of the file upload from the server. Uses a timeout to keep
  updating itself until the load is complete if 'repeat?' is true."
  ([progress-atm]
   (upload-progress-updater progress-atm true))
  ([progress-atm repeat?]
   (go (let [resp (<! (http/get (db/resolve-endpoint "get-upload-progress")))
             progress (:body resp)]
         (reset! progress-atm progress)
         (when (and progress
                    repeat?
                    (<= (:bytes-read progress)
                        (:content-length progress)))
           (js/setTimeout (partial upload-progress-updater progress-atm true) 750))))))

(defcard-rg test-upload-progress-updater
  "Tests /get-upload-progress. You will need a cookie associate with a file upload for this to return anything."
  (let [progress-atom (reagent/atom {})]
    (fn []
      [:div
       [:p "Progress atom: " @progress-atom]
       [:button {:on-click #(upload-progress-updater progress-atom false)} "Update once"]])))

(defn- display-in-megabytes [bytes]
  (if (>= bytes 1000000)
    (str (Math/round (/ bytes 1000000)) " MB")
    "<1 MB"))

(deftest test-display-in-megabytes
  (is (= (display-in-megabytes 50) "<1 MB"))
  (is (= (display-in-megabytes 1000000) "1 MB"))
  (is (= (display-in-megabytes 1500000) "2 MB")))

(defn upload-toast
  "Toast message that displays the current upload progress.
  When called with 1-arity, initializes an updater that gets status updates from the server."
  ([remote-delegate-atom upload-progress]
   (fn [remove-delegate-atm upload-progress]
     [:div {}
      (let [bytes-read     (:bytes-read upload-progress)
            content-length (:content-length upload-progress)]
        (if (and bytes-read content-length)
          (str "Uploading: "
               (let [percent
                     (Math/round (* (/ bytes-read content-length) 100))]
                 (if (number? percent) percent 0))
               "%"
               (str
                " ("
                (display-in-megabytes bytes-read)
                " of "
                (display-in-megabytes content-length)
                ")"))
          (str "Uploading...")))
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e] (@remove-delegate-atm))}
         "Ok"]]))
  ([remove-delegate-atm]
   (let [upload-progress-atom (reagent/atom {})
         _timer (upload-progress-updater upload-progress-atom)]
     (fn []
       [upload-toast remove-delegate-atm @upload-progress-atom])
     )))

(defcard-rg upload-toast-card
  [:div
   [upload-toast (fn [] nil) {}]
   [upload-toast (fn [] nil) {:bytes-read 1000 :content-length 2000}]
   [upload-toast (fn [] nil) {:bytes-read 1000000 :content-length 3000000}]])

(defn upload-card [video-listing-cursor]
  (let [file-input-ref-el (reagent/atom nil)]
    (fn [video-listing-cursor]
      [:div {:class "br3 shadow-4 dim mt3 flex"
             :style {:position :relative}}
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
