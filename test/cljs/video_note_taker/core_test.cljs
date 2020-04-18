(ns video-note-taker.core-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [video-note-taker.core :as core]))

(deftest fake-test
  (testing "fake description"
    (is (= 1 2))))
