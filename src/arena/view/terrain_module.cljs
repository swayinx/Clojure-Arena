(ns arena.view.terrain-module
  "Static arena art and animated terrain rendering."
  (:require [arena.engine :as e]
            [arena.view.canvas-module :as cv]))

(def canvas cv/canvas)
(def ctx cv/ctx)
(def reduced-motion? cv/reduced-motion?)
(def rect cv/rect)
(def line cv/line)
(def circle cv/circle)
(def ring cv/ring)
(def arc cv/arc)
(def label cv/label)
(def polygon cv/polygon)

(def floor-cache (atom nil))
(def elemental-art
  (let [image (js/Image.)]
    (set! (.-onload image) #(reset! floor-cache nil))
    (set! (.-src image) "art/elemental-tiles.png")
    image))
(def map-art {:agua elemental-art :lava elemental-art})

(defn paint-patio! []
  (rect -12 -12 984 624 "#0c171d")
  (polygon [[40 15] [920 15] [945 40] [945 560] [920 585] [40 585] [15 560] [15 40]] "#34484a")
  (polygon [[42 25] [918 25] [935 42] [935 558] [918 575] [42 575] [25 558] [25 42]] "#1c2d32")
  (rect 44 44 872 512 "#203137")
  (doseq [x (range 44 917 44)] (line x 44 x 556 "#abcfc909" 1))
  (doseq [y (range 44 557 44)] (line 44 y 916 y "#abcfc909" 1))
  (doseq [[x y] [[44 44] [696 44] [44 424] [696 424]]]
    (rect x y 220 132 "#9acbb203") (line x (+ y 132) (+ x 220) (+ y 132) "#8fc6b615" 1))
  ;; Panels, drains, and painted routes are rasterized once.
  (doseq [x (range 68 900 88) y (range 66 540 88)]
    (line x y (+ x 28) y "#aecfc310" 1)
    (rect (+ x 30) (- y 1) 2 2 "#8baca922"))
  (doseq [x [64 876] y [190 348]]
    (rect x y 20 62 "#101e26")
    (doseq [yy (range (+ y 5) (+ y 58) 7)] (line (+ x 3) yy (+ x 17) yy "#3d5057" 2))
    (rect (+ x 2) (+ y 63) 16 2 "#8baca92b"))
  (doseq [x [246 700]]
    (line x 80 x 520 "#9abbb41a" 2)
    (doseq [y [100 300 500]]
      (line (- x 5) (- y 5) x y "#9abbb433" 1)
      (line x y (+ x 5) (- y 5) "#9abbb433" 1)))
  (doseq [[x y label-text] [[72 80 "01 / NORTE"] [886 530 "02 / SUR"]]]
    (label label-text x y "#aecfc34a" 7))
  (ring 480 300 68 "#15262d" 14)
  (doseq [i (range 12)]
    (let [a (* i (/ e/pi 6))]
      (line (+ 480 (* 65 (e/cos a))) (+ 300 (* 65 (e/sin a)))
            (+ 480 (* 74 (e/cos a))) (+ 300 (* 74 (e/sin a))) "#6c96933a" 2)))
  (line 480 44 480 556 "#b6dac91b" 2) (line 44 300 916 300 "#b6dac912" 1)
  (ring 480 300 102 "#a6cebc15" 2) (ring 480 300 95 "#a6cebc0c" 1)
  (polygon [[480 276] [504 300] [480 324] [456 300]] "#d5f5cd0a")
  (label "PATIO" 480 260 "#a7d4c620" 18) (label "CERO" 480 354 "#a7d4c620" 18)
  (doseq [x [112 848] y [84 516]]
    (ring x y 18 "#9dd1bb25" 1)
    (line (- x 12) y (+ x 12) y "#bde5c930" 1)
    (line x (- y 12) x (+ y 12) "#bde5c930" 1))
  (doseq [y [160 438]]
    (line 367 y 593 y "#bce2c91a" 2)
    (doseq [x (range 385 580 32)]
      (rect x (- y 2) 12 4 "#d3c78236")))
  (doseq [[x y] e/spawn-points]
    (ring x y 37 "#9cc8b41a" 2)
    (doseq [i (range 4)] (let [a (* i (/ e/pi 2))]
                          (line (+ x (* (e/cos a) 30)) (+ y (* (e/sin a) 30))
                                (+ x (* (e/cos a) 43)) (+ y (* (e/sin a) 43)) "#9bc3ad33" 2))))
  (doseq [[x y w h] [[46 20 170 3] [744 20 170 3] [46 577 170 3] [744 577 170 3]]]
    (rect (- x 1) (- y 3) (+ w 2) (+ h 6) "#caf49308") (rect x y w h "#9cba72"))
  (doseq [[x y] [[20 110] [20 430] [937 110] [937 430]]]
    (rect x y 3 60 "#669ca0"))
  (doseq [{:keys [x y w h]} (get-in e/arenas [:patio :obstacles])]
    (rect (+ x 4) (+ y 8) w h "#0b171e80")
    (rect x y w h "#415255") (rect (+ x 3) (+ y 3) (- w 6) (- h 8) "#2c3d43")
    (rect x y w 3 "#657c79") (rect x (+ y h -5) w 5 "#17262c")
    (line (+ x 10) (+ y 12) (+ x w -10) (+ y 12) "#57706b" 2)
    (line (+ x 10) (+ y 18) (+ x w -10) (+ y 18) "#3c5354" 1)
    (rect (+ x 7) (+ y 6) (- w 14) 3 "#90c9b766")
    (doseq [yy (range (+ y 25) (+ y h -11) 7)]
      (line (+ x 9) yy (+ x w -9) yy "#8bb5ac24" 1))
    (doseq [offset [0 13 26 39]]
      (polygon [[(+ x 8 offset) (+ y h -12)] [(+ x 14 offset) (+ y h -12)]
                [(+ x 21 offset) (+ y h -5)] [(+ x 15 offset) (+ y h -5)]] "#d3b86b80"))
    (rect (+ x 7) (+ y 6) 4 3 "#c8e299") (rect (+ x w -11) (+ y 6) 4 3 "#c8e299")))

(defn paint-variant! [arena]
  (let [water? (= arena :agua)
        base (if water? "#0e222a" "#211214")
        border (if water? "#40a0c0" "#e05530")
        seam (if water? "#2a586829" "#6a241629")
        image (get map-art arena)
        image? (and image (.-complete image) (pos? (.-naturalWidth image)))
        [sx sy size] (if water? [0 0 16] [48 0 16])]
    (rect 0 0 960 600 (if water? "#081318" "#180a0a"))
    (rect 24 24 912 552 (if water? "#183842" "#421612"))
    (rect 40 40 880 520 base)
    ;; The original atlas repeats in the static cache; the engine defines collisions.
    (when image?
      (doseq [x (range 40 920 32) y (range 40 560 32)]
        (.drawImage ctx image sx sy size size x y 32 32)))
    (rect 40 40 880 520 (if water? "#0a202866" "#30100c50"))
    (doseq [x (range 40 921 32)] (line x 40 x 560 seam 1))
    (doseq [y (range 40 561 32)] (line 40 y 920 y seam 1))
    ;; Static base for the terrain hazards.
    (doseq [{:keys [x y w h kind]} (get-in e/arenas [arena :hazards])]
      (if (= kind :water)
        (do
          (rect x y w h "#0a2638")
          (rect (+ x 2) (+ y 2) (- w 4) (- h 4) "#10425c")
          (doseq [i (range (+ x 6) (+ x w -6) 16)]
            (line i (+ y 3) i (+ y h -3) "#19597a44" 1))
          (rect x y w 2 "#3892b8")
          (rect x (+ y h -2) w 2 "#0a2638")
          (rect x y 2 h "#206e90")
          (rect (+ x w -2) y 2 h "#206e90"))
        (do
          (rect x y w h "#3a0e08")
          (rect (+ x 2) (+ y 2) (- w 4) (- h 4) "#681c0d")
          (rect (+ x 5) (+ y 5) (- w 10) (- h 10) "#942f12")
          (rect x y w 2 "#ff5722")
          (rect x (+ y h -2) w 2 "#3a0e08")
          (rect x y 2 h "#bd3612")
          (rect (+ x w -2) y 2 h "#bd3612"))))
    (doseq [[x y] (get-in e/arenas [arena :spawns])]
      (ring x y 32 (str border "66") 2)
      (doseq [i (range 4)]
        (let [a (* i (/ e/pi 2))]
          (line (+ x (* 25 (e/cos a))) (+ y (* 25 (e/sin a)))
                (+ x (* 37 (e/cos a))) (+ y (* 37 (e/sin a))) border 2))))
    (ring 480 300 77 (str border "32") 2)
    (ring 480 300 61 (str border "22") 1)
    (label (if water? "CANALES / 02" "FRACTURA / 03") 480 300 (str border "44") 15)
    (doseq [{:keys [x y w h]} (get-in e/arenas [arena :obstacles])]
      (rect (+ x 6) (+ y 8) w h "#08131ba0")
      (rect x y w h (if water? "#1b3c48" "#4d1d16"))
      (rect (+ x 4) (+ y 4) (- w 8) (- h 8) (if water? "#2f6070" "#843022"))
      (when image?
        (let [[cx cy cs] (if water? [16 16 16] [64 16 16])]
          (doseq [xx (range (+ x 6) (+ x w -16) 24)]
            (.drawImage ctx image cx cy cs cs xx (+ y 6) 24 (- h 14)))))
      (rect x y w 4 border)
      (rect x (+ y h -5) w 5 (if water? "#10262e" "#30120d"))
      (doseq [xx (range (+ x 10) (+ x w -8) 18)]
        (line xx (+ y h -11) (+ xx 7) (+ y h -5) (str border "99") 2)))
    (doseq [x [24 932] y [90 450]] (rect x y 4 50 border))))

(defn cache-floor! [arena]
  (when (not= arena (:arena @floor-cache))
    (if (= arena :patio) (paint-patio!) (paint-variant! arena))
    (let [copy (.createElement js/document "canvas")]
      (set! (.-width copy) 960) (set! (.-height copy) 600)
      (.drawImage (.getContext copy "2d") canvas 0 0)
      (reset! floor-cache {:arena arena :canvas copy}))))

(defn draw-hazards! [arena-key now settings]
  (let [hazards (get-in e/arenas [(e/arena-id arena-key) :hazards])
        animated? (and (not (reduced-motion?)) (= :full (:particles settings)))]
    (doseq [{:keys [x y w h kind]} hazards]
      (case kind
        :water
        (do
          (.save ctx)
          (rect x y w h "#1b628238")
          (when animated?
            (doseq [yy (range (+ y 12) (+ y h -10) 18)]
              (let [wave (* 5 (e/sin (+ (* now 0.004) (/ (+ x yy) 25))))]
                (line (+ x 8) (+ yy wave) (+ x w -8) (+ yy wave) "#4ac2ea33" 1.5))))
          (.restore ctx))
        :lava
        (let [glow (+ 0.7 (* 0.2 (e/sin (+ (* now 0.005) (/ y 30)))))]
          (.save ctx)
          (set! (.-globalAlpha ctx) glow)
          (rect x y w h "#e64a1930")
          (rect (+ x 4) (+ y 4) (- w 8) (- h 8) "#ff980022")
          (when animated?
            (doseq [i (range 4)]
              (let [bx (+ x 15 (mod (+ (* i 47) (* now 0.015)) (- w 30)))
                    by (+ y 12 (mod (+ (* i 31) (* now 0.008)) (- h 24)))
                    bubble-r (+ 2 (* 1.5 (e/sin (+ (* now 0.01) i))))]
                (circle bx by bubble-r "#ffeb3b80"))))
          (.restore ctx))
        nil))))
