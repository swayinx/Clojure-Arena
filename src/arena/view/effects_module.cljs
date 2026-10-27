(ns arena.view.effects-module
  "Visual effect state, event processing, and rendering."
  (:require [arena.audio :as audio]
            [arena.engine :as e]
            [arena.presentation :as visual]
            [arena.view.canvas-module :as cv]))

(defonce settings (atom {:shake 0.45 :particles :full :sound true :volume 0.65}))
(defonce effects (atom {:particles [] :rings [] :texts [] :ghosts [] :last-event 0 :shake 0 :hit-at -1000 :kill-at -1000}))
(defonce poses (atom {}))
(defn resume-audio! []
  (audio/resume! (:sound @settings) (:volume @settings)))
(defn sound! [kind] (audio/play! kind (:sound @settings)))
(defn ultimate-ready! [] (sound! :ult-ready))
(defn reset!fx [] (reset! poses {}) (reset! effects {:particles [] :rings [] :texts [] :ghosts [] :last-event 0 :shake 0 :hit-at -1000 :kill-at -1000}))
(defn particle-burst [x y n speed color now angle spread]
  (let [count (if (or (cv/reduced-motion?) (= :low (:particles @settings))) (max 2 (quot n 3)) n)]
    (mapv (fn [i]
            (let [a (+ angle (* spread (- (* 2 (js/Math.random)) 1))) v (* speed (+ 0.35 (* 0.65 (js/Math.random))))]
              {:x x :y y :vx (* (e/cos a) v) :vy (* (e/sin a) v) :color color
               :size (+ 1.2 (* 2 (js/Math.random))) :born now :until (+ now 240 (* i 9))})) (range count))))
(defn event-fx [fx event world you now]
  (let [{:keys [kind x y player attacker amount absorbed angle]} event
        color (get cv/palette (get-in world [:players player :skin]) "#d9ed91")
        mine? (= you player) my-hit? (= you attacker)
        col (case kind :dash "#76f4ce" :shield "#6acfff" :heal "#a2f078" :boost "#ffbe67"
                  :ult (if mine? "#c7a0ff" "#ff817c") :ult-cast (if mine? "#c7a0ff" "#ff817c") :hit (if (pos? absorbed) "#6acfff" "#ffe3a5")
                  :kill color :pickup "#d9ed91" :wall "#a8c5b9" color)
        n (case kind :shot 3 :hit 7 :kill 26 :dash 13 :ult 46 :heal 10 :shield 12 :boost 16 :wall 3 :pickup 18 0)
        radius (case kind :ult 125 :kill 75 :dash 44 :shield 57 :heal 52 :boost 55 :hit 25 :pickup 50 0)
        text (case kind :hit (if (pos? amount) (str "−" amount) (str "ESCUDO −" absorbed))
                   :heal (str "+" amount) :kill (when my-hit? "+1 BAJA")
                   :pickup (case (:item event) :rapid "RÁFAGA" :scatter "DISPERSOR" :heal "+35 VIDA" :shield "+60 ESCUDO" :energy "+35 NOVA" "MEJORA")
                   :dodge (when mine? "INMUNE") nil)]
    (when (or mine? my-hit? (= kind :ult) (= kind :ult-cast)) (sound! kind))
    (cond-> fx
      (and player (contains? #{:shot :hit} kind)) (assoc-in [:cues player kind] now)
      (and x y (pos? n)) (update :particles #(cv/cap (into % (particle-burst x y n (if (= kind :ult) 340 210) col now (or angle 0) (if (= kind :shot) 0.3 e/pi))) 160))
      (and x y (pos? radius)) (update :rings #(cv/cap (conj % {:x x :y y :color col :max radius :born now :until (+ now (if (= kind :ult) 900 360)) :kind kind}) 40))
      (and x y text) (update :texts #(cv/cap (conj % {:x x :y (- y 36) :text text :color col :born now :until (+ now 850)}) 24))
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
                                                 (> (- now (get-in fx [:ghost-times id] 0)) (if (cv/reduced-motion?) 75 32)))]
                          {:x (:x p) :y (:y p) :skin (:skin p) :angle (:angle p) :born now :until (+ now 210) :id id})]
             (-> fx
                 (assoc :shake (visual/damp (:shake fx) 0 9 dt))
                 (update :particles #(mapv (fn [p] (visual/drift p dt)) (filter (fn [p] (> (:until p) now)) %)))
                 (update :rings #(filterv (fn [r] (> (:until r) now)) %))
                 (update :texts #(filterv (fn [r] (> (:until r) now)) %))
                 (update :ghosts #(cv/cap (into (filterv (fn [g] (> (:until g) now)) %) ghosts) 28))
                 (update :ghost-times #(reduce (fn [m g] (assoc m (:id g) now)) % ghosts)))))))
(defn draw-ambient! [arena now]
  (let [animated? (and (not (cv/reduced-motion?)) (= :full (:particles @settings)))
        pulse (if animated? (+ 0.45 (* 0.15 (e/sin (/ now 1500)))) 0.45)]
    (.save cv/ctx) (set! (.-globalAlpha cv/ctx) pulse)
    (doseq [a [0 e/pi] :when (= arena :patio)]
      (cv/arc 480 300 68 a (+ a 1.15) "#7dbcb0" 2))
    (doseq [x [20 937] y [110 430] :when (= arena :patio)] (cv/rect x y 3 60 "#8fcac8"))
    (.restore cv/ctx)
    ;; Twelve deterministic motes; no entities are added to the server.
    (when animated?
      (doseq [i (range 12)]
        (let [x (+ 58 (mod (+ (* i 137) (* now 0.003)) 844))
              y (+ 56 (mod (- (* i 83) (* now 0.006)) 488))]
          (cv/rect x y 1.5 1.5 (case arena :desierto "#ffd8a71e" :lab "#a8ddf522" "#b5d9c51c"))))
      (doseq [x [74 886] y [210 368]]
        (let [age (mod (+ now (* x 5)) 2800)]
          (.save cv/ctx) (set! (.-globalAlpha cv/ctx) (* 0.09 (e/sin (* e/pi (/ age 2800)))))
          (cv/circle (+ x (* 5 (e/sin (/ age 800)))) (- y (/ age 80)) (+ 5 (/ age 260)) "#b0d6d3")
          (.restore cv/ctx))))))
(defn nova-burst! [r progress]
  (let [{:keys [x y color]} r
        ease (- 1 (js/Math.pow (- 1 progress) 4))
        radius (* 125 ease)
        flash (max 0 (- 1 (* progress 5)))
        spin (if (cv/reduced-motion?) 0 (* progress 0.4))]
    ;; Localized flash, expanding front, and residue. Never a screen-wide flash.
    (cv/circle x y 125 (str color "0c"))
    (cv/circle x y radius (str color "28"))
    (cv/ring x y radius color (max 1 (* 9 (- 1 progress))))
    (cv/ring x y (* radius 0.79) "#f5eaff" (max 1 (* 4 (- 1 progress))))
    (cv/ring x y 125 (str color "66") 2)
    (doseq [i (range 12)]
      (let [a (+ spin (* i (/ e/pi 6)))
            inner (* radius (+ 0.58 (* 0.28 progress)))]
        (cv/line (+ x (* inner (e/cos a))) (+ y (* inner (e/sin a)))
              (+ x (* radius (e/cos a))) (+ y (* radius (e/sin a))) color 3)
        (cv/circle (+ x (* radius (e/cos a))) (+ y (* radius (e/sin a)))
                (* 3 (- 1 progress)) "#fff5ff")))
    (when (pos? flash)
      (.save cv/ctx) (set! (.-globalAlpha cv/ctx) (* flash (if (cv/reduced-motion?) 0.3 0.85)))
      (cv/circle x y (+ 10 (* 28 flash)) "#fff5ff")
      (cv/polygon [[(- x 9) y] [(- x 3) (- y 155)] [(+ x 3) (- y 155)] [(+ x 9) y]] color)
      (.restore cv/ctx))))
(defn draw-ghosts! [now]
  (doseq [g (:ghosts @effects)]
    (let [im (get (cv/sprite-map) (:skin g))]
      (when (and im (.-complete im) (pos? (.-naturalWidth im)))
        (.save cv/ctx) (set! (.-globalAlpha cv/ctx) (* 0.38 (e/clamp (/ (- (:until g) now) 210) 0 1)))
        (.drawImage cv/ctx im 160 0 32 32 (- (:x g) 38) (- (:y g) 45) 76 76) (.restore cv/ctx)))))
(defn draw-effects! [now]
  (doseq [r (:rings @effects)]
    (let [progress (e/clamp (/ (- now (:born r)) (- (:until r) (:born r))) 0 1)
          ease (- 1 (js/Math.pow (- 1 progress) 3)) radius (* (:max r) ease)]
      (.save cv/ctx) (set! (.-globalAlpha cv/ctx) (- 1 progress))
      (if (= :ult (:kind r))
        (nova-burst! r progress)
        (cv/ring (:x r) (:y r) radius (:color r) 2))
      (.restore cv/ctx)))
  (doseq [p (:particles @effects)]
    (.save cv/ctx) (set! (.-globalAlpha cv/ctx) (e/clamp (/ (- (:until p) now) 260) 0 1))
    (cv/line (:x p) (:y p) (- (:x p) (* (:vx p) 0.035)) (- (:y p) (* (:vy p) 0.035)) (:color p) (:size p))
    (.restore cv/ctx))
  (doseq [t (:texts @effects)]
    (let [age (- now (:born t)) y (- (:y t) (* 32 (- 1 (js/Math.pow (- 1 (e/clamp (/ age 850) 0 1)) 3))))]
      (.save cv/ctx) (set! (.-globalAlpha cv/ctx) (min 1 (/ (- (:until t) now) 200)))
      (cv/label (:text t) (+ (:x t) 1) (+ y 1) "#09161f" 12)
      (cv/label (:text t) (:x t) y (:color t) 12) (.restore cv/ctx))))
