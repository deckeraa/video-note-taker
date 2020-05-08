(ns video-note-taker.listing
  (:require [reagent.core :as reagent]
            [video-note-taker.svg :as svg]
            [video-note-taker.atoms :as atoms]
            [video-note-taker.video-notes :refer [load-connected-users]]
            [video-note-taker.pick-list :refer [pick-list]]
            [video-note-taker.db :as db])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]))

(defn remove-item-from-listing [data-cursor id-to-remove]
  (println "Removing " id-to-remove)
  (swap! data-cursor
         (fn [data]
           (vec (remove #(= (:_id %) id-to-remove)
                        data)))))

(defn listing [{:keys [data-cursor card-fn load-fn new-fn add-caption new-card-location]}]
  (when load-fn (load-fn data-cursor)
        (fn []
          [:div
           [:ul
            (doall
             (map (fn [idx]
                    (let [item-cursor (reagent/cursor data-cursor [idx])
                          id (:_id @item-cursor)]
                      ^{:key id}
                      [:li {} [card-fn item-cursor (partial remove-item-from-listing
                                                            data-cursor
                                                            id)]]))
                  (range 0 (count @data-cursor))))]
           (when new-fn
             [:button {:class ""
                       :on-click (fn [evt]
                                   (case new-card-location
                                     :top
                                     (swap! data-cursor (fn [data]
                                                          (vec (concat [(new-fn)] data))))
                                     :bottom
                                     (swap! data-cursor conj (new-fn))
                                     ;; default is the bottom
                                     (swap! data-cursor conj (new-fn))
                                     ))}
              (or add-caption "+ Add")])
           ])))

(defcard-rg listing-devcard
  (let [data-cursor (reagent/atom [])
        load-fn (fn [data-cursor] (reset! data-cursor [{:_id "abc" :text "foo"} {:_id "def" :text "bar"}]))
        card-fn (fn [item remove-delegate]
                  [:div {:class ""}
                   (str (:_id @item) " " (:text @item))
                   [:button {:class "red ma2" :on-click remove-delegate} "x"]])
        counter (reagent/atom 0)]
    (fn []
      [:div "Listing devcard"
       [:div {} (str "data-cursor " @data-cursor)]
       [listing {:data-cursor data-cursor
                 :card-fn card-fn
                 :load-fn load-fn
                 :new-fn #(let [v (swap! counter inc)]
                            {:_id v :text v})}]])))
