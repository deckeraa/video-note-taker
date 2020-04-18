(ns video-note-taker.core
  (:require
   [bidi.bidi :as bidi]
   [bidi.ring :refer [make-handler]]
   [ring.util.response :as response :refer [file-response content-type]]
   [ring.util.request :as request]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.file :refer [wrap-file]]
   [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

(defn text-type [v]
  (content-type v "text/html"))

(defn hello-handler [req]
  (text-type (response/response "hello")))

(def api-routes
  ["/" [["hello" hello-handler]
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
