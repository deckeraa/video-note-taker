(ns video-note-taker.editable-field
  (:require
   [reagent.core :as reagent]
   [video-note-taker.svg :as svg])
  (:require-macros
   [devcards.core :refer [defcard defcard-rg deftest]]))

(defn editable-field [initial-val save-fn]
  (let [editing? (reagent/atom false)
        restore-val-atm (reagent/atom initial-val)
        val-atm (reagent/atom initial-val)
        hover-atm (reagent/atom false)]
    (fn [initial-val save-fn]
      (if @editing?
        [:div {:class "flex items-center"}
         [:textarea {:type :text :value @val-atm
                     :rows 5 :cols 35
                     :on-change (fn [e]
                                  (println "on-change")
                                  (reset! val-atm (-> e .-target .-value)))
                     :on-click (fn [e]
                                 (println "textarea clicked.")
                                 (.stopPropagation e)
)}]
         [:div {:class "flex flex-column"}
          [svg/check {:class "dim ma2"
                      :on-click (fn [e]
                                  (.stopPropagation e) ;; prevent this click from registing as a click on underlying elements.
                                  (save-fn @val-atm #(reset! editing? false)))}
           "green" "24px"]
          [svg/x {:class "dim ma2"
                  :on-click (fn [e]
                              (.stopPropagation e) ;; prevent this click from registing as a click on underlying elements.
                              (reset! val-atm @restore-val-atm)
                              (reset! editing? false))}
           "red" "24px"]]]
        [:div {:class "flex items-center"
               :on-mouse-over (fn [e] (reset! hover-atm true))
               :on-mouse-out  (fn [e] (reset! hover-atm false))}
         [:p {:class ""}
          @val-atm]
         (if @hover-atm
           [svg/pencil {:class "dim ma2"
                        :on-click (fn [e]
                                    (.stopPropagation e) ;; prevent this click from registing as a click on underlying elements.
                                    (reset! editing? true)
                                    (reset! restore-val-atm @val-atm))}
            "gray" "18px"]
           [:div {:class "ma2"
                  :style {:width "18px"}}
            ;; empty div to reserve space for the pencil effect on hover
            ])]))))

(defcard-rg editable-field-devcard
  [editable-field "Initial text for the field" (fn [] (println "save-fn!!"))])
