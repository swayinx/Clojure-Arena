(ns arena.view.canvas-module
  "Canvas resources and drawing primitives shared by the view."
  (:require [arena.engine :as e]))

(def palette {"brasa" "#ff9474" "chispa" "#76e5d1" "mora" "#c6a2ff" "bastion" "#f6ce78"})
(def canvas (.getElementById js/document "arena"))
(def ctx (.getContext canvas "2d" #js {:alpha false}))
(def motion (.matchMedia js/window "(prefers-reduced-motion: reduce)"))
(defn reduced-motion? [] (.-matches motion))
(defn cap [v n] (vec (take-last n v)))
(defn rect [x y w h color] (set! (.-fillStyle ctx) color) (.fillRect ctx x y w h))
(defn line [x y xx yy color width]
  (set! (.-strokeStyle ctx) color) (set! (.-lineWidth ctx) width)
  (.beginPath ctx) (.moveTo ctx x y) (.lineTo ctx xx yy) (.stroke ctx))
(defn circle [x y r color]
  (set! (.-fillStyle ctx) color) (.beginPath ctx) (.arc ctx x y (max 0 r) 0 (* 2 e/pi)) (.fill ctx))
(defn ring [x y r color width]
  (set! (.-strokeStyle ctx) color) (set! (.-lineWidth ctx) width)
  (.beginPath ctx) (.arc ctx x y (max 0 r) 0 (* 2 e/pi)) (.stroke ctx))
(defn arc [x y r from to color width]
  (set! (.-strokeStyle ctx) color) (set! (.-lineWidth ctx) width)
  (.beginPath ctx) (.arc ctx x y r from to) (.stroke ctx))
(defn label [s x y color size]
  (set! (.-font ctx) (str "700 " size "px Mono"))
  (set! (.-textAlign ctx) "center") (set! (.-fillStyle ctx) color) (.fillText ctx s x y))
(defn polygon [pts color]
  (.beginPath ctx) (.moveTo ctx (ffirst pts) (second (first pts)))
  (doseq [[x y] (rest pts)] (.lineTo ctx x y))
  (.closePath ctx) (set! (.-fillStyle ctx) color) (.fill ctx))
(defonce sprite-bank (atom nil))
(defn initialize-sprites! []
  (let [loaded (into {} (map (fn [skin]
                               (let [im (js/Image.)]
                                 (set! (.-src im) (str "art/" skin ".svg"))
                                 [skin im])) e/skins))]
    (reset! sprite-bank loaded)
    loaded))
(defn sprite-map [] @sprite-bank)
