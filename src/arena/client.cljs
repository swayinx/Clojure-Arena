(ns arena.client)

;; ============================================================================
;; 1. CONSTANTES, PALETAS Y REFERENCIAS DOM
;; ============================================================================

(def canvas (.getElementById js/document "arena"))
(def ctx (.getContext canvas "2d"))
(set! (.-imageSmoothingEnabled ctx) false)
(def motion-query (.matchMedia js/window "(prefers-reduced-motion: reduce)"))

(def fighter-names ["brasa" "chispa" "mora" "bastion"])

(def fighter-palettes
  {"brasa"   {:primary "#f4774d" :accent "#ffd166" :trail "#ff8a5c"}
   "chispa"  {:primary "#72e1d2" :accent "#e9f19d" :trail "#72e1d2"}
   "mora"    {:primary "#ba93ed" :accent "#f1b9ed" :trail "#c8a8f4"}
   "bastion" {:primary "#f3ce78" :accent "#f6e5ae" :trail "#f3ce78"}})

(def power-colors
  {:dash   {:main "#a8ffdb" :glow "#72e1d2" :ring "#e0fff4"}
   :shield {:main "#8edff8" :glow "#4eb9d9" :ring "#c8f4ff"}
   :heal   {:main "#84ef88" :glow "#4cd137" :ring "#d2ffce"}
   :boost  {:main "#ff9f43" :glow "#ff6b35" :ring "#fff2a6"}
   :hurt   {:main "#ff6b6b" :glow "#ff3838" :ring "#ffd6d6"}})

(def sprites
  (into {}
        (map (fn [name]
               (let [image (js/Image.)]
                 (set! (.-src image) (str "art/" name ".svg"))
                 [name image]))
             fighter-names)))

(def arena-bounds {:min-x 45 :max-x 915 :min-y 45 :max-y 555})
(def walk-speed 280)
(def dash-speed 900)
(def dash-duration-ms 180)
(def dash-cooldown-ms 2500)
(def shield-max 60)
(def shield-duration-ms 5000)
(def shield-cooldown-ms 10000)
(def heal-amount 35)
(def heal-duration-ms 750)
(def heal-cooldown-ms 12000)
(def boost-duration-ms 6000)
(def boost-cooldown-ms 13000)

(def max-particles 110)
(def max-effects 36)
(def max-texts 20)
(def max-afterimages 5)

(def keys-down (atom #{}))
(def mouse (atom {:x 650 :y 300 :down false}))
(def selected (atom "brasa"))
(def last-hud (atom 0))

;; ============================================================================
;; 2. UTILIDADES MATEMÁTICAS Y CONSTRUCTORES DE EFECTOS
;; ============================================================================

(defn clamp [value lower upper]
  (max lower (min upper value)))

(defn distance [ax ay bx by]
  (js/Math.hypot (- ax bx) (- ay by)))

(defn reduced-motion? []
  (.-matches motion-query))

(defn cap-vec [coll limit]
  (let [v (vec coll)
        n (count v)]
    (if (> n limit)
      (vec (take-last limit v))
      v)))

(defn rand-range [low high]
  (+ low (* (js/Math.random) (- high low))))

(defn ring-effect [x y color max-r duration now kind]
  {:x x :y y :color color :max-r max-r
   :born now :until (+ now duration) :duration duration :kind kind})

(defn floating-text [x y text color now]
  {:x x :y y :vy -44 :text text :color color
   :born now :until (+ now 750) :duration 750})

(defn make-particle [x y vx vy size color duration now kind]
  {:x x :y y :vx vx :vy vy :size size :color color
   :born now :until (+ now duration) :duration duration :kind kind})

(defn spawn-sparks [x y base-angle spread count-num speed color duration now]
  (let [actual-count (if (reduced-motion?) (max 2 (js/Math.floor (/ count-num 3))) count-num)]
    (mapv (fn [_]
            (let [ang (+ base-angle (rand-range (- spread) spread))
                  spd (* speed (rand-range 0.45 1.15))]
              (make-particle x y
                             (* (js/Math.cos ang) spd)
                             (* (js/Math.sin ang) spd)
                             (rand-range 2.2 4.5)
                             color
                             (* duration (rand-range 0.7 1.15))
                             now
                             :spark)))
          (range actual-count))))

(defn spawn-heal-particles [x y now]
  (let [cnt (if (reduced-motion?) 3 9)]
    (mapv (fn [idx]
            (let [ang (* idx (/ (* 2 js/Math.PI) cnt))
                  rx (+ x (* (js/Math.cos ang) (rand-range 12 34)))
                  ry (+ y (* (js/Math.sin ang) (rand-range 8 24)))]
              (make-particle rx ry
                             (rand-range -12 12)
                             (rand-range -75 -38)
                             (rand-range 3.5 5.5)
                             (if (even? idx) "#84ef88" "#d2ffce")
                             680
                             now
                             :plus)))
          (range cnt))))

(defn trigger-shake [state intensity duration now]
  (if (reduced-motion?)
    state
    (let [current-until (get-in state [:shake :until] 0)
          current-int (get-in state [:shake :intensity] 0)]
      (if (and (< now current-until) (> current-int intensity))
        state
        (assoc state :shake {:intensity intensity :until (+ now duration) :duration duration})))))

;; ============================================================================
;; 3. ESTADO INICIAL DE PARTIDA, BLANCOS Y JUGADOR
;; ============================================================================

(defn make-target [id x y now]
  {:id id :x x :y y :hp 60 :max-hp 60 :alive true
   :respawn-at 0 :last-shot (+ now (* id 420))
   :hurt-until 0 :spawn-until 0 :muzzle-until 0 :aim-angle 0})

(defn fresh-player [now]
  {:x 480 :y 310
   :hp 100 :max-hp 100
   :shield 0 :shield-until 0 :shield-hit-until 0
   :boost-until 0 :heal-until 0
   :dash-until 0 :dash-dir {:dx 1 :dy 0} :last-afterimage 0
   :last-move-dir nil
   :hurt-until 0 :hurt-angle 0
   :muzzle-until 0
   :spawn-until (+ now 600)
   :angle 0 :moving false})

(defn fresh-game
  ([] (fresh-game 0))
  ([now]
   {:running true
    :player (fresh-player now)
    :targets [(make-target 0 195 170 now)
              (make-target 1 765 175 now)
              (make-target 2 250 465 now)
              (make-target 3 740 455 now)]
    :shots []
    :effects [(ring-effect 480 310 "#a8ffdb" 64 520 now :spawn)]
    :particles []
    :texts []
    :afterimages []
    :shake {:intensity 0 :until 0 :duration 1}
    :score 0
    :last-shot 0
    :cooldowns {:dash 0 :shield 0 :heal 0 :boost 0}
    :message "Acércate a los blancos, esquiva con SHIFT y dispara."}))

(def game (atom (assoc (fresh-game 0) :running false)))

;; ============================================================================
;; 4. LÓGICA DE DIRECCIÓN DE DASH, PODERES Y APLICACIÓN CENTRAL DE DAÑO
;; ============================================================================

(defn current-wasd-dir [keys-set]
  (let [dx (+ (if (contains? keys-set "d") 1 0)
              (if (contains? keys-set "a") -1 0))
        dy (+ (if (contains? keys-set "s") 1 0)
              (if (contains? keys-set "w") -1 0))]
    (when (or (not= dx 0) (not= dy 0))
      (let [len (js/Math.hypot dx dy)]
        {:dx (/ dx len) :dy (/ dy len)}))))

(defn resolve-dash-dir [player keys-set]
  ;; Prioridad: 1) WASD actual -> 2) última dirección de movimiento -> 3) dirección de apuntado
  (or (current-wasd-dir keys-set)
      (:last-move-dir player)
      {:dx (js/Math.cos (:angle player))
       :dy (js/Math.sin (:angle player))}))

(defn player-dashing? [player now]
  (< now (:dash-until player 0)))

(defn apply-player-damage
  "Punto central único donde se evalúa y aplica cualquier daño al jugador.
   La inmunidad del dash (aprox. 180 ms) tiene prioridad absoluta mientras está activo.
   Antes y después del dash recibe daño normalmente, evaluando primero el escudo Q."
  [state raw-damage hit-x hit-y source-angle now]
  (let [player (:player state)
        px (:x player)
        py (:y player)]
    (if (player-dashing? player now)
      ;; Inmunidad total durante los cuadros activos del dash: daño = 0
      (-> state
          (update :effects #(cap-vec (conj % (ring-effect hit-x hit-y "#a8ffdb" 34 240 now :dodge)) max-effects))
          (update :particles #(cap-vec (into % (spawn-sparks hit-x hit-y source-angle 1.1 7 210 "#a8ffdb" 240 now)) max-particles))
          (update :texts #(cap-vec (conj % (floating-text px (- py 44) "INMUNE (0)" "#a8ffdb" now)) max-texts))
          (assoc :message "¡Golpe esquivado con Dash! Daño recibido: 0."))
      ;; Fuera del dash: evaluar escudo Q primero, luego vida
      (let [active-shield (if (< now (:shield-until player)) (:shield player) 0)
            absorbed (min active-shield raw-damage)
            new-shield (- active-shield absorbed)
            hp-damage (- raw-damage absorbed)
            shield-broke? (and (> active-shield 0) (<= new-shield 0))
            next-hp (max 0 (- (:hp player) hp-damage))]
        (cond-> state
          true
          (assoc-in [:player :shield] new-shield)

          shield-broke?
          (-> (assoc-in [:player :shield-until] 0)
              (update :effects #(cap-vec (conj % (ring-effect px py "#8edff8" 68 380 now :shield-break)) max-effects))
              (update :particles #(cap-vec (into % (spawn-sparks px py 0 js/Math.PI 14 250 "#8edff8" 360 now)) max-particles))
              (update :texts #(cap-vec (conj % (floating-text px (- py 52) "¡ESCUDO ROTO!" "#8edff8" now)) max-texts))
              (assoc :message "¡El escudo absorbió el impacto y se rompió!"))

          (and (> absorbed 0) (not shield-broke?))
          (-> (assoc-in [:player :shield-hit-until] (+ now 240))
              (update :effects #(cap-vec (conj % (ring-effect hit-x hit-y "#8edff8" 42 250 now :shield-hit)) max-effects))
              (update :particles #(cap-vec (into % (spawn-sparks hit-x hit-y source-angle 0.9 7 180 "#8edff8" 240 now)) max-particles))
              (update :texts #(cap-vec (conj % (floating-text px (- py 46) (str "ESCUDO -" absorbed) "#8edff8" now)) max-texts))
              (assoc :message (str "Escudo absorbió " absorbed " de daño.")))

          (> hp-damage 0)
          (-> (assoc-in [:player :hp] next-hp)
              (assoc-in [:player :hurt-until] (+ now 240))
              (assoc-in [:player :hurt-angle] source-angle)
              (update :effects #(cap-vec (conj % (ring-effect hit-x hit-y "#ff6b6b" 38 280 now :hurt)) max-effects))
              (update :particles #(cap-vec (into % (spawn-sparks hit-x hit-y source-angle 1.0 9 210 "#ff6b6b" 280 now)) max-particles))
              (update :texts #(cap-vec (conj % (floating-text px (- py 40) (str "-" hp-damage) "#ff7b7b" now)) max-texts))
              (trigger-shake 5 160 now)
              (assoc :message (str "Recibiste " hp-damage " puntos de daño."))))))))

(defn activate-power! [power now]
  (let [s @game
        ready-at (get-in s [:cooldowns power] 0)]
    (when (and (:running s) (>= now ready-at))
      (case power
        :dash
        (let [p (:player s)
              dir (resolve-dash-dir p @keys-down)
              dash-ang (js/Math.atan2 (:dy dir) (:dx dir))
              pal (get fighter-palettes @selected {:primary "#a8ffdb"})]
          (swap! game
                 (fn [state]
                   (-> state
                       (assoc-in [:player :dash-until] (+ now dash-duration-ms))
                       (assoc-in [:player :dash-dir] dir)
                       (assoc-in [:player :last-afterimage] now)
                       (assoc-in [:cooldowns :dash] (+ now dash-cooldown-ms))
                       (update :afterimages
                               #(cap-vec (conj % {:x (:x p) :y (:y p) :born now :until (+ now 280)
                                                  :duration 220 :color (:primary pal) :frame 1})
                                         max-afterimages))
                       (update :effects
                               #(cap-vec (conj % (ring-effect (:x p) (:y p) "#a8ffdb" 48 260 now :dash))
                                         max-effects))
                       (update :particles
                               #(cap-vec (into % (spawn-sparks (:x p) (:y p) (+ dash-ang js/Math.PI) 0.65 10 240 "#a8ffdb" 260 now))
                                         max-particles))
                       (assoc :message "Dash evasivo activado: inmune durante 0,18 s.")))))

        :shield
        (let [p (:player s)]
          (swap! game
                 (fn [state]
                   (-> state
                       (assoc-in [:player :shield] shield-max)
                       (assoc-in [:player :shield-until] (+ now shield-duration-ms))
                       (assoc-in [:player :shield-hit-until] (+ now 180))
                       (assoc-in [:cooldowns :shield] (+ now shield-cooldown-ms))
                       (update :effects
                               #(cap-vec (conj % (ring-effect (:x p) (:y p) "#8edff8" 58 360 now :shield-cast))
                                         max-effects))
                       (update :texts
                               #(cap-vec (conj % (floating-text (:x p) (- (:y p) 44) "ESCUDO +60" "#8edff8" now))
                                         max-texts))
                       (assoc :message "Escudo de 60 puntos activo durante 5 segundos.")))))

        :heal
        (let [p (:player s)
              cur-hp (:hp p)]
          (if (< cur-hp 100)
            (let [healed (- (min 100 (+ cur-hp heal-amount)) cur-hp)]
              (swap! game
                     (fn [state]
                       (-> state
                           (assoc-in [:player :hp] (+ cur-hp healed))
                           (assoc-in [:player :heal-until] (+ now heal-duration-ms))
                           (assoc-in [:cooldowns :heal] (+ now heal-cooldown-ms))
                           (update :effects
                                   #(cap-vec (conj % (ring-effect (:x p) (:y p) "#84ef88" 60 440 now :heal))
                                             max-effects))
                           (update :particles
                                   #(cap-vec (into % (spawn-heal-particles (:x p) (:y p) now))
                                             max-particles))
                           (update :texts
                                   #(cap-vec (conj % (floating-text (:x p) (- (:y p) 44) (str "+" healed " VIDA") "#84ef88" now))
                                             max-texts))
                           (assoc :message (str "Recuperaste " healed " puntos de vida."))))))
            (swap! game assoc :message "Tu vida ya está al máximo (100 / 100).")))

        :boost
        (let [p (:player s)]
          (swap! game
                 (fn [state]
                   (-> state
                       (assoc-in [:player :boost-until] (+ now boost-duration-ms))
                       (assoc-in [:cooldowns :boost] (+ now boost-cooldown-ms))
                       (update :effects
                               #(cap-vec (conj % (ring-effect (:x p) (:y p) "#ff9f43" 62 400 now :boost))
                                         max-effects))
                       (update :particles
                               #(cap-vec (into % (spawn-sparks (:x p) (:y p) 0 js/Math.PI 12 200 "#ffd166" 340 now))
                                         max-particles))
                       (update :texts
                               #(cap-vec (conj % (floating-text (:x p) (- (:y p) 44) "DAÑO EXTRA" "#ffd166" now))
                                         max-texts))
                       (assoc :message "Daño aumentado (35 por disparo) durante 6 segundos.")))))
        nil))))

;; ============================================================================
;; 5. ACTUALIZACIÓN DEL ESTADO DE JUEGO (FÍSICA, COLISIONES, CICLO DE VIDA)
;; ============================================================================

(defn make-shot [x y angle team damage boosted? now]
  (let [speed (if (= team :player) (if boosted? 780 690) 275)
        spawn-dist (if (= team :player) 28 24)
        sx (+ x (* (js/Math.cos angle) spawn-dist))
        sy (+ y (* (js/Math.sin angle) spawn-dist))]
    {:x sx :y sy :prev-x x :prev-y y
     :vx (* (js/Math.cos angle) speed)
     :vy (* (js/Math.sin angle) speed)
     :angle angle
     :team team
     :damage damage
     :boosted (boolean boosted?)
     :born now
     :life (if (= team :player) 1.15 2.9)}))

(defn update-player-state [state dt now]
  (let [player (:player state)
        wasd (current-wasd-dir @keys-down)
        dashing? (player-dashing? player now)
        move-dir (if dashing? (:dash-dir player) (or wasd {:dx 0 :dy 0}))
        speed (if dashing? dash-speed walk-speed)
        nx (clamp (+ (:x player) (* speed dt (:dx move-dir)))
                  (:min-x arena-bounds) (:max-x arena-bounds))
        ny (clamp (+ (:y player) (* speed dt (:dy move-dir)))
                  (:min-y arena-bounds) (:max-y arena-bounds))
        aim-angle (js/Math.atan2 (- (:y @mouse) ny) (- (:x @mouse) nx))
        shield-expired? (and (> (:shield player) 0) (>= now (:shield-until player)))
        spawn-ghost? (and dashing? (>= (- now (:last-afterimage player 0)) 45))
        pal (get fighter-palettes @selected {:primary "#a8ffdb"})
        updated-player (-> player
                           (assoc :x nx
                                  :y ny
                                  :angle aim-angle
                                  :moving (or dashing? (boolean wasd)))
                           (cond-> wasd (assoc :last-move-dir wasd))
                           (cond-> shield-expired? (assoc :shield 0))
                           (cond-> spawn-ghost? (assoc :last-afterimage now)))]
    (cond-> (assoc state :player updated-player)
      shield-expired?
      (-> (update :effects #(cap-vec (conj % (ring-effect nx ny "#8edff8" 50 300 now :shield-expire)) max-effects))
          (update :texts #(cap-vec (conj % (floating-text nx (- ny 42) "ESCUDO EXPIRÓ" "#8edff8" now)) max-texts)))

      spawn-ghost?
      (-> (update :afterimages
                  #(cap-vec (conj % {:x nx :y ny :born now :until (+ now 240)
                                     :duration 200 :color (:primary pal) :frame 1})
                            max-afterimages))
          (update :particles
                  #(cap-vec (conj % (make-particle (+ nx (rand-range -10 10))
                                                   (+ ny (rand-range -12 12))
                                                   (* (- (:dx move-dir)) 140)
                                                   (* (- (:dy move-dir)) 140)
                                                   3.2 "#a8ffdb" 200 now :dash-line))
                            max-particles))))))

(defn update-targets-and-enemy-fire [state now]
  (let [player (:player state)]
    (reduce
      (fn [acc t]
        (let [regenerated (if (and (not (:alive t)) (>= now (:respawn-at t)))
                            (assoc t :alive true :hp 60 :max-hp 60 :last-shot now :spawn-until (+ now 520))
                            t)
              just-spawned? (and (not (:alive t)) (:alive regenerated))
              aim-ang (js/Math.atan2 (- (:y player) (:y regenerated))
                                     (- (:x player) (:x regenerated)))
              fire-interval (+ 3200 (* (:id regenerated) 260))
              should-fire? (and (:alive regenerated)
                                (> (- now (:last-shot regenerated)) fire-interval))
              final-t (-> regenerated
                          (assoc :aim-angle aim-ang)
                          (cond-> should-fire? (assoc :last-shot now :muzzle-until (+ now 160))))]
          (cond-> (update acc :targets conj final-t)
            just-spawned?
            (update :effects #(cap-vec (conj % (ring-effect (:x final-t) (:y final-t) "#f0ba74" 48 420 now :target-spawn)) max-effects))

            should-fire?
            (-> (update :shots conj (make-shot (:x final-t) (:y final-t) aim-ang :enemy 6 false now))
                (update :particles #(cap-vec (into % (spawn-sparks (+ (:x final-t) (* (js/Math.cos aim-ang) 22))
                                                                   (+ (:y final-t) (* (js/Math.sin aim-ang) 22))
                                                                   aim-ang 0.45 4 130 "#ff9270" 180 now))
                                             max-particles))))))
      (assoc state :targets [])
      (:targets state))))

(defn advance-shot [acc shot dt now]
  (let [x (+ (:x shot) (* (:vx shot) dt))
        y (+ (:y shot) (* (:vy shot) dt))
        moved (assoc shot :prev-x (:x shot) :prev-y (:y shot) :x x :y y :life (- (:life shot) dt))
        out-of-bounds? (or (< x 18) (> x 942) (< y 18) (> y 582))]
    (cond
      (<= (:life moved) 0)
      acc

      out-of-bounds?
      (update acc :particles
              #(cap-vec (into % (spawn-sparks (clamp x 20 940) (clamp y 20 580)
                                              (+ (:angle shot) js/Math.PI) 0.8 4 140
                                              (if (= (:team shot) :player) "#ffe680" "#ff9270")
                                              180 now))
                        max-particles))

      (= (:team shot) :player)
      (if-let [hit (some (fn [t] (when (and (:alive t) (< (distance x y (:x t) (:y t)) 29)) t))
                         (:targets acc))]
        (let [id (:id hit)
              dmg (:damage shot)
              killed? (<= (:hp hit) dmg)
              tx (:x hit)
              ty (:y hit)
              boosted? (:boosted shot)
              hit-color (if boosted? "#ff9f43" "#ffe680")]
          (-> acc
              (update-in [:targets id]
                         (fn [t]
                           (if killed?
                             (assoc t :hp 0 :alive false :respawn-at (+ now 1800) :hurt-until 0)
                             (-> t
                                 (update :hp - dmg)
                                 (assoc :hurt-until (+ now 150))))))
              (update :score + (if killed? 1 0))
              (update :effects
                      #(cap-vec (cond-> (conj % (ring-effect x y hit-color (if killed? 62 28) (if killed? 420 220) now (if killed? :explosion :hit)))
                                  killed? (conj (ring-effect tx ty "#fff3a3" 40 300 now :explosion-inner)))
                                max-effects))
              (update :particles
                      #(cap-vec (into % (spawn-sparks x y (+ (:angle shot) js/Math.PI)
                                                      (if killed? js/Math.PI 0.85)
                                                      (if killed? 16 7)
                                                      (if killed? 260 190)
                                                      (if killed? "#ffd166" hit-color)
                                                      (if killed? 440 230)
                                                      now))
                                max-particles))
              (update :texts
                      #(cap-vec (cond-> (conj % (floating-text tx (- ty 36) (str "-" dmg (if boosted? "!" "")) hit-color now))
                                  killed? (conj (floating-text tx (- ty 54) "DESTRUIDO +1" "#f3df64" now)))
                                max-texts))
              (cond-> killed?
                (-> (trigger-shake 7 210 now)
                    (assoc :message "Blanco destruido. Se regenerará en 1,8 s.")))))
        (update acc :shots conj moved))

      :else
      ;; Disparo enemigo contra el jugador -> pasa por apply-player-damage
      (let [px (get-in acc [:player :x])
            py (get-in acc [:player :y])]
        (if (< (distance x y px py) 21)
          (apply-player-damage acc (:damage shot) x y (+ (:angle shot) js/Math.PI) now)
          (update acc :shots conj moved))))))

(defn update-particles-and-cleanup [state dt now]
  (let [moved-particles (reduce
                          (fn [acc p]
                            (if (> (:until p) now)
                              (let [drag (if (= (:kind p) :plus) 0.98 0.92)]
                                (conj acc (-> p
                                              (update :x + (* (:vx p) dt))
                                              (update :y + (* (:vy p) dt))
                                              (update :vx * drag)
                                              (update :vy * drag))))
                              acc))
                          []
                          (:particles state))
        moved-texts (reduce
                      (fn [acc txt]
                        (if (> (:until txt) now)
                          (conj acc (update txt :y + (* (:vy txt) dt)))
                          acc))
                      []
                      (:texts state))]
    (-> state
        (assoc :particles (cap-vec moved-particles max-particles)
               :texts (cap-vec moved-texts max-texts)
               :effects (cap-vec (filterv #(> (:until %) now) (:effects state)) max-effects)
               :afterimages (cap-vec (filterv #(> (:until %) now) (:afterimages state)) max-afterimages)))))

(defn handle-player-respawn [state now]
  (if (<= (get-in state [:player :hp]) 0)
    (let [old-p (:player state)
          dx (:x old-p)
          dy (:y old-p)
          new-p (assoc (fresh-player now) :angle (:angle old-p))]
      (-> state
          (assoc :player new-p
                 :shots []
                 :afterimages []
                 :message "Te eliminaron. Reapareciste con la vida completa y estado limpio.")
          (update :effects
                  #(cap-vec (-> %
                                (conj (ring-effect dx dy "#ff6b6b" 68 450 now :death-exit))
                                (conj (ring-effect (:x new-p) (:y new-p) "#a8ffdb" 66 550 now :spawn)))
                            max-effects))
          (update :particles
                  #(cap-vec (-> %
                                (into (spawn-sparks dx dy 0 js/Math.PI 16 250 "#ff6b6b" 440 now))
                                (into (spawn-sparks (:x new-p) (:y new-p) 0 js/Math.PI 12 200 "#a8ffdb" 420 now)))
                            max-particles))
          (update :texts
                  #(cap-vec (conj % (floating-text (:x new-p) (- (:y new-p) 48) "REAPARICIÓN" "#a8ffdb" now))
                            max-texts))
          (trigger-shake 8 250 now)))
    state))

(defn advance [s dt now]
  (let [s1 (update-player-state s dt now)
        s2 (update-targets-and-enemy-fire s1 now)
        player (:player s2)
        firing? (and (:down @mouse) (> (- now (:last-shot s2)) 170))
        boosted? (< now (:boost-until player))
        player-shot (when firing?
                      (make-shot (:x player) (:y player) (:angle player)
                                 :player (if boosted? 35 20) boosted? now))
        muz-x (+ (:x player) (* (js/Math.cos (:angle player)) 34))
        muz-y (+ (:y player) (* (js/Math.sin (:angle player)) 34))
        s3 (cond-> s2
             firing?
             (-> (assoc :last-shot now)
                 (assoc-in [:player :muzzle-until] (+ now 115))
                 (update :shots conj player-shot)
                 (update :particles
                         #(cap-vec (into % (spawn-sparks muz-x muz-y (:angle player) 0.42
                                                         (if boosted? 7 4) 190
                                                         (if boosted? "#ffd166" "#fff3a3")
                                                         140 now))
                                   max-particles))
                 (cond-> boosted? (trigger-shake 2.2 65 now))))
        existing-shots (:shots s3)
        s4 (reduce (fn [acc shot] (advance-shot acc shot dt now))
                   (assoc s3 :shots [])
                   existing-shots)
        s5 (update-particles-and-cleanup s4 dt now)]
    (handle-player-respawn s5 now)))

;; ============================================================================
;; 6. FUNCIONES DE DIBUJO EN CANVAS (SEPARADAS DEL ESTADO)
;; ============================================================================

(defn rect! [x y w h color]
  (set! (.-fillStyle ctx) color)
  (.fillRect ctx x y w h))

(defn circle! [x y radius color]
  (set! (.-fillStyle ctx) color)
  (.beginPath ctx)
  (.arc ctx x y (max 0 radius) 0 (* 2 js/Math.PI))
  (.fill ctx))

(defn stroke-circle! [x y radius color width]
  (set! (.-strokeStyle ctx) color)
  (set! (.-lineWidth ctx) width)
  (.beginPath ctx)
  (.arc ctx x y (max 0 radius) 0 (* 2 js/Math.PI))
  (.stroke ctx))

(defn line! [x1 y1 x2 y2 color width]
  (set! (.-strokeStyle ctx) color)
  (set! (.-lineWidth ctx) width)
  (.beginPath ctx)
  (.moveTo ctx x1 y1)
  (.lineTo ctx x2 y2)
  (.stroke ctx))

(defn draw-floor! [now]
  (rect! 0 0 960 600 "#2e4134")
  ;; Viñeta interior sutil de la arena
  (rect! 22 22 916 556 "#34483a")
  ;; Cuadrícula táctica
  (doseq [x (range 48 960 48)] (line! x 22 x 578 "#70866b28" 1))
  (doseq [y (range 48 600 48)] (line! 22 y 938 y "#70866b28" 1))
  ;; Líneas divisorias centrales y círculo táctico
  (line! 480 22 480 578 "#8fa68622" 1)
  (line! 22 300 938 300 "#8fa68622" 1)
  (circle! 480 300 112 "#ffffff07")
  (stroke-circle! 480 300 112 "#b1c09530" 2)
  (stroke-circle! 480 300 36 "#b1c09522" 1.5)
  ;; Marco perimetral de límites
  (set! (.-strokeStyle ctx) "#aabd8266")
  (set! (.-lineWidth ctx) 3)
  (.strokeRect ctx 22 22 916 556)
  ;; Esquinas tácticas
  (doseq [[cx cy sx sy] [[30 30 1 1] [930 30 -1 1] [30 570 1 -1] [930 570 -1 -1]]]
    (line! cx cy (+ cx (* sx 22)) cy "#f3df6488" 3)
    (line! cx cy cx (+ cy (* sy 22)) "#f3df6488" 3)))

(defn draw-target! [t now]
  (let [x (:x t)
        y (:y t)]
    ;; Base en el suelo del blanco
    (circle! x y 32 "#18241c88")
    (stroke-circle! x y 32 "#687d6644" 1.5)
    (if (:alive t)
      (let [pulse (if (reduced-motion?) 1 (+ 1 (* 0.045 (js/Math.sin (/ now 210)))))
            hurt? (< now (:hurt-until t 0))
            spawning? (< now (:spawn-until t 0))
            fire-cd (- (+ 3200 (* (:id t) 260)) (- now (:last-shot t)))
            charging? (and (< fire-cd 420) (> fire-cd 0))
            ang (:aim-angle t 0)
            bx (+ x (* (js/Math.cos ang) 22))
            by (+ y (* (js/Math.sin ang) 22))]
        ;; Efecto de aparición al regenerarse
        (when spawning?
          (let [p (/ (- (:spawn-until t) now) 520)]
            (stroke-circle! x y (* 44 (- 1 p)) "#f0ba74aa" 3)))
        ;; Aviso de carga antes de disparar
        (when charging?
          (stroke-circle! x y 33 "#ff927088" 2))
        ;; Sombra y cuerpo del blanco
        (circle! x (+ y 22) 27 "#0e1711a8")
        (circle! x y (* 28 pulse) (if hurt? "#fff3a3" "#1b2620"))
        (circle! x y (* 22 pulse) (if hurt? "#ffffff" "#8d9e82"))
        (circle! x y (* 15 pulse) (if hurt? "#ff9f43" "#293d30"))
        ;; Cañón orientado hacia el jugador
        (line! x y bx by (if charging? "#ff9270" "#536751") 6)
        (circle! x y 8 (if (or hurt? charging?) "#ff6b6b" "#e2a66f"))
        (line! (- x 9) y (+ x 9) y "#f1d8aa" 2)
        (line! x (- y 9) x (+ y 9) "#f1d8aa" 2)
        ;; Fogonazo enemigo al disparar
        (when (< now (:muzzle-until t 0))
          (circle! bx by 9 "#ff9270")
          (circle! bx by 4 "#ffffff"))
        ;; Barra de vida del blanco
        (rect! (- x 31) (- y 40) 62 7 "#111c14")
        (rect! (- x 30) (- y 39) (max 0 (:hp t)) 5 (if hurt? "#ffffff" "#f0ba74"))
        (set! (.-font ctx) "bold 11px Mono")
        (set! (.-textAlign ctx) "center")
        (set! (.-fillStyle ctx) "#e9edda")
        (.fillText ctx (str "BLANCO · " (:hp t)) x (+ y 49)))
      ;; Blanco destruido: indicador de regeneración
      (let [rem-ms (max 0 (- (:respawn-at t) now))
            progress (- 1 (/ rem-ms 1800))]
        (set! (.-strokeStyle ctx) "#f3df6488")
        (set! (.-lineWidth ctx) 3)
        (.beginPath ctx)
        (.arc ctx x y 22 (- (/ js/Math.PI 2)) (+ (- (/ js/Math.PI 2)) (* progress 2 js/Math.PI)))
        (.stroke ctx)
        (set! (.-font ctx) "bold 10px Mono")
        (set! (.-textAlign ctx) "center")
        (set! (.-fillStyle ctx) "#b5c3b1")
        (.fillText ctx "REGENERANDO" x (+ y 40))))))

(defn draw-afterimages! [afterimages now]
  (let [image (get sprites @selected)]
    (doseq [ghost afterimages]
      (let [alpha (* 0.48 (clamp (/ (- (:until ghost) now) (:duration ghost)) 0 1))
            gx (:x ghost)
            gy (:y ghost)]
        (.save ctx)
        (set! (.-globalAlpha ctx) alpha)
        (stroke-circle! gx gy 28 (:color ghost) 2)
        (when (and image (.-complete image) (> (.-naturalWidth image) 0))
          (.drawImage ctx image (* (:frame ghost 5) 32) 0 32 32 (- gx 46) (- gy 56) 92 92))
        (.restore ctx)))))

(defn draw-shots! [shots]
  (doseq [shot shots]
    (let [x (:x shot)
          y (:y shot)
          ang (:angle shot 0)
          player? (= (:team shot) :player)
          boosted? (:boosted shot)
          tail-len (if player? (if boosted? 34 22) 18)
          tx (- x (* (js/Math.cos ang) tail-len))
          ty (- y (* (js/Math.sin ang) tail-len))]
      (if player?
        (if boosted?
          (do
            (line! tx ty x y "#ff6b3588" 10)
            (line! tx ty x y "#ff9f43" 5)
            (circle! x y 9 "#ff9f43")
            (circle! x y 5 "#ffffff"))
          (do
            (line! tx ty x y "#f5b84177" 6)
            (line! tx ty x y "#ffe680" 3)
            (circle! x y 6.5 "#ffe680")
            (circle! x y 3.2 "#ffffff")))
        ;; Proyectil enemigo con rastro y halo contrastado
        (do
          (line! tx ty x y "#ff383866" 7)
          (line! tx ty x y "#ff9270" 3.5)
          (circle! x y 6.5 "#ff5e57")
          (circle! x y 3.2 "#fff2a6"))))))

(defn draw-effects-and-particles! [s now]
  ;; Anillos de impacto, explosión, curación y poderes
  (doseq [fx (:effects s)]
    (let [progress (clamp (- 1 (/ (- (:until fx) now) (:duration fx))) 0 1)
          alpha (clamp (- 1 progress) 0 1)
          radius (* (:max-r fx) (+ 0.2 (* 0.8 progress)))]
      (.save ctx)
      (set! (.-globalAlpha ctx) alpha)
      (when (contains? #{:explosion :shield-break :death-exit} (:kind fx))
        (circle! (:x fx) (:y fx) (* radius 0.65) (:color fx)))
      (stroke-circle! (:x fx) (:y fx) radius (:color fx)
                      (if (= (:kind fx) :explosion) 4.5 3))
      (.restore ctx)))

  ;; Partículas de chispas, estela de velocidad y cruces de curación
  (doseq [p (:particles s)]
    (let [alpha (clamp (/ (- (:until p) now) (:duration p)) 0 1)
          px (:x p)
          py (:y p)
          sz (:size p)]
      (.save ctx)
      (set! (.-globalAlpha ctx) alpha)
      (case (:kind p)
        :plus
        (do
          (rect! (- px 1.5) (- py 5) 3 10 (:color p))
          (rect! (- px 5) (- py 1.5) 10 3 (:color p)))
        :dash-line
        (line! px py (- px (* (:vx p) 0.08)) (- py (* (:vy p) 0.08)) (:color p) 2.5)
        ;; :spark por defecto
        (do
          (line! px py (- px (* (:vx p) 0.045)) (- py (* (:vy p) 0.045)) (:color p) sz)
          (circle! px py (* sz 0.55) "#ffffff")))
      (.restore ctx))))

(defn select-sprite-frame [player s now]
  (cond
    (< now (:hurt-until player 0)) 6
    (player-dashing? player now) 5
    (< (- now (:last-shot s 0)) 115) 4
    (:moving player) (nth [0 1 2 3] (mod (js/Math.floor (/ now 95)) 4))
    (< now (:boost-until player 0)) 7
    :else 0))

(defn draw-player! [s now]
  (let [player (:player s)
        x (:x player)
        y (:y player)
        ang (:angle player)
        image (get sprites @selected)
        frame (select-sprite-frame player s now)
        boosted? (< now (:boost-until player 0))
        dashing? (player-dashing? player now)
        shielded? (and (> (:shield player) 0) (< now (:shield-until player 0)))
        shield-hit? (< now (:shield-hit-until player 0))
        healing? (< now (:heal-until player 0))
        hurt? (< now (:hurt-until player 0))
        spawning? (< now (:spawn-until player 0))
        stride (if (:moving player) (js/Math.sin (/ now 72)) 0)
        bob (cond dashing? -4
                  (:moving player) (* -3 (js/Math.abs stride))
                  :else (* 1.2 (js/Math.sin (/ now 330))))
        stretch-x (if dashing? 1.22 1)
        stretch-y (if dashing? 0.82 (+ 1 (* 0.025 (js/Math.sin (/ now 300)))))
        recoil (if (< now (:muzzle-until player 0)) 4 0)]
    ;; Sombra en el suelo
    (circle! x (+ y 26) 33 "#0e1711a8")

    ;; Haz de entrada al aparecer o reaparecer
    (when spawning?
      (let [p (/ (- (:spawn-until player) now) 600)]
        (.save ctx)
        (set! (.-globalAlpha ctx) (clamp p 0 1))
        (rect! (- x 26) 22 52 (- y 10) "#a8ffdb33")
        (stroke-circle! x y (* 52 p) "#a8ffdb" 3)
        (.restore ctx)))

    ;; Aura de Daño Extra (R)
    (when boosted?
      (let [rot (if (reduced-motion?) 0 (/ now 280))]
        (circle! x y 36 "#ff9f4328")
        (stroke-circle! x y 36 "#ff9f43" 3)
        (doseq [i (range 4)]
          (let [a (+ rot (* i (/ js/Math.PI 2)))
                cx (+ x (* (js/Math.cos a) 41))
                cy (+ y (* (js/Math.sin a) 41))]
            (circle! cx cy 3.5 "#ffd166")))))

    ;; Aura de Curación (E)
    (when healing?
      (circle! x y 48 "#84ef8830")
      (stroke-circle! x y 46 "#84ef88" 3))

    ;; Aura de Inmunidad de Dash (Shift)
    (when dashing?
      (let [ddir (:dash-dir player {:dx 1 :dy 0})]
        (circle! x y 42 "#a8ffdb38")
        (stroke-circle! x y 42 "#e0fff4" 3.5)
        (line! (- x (* (:dx ddir) 38)) (- y (* (:dy ddir) 38))
               (- x (* (:dx ddir) 14)) (- y (* (:dy ddir) 14))
               "#a8ffdb" 4)))

    ;; Contorno animado y destello del Escudo (Q)
    (when shielded?
      (let [rot (if (reduced-motion?) 0 (/ now 420))]
        (circle! x y 46 (if shield-hit? "#ffffff55" "#72caee24"))
        (stroke-circle! x y 46 (if shield-hit? "#ffffff" "#8edff8") (if shield-hit? 5 2.5))
        (.save ctx)
        (set! (.-strokeStyle ctx) (if shield-hit? "#ffffff" "#c8f4ff"))
        (set! (.-lineWidth ctx) 3.5)
        (doseq [i (range 3)]
          (let [start (+ rot (* i (/ (* 2 js/Math.PI) 3)))]
            (.beginPath ctx)
            (.arc ctx x y 49 start (+ start 1.15))
            (.stroke ctx)))
        (.restore ctx)))

    ;; Indicador direccional de daño recibido
    (when hurt?
      (let [hang (:hurt-angle player 0)]
        (circle! x y 38 "#ff383838")
        (.save ctx)
        (set! (.-strokeStyle ctx) "#ff6b6b")
        (set! (.-lineWidth ctx) 5)
        (.beginPath ctx)
        (.arc ctx x y 44 (- hang 0.7) (+ hang 0.7))
        (.stroke ctx)
        (.restore ctx)))

    ;; Línea de puntería sutil y cañón del arma
    (let [tip-x (+ x (* (js/Math.cos ang) 39))
          tip-y (+ y (* (js/Math.sin ang) 39))
          sight-x (+ x (* (js/Math.cos ang) 95))
          sight-y (+ y (* (js/Math.sin ang) 95))]
      (line! tip-x tip-y sight-x sight-y (if boosted? "#ff9f4344" "#e8ecdc26") 1.5)
      (line! x y tip-x tip-y (if boosted? "#ffd166" "#d4ded1") (if boosted? 8 6.5))
      ;; Fogonazo de arma (Muzzle Flash)
      (when (< now (:muzzle-until player 0))
        (let [fl-r (if boosted? 15 11)
              perp (+ ang (/ js/Math.PI 2))]
          (circle! tip-x tip-y fl-r (if boosted? "#ff9f43" "#ffe680"))
          (circle! tip-x tip-y (* fl-r 0.55) "#ffffff")
          (line! (- tip-x (* (js/Math.cos perp) fl-r))
                 (- tip-y (* (js/Math.sin perp) fl-r))
                 (+ tip-x (* (js/Math.cos perp) fl-r))
                 (+ tip-y (* (js/Math.sin perp) fl-r))
                 "#ffffff" 2.5))))

    ;; Sprite del personaje seleccionado (8 cuadros en SVG)
    (when (and image (.-complete image) (> (.-naturalWidth image) 0))
      (.save ctx)
      (.translate ctx (- x (* recoil (js/Math.cos ang)))
                     (- (+ y bob) (* recoil (js/Math.sin ang))))
      (.rotate ctx (if dashing? (* 0.07 (get-in player [:dash-dir :dx] 0))
                       (* 0.025 stride)))
      (.scale ctx stretch-x stretch-y)
      (.drawImage ctx image (* frame 32) 0 32 32 -46 -56 92 92)
      (.restore ctx))

    ;; Micro-barra de recarga de Dash bajo el jugador cuando está en enfriamiento
    (let [dash-ready (get-in s [:cooldowns :dash] 0)]
      (when (> dash-ready now)
        (let [pct (clamp (- 1 (/ (- dash-ready now) dash-cooldown-ms)) 0 1)]
          (rect! (- x 22) (+ y 36) 44 4 "#111c14")
          (rect! (- x 21) (+ y 37) (* 42 pct) 2 "#a8ffdb"))))

    (set! (.-font ctx) "bold 12px Mono")
    (set! (.-textAlign ctx) "center")
    (set! (.-fillStyle ctx) (if dashing? "#a8ffdb" (if boosted? "#ffd166" "#f7f5df")))
    (.fillText ctx (if dashing? "DASH" "TÚ") x (+ y 54))))

(defn draw-floating-texts! [texts now]
  (doseq [txt texts]
    (let [alpha (clamp (/ (- (:until txt) now) (:duration txt)) 0 1)]
      (.save ctx)
      (set! (.-globalAlpha ctx) alpha)
      (set! (.-font ctx) "bold 12px Mono")
      (set! (.-textAlign ctx) "center")
      (set! (.-fillStyle ctx) "#0d1510")
      (.fillText ctx (:text txt) (+ (:x txt) 1) (+ (:y txt) 1))
      (set! (.-fillStyle ctx) (:color txt))
      (.fillText ctx (:text txt) (:x txt) (:y txt))
      (.restore ctx))))

(defn draw-crosshair! [s now]
  (let [x (:x @mouse)
        y (:y @mouse)
        boosted? (< now (get-in s [:player :boost-until] 0))
        firing? (< (- now (:last-shot s 0)) 120)
        gap (if firing? 6 3)
        outer (if firing? 13 10)
        col (if boosted? "#ffd166" "#f4e79c")]
    (when boosted?
      (stroke-circle! x y 9 "#ff9f4388" 1.5))
    (line! (- x outer) y (- x gap) y col 2)
    (line! (+ x gap) y (+ x outer) y col 2)
    (line! x (- y outer) x (- y gap) col 2)
    (line! x (+ y gap) x (+ y outer) col 2)))

(defn draw! [s now]
  (let [shake (:shake s)
        shaking? (and (not (reduced-motion?)) (< now (:until shake 0)))
        rem-ratio (if shaking? (clamp (/ (- (:until shake) now) (:duration shake 1)) 0 1) 0)
        mag (* (:intensity shake 0) rem-ratio)
        sx (if shaking? (* (js/Math.sin (* now 0.09)) mag) 0)
        sy (if shaking? (* (js/Math.cos (* now 0.11)) mag) 0)]
    (.save ctx)
    (.translate ctx sx sy)
    (draw-floor! now)
    (doseq [t (:targets s)] (draw-target! t now))
    (draw-afterimages! (:afterimages s) now)
    (draw-shots! (:shots s))
    (draw-effects-and-particles! s now)
    (draw-player! s now)
    (draw-floating-texts! (:texts s) now)
    (draw-crosshair! s now)
    (.restore ctx)))

;; ============================================================================
;; 7. ACTUALIZACIÓN DEL HUD Y EVENTOS DE INTERFAZ / TECLADO / RATÓN
;; ============================================================================

(defn set-text! [id value]
  (when-let [el (.getElementById js/document id)]
    (set! (.-textContent el) value)))

(defn format-cooldown [ready-at now active-until precise?]
  (cond
    (> active-until now) "ACTIVO"
    (> ready-at now)
    (let [secs (/ (- ready-at now) 1000)]
      (if precise?
        (str (.toFixed secs 1) " s")
        (str (js/Math.ceil secs) " s")))
    :else "LISTO"))

(defn update-hud! [s now]
  (let [p (:player s)
        cds (:cooldowns s)]
    (set-text! "health-text" (str (:hp p) " / 100"))
    (set! (.. js/document (getElementById "health-bar") -style -width) (str (:hp p) "%"))
    (set-text! "shield-text" (str (:shield p) " / 60"))
    (set! (.. js/document (getElementById "shield-bar") -style -width) (str (* (/ (:shield p) 60) 100) "%"))
    (set-text! "score" (:score s))
    (set-text! "game-message" (:message s))
    (doseq [[power suffix active precise?]
            [[:dash   "dash"   (:dash-until p) true]
             [:shield "shield" (if (> (:shield p) 0) (:shield-until p) 0) false]
             [:heal   "heal"   (:heal-until p) false]
             [:boost  "boost"  (:boost-until p) false]]]
      (when-let [row (.getElementById js/document (str "power-" suffix))]
        (let [label (format-cooldown (get cds power 0) now active precise?)]
          (set-text! (str suffix "-cooldown") label)
          (.toggle (.-classList row) "active" (= label "ACTIVO"))
          (.toggle (.-classList row) "cooling" (and (not= label "ACTIVO") (not= label "LISTO"))))))))

(defn show-screen! [id]
  (.forEach (.querySelectorAll js/document ".screen")
            (fn [screen] (.toggle (.-classList screen) "active" (= (.-id screen) id))))
  (.scrollTo js/window 0 0))

(defn start! []
  (let [now (js/performance.now)]
    (reset! game (fresh-game now))
    (reset! keys-down #{})
    (reset! mouse {:x 650 :y 300 :down false})
    (set-text! "hud-name" (.toUpperCase @selected))
    (show-screen! "juego")
    (draw! @game now)
    (update-hud! @game now)
    (reset! last-hud now)
    (.focus canvas)))

(defn leave! []
  (swap! game assoc :running false)
  (reset! keys-down #{})
  (swap! mouse assoc :down false)
  (show-screen! "inicio"))

(.addEventListener (.getElementById js/document "start-game") "click" start!)
(.addEventListener (.getElementById js/document "restart-game") "click" start!)
(.addEventListener (.getElementById js/document "leave-game") "click" leave!)

(.forEach (.querySelectorAll js/document "[data-fighter]")
          (fn [button]
            (.addEventListener button "click"
              (fn []
                (reset! selected (.-fighter (.-dataset button)))
                (.forEach (.querySelectorAll js/document "[data-fighter]")
                          (fn [card] (.remove (.-classList card) "active")))
                (.add (.-classList button) "active")))))

(def game-keys #{"w" "a" "s" "d" "q" "e" "r" "shift" " " "arrowup" "arrowdown" "arrowleft" "arrowright"})

(.addEventListener js/window "keydown"
  (fn [event]
    (let [key (.toLowerCase (.-key event))]
      (when (and (:running @game) (contains? game-keys key))
        (.preventDefault event)
        (swap! keys-down conj key)
        (when-not (.-repeat event)
          (let [now (js/performance.now)]
            (case key
              "shift" (activate-power! :dash now)
              "q"     (activate-power! :shield now)
              "e"     (activate-power! :heal now)
              "r"     (activate-power! :boost now)
              nil)))))))

(.addEventListener js/window "keyup"
  (fn [event]
    (let [key (.toLowerCase (.-key event))]
      (swap! keys-down disj key))))

(.addEventListener js/window "blur"
  (fn []
    (reset! keys-down #{})
    (swap! mouse assoc :down false)))

(defn aim! [event]
  (let [rect (.getBoundingClientRect canvas)
        x (* (/ (- (.-clientX event) (.-left rect)) (.-width rect)) 960)
        y (* (/ (- (.-clientY event) (.-top rect)) (.-height rect)) 600)]
    (swap! mouse assoc :x (clamp x 0 960) :y (clamp y 0 600))))

(.addEventListener canvas "pointermove" aim!)
(.addEventListener canvas "pointerdown"
  (fn [event]
    (when (= (.-button event) 0)
      (aim! event)
      (swap! mouse assoc :down true)
      (.focus canvas))))
(.addEventListener js/window "pointerup" (fn [] (swap! mouse assoc :down false)))
(.addEventListener canvas "contextmenu" (fn [event] (.preventDefault event)))

;; ============================================================================
;; 8. BUCLE PRINCIPAL CON DELTA ACOTADO
;; ============================================================================

(def last-frame (atom 0))

(defn tick [now]
  (let [prev @last-frame
        raw-dt (if (> prev 0) (/ (- now prev) 1000) 0.016)
        elapsed (clamp raw-dt 0 0.25)]
    (reset! last-frame now)
    (when (:running @game)
      ;; Pasos cortos conservan la velocidad real y evitan saltarse colisiones
      ;; cuando la vista previa del navegador entrega pocos cuadros.
      (loop [remaining elapsed
             step-now (- now (* elapsed 1000))]
        (when (> remaining 0.0001)
          (let [dt (min remaining 0.025)
                next-now (+ step-now (* dt 1000))]
            (swap! game advance dt next-now)
            (recur (- remaining dt) next-now))))
      (draw! @game now)
      (when (>= (- now @last-hud) 60)
        (update-hud! @game now)
        (reset! last-hud now)))
    (js/requestAnimationFrame tick)))

(js/requestAnimationFrame tick)
