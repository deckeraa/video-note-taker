(ns video-note-taker.auth-util
  "Exists to avoid a circular dependency between auth.cljs and db.cljs.")

(defn needs-auth-cookie
  "Determines whether the user needs to log in by examining
  the presence of the authorization cookie."
  []
  (as-> js/document $
    (.-cookie $)
    (clojure.string/split $ "; ")
    (map #(clojure.string/split % "=") $)
    (filter #(and (= (first %) "AuthSession")
                  (not (nil? (second %)))) $)
    (empty? $)))
