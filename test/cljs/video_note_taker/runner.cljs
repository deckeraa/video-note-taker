(ns video-note-taker.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [video-note-taker.core-test]))

(doo-tests 'video-note-taker.core-test)
