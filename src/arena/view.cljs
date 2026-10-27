(ns arena.view
  "Presentación: Canvas y audio. Los efectos visuales no modifican la simulación."
  (:require [arena.engine :as e] [arena.audio :as audio] [arena.presentation :as visual]))

(def palette {"brasa" "#ff9474" "chispa" "#76e5d1" "mora" "#c6a2ff" "bastion" "#f6ce78"})
(def canvas (.getElementById js/document "arena"))
(def ctx (.getContext canvas "2d" #js {:alpha false}))
(def motion (.matchMedia js/window "(prefers-reduced-motion: reduce)"))
(defonce settings (atom {:shake 0.45 :particles :full :sound true :volume 0.65}))
(defonce effects (atom {:particles [] :rings [] :texts [] :ghosts [] :last-event 0 :shake 0 :hit-at -1000 :kill-at -1000}))
(defonce poses (atom {}))
(def sprites (into {} (map (fn [skin] (let [im (js/Image.)] (set! (.-src im) (str "art/" skin ".svg")) [skin im])) e/skins)))
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

(defn resume-audio! []
  (audio/resume! (:sound @settings) (:volume @settings)))
(defn sound! [kind] (audio/play! kind (:sound @settings)))
(defn ultimate-ready! [] (sound! :ult-ready))
(defn reset!fx [] (reset! poses {}) (reset! effects {:particles [] :rings [] :texts [] :ghosts [] :last-event 0 :shake 0 :hit-at -1000 :kill-at -1000}))
(defn particle-burst [x y n speed color now angle spread]
  (let [count (if (or (reduced-motion?) (= :low (:particles @settings))) (max 2 (quot n 3)) n)]
    (mapv (fn [i]
            (let [a (+ angle (* spread (- (* 2 (js/Math.random)) 1))) v (* speed (+ 0.35 (* 0.65 (js/Math.random))))]
              {:x x :y y :vx (* (e/cos a) v) :vy (* (e/sin a) v) :color color
               :size (+ 1.2 (* 2 (js/Math.random))) :born now :until (+ now 240 (* i 9))})) (range count))))
(defn event-fx [fx event world you now]
  (let [{:keys [kind x y player attacker amount absorbed angle]} event
        color (get palette (get-in world [:players player :skin]) "#d9ed91")
        mine? (= you player) my-hit? (= you attacker)
        col (case kind :dash "#76f4ce" :shield "#6acfff" :heal "#a2f078" :boost "#ffbe67"
                  :ult (if mine? "#c7a0ff" "#ff817c") :ult-cast (if mine? "#c7a0ff" "#ff817c") :hit (if (pos? absorbed) "#6acfff" "#ffe3a5")
                  :burn "#ff7043" :kill color :pickup "#d9ed91" :wall "#a8c5b9" color)
        n (case kind :shot 3 :hit 7 :kill 26 :dash 13 :ult 46 :heal 10 :shield 12 :boost 16 :burn 8 :wall 3 :pickup 18 0)
        radius (case kind :ult 125 :kill 75 :dash 44 :shield 57 :heal 52 :boost 55 :hit 25 :burn 20 :pickup 50 0)
        text (case kind :hit (if (pos? amount) (str "−" amount) (str "ESCUDO −" absorbed))
                   :heal (str "+" amount) :kill (when my-hit? "+1 BAJA")
                   :pickup (case (:item event) :rapid "RÁFAGA" :scatter "DISPERSOR" :heal "+35 VIDA" :shield "+60 ESCUDO" :energy "+35 NOVA" "MEJORA")
                   :burn (when mine? "FUEGO")
                   :dodge (when mine? "INMUNE") nil)]
    (when (or mine? my-hit? (= kind :ult) (= kind :ult-cast)) (sound! kind))
    (cond-> fx
      (and player (contains? #{:shot :hit} kind)) (assoc-in [:cues player kind] now)
      (and x y (pos? n)) (update :particles #(cap (into % (particle-burst x y n (if (= kind :ult) 340 210) col now (or angle 0) (if (= kind :shot) 0.3 e/pi))) 160))
      (and x y (pos? radius)) (update :rings #(cap (conj % {:x x :y y :color col :max radius :born now :until (+ now (if (= kind :ult) 900 360)) :kind kind}) 40))
      (and x y text) (update :texts #(cap (conj % {:x x :y (- y 36) :text text :color col :born now :until (+ now 850)}) 24))
      (and my-hit? (= kind :hit)) (assoc :hit-at now)
      (and my-hit? (= kind :kill)) (assoc :kill-at now :shake 6)
      (and mine? (= kind :hit) (pos? amount)) (assoc :shake 4)
      (= kind :ult) (assoc :shake 10))))

(defn consume! [world you now]
  (let [events (filter #(> (:id %) (:last-event @effects)) (:events world))]
    (when (seq events)
      (swap! effects (fn [fx] (assoc (reduce #(event-fx %1 %2 world you now) fx events)
                                    :last-event (:event-seq world)))))))
(defn advance-effects! [dt now world game-now]
  (swap! poses
         (fn [old]
           (into {} (for [[id p] (:players world) :when (:alive p)]
                      (let [last (get old id) same? (= (:spawn-until p) (:spawn last))
                            distance (if (and last same?) (min 20 (e/distance (:x p) (:y p) (:x last) (:y last))) 0)
                            dash? (< game-now (:dash-until p))]
                        [id {:x (:x p) :y (:y p) :spawn (:spawn-until p)
                             :appeared (if same? (:appeared last now) now)
                             :gait (+ (if same? (:gait last 0) 0) (if dash? 0 (/ distance 18)))
                             :walk (visual/damp (:walk last 0) (if (:moving p) 1 0) 18 dt)
                             :dash (visual/damp (:dash last 0) (if dash? 1 0) 28 dt)
                             :shield (visual/damp (:shield last 0) (if (pos? (:shield p)) 1 0) 22 dt)}])))))
  (swap! effects
         (fn [fx]
           (let [ghosts (for [[id p] (:players world) :when (and (:alive p) (< game-now (:dash-until p))
                                                 (> (- now (get-in fx [:ghost-times id] 0)) (if (reduced-motion?) 75 32)))]
                          {:x (:x p) :y (:y p) :skin (:skin p) :angle (:angle p) :born now :until (+ now 210) :id id})]
             (-> fx
                 (assoc :shake (visual/damp (:shake fx) 0 9 dt))
                 (update :particles #(mapv (fn [p] (visual/drift p dt)) (filter (fn [p] (> (:until p) now)) %)))
                 (update :rings #(filterv (fn [r] (> (:until r) now)) %))
                 (update :texts #(filterv (fn [r] (> (:until r) now)) %))
                 (update :ghosts #(cap (into (filterv (fn [g] (> (:until g) now)) %) ghosts) 28))
                 (update :ghost-times #(reduce (fn [m g] (assoc m (:id g) now)) % ghosts)))))))

(def floor-cache (atom nil))
(def elemental-art
  (let [im (js/Image.)]
    (set! (.-onload im) #(reset! floor-cache nil))
    (set! (.-src im) "art/elemental-tiles.png")
    im))
(defn paint-patio! []
  (rect -12 -12 984 624 "#0c171d")
  (polygon [[40 15] [920 15] [945 40] [945 560] [920 585] [40 585] [15 560] [15 40]] "#34484a")
  (polygon [[42 25] [918 25] [935 42] [935 558] [918 575] [42 575] [25 558] [25 42]] "#1c2d32")
  (rect 44 44 872 512 "#203137")
  (doseq [x (range 44 917 44)] (line x 44 x 556 "#abcfc909" 1))
  (doseq [y (range 44 557 44)] (line 44 y 916 y "#abcfc909" 1))
  (doseq [[x y] [[44 44] [696 44] [44 424] [696 424]]]
    (rect x y 220 132 "#9acbb203") (line x (+ y 132) (+ x 220) (+ y 132) "#8fc6b615" 1))
  ;; Paneles, drenajes y rutas pintadas: se rasterizan una sola vez.
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
  (let [agua? (= arena :agua)
        base (if agua? "#0e222a" "#211214")
        border (if agua? "#40a0c0" "#e05530")
        seam (if agua? "#2a586829" "#6a241629")
        im elemental-art
        image? (and im (.-complete im) (pos? (.-naturalWidth im)))
        [sx sy size] (if agua? [0 0 16] [48 0 16])]
    (rect 0 0 960 600 (if agua? "#081318" "#180a0a"))
    (rect 24 24 912 552 (if agua? "#183842" "#421612"))
    (rect 40 40 880 520 base)
    (when image?
      (doseq [x (range 40 920 32) y (range 40 560 32)]
        (.drawImage ctx im sx sy size size x y 32 32)))
    (rect 40 40 880 520 (if agua? "#0a202866" "#30100c50"))
    (doseq [x (range 40 921 32)] (line x 40 x 560 seam 1))
    (doseq [y (range 40 561 32)] (line 40 y 920 y seam 1))
    ;; Base estática de zonas de peligro
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
    (label (if agua? "CANALES / 02" "FRACTURA / 03") 480 300 (str border "44") 15)
    (doseq [{:keys [x y w h]} (get-in e/arenas [arena :obstacles])]
      (rect (+ x 6) (+ y 8) w h "#08131ba0")
      (rect x y w h (if agua? "#1b3c48" "#4d1d16"))
      (rect (+ x 4) (+ y 4) (- w 8) (- h 8) (if agua? "#2f6070" "#843022"))
      (when image?
        (let [[cx cy cs] (if agua? [16 16 16] [64 16 16])]
          (doseq [xx (range (+ x 6) (+ x w -16) 24)]
            (.drawImage ctx im cx cy cs cs xx (+ y 6) 24 (- h 14)))))
      (rect x y w 4 border)
      (rect x (+ y h -5) w 5 (if agua? "#10262e" "#30120d"))
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

(defn draw-hazards! [arena-key now]
  (let [hazards (get-in e/arenas [(e/arena-id arena-key) :hazards])
        animated? (and (not (reduced-motion?)) (= :full (:particles @settings)))]
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

(defn draw-ambient! [arena now]
  (let [animated? (and (not (reduced-motion?)) (= :full (:particles @settings)))
        pulse (if animated? (+ 0.45 (* 0.15 (e/sin (/ now 1500)))) 0.45)]
    (.save ctx) (set! (.-globalAlpha ctx) pulse)
    (doseq [a [0 e/pi] :when (= arena :patio)]
      (arc 480 300 68 a (+ a 1.15) "#7dbcb0" 2))
    (doseq [x [20 937] y [110 430] :when (= arena :patio)] (rect x y 3 60 "#8fcac8"))
    (.restore ctx)
    ;; Motas y chispas ambientales según el mapa
    (when animated?
      (doseq [i (range 12)]
        (let [x (+ 58 (mod (+ (* i 137) (* now 0.003)) 844))
              y (+ 56 (mod (- (* i 83) (* now 0.006)) 488))]
          (rect x y 1.5 1.5 (case arena :lava "#ff8a6533" :agua "#80deea33" "#b5d9c51c"))))
      (doseq [x [74 886] y [210 368]]
        (let [age (mod (+ now (* x 5)) 2800)]
          (.save ctx) (set! (.-globalAlpha ctx) (* 0.09 (e/sin (* e/pi (/ age 2800)))))
          (circle (+ x (* 5 (e/sin (/ age 800)))) (- y (/ age 80)) (+ 5 (/ age 260))
                  (case arena :lava "#ff7043" :agua "#80deea" "#b0d6d3"))
          (.restore ctx))))))

(defn draw-pickups! [world now]
  (doseq [{:keys [x y kind land-at until]} (:pickups world)]
    (let [fall (e/clamp (/ (- land-at now) 1000) 0 1)
          bob (if (reduced-motion?) 0 (* 3 (e/sin (/ now 220))))
          py (+ y (* bob (- 1 fall)) (* -100 fall fall))
          color (case kind :heal "#a2f078" :shield "#6acfff" :energy "#c7a0ff" "#ffbe67")
          name (case kind :rapid "RÁFAGA" :scatter "DISPERSOR" :heal "VIDA" :shield "ESCUDO" :energy "NOVA")]
      (circle x (+ y 13) 23 "#0b151c88")
      (when (and (<= land-at now) (< (- now land-at) 400))
        (let [p (/ (- now land-at) 400)]
          (.save ctx) (set! (.-globalAlpha ctx) (- 1 p))
          (ring x y (+ 20 (* p 22)) color 2) (.restore ctx)))
      (ring x y (+ 20 (* fall 22)) (str color "65") 1.5)
      (when (pos? fall)
        (line x (- y 110) x (- py 12) (str color "35") 2)
        (label "SUMINISTRO" x (+ y 40) color 8))
      (.save ctx) (.translate ctx x py)
      (polygon [[0 -17] [17 0] [0 17] [-17 0]] (str color "35"))
      (polygon [[0 -13] [13 0] [0 13] [-13 0]] "#122127")
      (case kind
        :heal (do (rect -2 -7 4 14 color) (rect -7 -2 14 4 color))
        :shield (polygon [[-6 -7] [6 -7] [6 3] [0 8] [-6 3]] color)
        :energy (polygon [[2 -9] [-6 1] [0 1] [-2 9] [7 -3] [2 -3]] color)
        :rapid (doseq [yy [-5 0 5]] (line -5 yy 5 yy color 2))
        :scatter (doseq [yy [-5 0 5]] (line -5 0 6 yy color 2)))
      (.restore ctx)
      (when (<= fall 0) (label name x (+ y 32) color 8)))))

(defn draw-ultimates! [world you now]
  (doseq [[id p] (:players world) :when (and (:alive p) (> (:ult-at p) now))]
    (let [{:keys [x y]} (:ult-target p)
          progress (e/clamp (- 1 (/ (- (:ult-at p) now) 650)) 0 1)
          color (if (= id you) "#c7a0ff" "#ff817c")
          spin (if (reduced-motion?) 0 (* progress 1.8))
          inward (- 125 (* 98 progress))]
      ;; El borde fijo muestra el área real; las chispas convergen antes del impacto.
      (circle x y 125 (str color "12"))
      (ring x y 125 (str color "85") 2)
      (ring x y 117 (str color "2d") 9)
      (arc x y 128 (- (/ e/pi 2)) (+ (- (/ e/pi 2)) (* 2 e/pi progress)) color 3)
      (ring x y inward (str color "70") 1.5)
      (doseq [i (range 12)]
        (let [a (+ spin (* i (/ e/pi 6)))
              xx (+ x (* inward (e/cos a))) yy (+ y (* inward (e/sin a)))]
          (line (+ x (* 122 (e/cos a))) (+ y (* 122 (e/sin a))) xx yy (str color "30") 1)
          (circle xx yy (+ 2 (* progress 2)) color)
          (line (+ x (* 123 (e/cos a))) (+ y (* 123 (e/sin a)))
                (+ x (* 137 (e/cos a))) (+ y (* 137 (e/sin a))) color 2)))
      (doseq [i (range 4)]
        (let [a (+ spin (* i (/ e/pi 2)))]
          (polygon [[(+ x (* 95 (e/cos a))) (+ y (* 95 (e/sin a)))]
                    [(+ x (* 113 (e/cos (+ a 0.16)))) (+ y (* 113 (e/sin (+ a 0.16))))]
                    [(+ x (* 113 (e/cos (- a 0.16)))) (+ y (* 113 (e/sin (- a 0.16))))]]
                   (str color "aa"))))
      (.save ctx) (.translate ctx x y) (.rotate ctx spin)
      (let [r (+ 7 (* progress 12))]
        (polygon [[0 (- r)] [r 0] [0 r] [(- r) 0]] color)
        (polygon [[0 -6] [6 0] [0 6] [-6 0]] "#fbf3ff"))
      (.restore ctx)
      (label (str (if (= id you) "TU NOVA " "PELIGRO ") (.toFixed (/ (- (:ult-at p) now) 1000) 1))
             x (e/clamp (- y 138) 22 574) color 11))))

(defn nova-burst! [r progress]
  (let [{:keys [x y color]} r
        ease (- 1 (js/Math.pow (- 1 progress) 4))
        radius (* 125 ease)
        flash (max 0 (- 1 (* progress 5)))
        spin (if (reduced-motion?) 0 (* progress 0.4))]
    ;; Destello localizado, frente expansivo y residuos. Nunca flash de pantalla.
    (circle x y 125 (str color "0c"))
    (circle x y radius (str color "28"))
    (ring x y radius color (max 1 (* 9 (- 1 progress))))
    (ring x y (* radius 0.79) "#f5eaff" (max 1 (* 4 (- 1 progress))))
    (ring x y 125 (str color "66") 2)
    (doseq [i (range 12)]
      (let [a (+ spin (* i (/ e/pi 6)))
            inner (* radius (+ 0.58 (* 0.28 progress)))]
        (line (+ x (* inner (e/cos a))) (+ y (* inner (e/sin a)))
              (+ x (* radius (e/cos a))) (+ y (* radius (e/sin a))) color 3)
        (circle (+ x (* radius (e/cos a))) (+ y (* radius (e/sin a)))
                (* 3 (- 1 progress)) "#fff5ff")))
    (when (pos? flash)
      (.save ctx) (set! (.-globalAlpha ctx) (* flash (if (reduced-motion?) 0.3 0.85)))
      (circle x y (+ 10 (* 28 flash)) "#fff5ff")
      (polygon [[(- x 9) y] [(- x 3) (- y 155)] [(+ x 3) (- y 155)] [(+ x 9) y]] color)
      (.restore ctx))))

(defn draw-shots! [world you]
  (doseq [s (:shots world)]
    (let [mine? (= you (:owner s)) c (if mine? (if (:boosted s) "#ffbd68" "#daef9a") "#ff827b")
          x (:x s) y (:y s) a (:angle s)
          len (min (if (:boosted s) 29 20)
                   (e/distance x y (:origin-x s x) (:origin-y s y)))]
      (line (- x (* len (e/cos a))) (- y (* len (e/sin a))) x y (str c "25") 9)
      (line (- x (* len (e/cos a))) (- y (* len (e/sin a))) x y c 3)
      (circle x y 3.5 "#fff8df"))))

(defn draw-ghosts! [now]
  (doseq [g (:ghosts @effects)]
    (let [im (get sprites (:skin g))]
      (when (and im (.-complete im) (pos? (.-naturalWidth im)))
        (.save ctx) (set! (.-globalAlpha ctx) (* 0.38 (e/clamp (/ (- (:until g) now) 210) 0 1)))
        (.drawImage ctx im 160 0 32 32 (- (:x g) 38) (- (:y g) 45) 76 76) (.restore ctx)))))

(defn draw-player! [p you now fx-now]
  (when (:alive p)
    (let [{:keys [x y angle skin]} p im (get sprites skin) color (get palette skin)
          mine? (= (:id p) you) dash? (< now (:dash-until p)) shield? (pos? (:shield p))
          boost? (< now (:boost-until p)) heal? (< now (:heal-until p))
          hurt? (< (- fx-now (get-in @effects [:cues (:id p) :hit] -1000)) 140)
          spawning? (< now (:spawn-until p))
          ready-ult? (and (>= (:ulti p) 100) (not (pos? (:ult-at p))))
          pose (get @poses (:id p)) gait (:gait pose 0) walk (:walk pose 0)
          moving? (> walk 0.05)
          stride (if (reduced-motion?) 0 (* walk (e/sin gait)))
          bob (if (reduced-motion?) 0 (+ (* -2.4 (js/Math.abs stride)) (* (- 1 walk) 0.7 (e/sin (/ fx-now 350)))))
          shot-age (- fx-now (get-in @effects [:cues (:id p) :shot] -1000))
          muzzle? (< shot-age 90)
          frame (cond dash? 5 hurt? 6 muzzle? 4 moving? (mod (js/Math.floor (/ gait 1.57)) 4) boost? 7 :else 0)
          recoil (if (< shot-age 180) (* 4 (visual/decay 22 (/ shot-age 1000))) 0)]
      (.save ctx) (.translate ctx x (+ y 20)) (.scale ctx 1 0.35) (circle 0 0 27 "#08151db0") (.restore ctx)
      (ring x (+ y 7) 24 (if mine? "#d9ed91b0" (str color "70")) (if mine? 2 1))
      (when ready-ult?
        (let [pulse (if (reduced-motion?) 0.5 (+ 0.5 (* 0.5 (e/sin (/ fx-now 230)))))
              radius (+ 38 (* 4 pulse))]
          (circle x y (+ radius 7) "#bd89ff12")
          (ring x y radius (if mine? "#ead5ff" "#b89aff99") (+ 1.5 pulse))
          (doseq [i (range 4)]
            (let [a (+ (* i (/ e/pi 2)) (if (reduced-motion?) 0 (/ fx-now 900)))
                  xx (+ x (* radius (e/cos a))) yy (+ y (* radius (e/sin a)))]
              (polygon [[xx (- yy 5)] [(+ xx 5) yy] [xx (+ yy 5)] [(- xx 5) yy]] "#e5cbff")))
          (when mine? (label "F · NOVA" x (- y 64) "#ead5ff" 9))))
      (when boost?
        (circle x y 31 "#ffbe6712")
        (doseq [i (range 4)]
          (let [a (+ (* i (/ e/pi 2)) (if (reduced-motion?) 0 (/ fx-now 350)))]
            (polygon [[(+ x (* 35 (e/cos a))) (+ y (* 35 (e/sin a)))]
                      [(+ x (* 28 (e/cos (+ a 0.12)))) (+ y (* 28 (e/sin (+ a 0.12))))]
                      [(+ x (* 28 (e/cos (- a 0.12)))) (+ y (* 28 (e/sin (- a 0.12))))]] "#ffbe67"))))
      (when heal?
        (let [progress (e/clamp (- 1 (/ (- (:heal-until p) now) 750)) 0 1)]
          (.save ctx) (set! (.-globalAlpha ctx) (- 1 progress))
          (ring x y (+ 30 (* 15 progress)) "#a2f078" 2)
          (doseq [offset (if (reduced-motion?) [0] [-22 0 22])]
            (let [py (- y 12 (* 28 progress) (if (zero? offset) 12 0)) px (+ x offset)]
              (line (- px 3) py (+ px 3) py "#c6ffa5" 2)
              (line px (- py 3) px (+ py 3) "#c6ffa5" 2)))
          (.restore ctx)))
      (when (or dash? spawning?)
        (ring x y 34 (if dash? "#b9ffe7" "#badca15f") 2)
        (when dash? (line (- x (* 38 (get-in p [:dash-dir :dx]))) (- y (* 38 (get-in p [:dash-dir :dy]))) x y "#76f4ce55" 9)))
      ;; Cuerpo y piernas animados; el arma apunta de forma independiente.
      (when (and im (.-complete im) (pos? (.-naturalWidth im)))
        (.save ctx) (.translate ctx (- x (* recoil (e/cos angle))) (- (+ y bob) (* recoil (e/sin angle))))
        (.rotate ctx (* 0.025 stride))
        (let [entrance (if (reduced-motion?) 1 (e/clamp (/ (- fx-now (:appeared pose fx-now)) 240) 0 1))
              size (+ 0.82 (* 0.18 (- 1 (js/Math.pow (- 1 entrance) 3))))
              stretch (if (reduced-motion?) 0 (:dash pose 0))
              sx (+ 1 (* stretch (- (* 0.24 (js/Math.abs (get-in p [:dash-dir :dx] 1))) 0.08)))
              sy (+ 1 (* stretch (- (* 0.24 (js/Math.abs (get-in p [:dash-dir :dy] 0))) 0.08)))]
          (.scale ctx (* size sx) (* size sy)))
        (.drawImage ctx im (* frame 32) 0 32 32 -38 -45 76 76) (.restore ctx))
      (let [tipx (+ x (* (- 34 recoil) (e/cos angle))) tipy (+ y (* (- 34 recoil) (e/sin angle)))]
        (line (+ x (* (- 8 recoil) (e/cos angle))) (+ y (* (- 8 recoil) (e/sin angle))) tipx tipy "#101c24" 9)
        (line (+ x (* (- 13 recoil) (e/cos angle))) (+ y (* (- 13 recoil) (e/sin angle))) tipx tipy (if boost? "#ffc884" "#b4c7c0") 4)
        (when mine? (line tipx tipy (+ x (* 80 (e/cos angle))) (+ y (* 80 (e/sin angle))) "#dde9c020" 1))
        (when muzzle?
          (circle tipx tipy 13 (if boost? "#ffb86428" "#e4f3a628"))
          (polygon [[(+ tipx (* 15 (e/cos angle))) (+ tipy (* 15 (e/sin angle)))]
                    [(+ tipx (* 6 (e/cos (+ angle 1.7)))) (+ tipy (* 6 (e/sin (+ angle 1.7))))]
                    [(+ tipx (* 6 (e/cos (- angle 1.7)))) (+ tipy (* 6 (e/sin (- angle 1.7))))]] "#fff5c6")))
      (when (> (:shield pose 0) 0.015)
        (.save ctx) (.translate ctx x y)
        (let [scale (+ 0.72 (* 0.28 (:shield pose 0)))] (.scale ctx scale scale))
        (.translate ctx (- x) (- y)) (set! (.-globalAlpha ctx) (:shield pose 0))
        (let [hit? (and shield? hurt?) rot (if (reduced-motion?) 0 (/ fx-now 550))]
          (circle x y 38 (if hit? "#b5eaff33" "#7bd1ff0c"))
          (ring x y 38 (if hit? "#effcff" "#6acfff88") (if hit? 4 1.5))
          (doseq [i (range 3)] (let [a (+ rot (* i (/ (* 2 e/pi) 3)))] (arc x y 40 a (+ a 1.1) "#9bdeff" 2.5)))
          (arc x y 43 (- (/ e/pi 2)) (+ (- (/ e/pi 2)) (* (/ (:shield p) 60) 2 e/pi)) "#6acfff" 2))
        (.restore ctx))
      (when hurt? (arc x y 35 (+ angle 2.2) (+ angle 4.1) "#ff817c" 3))
      (when (< now (:burn-until p 0))
        (let [flame-flicker (* 3 (e/sin (+ (* fx-now 0.02) (* x 0.1))))]
          (.save ctx) (set! (.-globalAlpha ctx) 0.85)
          (circle (+ x flame-flicker) (- y 10) 8 "#ff5722cc")
          (circle (- x flame-flicker) (- y 15) 5 "#ffeb3baa")
          (.restore ctx)))
      (when (= :water (e/terrain-at (:arena p) (:x p) (:y p)))
        (ring x (+ y 20) (+ 8 (* 4 (e/sin (* fx-now 0.01)))) "#6ad4ff55" 1.5))
      (let [name (if mine? "TÚ" (:name p))]
        (label name x (- y 46) (if mine? "#e8f7b9" color) 9))
      (rect (- x 22) (+ y 33) 44 4 "#0a161d")
      (rect (- x 21) (+ y 34) (* 42 (/ (:hp p) 100)) 2 (if mine? "#c5e79b" "#f29885"))
      (when (and mine? (> (get-in p [:cooldowns :dash]) now))
        (rect (- x 21) (+ y 39) (* 42 (- 1 (/ (- (get-in p [:cooldowns :dash]) now) 2500))) 2 "#76f4ce")))))

(defn draw-effects! [now]
  (doseq [r (:rings @effects)]
    (let [progress (e/clamp (/ (- now (:born r)) (- (:until r) (:born r))) 0 1)
          ease (- 1 (js/Math.pow (- 1 progress) 3)) radius (* (:max r) ease)]
      (.save ctx) (set! (.-globalAlpha ctx) (- 1 progress))
      (if (= :ult (:kind r))
        (nova-burst! r progress)
        (ring (:x r) (:y r) radius (:color r) 2))
      (.restore ctx)))
  (doseq [p (:particles @effects)]
    (.save ctx) (set! (.-globalAlpha ctx) (e/clamp (/ (- (:until p) now) 260) 0 1))
    (line (:x p) (:y p) (- (:x p) (* (:vx p) 0.035)) (- (:y p) (* (:vy p) 0.035)) (:color p) (:size p))
    (.restore ctx))
  (doseq [t (:texts @effects)]
    (let [age (- now (:born t)) y (- (:y t) (* 32 (- 1 (js/Math.pow (- 1 (e/clamp (/ age 850) 0 1)) 3))))]
      (.save ctx) (set! (.-globalAlpha ctx) (min 1 (/ (- (:until t) now) 200)))
      (label (:text t) (+ (:x t) 1) (+ y 1) "#09161f" 12)
      (label (:text t) (:x t) y (:color t) 12) (.restore ctx))))

(defn crosshair! [world you mouse now fx-now]
  (let [{:keys [x y]} mouse p (get-in world [:players you])
        recent-hit? (< (- fx-now (:hit-at @effects)) 140)
        recent-kill? (< (- fx-now (:kill-at @effects)) 260)
        nova? (>= (:ulti p 0) 100) color (cond recent-kill? "#ffe296" nova? "#d2b3ff" :else "#e1edcb")
        gap (if (< now (:muzzle-until p 0)) 6 4)]
    (doseq [[dx dy] [[-1 0] [1 0] [0 -1] [0 1]]]
      (line (+ x (* gap dx)) (+ y (* gap dy)) (+ x (* 11 dx)) (+ y (* 11 dy)) "#0b1821" 4)
      (line (+ x (* gap dx)) (+ y (* gap dy)) (+ x (* 11 dx)) (+ y (* 11 dy)) color 1.5))
    (when recent-hit?
      (doseq [[dx dy] [[-1 -1] [1 1] [-1 1] [1 -1]]]
        (line (+ x (* 13 dx)) (+ y (* 13 dy)) (+ x (* 18 dx)) (+ y (* 18 dy)) (if recent-kill? "#ffdc83" "#fff") 2)))
    (when nova? (label "F" (+ x 19) (+ y 22) "#d2b3ff" 9))))

(defn draw! [world you mouse now dt fx-now]
  (advance-effects! dt fx-now world now)
  (set! (.-imageSmoothingEnabled ctx) false)
  ;; Crear fuera de la cámara; después solo un blit por fotograma.
  (cache-floor! (e/arena-id (:arena world)))
  (rect 0 0 960 600 "#102027")
  (.save ctx)
  (let [mag (if (reduced-motion?) 0 (* (:shake @effects) (:shake @settings)))]
    (.translate ctx (* mag 0.5 (+ (e/sin (* fx-now 0.045)) (e/sin (* fx-now 0.071))))
                    (* mag 0.5 (+ (e/cos (* fx-now 0.052)) (e/cos (* fx-now 0.083))))))
  (.drawImage ctx (:canvas @floor-cache) 0 0)
  (draw-ambient! (:arena world) fx-now)
  (draw-hazards! (:arena world) fx-now)
  (draw-pickups! world now) (draw-ultimates! world you now) (draw-ghosts! fx-now)
  (doseq [p (sort-by :y (vals (:players world)))] (draw-player! p you now fx-now))
  (draw-shots! world you) (draw-effects! fx-now)
  (.restore ctx)
  ;; Retícula y HUD no tiemblan; el apuntado conserva coordenadas del mundo.
  (when (get-in world [:players you :alive]) (crosshair! world you mouse now fx-now))
  (let [hp (get-in world [:players you :hp] 100)]
    (when (and (< hp 30) (pos? hp))
      (set! (.-strokeStyle ctx) "#ee746660") (set! (.-lineWidth ctx) 5) (.strokeRect ctx 2.5 2.5 955 595))))
