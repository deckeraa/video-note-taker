(ns video-note-taker.svg
  "Contains reagent components that generate various SVGs.
   Most of these are based off of the Open Iconic library ( https://github.com/iconic/open-iconic/ ). 
   To the extent required by the Open Font License ( https://github.com/iconic/open-iconic/blob/master/FONT-LICENSE ),
  this code is available under the Open Font License. Otherwise, it is under the GPL v3, as is the rest of the project."
  (:require
   [reagent.core :as reagent]
   [devcards.core])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [devcards.core :refer [defcard defcard-rg]]))

(defn cog [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M3.5 0l-.5 1.19c-.1.03-.19.08-.28.13l-1.19-.5-.72.72.5 1.19c-.05.1-.09.18-.13.28l-1.19.5v1l1.19.5c.04.1.08.18.13.28l-.5 1.19.72.72 1.19-.5c.09.04.18.09.28.13l.5 1.19h1l.5-1.19c.09-.04.19-.08.28-.13l1.19.5.72-.72-.5-1.19c.04-.09.09-.19.13-.28l1.19-.5v-1l-1.19-.5c-.03-.09-.08-.19-.13-.28l.5-1.19-.72-.72-1.19.5c-.09-.04-.19-.09-.28-.13l-.5-1.19h-1zm.5 2.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5-1.5-.67-1.5-1.5.67-1.5 1.5-1.5z"}]]])

(defn bell [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M4 0c-1.1 0-2 .9-2 2 0 1.04-.52 1.98-1.34 2.66-.41.34-.66.82-.66 1.34h8c0-.52-.24-1-.66-1.34-.82-.68-1.34-1.62-1.34-2.66 0-1.1-.89-2-2-2zm-1 7c0 .55.45 1 1 1s1-.45 1-1h-2z"}]]
])

(defn chevron-left [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 9" :fill color}
    [:path {:d "M4 0l-4 4 4 4 1.5-1.5-2.5-2.5 2.5-2.5-1.5-1.5z" :transform "translate(0,1)"}]]])

(defn chevron-right [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M1.5 0l-1.5 1.5 2.5 2.5-2.5 2.5 1.5 1.5 4-4-4-4z" :transform "translate(1)"}]]])

(defn home [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M4 0l-4 3h1v4h2v-2h2v2h2v-4.03l1 .03-4-3z"}]]])

(defn delete [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M2 0l-2 3 2 3h6v-6h-6zm1.5.78l1.5 1.5 1.5-1.5.72.72-1.5 1.5 1.5 1.5-.72.72-1.5-1.5-1.5 1.5-.72-.72 1.5-1.5-1.5-1.5.72-.72z" :transform "translate(0 1)"}]]])

(defn trash [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M3 0c-.55 0-1 .45-1 1h-1c-.55 0-1 .45-1 1h7c0-.55-.45-1-1-1h-1c0-.55-.45-1-1-1h-1zm-2 3v4.81c0 .11.08.19.19.19h4.63c.11 0 .19-.08.19-.19v-4.81h-1v3.5c0 .28-.22.5-.5.5s-.5-.22-.5-.5v-3.5h-1v3.5c0 .28-.22.5-.5.5s-.5-.22-.5-.5v-3.5h-1z"}]]])

(defn check [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M6.41 0l-.69.72-2.78 2.78-.81-.78-.72-.72-1.41 1.41.72.72 1.5 1.5.69.72.72-.72 3.5-3.5.72-.72-1.44-1.41z" :transform "translate(0 1)"}]]])

(defn x [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M1.41 0l-1.41 1.41.72.72 1.78 1.81-1.78 1.78-.72.69 1.41 1.44.72-.72 1.81-1.81 1.78 1.81.69.72 1.44-1.44-.72-.69-1.81-1.78 1.81-1.81.72-.72-1.44-1.41-.69.72-1.78 1.78-1.81-1.78-.72-.72z"}]]])

(defn right-arrow [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M5 0v2h-5v1h5v2l3-2.53-3-2.47z" :transform "translate(0 1)"}]]])

(defn media-play [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M0 0v6l6-3-6-3z" :transform "translate(1 1)"}]]])

(defn pencil [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M6 0l-1 1 2 2 1-1-2-2zm-2 2l-4 4v2h2l4-4-2-2z"}]]])

(defn plus [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M3 0v3h-3v2h3v3h2v-3h3v-2h-3v-3h-2z"}]]])

(defn magnifying-glass [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M3.5 0c-1.93 0-3.5 1.57-3.5 3.5s1.57 3.5 3.5 3.5c.59 0 1.17-.14 1.66-.41a1 1 0 0 0 .13.13l1 1a1.02 1.02 0 1 0 1.44-1.44l-1-1a1 1 0 0 0-.16-.13c.27-.49.44-1.06.44-1.66 0-1.93-1.57-3.5-3.5-3.5zm0 1c1.39 0 2.5 1.11 2.5 2.5 0 .66-.24 1.27-.66 1.72-.01.01-.02.02-.03.03a1 1 0 0 0-.13.13c-.44.4-1.04.63-1.69.63-1.39 0-2.5-1.11-2.5-2.5s1.11-2.5 2.5-2.5z"}]]])

(defn cloud-upload [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M4.5 0c-1.21 0-2.27.86-2.5 2-1.1 0-2 .9-2 2 0 .37.11.71.28 1h2.22l2-2 2 2h1.41c.06-.16.09-.32.09-.5 0-.65-.42-1.29-1-1.5v-.5c0-1.38-1.12-2.5-2.5-2.5zm0 4.5l-2.5 2.5h2v.5a.5.5 0 1 0 1 0v-.5h2l-2.5-2.5z"}]]])


(defn share-arrow [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 8 8" :fill color}
    [:path {:d "M5 0v2c-4 0-5 2.05-5 5 .52-1.98 2-3 4-3h1v2l3-3.16-3-2.84z"}]]])

(defn share-graph
  "Licensed under MIT license from https://feathericons.com/"
  [style color size]
  [:div style
   [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 24 24"
          :fill "none" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round"
          :stroke-linejoin "round" :class "feather feather-share-2"}
    [:circle {:cx "18" :cy  "5" :r "3"}]
    [:circle {:cx  "6" :cy "12" :r "3"}]
    [:circle {:cx "18" :cy "19" :r "3"}]
    [:line   {:x1  "8.59" :y1 "13.51" :x2 "15.42" :y2 "17.49"}]
    [:line   {:x1 "15.41" :y1  "6.51" :x2  "8.59" :y2 "10.49"}]]])


;; I chose to import svg via img instead. See /resources/public/video-download.svg
;; (defn video-download
;;   "My (Aaron Decker's) own creation. Feel free to use this icon under MIT license."
;;   [style color size]
;;   [:div style
;;    [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 24 24"
;;           :fill "none" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round"
;;           :stroke-linejoin "round"}
;;     [:g {:transform "translate(0,-290.65)"}
;;      [:path 
;;       {:d "M 1.3094806,295.08652 H 0.79404482 c -0.36137628,0 -0.65230375,-0.29093 -0.65230375,-0.6523 v -1.70739 c 0,-0.36138 0.29092747,-0.65231 0.65230375,-0.65231 H 3.7062342 c 0.3613763,0 0.6523037,0.29093 0.6523037,0.65231 v 1.70739 c 0,0.36137 -0.2909274,0.6523 -0.6523037,0.6523 H 3.0667751"}]
;;      [:path
;;       {:d "m 2.1733631,294.4841 v 2.12611"}]
;;      [:path
;;       {:d "m 1.0941347,295.85993 1.0792284,0.75028 1.1424496,-0.74193"}]
;;      [:path
;;       {:d "m 4.4851166,293.55473 1.7484496,-1.09867 -0.012528,2.21786 z"}]]
;;     ]])
