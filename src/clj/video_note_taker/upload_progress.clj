(ns video-note-taker.upload-progress
  (:require
   [video-note-taker.db :as db]
   [com.stronganchortech.couchdb-auth-for-ring :as auth]
   [ring.middleware.cookies :refer [cookies-request]]
   [ring.util.json-response :refer [json-response]]))

(defonce file-upload-progress-atom (atom {}))
(defonce cookie-mapping-atom (atom {}))

(defn get-upload-id-from-query-string [query-string]
  (let [;; kv-vec will look something like ["username" "yankee1"] ["id" "a2c9effc-0a77-4be3-b02e-6cd1299b82ec"]
        kv-vec (map (fn [kv]
                      (clojure.string/split kv #"="))
                    (clojure.string/split query-string #"&"))
        upload-id-kv (first (filter #(= "id" (first %)) kv-vec))]
    (second upload-id-kv)))

(defn upload-progress-fn [db req bytes-read content-length item-count]
  (let [req (cookies-request req)
        cookie-value (get-in req [:cookies "AuthSession" :value])
        upload-id (when-let [query-string (:query-string req)]
                    (get-upload-id-from-query-string query-string))
        ;; (when-let [query-string (:query-string req)]
        ;;   (second (clojure.string/split query-string #"=")))
        username (get (swap! cookie-mapping-atom
                             (fn [cookie-map]
                               (if (get cookie-map cookie-value) ; if the cookie is already mapped ...
                                 cookie-map ;; ... then no change
                                 ;; otherwise look up the username based on the cookie (this does a server -> CouchDB network call
                                 (let [cookie-check-val (auth/cookie-check cookie-value)]
                                   (assoc cookie-map cookie-value
                                          (:name (first cookie-check-val)))))))
                      cookie-value)]
    ;; (println "query-string " (:query-string req))
    ;; (println "upload-progress-fn: " username upload-id req)
    (swap! file-upload-progress-atom assoc-in [username upload-id] {:bytes-read bytes-read :content-length content-length})))

(defn get-upload-progress [req username roles]
  (let [upload-id (when-let [query-string (:query-string req)]
                    (second (clojure.string/split query-string #"=")))]
    ;;(println "query-string: " (:query-string req) "upload-id: " upload-id)
    ;;(println "@file-upload-progress-atom" @file-upload-progress-atom)
    (json-response (get-in @file-upload-progress-atom [username upload-id]))))
