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
