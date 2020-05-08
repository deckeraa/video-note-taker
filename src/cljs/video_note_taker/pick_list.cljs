(ns video-note-taker.pick-list
  (:require [reagent.core :as reagent]
            [video-note-taker.svg :as svg])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]))

(defn pick-list
  "Dialog that allows a user to share the video with other users."
  [{:keys [remove-delegate-atom
           data-cursor
           option-load-fn
           can-delete-option-fn
           caption
           ok-fn
           cancel-fn]}
   ]
  (println "caption" caption)
  (println "remove-delegate-atom" remove-delegate-atom)
  (println "ok-fn" ok-fn)
  (let [selected-data-atm (reagent/atom (set @data-cursor))
        user-input-atm (reagent/atom "")
        option-list-atm  (reagent/atom #{})
        preferred-type (type @data-cursor) ; we preserve the seq type of data-cursor, but use sets internally. preferred-type tells us what seq type to case back to when putting the selection back into the data-cursor.
        _ (when option-load-fn (option-load-fn option-list-atm)
                                        ;(reset! option-list-atm (set (option-load-fn)))
                )
        ]
    (fn []
      [:div {:class "flex flex-column"}
       ;; List out the current selection who selected to be on the video
       [:div {} caption]
       [:ul
        (doall (map (fn [option]
                      ^{:key option}
                      [:li {:class "flex items-center justify-center"}
                       option
                       (when (or (nil? can-delete-option-fn) (can-delete-option-fn option))
                         [svg/x {:class "ma2 dim"
                                 :on-click (fn []
                                             (swap! selected-data-atm disj option))}
                          "red" "12px"])])
                    @selected-data-atm))]
       ;; A selection box for adding new users
       [:div {:class "flex br3"}
        [:select {:type :text
                  :class "bn"
                  :value @user-input-atm
                  :on-change (fn [e]
                               (swap! selected-data-atm conj (-> e .-target .-value))
                               (reset! user-input-atm "")
                               )}
         (doall (map (fn [name]
                       ^{:key name}
                       [:option {:value name} (if (= name "") "-- Select option --" name)])
                     (conj (clojure.set/difference @option-list-atm @selected-data-atm) "")))]]
       ;; Cancel and OK buttons
       [:div {:class "flex mt2 mh2"}
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e]
                              (@remove-delegate-atom)
                              (when cancel-fn (cancel-fn)))} ; closes the dialog
         "Cancel"]
        [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                  :on-click (fn [e]
                              (reset! data-cursor
                                      (condp = preferred-type
                                        cljs.core/List
                                        (list @selected-data-atm)
                                        cljs.core/PersistentVector
                                        (vec @selected-data-atm)
                                        @selected-data-atm))
                              (@remove-delegate-atom) ; closes the dialog
                              (when ok-fn (ok-fn)))}
         "Ok"]]])))

(defcard-rg test-pick-list
  (let [data-cursor        (reagent/atom ["a" "b" "c"])]
    (fn []
      [:div {:class ""}
       [pick-list
        {:remove-delegate-atom    (reagent/atom (fn [] nil))
         :data-cursor           data-cursor     
         :option-load-fn        (fn [options-cursor] (reset! options-cursor #{"c" "d" "e" "f"}))
         :can-delete-option-fn  (fn [option] (not (= option "a")))
         :caption               "CAPTION GOES HERE:"
         }]
       [:p (str @data-cursor)]])))
