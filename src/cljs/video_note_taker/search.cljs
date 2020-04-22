(ns video-note-taker.search
  (:require
   [reagent.core :as reagent]
   [cljs-http.client :as http]
   [cljs.core.async :refer [<! >! chan close! timeout put!]]
   [video-note-taker.db :as db]
   [video-note-taker.svg :as svg]
   [video-note-taker.auth :as auth]
   [video-note-taker.video-notes :as video-notes])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]
   [cljs.core.async.macros :refer [go go-loop]]))

(defn highlight-str [full-str search-str]
  [:div {}
   (map (fn [piece]
          [:span (if (= piece search-str)
                   {:class "bg-light-yellow"}
                   {:class ""})
           piece])
    (interpose search-str (clojure.string/split full-str (re-pattern search-str))))])

(defcard-rg test-string-highlight
  [:div {}
   [highlight-str "Abby absolutely abhors slabs of drab tabs." "ab"]])

(defn search [video-cursor screen-cursor]
  (let [input-atm   (reagent/atom "")
        results-atm (reagent/atom "")
        search-fn (fn []
                          (go (let [resp (<! (http/post (db/resolve-endpoint "search-text")
                                                        {:json-params {:text @input-atm}
                                                         :with-credentials true}))]
                                (reset! results-atm resp))))
        search-as-you-type true]
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
                                (search-fn)))}]
        [svg/magnifying-glass {:class "dib "
                               :style {:margin "8px"}
                               :on-click search-fn} "gray" "48px"]]
       ;[:div (str (:body @results-atm))]
       (when (not (empty? @input-atm))
         [:div {:class ""}
          ;; Search result cards
          (map (fn [note]
                 [:div {:class "br3 ba b--black-10 pa3 mv2 bg-animate hover-bg-yellow"
                        :on-click (fn []
                                    (reset! video-cursor {:src (:video note) :requested-time (:time note)})
                                    (swap! screen-cursor conj :video))}
                  [:div {:class "f2"}
                   [highlight-str (:text note) @input-atm]]
                  [:div {:class "f3"} (str (video-notes/format-time (:time note)) "  " (:video note) )]])
               (get-in @results-atm [:body :docs]))])])))
