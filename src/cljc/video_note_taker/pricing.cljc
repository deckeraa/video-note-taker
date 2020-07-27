(ns video-note-taker.pricing
  #?(:clj
     (:require [clojure.math.numeric-tower :as math]))
  #?(:cljs (:require [devcards.core]
                     ))
  #?(:cljs (:require-macros
            [devcards.core :refer [defcard defcard-rg deftest]]
            [cljs.test :refer [testing is]])))

#?(:clj
   (def ceil math/ceil))

#?(:cljs
   (def ceil Math/ceil))

(defn b2b-price-in-cents-calculations [gbs family-members months]
  (let [gb-bucket 50
        family-bucket 15
        bucket-price 500
        setup-fee 1000
        gb-buckets (ceil (/ gbs gb-bucket))
        family-buckets (ceil (/ family-members family-bucket))
        gb-fee (* gb-buckets bucket-price months)
        family-fee (* family-buckets bucket-price months)
        num-buckets (max gb-buckets family-buckets)
        total (+ (* num-buckets bucket-price months)
                 setup-fee)]
    (merge
     {:gb-buckets gb-buckets :family-buckets family-buckets
      :setup-fee setup-fee
      :total total}
     (if (>= gb-fee family-fee)
       {:gb-fee gb-fee}
       {:family-fee family-fee}))
    ))

(defn b2b-price-in-cents [gbs family-members months]
  (:total (b2b-price-in-cents-calculations gbs family-members months)))

#?(:cljs
   (deftest b2b-price-test
     (is (= (b2b-price-in-cents 5 5 1) 1500))
     (is (= (b2b-price-in-cents 5 5 2) 2000))
     (is (= (b2b-price-in-cents 105 5 2) 4000))
     (is (= (b2b-price-in-cents 5 25 1) 2000))))

(defn in-dollars [cents]
  (when cents
    (let [as-vec (vec (str cents))
          dollars-vec (subvec as-vec
                              0
                              (- (count as-vec) 2))
          cents-vec   (subvec as-vec
                              (- (count as-vec) 2))]
      (str "$" (apply str dollars-vec) "." (apply str cents-vec)))))

(defn pluralize [s x]
  (if (= x 1)
    s
    (str s "s")))

#?(:cljs
   (defn price-card [gbs family-members months]
     (let [
           calc (b2b-price-in-cents-calculations gbs family-members months)]
       [:div {}
        [:table {:style {:border-collapse :collapse}}
         [:tr [:td] [:td "Quantity"] [:td "Charge"]]
         [:tr {:class (when (nil? (:gb-fee calc)) "gray")}
          [:td gbs " GBs"]
          [:td (:gb-buckets calc) " " (pluralize "unit" (:gb-buckets calc)) " of 50 GB"]
          [:td (in-dollars (:gb-fee calc))]]
         [:tr {:class (when (nil? (:family-fee calc)) "gray")}
          [:td family-members " family members"]
          [:td (:family-buckets calc) " " (pluralize "unit" (:family-buckets calc)) " of 15 family members"]
          [:td (in-dollars (:family-fee calc))]]
         [:tr [:td "Setup Fee"] [:td] [:td (in-dollars (:setup-fee calc))]]
         [:tr {:style {:border-top "1px solid #ddd"}} [:td "Total"] [:td] [:td (in-dollars (:total calc))]]]
        ])))

#?(:cljs
   (defcard-rg price-display-card
     [price-card 105 5 2]))
