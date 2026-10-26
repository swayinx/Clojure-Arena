(ns arena.presentation
  "Presentación pura: interpola copias; jamás decide colisiones o daño."
  (:require [arena.engine :as e]))

(def buffer-ms 100)
(defn lerp [a b t] (+ a (* (e/clamp t 0 1) (- b a))))
(defn angle-between [a b t]
  (+ a (* (e/clamp t 0 1) (e/atan2 (e/sin (- b a)) (e/cos (- b a))))))
(defn decay [rate dt] (#?(:clj Math/exp :cljs js/Math.exp) (* (- rate) dt)))
(defn damp [a b rate dt] (+ b (* (- a b) (decay rate dt))))
(defn drift
  "Integra velocidad con fricción exponencial, independiente de los FPS."
  [p dt]
  (let [d (decay 2.8 dt) travel (/ (- 1 d) 2.8)]
    (-> p (update :x + (* (:vx p) travel)) (update :y + (* (:vy p) travel))
        (update :vx * d) (update :vy * d))))
(defn same-life? [a b]
  (and a b (= (select-keys a [:alive :spawn-until :respawn-at])
              (select-keys b [:alive :spawn-until :respawn-at]))))
(defn shot-key [s] [(:id s) (:owner s) (:born s)])
(defn position [old p alpha]
  (assoc p :x (lerp (:x old) (:x p) alpha) :y (lerp (:y old) (:y p) alpha)))
(defn interpolate [previous current alpha]
  (let [t (lerp (:time previous) (:time current) alpha)
        old-shots (into {} (map (juxt shot-key identity) (:shots previous)))]
    (-> current
        (assoc :shots
               (into [] (keep (fn [s]
                                (if-let [old (get old-shots (shot-key s))]
                                  (position old s alpha)
                                  (when (>= t (:born s))
                                    (let [span (- (:time current) (:born s))]
                                      (if (and (pos? span) (:origin-x s))
                                        (position {:x (:origin-x s) :y (:origin-y s)} s (/ (- t (:born s)) span)) s))))))
                     (:shots current)))
        (update :players
                (fn [players]
                  (reduce-kv
                    (fn [ps id p]
                      (let [old (get-in previous [:players id])]
                        (if (and (same-life? old p) (< (e/distance (:x old) (:y old) (:x p) (:y p)) 110))
                          (assoc ps id (assoc (position old p alpha) :angle (angle-between (:angle old 0) (:angle p 0) alpha))) ps)))
                    players players))))))

(defn remember [snapshots world]
  (vec (take-last 8 (conj (filterv #(< (:time %) (:time world)) snapshots) world))))
(defn advance-clock
  "Cursor monótono. Absorbe jitter con ±10% de velocidad; nunca extrapola el mundo."
  [cursor latest received-age dt-ms]
  (let [target (- (+ latest (min received-age 180)) buffer-ms)
        cursor (or cursor (- latest buffer-ms))
        speed (e/clamp (+ 1 (/ (- target cursor) 500)) 0.9 1.1)]
    (if (> (- target cursor) 250) (min target latest)
        (min latest (+ cursor (* dt-ms speed))))))
(defn sample
  "Muestrea posiciones retrasadas, conservando bajas, recursos y roster actuales."
  [snapshots cursor latest]
  (let [pairs (partition 2 1 snapshots)
        [a b] (or (first (filter #(>= (:time (second %)) cursor) pairs))
                  [(last snapshots) (last snapshots)])
        a (or a latest) b (or b latest)
        span (- (:time b) (:time a))
        visual (interpolate a b (if (pos? span) (/ (- cursor (:time a)) span) 1))
        live-shots (set (map shot-key (:shots latest)))]
    (-> latest
        (assoc :shots (filterv #(contains? live-shots (shot-key %)) (:shots visual)))
        (update :players
                (fn [ps]
                  (reduce-kv
                    (fn [result id p]
                      (let [v (get-in visual [:players id])]
                        (if (same-life? v p)
                          (assoc result id (merge p (select-keys v [:x :y :angle :moving]))) result))) ps ps))))))

(defn follow-time
  "Reloj cosmético estable ante pequeñas correcciones de ping. No afecta reglas."
  [cursor target dt-ms]
  (if (or (nil? cursor) (> (abs (- target cursor)) 250)) target
      (max cursor (min (+ target 30)
                       (+ cursor (* dt-ms (e/clamp (+ 1 (/ (- target cursor dt-ms) 500)) 0.9 1.1)))))))
