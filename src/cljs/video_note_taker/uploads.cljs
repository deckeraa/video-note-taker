(ns video-note-taker.uploads
  (:require
   [reagent.core :as reagent]
   [cljs-uuid-utils.core :as uuid]
   [cljs.test :include-macros true :refer-macros [testing is]]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.db :as db])
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

(defn cursor-entry [upload-id files]
  {:files (mapv #(.-name %) files)
   :progress nil})

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

(defn upload-files [uploads-cursor file-input-ref-atom upload-endpoint]
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
            ;; nothing to do here, since file upload progress is tracked separately
            ))
      ;; Add in this upload to the uploads cursor.
      ;; This enables the file upload progress tracker to 
      (swap! uploads-cursor assoc upload-id (cursor-entry upload-id files)))))

(defn upload-progress-updater
  ([uploads-cursor repeat?]
   (let [in-progress-uploads
         (remove (fn [k v] (is-upload-complete? {:progress v})) @uploads-cursor)
         ;; response-counter (atom (count in-progress-uploads))
         ;; db-resp-fn (fn (when (and (= 0 (swap! response-counter dec))
         ;;                           repeat?)
         ;;                  (js/setTimeout )))
         ]
     (println in-progress-uploads)
     (map (fn [upload-id]
            (db/post-to-endpoint
             (db/resolve-endpoint "get-upload-progress")
             {:query-params {:id upload-id}}
             (fn [updated-progress]
               (swap! uploads-cursor assoc-in [upload-id :progress] updated-progress))))
          (keys in-progress-uploads))
     (js/setTimeout (partial upload-progress-updater uploads-cursor repeat?) 3000)))
  ([uploads-cursor]
   (upload-progress-updater uploads-cursor true)))

(defonce _upload-progress-updater (upload-progress-updater atoms/uploads-cursor))

(defn- add-upload-to-cursor
  ([uploads-cursor file-input-ref-atom]
   (add-upload-to-cursor uploads-cursor file-input-ref-atom (uuid/uuid-string (uuid/make-random-uuid))))
  ([uploads-cursor file-input-ref-atom upload-id]
   (swap! uploads-cursor assoc upload-id
          ;; {:files (mapv #(.-name %) (file-objects file-input-ref-atom))
          ;;  :progress nil}
          (cursor-entry upload-id (file-objects file-input-ref-atom)))))

(defcard-rg test-add-to-atom
  (let [file-input-ref-atom (reagent/atom nil)
        uploads-cursor (reagent/atom nil)]
    (fn []
      [:div "Dummy Uploader"
       [:input {:id "file-upload"
                :name "file"
                :type "file"
                :multiple "true"
                :ref (fn [el]
                       (reset! file-input-ref-atom el))}]
       [:button {:on-click
                 (fn [e]
                   (add-upload-to-cursor uploads-cursor file-input-ref-atom)
                   )} "Upload"]
       [:p {} (str "uploads-cursor: " @uploads-cursor)]])))
