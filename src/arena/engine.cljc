(ns arena.engine
  "Núcleo funcional compartido. No lee relojes, red, DOM ni átomos.
   Cada transición recibe valores y devuelve un nuevo mapa persistente.")

(def width 960)
(def height 600)
(def step-ms 25)
(def round-ms 180000)
(def kill-limit 12)
(def max-players 4)
(def skins #{"brasa" "chispa" "mora" "bastion"})
(def pi #?(:clj Math/PI :cljs js/Math.PI))
(defn sin [x] (#?(:clj Math/sin :cljs js/Math.sin) x))
(defn cos [x] (#?(:clj Math/cos :cljs js/Math.cos) x))
(defn atan2 [y x] (#?(:clj Math/atan2 :cljs js/Math.atan2) y x))
(defn sqrt [x] (#?(:clj Math/sqrt :cljs js/Math.sqrt) x))
(defn clamp [v lo hi] (max lo (min hi v)))
(defn distance [x y xx yy] (sqrt (+ (* (- x xx) (- x xx)) (* (- y yy) (- y yy)))))
(defn direction [dx dy]
  (let [length (sqrt (+ (* dx dx) (* dy dy)))]
    (if (pos? length) {:dx (/ dx length) :dy (/ dy length)} {:dx 0 :dy 0})))

(def abilities
  {:dash {:key "SHIFT" :name "Dash" :cooldown 2500 :duration 180 :color "#76f4ce"}
   :shield {:key "Q" :name "Escudo" :cooldown 10000 :duration 5000 :color "#6acfff"}
   :heal {:key "E" :name "Curación" :cooldown 12000 :duration 750 :color "#a2f078"}
   :boost {:key "R" :name "Potencia" :cooldown 13000 :duration 6000 :color "#ffbe67"}
   :ult {:key "F" :name "Nova" :cooldown 0 :duration 650 :color "#c7a0ff"}})
(def weapons
  {:pulse {:name "PULSO" :interval 180 :damage 20 :speed 700 :angles [0]}
   :rapid {:name "RÁFAGA" :interval 90 :damage 12 :speed 760 :angles [0]}
   :scatter {:name "DISPERSOR" :interval 480 :damage 14 :speed 620 :angles [-0.20 -0.10 0 0.10 0.20]}})
(def obstacles [{:x 292 :y 164 :w 74 :h 46}
                {:x 594 :y 164 :w 74 :h 46}
                {:x 292 :y 390 :w 74 :h 46}
                {:x 594 :y 390 :w 74 :h 46}])
(def spawn-points [[132 112] [828 488] [828 112] [132 488]])
(def drop-points [[480 300] [480 122] [480 478] [162 300] [798 300]])
(def arenas
  {:patio {:name "Patio Cero" :obstacles obstacles :spawns spawn-points :drops drop-points}
   :lab {:name "Laboratorio Azul"
         :obstacles [{:x 202 :y 224 :w 110 :h 34} {:x 648 :y 224 :w 110 :h 34}
                     {:x 202 :y 342 :w 110 :h 34} {:x 648 :y 342 :w 110 :h 34}
                     {:x 430 :y 170 :w 100 :h 36} {:x 430 :y 394 :w 100 :h 36}]
         :spawns spawn-points :drops drop-points}
   :desierto {:name "Puesto del Desierto"
              :obstacles [{:x 244 :y 156 :w 72 :h 52} {:x 644 :y 156 :w 72 :h 52}
                          {:x 244 :y 392 :w 72 :h 52} {:x 644 :y 392 :w 72 :h 52}
                          {:x 388 :y 240 :w 44 :h 48} {:x 528 :y 240 :w 44 :h 48}
                          {:x 388 :y 312 :w 44 :h 48} {:x 528 :y 312 :w 44 :h 48}]
              :spawns spawn-points :drops drop-points}})
(defn arena-id [id] (if (contains? arenas id) id :patio))
(def drop-kinds [:rapid :heal :scatter :shield :energy])
(def empty-input {:dx 0 :dy 0 :ax 480 :ay 300 :fire false :actions []})

(defn blocked?
  ([x y radius] (blocked? :patio x y radius))
  ([arena x y radius]
   (some (fn [{ox :x oy :y w :w h :h}]
           (< (distance x y (clamp x ox (+ ox w)) (clamp y oy (+ oy h))) radius))
         (get-in arenas [(arena-id arena) :obstacles]))))

(defn move-player
  "Movimiento puro; usa deslizamiento por ejes para no atravesar la cobertura."
  [p input dt now]
  (let [dashing? (< now (:dash-until p))
        moving (direction (:dx input 0) (:dy input 0))
        dir (if dashing? (:dash-dir p) moving)
        speed (if dashing? 900 280)
        nx (clamp (+ (:x p) (* speed dt (:dx dir))) 42 918)
        x (if (blocked? (:arena p) nx (:y p) 16) (:x p) nx)
        ny (clamp (+ (:y p) (* speed dt (:dy dir))) 42 558)
        y (if (blocked? (:arena p) x ny 16) (:y p) ny)
        walking? (or (not (zero? (:dx moving))) (not (zero? (:dy moving))))]
    (cond-> (assoc p :x x :y y :angle (atan2 (- (:ay input 300) y) (- (:ax input 480) x))
                     :moving (or dashing? walking?))
      walking? (assoc :last-dir moving))))

(defn player
  ([id name skin slot now] (player id name skin slot now :patio))
  ([id name skin slot now arena]
  (let [arena (arena-id arena)
        [x y] (nth (get-in arenas [arena :spawns]) (mod slot 4))]
    {:id id :name name :skin skin :slot slot :arena arena :x x :y y :hp 100 :shield 0
     :angle 0 :moving false :alive true :kills 0 :deaths 0 :damage 0
     :shield-until 0 :boost-until 0 :heal-until 0 :dash-until 0
     :dash-dir {:dx 1 :dy 0} :last-dir {:dx 1 :dy 0}
     :hurt-until 0 :shield-hit-until 0 :muzzle-until 0 :last-shot -1000
     :spawn-until (+ now 1200) :respawn-at 0 :ulti 20 :ult-at 0 :ult-target nil
     :weapon :pulse :weapon-until 0 :cooldowns {:dash 0 :shield 0 :heal 0 :boost 0}})))

(defn world
  ([mode now] (world mode now :patio))
  ([mode now arena]
  {:mode mode :arena (arena-id arena) :phase :waiting :time now :tick 0 :players (sorted-map)
   :shots [] :pickups [] :events [] :event-seq 0 :shot-seq 0
   :next-drop (+ now 8000) :drop-index 0 :round-end (+ now round-ms) :winner nil}))

(defn add-event [w kind data]
  (let [n (inc (:event-seq w))]
    (-> w (assoc :event-seq n)
        (update :events #(vec (take-last 80 (conj % (assoc data :id n :kind kind :at (:time w)))))))))

(defn add-player [w id name skin]
  (if (or (>= (count (:players w)) max-players) (contains? (:players w) id)) w
      (let [occupied (set (map :slot (vals (:players w))))
            slot (first (remove occupied (range 4)))]
        (assoc-in w [:players id] (player id name skin slot (:time w) (:arena w))))))

(defn remove-player [w id]
  (-> w (update :players dissoc id)
      (update :shots #(filterv (fn [s] (not= id (:owner s))) %))))

(defn start-round [w now]
  (let [fresh (reduce (fn [m [id p]]
                        (assoc m id (merge (player id (:name p) (:skin p) (:slot p) now (:arena w))
                                          (select-keys p [:bot?]))))
                      (sorted-map) (:players w))]
    (-> (world (:mode w) now (:arena w))
        (assoc :players fresh :phase :playing :round-end (+ now round-ms)
               :event-seq (:event-seq w))
        (add-event :round-start {}))))

(defn training
  ([name skin now] (training name skin now :patio))
  ([name skin now arena]
  (let [w (-> (world :training now arena)
              (add-player "you" name skin)
              (add-player "bot-1" "SENTINELA 01" "chispa")
              (add-player "bot-2" "SENTINELA 02" "mora")
              (add-player "bot-3" "SENTINELA 03" "bastion")
              (start-round now))]
    (-> (reduce (fn [s id] (assoc-in s [:players id :bot?] true)) w ["bot-1" "bot-2" "bot-3"])
        (assoc-in [:players "you" :x] 480)
        (assoc-in [:players "you" :y] 320)
        (assoc-in [:players "you" :ulti] 100)))))

(defn charge [w id amount]
  (if (get-in w [:players id])
    (update-in w [:players id :ulti] #(clamp (+ % amount) 0 100)) w))

(defn damage-player
  "Única transición de daño: dash/spawn, escudo, vida, baja y puntuación."
  [w victim attacker amount source]
  (let [p (get-in w [:players victim]) now (:time w)]
    (cond
      (or (nil? p) (not (:alive p))) w
      (or (< now (:dash-until p)) (< now (:spawn-until p)))
      (add-event w :dodge {:player victim :x (:x p) :y (:y p)})
      :else
      (let [shield (if (< now (:shield-until p)) (:shield p) 0)
            absorbed (min amount shield)
            lost (min (:hp p) (- amount absorbed))
            killed? (>= lost (:hp p))
            w2 (-> w
                   (assoc-in [:players victim :shield] (- shield absorbed))
                   (assoc-in [:players victim :hp] (- (:hp p) lost))
                   (assoc-in [:players victim :hurt-until] (+ now (if (pos? lost) 170 0)))
                   (assoc-in [:players victim :shield-hit-until] (+ now (if (pos? absorbed) 230 0)))
                   (add-event :hit {:player victim :attacker attacker :x (:x p) :y (:y p)
                                    :amount lost :absorbed absorbed :source source}))
            w3 (if (and attacker (not= attacker victim) (get-in w [:players attacker]))
                 (cond-> (update-in w2 [:players attacker :damage] + lost absorbed)
                   (not= source :ult) (charge attacker (* 0.20 (+ lost absorbed)))) w2)]
        (if killed?
          (cond-> (-> w3
                      (assoc-in [:players victim :alive] false)
                      (assoc-in [:players victim :respawn-at] (+ now 2500))
                      (assoc-in [:players victim :ult-at] 0)
                      (update-in [:players victim :deaths] inc)
                      (add-event :kill {:player victim :attacker attacker :x (:x p) :y (:y p)}))
            (and attacker (not= attacker victim) (get-in w [:players attacker]))
            (-> (update-in [:players attacker :kills] inc) (charge attacker 22)))
          w3)))))

(defn activate
  "Valida recursos y cooldowns. Nunca consume curación con vida completa."
  [w id power input]
  (let [p (get-in w [:players id]) now (:time w)
        ready? (>= now (get-in p [:cooldowns power] 0))
        [x y] [(:x p) (:y p)]
        ability (get abilities power)]
    (if (or (nil? ability) (not (:alive p)) (not ready?)) w
        (case power
          :dash
          (let [dir (direction (:dx input 0) (:dy input 0))
                dir (if (and (zero? (:dx dir)) (zero? (:dy dir))) (:last-dir p) dir)]
            (-> w (assoc-in [:players id :dash-dir] dir)
                (assoc-in [:players id :dash-until] (+ now 180))
                (assoc-in [:players id :cooldowns :dash] (+ now 2500))
                (add-event :dash {:player id :x x :y y :dx (:dx dir) :dy (:dy dir)})))
          :shield (-> w (assoc-in [:players id :shield] 60)
                      (assoc-in [:players id :shield-until] (+ now 5000))
                      (assoc-in [:players id :cooldowns :shield] (+ now 10000))
                      (add-event :shield {:player id :x x :y y}))
          :heal (if (>= (:hp p) 100) w
                    (-> w (assoc-in [:players id :hp] (min 100 (+ (:hp p) 35)))
                        (assoc-in [:players id :heal-until] (+ now 750))
                        (assoc-in [:players id :cooldowns :heal] (+ now 12000))
                        (add-event :heal {:player id :x x :y y :amount (min 35 (- 100 (:hp p)))})))
          :boost (-> w (assoc-in [:players id :boost-until] (+ now 6000))
                     (assoc-in [:players id :cooldowns :boost] (+ now 13000))
                     (add-event :boost {:player id :x x :y y}))
          :ult (if (or (< (:ulti p) 100) (pos? (:ult-at p))) w
                   (let [dir (direction (- (:ax input) x) (- (:ay input) y))
                         reach (min 280 (distance x y (:ax input) (:ay input)))
                         tx (clamp (+ x (* reach (:dx dir))) 42 918)
                         ty (clamp (+ y (* reach (:dy dir))) 42 558)]
                     (-> w (assoc-in [:players id :ulti] 0)
                         (assoc-in [:players id :ult-at] (+ now 650))
                         (assoc-in [:players id :ult-target] {:x tx :y ty})
                         (add-event :ult-cast {:player id :x tx :y ty}))))
          w))))

(defn fire [w id input]
  (let [p (get-in w [:players id]) now (:time w)
        gun (get weapons (:weapon p) (:pulse weapons))
        boosted? (< now (:boost-until p))
        interval (if (:bot? p) (+ 1450 (* (:slot p) 360)) (:interval gun))]
    (if (and (:alive p) (:fire input) (>= (- now (:last-shot p)) interval))
      (let [damage (if (:bot? p) 7 (+ (:damage gun) (if boosted? 15 0)))
            shots (mapv (fn [i a]
                          (let [angle (+ (:angle p) a)
                                sx (+ (:x p) (* 27 (cos angle)))
                                sy (+ (:y p) (* 27 (sin angle)))]
                            {:id (+ (:shot-seq w) i 1) :owner id :x sx :y sy :origin-x sx :origin-y sy
                             :vx (* (:speed gun) (cos angle)) :vy (* (:speed gun) (sin angle))
                             :angle angle :damage damage :boosted boosted? :born now
                             :until (+ now 1400)}))
                        (range) (:angles gun))]
        (-> w (update :shot-seq + (count shots))
            (update :shots #(vec (take-last 200 (into % shots))))
            (assoc-in [:players id :last-shot] now)
            (assoc-in [:players id :muzzle-until] (+ now 90))
            (add-event :shot {:player id :x (:x p) :y (:y p) :angle (:angle p) :boosted boosted?}))) w)))

(defn segment-distance [ax ay bx by px py]
  (let [dx (- bx ax) dy (- by ay) len2 (+ (* dx dx) (* dy dy))
        t (if (pos? len2) (clamp (/ (+ (* (- px ax) dx) (* (- py ay) dy)) len2) 0 1) 0)]
    (distance (+ ax (* t dx)) (+ ay (* t dy)) px py)))

(defn advance-shot [w s dt]
  (let [x (+ (:x s) (* (:vx s) dt)) y (+ (:y s) (* (:vy s) dt))
        hit (first (sort-by (fn [p] (distance (:x s) (:y s) (:x p) (:y p)))
                           (filter (fn [p] (and (:alive p) (not= (:id p) (:owner s))
                                                (< (segment-distance (:x s) (:y s) x y (:x p) (:y p)) 19)))
                                   (vals (:players w)))))
        ;; At 40 Hz a projectile moves <=19 px; midpoint protects narrow edges.
        wall? (or (blocked? (:arena w) x y 4)
                  (blocked? (:arena w) (/ (+ (:x s) x) 2) (/ (+ (:y s) y) 2) 4))]
    (cond
      (or (> (:time w) (:until s)) (< x 24) (> x 936) (< y 24) (> y 576) wall?)
      (if (> (:until s) (:time w)) (add-event w :wall {:x (clamp x 24 936) :y (clamp y 24 576)}) w)
      hit (damage-player w (:id hit) (:owner s) (:damage s) :shot)
      :else (update w :shots conj (assoc s :x x :y y)))))

(defn resolve-ultimates [w]
  (reduce (fn [s [id p]]
            (if (and (:alive p) (pos? (:ult-at p)) (>= (:time s) (:ult-at p)))
              (let [{x :x y :y} (:ult-target p)
                    victims (filter #(and (:alive %) (not= id (:id %))
                                          (< (distance x y (:x %) (:y %)) 125)) (vals (:players s)))]
                (-> (reduce #(damage-player %1 (:id %2) id 65 :ult) s victims)
                    (assoc-in [:players id :ult-at] 0)
                    (add-event :ult {:player id :x x :y y}))) s)) w (:players w)))

(defn lifecycle [w id dt]
  (let [p (get-in w [:players id]) now (:time w)]
    (if (and (not (:alive p)) (>= now (:respawn-at p)))
      (let [fresh (player id (:name p) (:skin p) (:slot p) now (:arena w))]
        (-> w (assoc-in [:players id] (merge fresh (select-keys p [:kills :deaths :damage :bot?])
                                            {:ulti (* 0.65 (:ulti p))}))
            (add-event :spawn {:player id :x (:x fresh) :y (:y fresh)})))
      (cond-> w
        (and (:alive p) (not (:bot? p))) (charge id (* dt 2))
        (>= now (:shield-until p)) (assoc-in [:players id :shield] 0)
        (>= now (:weapon-until p)) (assoc-in [:players id :weapon] :pulse)))))

(defn drop-item [w]
  (if (< (:time w) (:next-drop w)) w
      (let [i (:drop-index w) points (get-in arenas [(:arena w) :drops])
            [x y] (nth points (mod i (count points)))
            kind (nth drop-kinds (mod i 5))]
        (-> w (update :drop-index inc) (assoc :next-drop (+ (:time w) 9000))
            (update :pickups conj {:id i :kind kind :x x :y y :land-at (+ (:time w) 1000)
                                  :until (+ (:time w) 19000)})
            (add-event :drop {:x x :y y :item kind})))))

(defn collect-items [w]
  (reduce (fn [s item]
            (let [p (first (filter #(and (:alive %) (not (:bot? %))
                                        (< (distance (:x %) (:y %) (:x item) (:y item)) 32)
                                        (or (not= (:kind item) :heal) (< (:hp %) 100)))
                                  (vals (:players s))))
                  id (:id p) now (:time s)]
              (cond
                (> now (:until item)) s
                (and p (>= now (:land-at item)))
                (-> (case (:kind item)
                      :heal (update-in s [:players id :hp] #(min 100 (+ % 35)))
                      :shield (-> s (assoc-in [:players id :shield] 60)
                                  (assoc-in [:players id :shield-until] (+ now 6000)))
                      :energy (charge s id 35)
                      (-> s (assoc-in [:players id :weapon] (:kind item))
                          (assoc-in [:players id :weapon-until] (+ now 12000))))
                    (add-event :pickup {:player id :item (:kind item) :x (:x item) :y (:y item)}))
                :else (update s :pickups conj item))))
          (assoc w :pickups []) (:pickups w)))

(defn ranking [w]
  (sort-by (juxt (comp - :kills) :deaths (comp - :damage) :slot) (vals (:players w))))

(defn bot-input [w p]
  (let [target (first (filter #(and (:alive %) (not (:bot? %))) (vals (:players w))))]
    (assoc empty-input :ax (:x target 480) :ay (:y target 300) :fire (boolean target))))

(defn step
  "Una transición de simulación. Reloj e inputs son argumentos explícitos.
   dt nunca supera 25 ms: cliente y servidor invocan pasos fijos."
  [w inputs dt now]
  (if (not= (:phase w) :playing) (assoc w :time now)
      (let [w0 (-> w (assoc :time now) (update :tick inc)
                   (update :events #(filterv (fn [e] (< (- now (:at e)) 1600)) %)))
            w1 (reduce (fn [s id]
                         (let [s (lifecycle s id dt) p (get-in s [:players id])
                               input (if (:bot? p) (bot-input s p) (get inputs id empty-input))
                               s (if (:alive p)
                                   (reduce #(activate %1 id %2 input) s (:actions input)) s)]
                           (if (get-in s [:players id :alive])
                             (-> s (update-in [:players id] move-player input dt now) (fire id input)) s)))
                       w0 (keys (:players w0)))
            w2 (reduce #(advance-shot %1 %2 dt) (assoc w1 :shots []) (:shots w1))
            w3 (-> w2 resolve-ultimates drop-item collect-items)
            finished? (and (= :arena (:mode w3))
                           (or (>= now (:round-end w3)) (some #(>= (:kills %) kill-limit) (vals (:players w3)))))]
        (if finished?
          (let [leaders (ranking w3) first-p (first leaders) second-p (second leaders)
                tie? (and second-p (= (:kills first-p) (:kills second-p))
                          (= (:deaths first-p) (:deaths second-p)) (= (:damage first-p) (:damage second-p)))]
            (-> w3 (assoc :phase :finished :winner (when-not tie? (:id first-p)) :shots [])
                (add-event :round-end {}))) w3))))
