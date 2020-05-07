(ns video-note-taker.listing
  (:require [reagent.core :as reagent]
            [video-note-taker.svg :as svg]
            [video-note-taker.atoms :as atoms]
            [video-note-taker.video-notes :refer [load-connected-users]]
            [video-note-taker.pick-list :refer [pick-list]]
            [video-note-taker.db :as db])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]))

(defn listing [{:keys [data-cursor card-fn load-fn new-fn add-caption]}]
  (when load-fn (reset! data-cursor (load-fn))
        (fn []
          [:div
           [:ul
            (doall
             (map (fn [item]
                    ^{:key (:_id item)}
                    [:li {} [card-fn item]])
                  @data-cursor))]
           ])))

(defcard-rg listing-devcard
  (let [data-cursor (reagent/atom [])
        load-fn (fn [] (println "load-fn") [{:_id "abc" :text "foo"} {:_id "def" :text "bar"}])
        card-fn (fn [item]
                  [:div {:class ""} (:text item)])
        counter (reagent/atom 0)]
    (fn []
      [:div "Listing devcard"
       [listing {:data-cursor data-cursor
                 :card-fn card-fn
                 :load-fn load-fn
                 :new-fn #(swap! counter inc)}]])))
