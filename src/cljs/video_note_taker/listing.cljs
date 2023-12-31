(ns video-note-taker.listing
  (:require [reagent.core :as reagent]
            [video-note-taker.svg :as svg]
            [video-note-taker.atoms :as atoms]
            [video-note-taker.pick-list :refer [pick-list]]
            [video-note-taker.db :as db])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]))

(defn remove-item-from-listing [data-cursor id-to-remove]
  (swap! data-cursor
         (fn [data]
           (vec (remove #(= (:_id %) id-to-remove)
                        data)))))

(defn add-item [data-cursor new-card-location item-to-add]
  (case new-card-location
    :top
    (swap! data-cursor (fn [data]
                         (vec (concat [item-to-add] data))))
    :bottom
    (swap! data-cursor conj item-to-add)
    ;; default is the bottom
    (swap! data-cursor conj item-to-add)
    ))

(defn reload [{:keys [load-fn data-cursor] :as options}]
  ((:load-fn options) (:data-cursor options)))

(defn listing [{:keys [data-cursor card-fn load-fn new-fn new-async-fn add-caption new-card-location] :as options}]
  (when load-fn (do (println "running load-fn: " load-fn data-cursor)
                    (load-fn data-cursor)
                    (println "after load-fn: " data-cursor)))
  (fn []
    [:div {:class "flex flex-column items-center"}
     ;;[:div {} (str @data-cursor)]
     [:ul {:class "list"}
      (doall
       (map (fn [idx]
              (let [item-cursor (reagent/cursor data-cursor [idx])
                    id (:_id @item-cursor)]
                ^{:key id}
                [:li {} [card-fn item-cursor options;; (partial remove-item-from-listing
                         ;;          data-cursor
                         ;;          id)
                         ]]))
            (range 0 (count @data-cursor))))]
     (when (or new-fn new-async-fn)
       [:button {:class "bn br3 white bg-green dim pa2"
                 :on-click
                 (fn [evt]
                                        ;                         (new-async-fn (partial add-item data-cursor new-card-location))
                   (if new-async-fn
                     (new-async-fn (partial add-item data-cursor new-card-location))
                     (add-item data-cursor new-card-location (new-fn)))
                   )}
        (or add-caption "+ Add")])
     ]))

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
