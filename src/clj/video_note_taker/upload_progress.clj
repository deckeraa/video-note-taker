(ns video-note-taker.upload-progress
  (:require
   [video-note-taker.db :as db]
   [com.stronganchortech.couchdb-auth-for-ring :as auth]
   [ring.middleware.cookies :refer [cookies-request]]
   [ring.util.json-response :refer [json-response]]))

(defonce file-upload-progress-atom (atom {}))
(defonce cookie-mapping-atom (atom {}))

(defn upload-progress-fn [db req bytes-read content-length item-count]
  (let [req (cookies-request req)
        cookie-value (get-in req [:cookies "AuthSession" :value])
        username (get (swap! cookie-mapping-atom
                             (fn [cookie-map]
                               (if (get cookie-map cookie-value) ; if the cookie is already mapped ...
                                 cookie-map ;; ... then no change
                                 ;; otherwise look up the username based on the cookie (this does a server -> CouchDB network call
                                 (let [cookie-check-val (auth/cookie-check cookie-value)]
                                   (assoc cookie-map cookie-value
                                          (:name (first cookie-check-val)))))))
                      cookie-value)]
    (swap! file-upload-progress-atom assoc username {:bytes-read bytes-read :content-length content-length})))

(defn get-upload-progress [req username roles]
  (println "get-upload-progress " @file-upload-progress-atom)
  (json-response (get @file-upload-progress-atom username)))
