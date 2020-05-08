(ns video-note-taker.db
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!] :as async]
   [cljs.test :include-macros true :refer-macros [testing is]]
   [devcards.core :refer-macros [defcard deftest]]
   [video-note-taker.svg :as svg]
   [video-note-taker.toaster-oven :as toaster-oven])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(defn get-server-url
  ([]
   (get-server-url (str (.-location js/window))))
  ([url]
   (if (re-matches #".*:3450.*" url)
     "http://localhost:3000/"
     url)))

(defn resolve-endpoint [endpoint]
  (str (get-server-url) endpoint))

(deftest get-server-url-test
  (is (= (get-server-url "http://localhost:3450") "http://localhost:3000"))
  (is (= (get-server-url "https://localhost:3002") "https://localhost:3002")))

(defcard show-server-url
  (get-server-url))

(defcard show-resolve-endpoint
  (resolve-endpoint "foo"))

(defn toast-server-error-if-needed [resp doc]
  (when (not (= 200 (:status resp)))
    (toaster-oven/add-toast (str "Server error: " resp doc) svg/x "red"
                            {:ok-fn    (fn [] nil)})))

(defn get-doc [id success-fn fail-fn]
  (go (let [resp (<! (http/post (resolve-endpoint "get-doc")
                                {:json-params {:_id id}
                                 :with-credentials false}
                                ))]
        (toast-server-error-if-needed resp nil)
        (when (and
               (= 200 (:status resp))
               (:body resp)
               success-fn)
          (success-fn (:body resp) resp))
        (when (and
               (or
                (not (= 200 (:status resp)))
                (nil? (:body resp)))
               fail-fn)
          (fail-fn (:body resp) resp))
        )))

(defn put-doc [doc handler-fn]
  (go (let [resp (<! (http/post (resolve-endpoint "put-doc")
                                {:json-params doc
                                 :with-credentials false}
                                ))]
        (toast-server-error-if-needed resp doc)
        (println resp)
        (println (:body resp))
        (handler-fn (:body resp) resp))))

(defn delete-doc [doc handler-fn]
  (go (let [resp (<! (http/post (resolve-endpoint "delete-doc")
                                {:json-params doc
                                 :with-credentials true}
                                ))]
        (toast-server-error-if-needed resp doc)
        (println resp)
        (println (:body resp))
        (handler-fn (:body resp) resp))))

(defn put-endpoint-in-atom [endpoint params data-atom]
  (go (let [resp (<! (http/post (resolve-endpoint endpoint)
                                {:json-params params
                                 :with-credentials true}))]
        (toast-server-error-if-needed resp nil)
        (when (= 200 (:status resp))
          (reset! data-atom (:body resp))))))

(defn post-to-endpoint
  ([endpoint params]
   (post-to-endpoint endpoint params nil nil))
  ([endpoint params success-fn]
   (post-to-endpoint endpoint params success-fn nil))
  ([endpoint params success-fn fail-fn]
   (go (let [resp (<! (http/post (resolve-endpoint endpoint)
                                 {:json-params params
                                  :with-credentials true}))]
         (println "post-to-endpoint resp: " resp)
         (toast-server-error-if-needed resp nil)
         (if (= 200 (:status resp))
           (when success-fn (success-fn (:body resp) resp))
           (when fail-fn (fail-fn (:body resp) resp)))))))
