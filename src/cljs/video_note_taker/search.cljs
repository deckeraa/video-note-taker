(ns video-note-taker.search
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!]]
   [video-note-taker.db :as db]
   [video-note-taker.svg :as svg]
   [video-note-taker.auth :as auth]
   [video-note-taker.atoms :as atoms]
   [video-note-taker.video-notes :as video-notes])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn highlight-str [full-str search-str]
  [:div {}
   (map-indexed (fn [idx piece]
                  ^{:key idx}
                  [:span (if (= piece search-str)
                           {:class "bg-light-yellow"}
                           {:class ""})
                   piece])
    (interpose search-str (clojure.string/split full-str (re-pattern search-str))))])

(defcard-rg test-string-highlight
  [:div {}
   [highlight-str "Abby absolutely abhors slabs of drab tabs." "ab"]])

(defn search-fn [search-locked input-atm results-atm search-text]
  (when (compare-and-set! search-locked false true)
    (go (let [resp (<! (http/post (db/resolve-endpoint "search-text")
                                  {:json-params {:text search-text}
                                   :with-credentials true}))]
          (reset! results-atm resp)
          (reset! search-locked false)
          (let [cur-text @input-atm]
            (when (not (= search-text cur-text))
              (do
                (search-fn search-locked input-atm results-atm cur-text)))))))
  )

(defn search [video-cursor screen-cursor]
  (let [input-atm   (reagent/atom "")
        results-atm (reagent/atom "")
        search-locked (reagent/atom false)
        search-as-you-type true
        search-fn (partial search-fn search-locked input-atm results-atm)
        ]
    (fn []
      [:div {:class "flex flex-column items-center mv2 mh4"}
       [:div {:class "flex justify-center ba br3 b--black-20"}
        [:input {:type :text
                 :placeholder "Search video notes"
                 :value @input-atm
                 :class "f3 dib bn ma3"
                 :on-change (fn [e]
                              (reset! input-atm (-> e .-target .-value))
                              (when (and search-as-you-type
                                         (not (empty? @input-atm)))
                                (search-fn @input-atm)
))}]
        [svg/magnifying-glass {:class "dib "
                               :style {:margin "8px"}
                               :on-click #(search-fn @input-atm)} "gray" "48px"]]
       (when (not (empty? @input-atm))
         [:div {:class ""}
          ;; Search result cards
          (doall (map (fn [note]
                        ^{:key (:_id note)}
                        [:div {:class "br3 ba b--black-10 pa3 mv2 bg-animate hover-bg-yellow"
                               :on-click (fn []
                                           (reset! atoms/video-options-cursor {:src (:video note) :requested-time (:time note)})

                                           (db/get-doc
                                            (:video note)
                                            (fn [doc]
                                              (reset! video-cursor doc)
                                              (video-notes/load-notes atoms/notes-cursor video-cursor)) nil)
                                           
                                           (swap! screen-cursor conj :video))}
                         [:div {:class "f2"}
                          [highlight-str (:text note) @input-atm]]
                         [:div {:class "f3"} (str (video-notes/format-time (:time note)) "  " (or (:video-display-name note) (:video note)) )]])
                      (get-in @results-atm [:body :docs])))
          (when (empty? (get-in @results-atm [:body :docs]))
            [:div {:class "f3 white bg-light-red tc pa3 ma3 br3"}
             [:p {:class "b"} "No results found :("]
             [:p {:class "f4"} "The search looks for exact matches."]])])])))
