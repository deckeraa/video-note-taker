(ns video-note-taker.search-shared
  #?(:cljs (:require [devcards.core]))
  #?(:cljs (:require-macros
            [devcards.core :refer [defcard defcard-rg deftest]]
            [cljs.test :refer [testing is]])))

(defn construct-search-regex [text]
  (if (empty? text)
    ""
    (str ".*["
         (clojure.string/upper-case (first text))
         (clojure.string/lower-case (first text))
         "]"
         (subs text 1 (count text)) ; drop the first letter
         ".*")))

#?(:cljs
   (deftest test-construct-search-regex
     (is (= (construct-search-regex "bravo") ".*[Bb]ravo.*"))
     (is (= (construct-search-regex "") ""))))

(defn highlight-str [full-str search-str]
  [:div {}
   (map-indexed (fn [idx piece]
                  ^{:key idx}
                  [:span (if (= piece search-str)
                           {:class "bg-light-yellow"}
                           {:class ""})
                   piece])
    (interpose search-str (clojure.string/split full-str (re-pattern search-str))))])

#?(:cljs (defcard-rg test-string-highlight
           [:div {}
            [highlight-str "Abby absolutely abhors slabs of drab tabs." "ab"]]))
