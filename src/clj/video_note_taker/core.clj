(ns video-note-taker.core
  (:require
   [bidi.bidi :as bidi]
   [bidi.ring :refer [make-handler]]
   [ring.util.response :as response :refer [file-response content-type]]
   [ring.util.request :as request]
   [ring.util.json-response :refer [json-response]]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.file :refer [wrap-file]]
   [ring.adapter.jetty :refer [run-jetty]]
   [clojure.edn :as edn]
   [cemerick.url]
   [com.ashafa.clutch :as couch]
   [clojure.data.json :as json])
  (:gen-class))

(def db (assoc (cemerick.url/url "http:localhost:5984/video-note-taker")
               :username "admin"
               :password "test"         ; TODO add real credential handling
               ))

(defn text-type [v]
  (content-type v "text/html"))

(defn json-type [v]
  (content-type v "application/json"))

(defn hello-handler [req]
  (text-type (response/response "hello")))

(defn put-doc-handler [req]
  (println req)
  (let [body-string (request/body-string req)]
    (println "body-string: " body-string)
    (let [body (json/read-str body-string)]
      (println body)
      (json-response (couch/put-document db body)))
   ; (json-type (response/response "put-doc"))
    ))

(defn get-doc [id]
  (couch/get-document db id))

(def api-routes
  ["/" [["hello" hello-handler]
        ["put-doc" put-doc-handler]
        [true  (fn [req] (content-type (response/response "<h1>Default Page</h1>") "text/html"))]]])

(def app
  (-> (make-handler api-routes)
      (wrap-file "resources/public")
      (wrap-content-type)
      (wrap-cors
       :access-control-allow-origin [#".*"]
       :access-control-allow-methods [:get :put :post :delete]
       :access-control-allow-credentials ["true"]
       :access-control-allow-headers ["X-Requested-With","Content-Type","Cache-Control"])))

(defn -main [& args]
  (let [port (try (Integer/parseInt (first args))
                  (catch Exception ex
                    3000))]
    (println port)
    (run-jetty app {:port port})))
