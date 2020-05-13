(ns video-note-taker.pick-list
  (:require [reagent.core :as reagent]
            [video-note-taker.svg :as svg]
            [video-note-taker.db :as db])
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
           cancel-fn
           save-to-cursor-delegate-atom
           name-key]}
   ]
  (let [selected-data-atm (reagent/atom (set @data-cursor))
        user-input-atm (reagent/atom "")
        option-list-atm  (reagent/atom #{})
        preferred-type (type @data-cursor) ; we preserve the seq type of data-cursor, but use sets internally. preferred-type tells us what seq type to case back to when putting the selection back into the data-cursor.
        save-to-cursor-fn #(reset! data-cursor
                                   (condp = preferred-type
                                     cljs.core/List
                                     (list @selected-data-atm)
                                     cljs.core/PersistentVector
                                     (vec @selected-data-atm)
                                     @selected-data-atm))
        _ (when save-to-cursor-delegate-atom (reset! save-to-cursor-delegate-atom save-to-cursor-fn))
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
                       (if name-key
                         (str option)
                           option)
                       (when (or (nil? can-delete-option-fn) (can-delete-option-fn option))
                         [svg/x {:class "ma2 dim"
                                 :on-click (fn []
                                             (swap! selected-data-atm disj option))}
                          "red" "12px"])])
                    @selected-data-atm))]
       ;; A selection box for adding new users
       [:div {:class "flex br3"}
        [:select {:type :text
                  :class "bn w5"
                  :value @user-input-atm
                  :on-change (fn [e]
                               (swap! selected-data-atm conj (-> e .-target .-value))
                               (reset! user-input-atm "")
                               )}
         (doall
          (if name-key
            (map (fn [item]
                   ^{:key item}
                   [:option {:value (or item "")}
                    (if (= item "")
                      "-- Select option --"
                      (:name item))])
                 (conj (clojure.set/difference @option-list-atm @selected-data-atm) ""))
            (map (fn [name]
                       ^{:key name}
                       [:option {:value name} (if (= name "") "-- Select option --" name)])
                     (conj (clojure.set/difference @option-list-atm @selected-data-atm) ""))))]]
       ;; Cancel and OK buttons
       [:div {:class "flex mt2 mh2"}
        (when cancel-fn
          [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                    :on-click (fn [e]
                                (when remove-delegate-atom (@remove-delegate-atom))
                                (when cancel-fn (cancel-fn)))} ; closes the dialog
           "Cancel"])
        (when ok-fn
          [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                    :on-click (fn [e]
                                (save-to-cursor-fn)
                                (when remove-delegate-atom (@remove-delegate-atom)) ; closes the dialog
                                (when ok-fn (ok-fn)))}
           "Ok"])]])))



(defcard-rg test-pick-list
  (let [data-cursor        (reagent/atom ["a" "b" "c"])]
    (fn []
      [:div {:class ""}
       [pick-list
        {:remove-delegate-atom    (reagent/atom (fn [] nil))
         :data-cursor           data-cursor     
         :option-load-fn        (fn [options-cursor] (reset! options-cursor #{"c" "d" "e" "f" "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"}))
         :can-delete-option-fn  (fn [option] (not (= option "a")))
         :caption               "CAPTION GOES HERE:"
         }]
       [:p (str @data-cursor)]])))

(defn pick-list-with-docs
  "Dialog that allows a user to share the video with other users."
  [{:keys [remove-delegate-atom
           data-cursor
           option-load-fn
           can-delete-option-fn
           caption
           ok-fn
           cancel-fn
           save-to-cursor-delegate-atom
           name-key
           id-lookup-fn
           ]}
   ]
  (let [selected-data-atm (reagent/atom nil)
        user-input-atm (reagent/atom "")
        option-list-atm  (reagent/atom #{})
        preferred-type (type @data-cursor) ; we preserve the seq type of data-cursor, but use sets internally. preferred-type tells us what seq type to case back to when putting the selection back into the data-cursor.
        save-to-cursor-fn #(reset! data-cursor
                                      (condp = preferred-type
                                        cljs.core/List
                                        (list (map :_id @selected-data-atm))
                                        cljs.core/PersistentVector
                                        (vec  (map :_id @selected-data-atm))
                                        (map :_id @selected-data-atm)))
        ;; save off save-to-cursor-fn into a passed-in atom so that parent controls can implement their own "ok" button if desired
        _ (when save-to-cursor-delegate-atom (reset! save-to-cursor-delegate-atom save-to-cursor-fn))
        _ (when option-load-fn (option-load-fn option-list-atm)
                                        ;(reset! option-list-atm (set (option-load-fn)))
                )]
    (if id-lookup-fn
      (id-lookup-fn (vec @data-cursor) selected-data-atm)
      (db/bulk-lookup (vec @data-cursor) (fn [docs] (reset! selected-data-atm (set docs)))))
    (fn []
      [:div {:class "flex flex-column"}
       ;; List out the current selections
       [:div {} caption]
       [:ul
        (doall (map (fn [option]
                      ^{:key option}
                      [:li {:class "flex items-center justify-center"}
                       (get option name-key)
                       (when (and (or (nil? can-delete-option-fn)
                                      (can-delete-option-fn option))
                                  (get @option-list-atm (str (:_id option))))
                         [svg/x {:class "ma2 dim"
                                 :on-click (fn []
                                             (swap! selected-data-atm disj option))}
                          "red" "12px"])])
                    @selected-data-atm))]
       ;; A selection box for adding new items
       [:div {:class "flex br3"}
        [:select {:type :text
                  :class "bn w5"
                  :value @user-input-atm
                  :on-change (fn [e]
                               (swap! selected-data-atm
                                      conj
                                      (get @option-list-atm
                                           (-> e .-target .-value)))
                               (reset! user-input-atm "")
                               )}
         (doall
          (map (fn [item]
                 ^{:key (or (:_id item) "-- Select option --")}
                 [:option {:value (or (:_id item) "")}
                  (if (= item "")
                    "-- Select option --"
                    (get item name-key))])
               (conj (clojure.set/difference
                      (set (vals @option-list-atm))
                      @selected-data-atm)
                     "")))]]
       ;; Cancel and OK buttons

       [:div {:class "flex mt2 mh2"}
        (when cancel-fn
          [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                    :on-click (fn [e]
                                (@remove-delegate-atom)
                                (cancel-fn))} ; closes the dialog
           "Cancel"])
        (when ok-fn
          [:button {:class "black bg-white br3 dim pa2 ma2 shadow-4 bn"
                    :on-click (fn [e]
                                (save-to-cursor-fn)
                                (@remove-delegate-atom) ; closes the dialog
                                (ok-fn))}
           "Ok"])]])))

(defcard-rg test-pick-list-with-map
  (let [data-cursor        (reagent/atom [123])
        save-to-cursor-delegate-atom (reagent/atom (fn [] nil))]
    (fn []
      [:div {:class ""}
       [pick-list-with-docs
        {:remove-delegate-atom    (reagent/atom (fn [] nil))
         :data-cursor           data-cursor     
         :option-load-fn
         (fn [options-cursor]
           (reset! options-cursor
                   {"456" {:_id 456 :name "def"}
                    "789" {:_id 789 :name "ghi"}}))
         :can-delete-option-fn  (fn [option] (not (= option "a")))
         :caption               "CAPTION GOES HERE:"
         :name-key :name
         :save-to-cursor-delegate-atom save-to-cursor-delegate-atom
         :id-lookup-fn (fn [ids atom-to-put-docs-in]
                         (reset! atom-to-put-docs-in
                                 (set (map (fn [id]
                                             {:_id id :name (str "foo_" id)})
                                           ids))))
         }]
       [:button {:on-click #(@save-to-cursor-delegate-atom)} "save-to-cursor-delegate"]
       [:p (str @data-cursor)]])))

(defcard-rg double-pick-list
  (let [video-cursor  (reagent/atom {:users [] :groups []})
        users-cursor  (reagent/cursor video-cursor [:users])
        groups-cursor (reagent/cursor video-cursor [:groups])
        users-save-atom (reagent/atom #())
        groups-save-atom (reagent/atom #())]
    (fn []
      [:div {:class ""}
       [pick-list
        {:data-cursor users-cursor
         :option-load-fn #(reset! % #{"Alpha" "Bravo" "Charlie"})
         :caption "Users:"
         :save-to-cursor-delegate-atom users-save-atom}]
       [pick-list-with-docs
        {:data-cursor groups-cursor
         :option-load-fn #(reset! % {"456" {:_id 456 :name "def"}
                                     "789" {:_id 789 :name "ghi"}})
         :caption "Groups:"
         :name-key :name
         :save-to-cursor-delegate-atom groups-save-atom}]
       [:button {:on-click (fn [] (@users-save-atom) (@groups-save-atom))} "Ok"]
       [:p (str @video-cursor)]])))
