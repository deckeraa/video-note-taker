(ns video-note-taker.uploads
  (:require
   [reagent.core :as reagent]
   [cljs-uuid-utils.core :as uuid]
   [cljs.test :include-macros true :refer-macros [testing is]]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.db :as db]
   [video-note-taker.svg :as svg]
   [s3-beam.client :refer [s3-pipe]])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn file-objects
  "Returns a vector of File objects from the file input provided in the file-input-ref-atom.
  https://developer.mozilla.org/en-US/docs/Web/API/File"
  [file-input-ref-atom]
  (when-let [file-input @file-input-ref-atom]
    (vec (map (fn [idx]
                (aget (.-files file-input) idx))
              (range (alength (.-files file-input)))))))

(defn cursor-entry [upload-id files storage-location]
  {:files (mapv #(.-name %) files)
   :progress nil
   :storage-location storage-location})

(defn is-upload-complete? [progress]
  (let [bytes-read     (:bytes-read progress)
        content-length (:content-length progress)]
    (if (and bytes-read content-length (= bytes-read content-length))
      true
      false ;; otherwise we get "nil" sometimes from the and statement
      )))

(deftest test-is-upload-complete?
  (is (= (is-upload-complete? nil) false))
  (is (= (is-upload-complete? {:bytes-read 100 :content-length 200}) false))
  (is (= (is-upload-complete? {:bytes-read 200 :content-length 200}) true)))

(defn uploads-in-progress?
  ([]
   (uploads-in-progress? @atoms/uploads-cursor))
  ([uploads]
   (not (empty? (remove (fn [[k v]] (is-upload-complete? (:progress v))) uploads)))))

(deftest test-uploads-in-progress
  (is (= (uploads-in-progress? {"a3sd2" {:files ["foo.mp3"] :progress {:bytes-read 200 :content-length 200}}}) false))
  (is (= (uploads-in-progress? {"a3sd2" {:files ["foo.mp3"] :progress {:bytes-read 200 :content-length 400}}}) true)))

(defn number-of-in-progress-uploads [uploads]
  (count (remove (fn [[k v]] (is-upload-complete? (:progress v))) uploads)))

(defn number-of-finished-uploads [uploads]
  (count (filter (fn [[k v]] (is-upload-complete? (:progress v))) uploads)))

(defn uploads-this-session? [uploads]
  (not (empty? uploads)))

(defn upload-files-to-s3 [uploads-cursor file-input-ref-atom success-fn]
  (let [upload-id (uuid/uuid-string (uuid/make-random-uuid))
        uploaded (chan)
        upload-queue (s3-pipe uploaded {:server-url (db/resolve-endpoint "spaces-upload")
                                        :progress-events? true})]
    (when-let [files (file-objects file-input-ref-atom)]
      (let [file (get files 0)]
;;        (println "file" file upload-id)
        (put! upload-queue {:file file :identifier upload-id})
        (swap! uploads-cursor assoc upload-id (cursor-entry upload-id [file] :s3))
        (go-loop []
          (let [update (<! uploaded)]
 ;;           (println "From upload-queue: " update)
            (when (= :progress (:type update))
              (let [processed-update
                    (-> update
                        ;; rename keys to match up with what we get through Ring's file progress fn
                        (clojure.set/rename-keys {:bytes-sent :bytes-read :bytes-total :content-length})
                        (select-keys [:bytes-read :content-length]))]
                (swap! uploads-cursor assoc-in [upload-id :progress]
                       processed-update)))
            (when (and success-fn (= :success (:type update))) (success-fn))
            (recur)))))))

(defn upload-files [uploads-cursor file-input-ref-atom upload-endpoint success-fn fail-fn]
  (when-let [files (file-objects file-input-ref-atom)]
    (let [upload-id (uuid/uuid-string (uuid/make-random-uuid))]
      ;; Send the POST to the upload endpoint
      (go (let [resp (<! (http/post
                          (db/resolve-endpoint upload-endpoint)
                          {:multipart-params
                           (mapv (fn [file] ["file" file]) files)          
                           ;; (vec (map (fn [idx]
                           ;;             ["file" (aget (.-files file-input) idx)])
                           ;;           (range (alength (.-files file-input)))))
                           :query-params {:id upload-id}
                           }))]
            (if (= 200 (:status resp))
              (when success-fn (success-fn (:body resp) resp))
              (when fail-fn    (fail-fn    (:body resp) resp)))
            ;; nothing to do here, since file upload progress is tracked separately
            ))
      ;; Add in this upload to the uploads cursor.
      ;; This enables the file upload progress tracker to 
      (swap! uploads-cursor assoc upload-id (cursor-entry upload-id files :local)))))

(defn upload-progress-updater
  ([uploads-cursor repeat?]
   (let [in-progress-uploads
         (remove (fn [[k v]] (is-upload-complete? (:progress v))) @uploads-cursor)
         ;; response-counter (atom (count in-progress-uploads))
         ;; db-resp-fn (fn (when (and (= 0 (swap! response-counter dec))
         ;;                           repeat?)
         ;;                  (js/setTimeout )))
         ]
     (doall (map (fn [upload-id]
                   (go (let [resp (<! (http/post
                                       (db/resolve-endpoint "get-upload-progress")
                                       {:query-params {:id upload-id}}))
                             updated-progress (:body resp)]
                         (if (= 200 (:status resp))
                           (do
                             (swap! uploads-cursor assoc-in [upload-id :progress] updated-progress))                         
                           (println "upload progress update failed: " resp)))))
                 (->> in-progress-uploads
                      (filter (fn [[k v]] (= :local (:storage-location v))))
                      (keys))
                 ))
     (js/setTimeout (partial upload-progress-updater uploads-cursor repeat?) 3000)))
  ([uploads-cursor]
   (upload-progress-updater uploads-cursor true)))

;; Start the updater
(defonce _upload-progress-updater (upload-progress-updater atoms/uploads-cursor))

(defn- display-in-megabytes [bytes]
  (if (>= bytes 1000000)
    (str (Math/round (/ bytes 1000000)) " MB")
    "<1 MB"))

(deftest test-display-in-megabytes
  (is (= (display-in-megabytes 50) "<1 MB"))
  (is (= (display-in-megabytes 1000000) "1 MB"))
  (is (= (display-in-megabytes 1500000) "2 MB")))

(defn single-upload-card [upload-id {{:keys [bytes-read content-length]} :progress :as upload}]
  ^{:key upload-id}
  [:li {:class "ma3 flex flex-columns items-center"}
   (if (and bytes-read content-length (= bytes-read content-length))
     [svg/check {:class "ma2"} "green" "26px"]
     [:div {:class "ma2" :style {:width "26px"}}] ;; TODO replace with loading spinner
     )
   [:div {:class "f3"}
    (cond
      ;; Case 1: Video has finished uploading
      (and bytes-read content-length (= bytes-read content-length))
      "Uploaded"
      ;; Case 2: Video is uploading and we have progress information
      (and bytes-read content-length)
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
      ;; Case 3: Video is uploading and we don't have progress information
      :default
      (str "Uploading..."))
    [:ul {:class "list" :style {}}
     (doall (map (fn [filename]
                   ^{:key filename}
                   [:li {:class "f4 mv1" :style {:overflow-wrap :anywhere}} filename])
                 (:files upload)))]]
   ])

(defn upload-display [uploads-cursor]
  (let [has-auto-expanded? (reagent/atom false)
        expanded? (reagent/atom false)]
    (fn []
      [:div
       (when (uploads-this-session? @uploads-cursor)
         (when (and (not @has-auto-expanded?) (not @expanded?))
           (reset! has-auto-expanded? true)
           (reset! expanded? true))
         [:div {:class "fixed bg-white w4 h4 br-100 shadow-3 bottom-1 right-1 flex flex-column items-center justify-center grow rise-from-bottom"
                :on-click #(swap! expanded? not)}
          (let [in-progress (number-of-in-progress-uploads @uploads-cursor)]
            (if (> in-progress 0)
              [:<>
               [:div {:class "f2"} in-progress]
               [:div {:class "f5"} "running"]
               [:div {:class "f5"} "uploads"]]
              [:<>
               [:div {:class "f2 flex items-center"}
                [svg/check {:class "mr2"} "green" "24px"]
                (number-of-finished-uploads @uploads-cursor)]
               [:div {:class "f5"} "finished"]
               [:div {:class "f5"} "uploads"]]
              ))])
       (when @expanded?
         [:div {:class "fixed bg-white br3 shadow-3 w-80 upload-details-rise-from-bottom"
                :style {:bottom "10rem" :right "10%"}}
          [:div {:class "f2 ma2"} "Recent Uploads"]
          [:ul {:class "list"}
           (doall (map (fn [[k v]] (single-upload-card k v)) @uploads-cursor))]])])))

(defcard-rg test-upload-display
  (let [uploads-cursor (reagent/atom {"dfh4" {:files ["foo.mp3" "bar.mp3" "a_really_really_long_file_name_123456789012334567890123456789012334567890123456789012334567890.mp4"] :progress {:bytes-read 100 :content-length 200}}
                                        "823f" {:files ["qux.mp4"] :progress nil}
                                        "4832" {:files ["baz.mp4"] :progress {:bytes-read 200 :content-length 200}}})]
    (fn []
      [upload-display uploads-cursor])))

(defn- add-upload-to-cursor
  ([uploads-cursor file-input-ref-atom]
   (add-upload-to-cursor uploads-cursor file-input-ref-atom (uuid/uuid-string (uuid/make-random-uuid))))
  ([uploads-cursor file-input-ref-atom upload-id]
   (swap! uploads-cursor assoc upload-id
          ;; {:files (mapv #(.-name %) (file-objects file-input-ref-atom))
          ;;  :progress nil}
          (cursor-entry upload-id (file-objects file-input-ref-atom) :local))))

(defcard-rg test-add-to-atom
  (let [file-input-ref-atom (reagent/atom nil)
        uploads-cursor (reagent/atom nil)]
    (fn []
      [:div "Dummy Uploader"
       [:input {:id "file-upload"
                :name "file"
                :type "file"
                :multiple true
                :ref (fn [el]
                       (reset! file-input-ref-atom el))}]
       [:button {:on-click
                 (fn [e]
                   (add-upload-to-cursor uploads-cursor file-input-ref-atom)
                   )} "Upload"]
       [:p {} (str "uploads-cursor: " @uploads-cursor)]])))
