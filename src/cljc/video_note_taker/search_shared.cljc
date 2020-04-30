(ns video-note-taker.search-shared
  #?(:cljs (:require [devcards.core]))
  #?(:cljs (:require-macros
            [devcards.core :refer [defcard defcard-rg deftest]]
            [cljs.test :refer [testing is]])))

(defn construct-search-regex [text match-whole-string?]
  "match-whole-string causes the regex to match the whole string, otherwise it sets up a capture group for the text"
  (if (empty? text)
    ""
    (str (if match-whole-string? ".*" "(")
         "["
         (clojure.string/upper-case (first text))
         (clojure.string/lower-case (first text))
         "]"
         (subs text 1 (count text)) ; drop the first letter
         (if match-whole-string? ".*" ")"))))

#?(:cljs
   (deftest test-construct-search-regex
     (is (= (construct-search-regex "bravo" true) ".*[Bb]ravo.*"))
     (is (= (construct-search-regex "" true) ""))))

(defn highlight-str [full-str search-str]
  [:div {}
   (map-indexed (fn [idx piece]
                  ^{:key idx}
                  [:span (if (odd? idx)
                           {:class "bg-light-yellow"}
                           {:class ""})
                   piece])
                (clojure.string/split
                 full-str
                 (re-pattern (construct-search-regex search-str false))))])

#?(:cljs (defcard-rg test-string-highlight
           [:div {}
            [highlight-str "Abby absolutely abhors slabs of drab tabs. Abab." "ab"]]))
