(ns video-note-taker.editable-field
  (:require
   [reagent.core :as reagent]
   [video-note-taker.svg :as svg]))

(defn editable-field [initial-val save-fn]
  (let [editing? (reagent/atom false)
        restore-val-atm (reagent/atom initial-val)
        val-atm (reagent/atom initial-val)]
    (fn [initial-val save-fn]
      (if @editing?
        [:div {:class "flex items-center"}
         [:textarea {:type :text :value @val-atm
                     :rows 5 :cols 35
                     :on-change (fn [e]
                                  (reset! val-atm (-> e .-target .-value)))}]
         [:div {:class "flex flex-column"}
          [svg/check {:class "dim ma2"
                      :on-click (fn []
                                  (save-fn @val-atm #(reset! editing? false)))}
           "green" "24px"]
          [svg/x {:class "dim ma2"
                  :on-click (fn []
                              (reset! val-atm @restore-val-atm)
                              (reset! editing? false))}
           "red" "24px"]]]
        [:div {:class "flex items-center"}
         [:p {:class ""} @val-atm]
         [svg/pencil {:class "dim ma2"
                     :on-click #(do (reset! editing? true)
                                    (reset! restore-val-atm @val-atm))}
          "gray" "18px"]]))))
