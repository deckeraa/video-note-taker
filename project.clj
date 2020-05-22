(defproject video-note-taker "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.946"]
                 [javax.xml.bind/jaxb-api "2.2.11"]
                 [reagent "1.0.0-alpha2"]
                 [devcards "0.2.4" :exclusions [cljsjs/react]]
                 [bidi "2.1.6"]
                 [ring "1.8.1"]
                 [ring-cors "0.1.12"]
                 [ring/ring-json "0.5.0"]
                 [ring/ring-defaults "0.3.2"]
                 [ring-json-response "0.2.0"]
                 [ring/ring-codec "1.1.1"]
                 [ring-logger "1.0.1"]
                 [net.java.dev.jna/jna "5.5.0"]
                 [com.taoensso/timbre "4.11.0-alpha1"]
                 [clj-http "3.10.1"]
                 [cljs-http "0.1.46"]
                 [com.lucasbradstreet/cljs-uuid-utils "1.0.2"]
                 [danlentz/clj-uuid "0.1.9"]
                 [com.cemerick/url "0.1.1"]
                 [doo "0.1.11"]
                 [org.clojure/data.csv "1.0.0"]
                 [clj-time "0.15.2"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [ring-partial-content "1.0.0"]
                 [cljc.java-time "0.1.8"]
                 [com.stronganchortech/couchdb-auth-for-ring "1.0.1"]
                 [org.martinklepsch/s3-beam "0.6.0-alpha5"]
                 [amazonica "0.3.152" :exclusions [com.amazonaws/aws-java-sdk
                                                   com.amazonaws/amazon-kinesis-client
                                                   com.fasterxml.jackson.core/jackson-core]]
                 [com.amazonaws/aws-java-sdk-core "1.11.788" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [com.amazonaws/aws-java-sdk-s3 "1.11.788" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [org.sharetribe/aws-sig4 "0.1.4"]
                 ]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj" "src/cljc"]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-ring "0.12.5"]]

  :ring {:handler video-note-taker.core/hook-for-lein-ring-plugin}

  :clean-targets ^{:protect false} ["resources/public/js"
                                    "target"
                                    "test/js"]

  :figwheel {:css-dirs ["resources/public/css"]
             :server-port 3450}


                                        ;  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :jar-exclusions      [#"private/.*"]
  :uber-jar-exclusions [#"private/.*"]


  :profiles
  {:dev
   {:dependencies [
                   [figwheel-sidecar "0.5.15"]
                   [com.cemerick/piggieback "0.2.1"]
                   [binaryage/devtools "0.9.9"]]

    :plugins      [[lein-figwheel "0.5.15"]
                   [lein-doo "0.1.11"]]}
   :uberjar
   {:aot :all
    :main video-note-taker.core
    }}

  :cljsbuild
  {:builds
   [{:id           "dev"
     :source-paths ["src/cljs" "src/cljc"]
     :figwheel     {:on-jsload "video-note-taker.core/reload"}
     :compiler     {:main                 video-note-taker.core
                    :optimizations        :none
                    :output-to            "resources/public/js/app.js"
                    :output-dir           "resources/public/js/dev"
                    :asset-path           "js/dev"
                    :source-map-timestamp true
                    :preloads             [devtools.preload]
                    :external-config
                    {:devtools/config
                     {:features-to-install    [:formatters :hints]
                      :fn-symbol              "F"
                      :print-config-overrides true}}}}

    {:id           "devcards"
     :source-paths ["src/devcards" "src/cljs" "src/cljc"]
     :figwheel     {:devcards true}
     :compiler     {:main                 "video-note-taker.core-card"
                    :optimizations        :none
                    :output-to            "resources/public/js/devcards.js"
                    :output-dir           "resources/public/js/devcards"
                    :asset-path           "js/devcards"
                    :source-map-timestamp true
                    :preloads             [devtools.preload]
                    :external-config
                    {:devtools/config
                     {:features-to-install    [:formatters :hints]
                      :fn-symbol              "F"
                      :print-config-overrides true}}}}

    {:id           "hostedcards"
     :source-paths ["src/devcards" "src/cljs"]
     :compiler     {:main          "video-note-taker.core-card"
                    :optimizations :advanced
                    :devcards      true
                    :output-to     "resources/public/js/devcards.js"
                    :output-dir    "resources/public/js/hostedcards"}}

    {:id           "min"
     :source-paths ["src/cljs"]
     :compiler     {:main            video-note-taker.core
                    :optimizations   :simple
                    :output-to       "resources/public/js/app.js"
                    :output-dir      "resources/public/js/min"
                    :elide-asserts   true
                    :closure-defines {goog.DEBUG false}
                    :pretty-print    false
                    :source-map      "resources/public/js/main.js.map"}}

    {:id           "test"
     :source-paths ["src/cljs" "test/cljs"]
     :compiler     {:output-to     "resources/public/js/test.js"
                    :output-dir    "resources/public/js/test"
                    :main          video-note-taker.runner
                    :optimizations :none}}
    ]})
