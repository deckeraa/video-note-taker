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

(defn b2b-price-in-cents [gbs family-members months]
  (let [gb-bucket 50
        family-bucket 15
        bucket-price 500
        setup-fee 1000
        num-buckets (max (ceil (/ gbs gb-bucket))
                         (ceil (/ family-members family-bucket)))]
    (+ (* num-buckets bucket-price months)
       setup-fee)))

#?(:cljs
   (deftest b2b-price-test
     (is (= (b2b-price-in-cents 5 5 1) 1500))
     (is (= (b2b-price-in-cents 5 5 2) 2000))
     (is (= (b2b-price-in-cents 105 5 2) 4000))
     (is (= (b2b-price-in-cents 5 25 1) 2000))))
