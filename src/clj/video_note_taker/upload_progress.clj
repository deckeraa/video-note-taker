(ns video-note-taker.upload-progress
  (:require
   [ring.middleware.cookies :refer [cookies-request]]
   [ring.util.json-response :refer [json-response]]))

(defonce file-upload-progress-atom (atom {}))

(defn upload-progress-fn [req bytes-read content-length item-count]
  (let [req (cookies-request req)
        cookie-value (get-in req [:cookies "AuthSession" :value])]
    ;; TODO this isn't quite correct since we're using cookie-value as a session key, but the cookie-value gets refreshed mid-session on a regular basis.
    (swap! file-upload-progress-atom assoc cookie-value {:bytes-read bytes-read :content-length content-length})))

(defn get-upload-progress [req username]
  (let [cookie-value (get-in req [:cookies "AuthSession" :value])]
    (json-response (get @file-upload-progress-atom cookie-value))))
