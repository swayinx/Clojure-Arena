(ns arena.terrain
  "Shared arena layouts and terrain metadata.")

(def obstacles [{:x 292 :y 164 :w 74 :h 46}
                {:x 594 :y 164 :w 74 :h 46}
                {:x 292 :y 390 :w 74 :h 46}
                {:x 594 :y 390 :w 74 :h 46}])
(def spawn-points [[132 112] [828 488] [828 112] [132 488]])
(def drop-points [[480 300] [480 122] [480 478] [162 300] [798 300]])
(def arenas
  {:patio {:name "Patio Cero" :obstacles obstacles :spawns spawn-points :drops drop-points}
   :agua {:name "Canales de Agua"
          :obstacles [{:x 385 :y 165 :w 70 :h 48} {:x 505 :y 165 :w 70 :h 48}
                      {:x 385 :y 387 :w 70 :h 48} {:x 505 :y 387 :w 70 :h 48}]
          :hazards [{:x 220 :y 205 :w 125 :h 78 :kind :water}
                    {:x 615 :y 205 :w 125 :h 78 :kind :water}
                    {:x 220 :y 317 :w 125 :h 78 :kind :water}
                    {:x 615 :y 317 :w 125 :h 78 :kind :water}]
          :spawns spawn-points :drops drop-points}
   :lava {:name "Fractura de Lava"
          :obstacles [{:x 412 :y 150 :w 136 :h 44} {:x 412 :y 406 :w 136 :h 44}
                      {:x 178 :y 154 :w 56 :h 46} {:x 726 :y 154 :w 56 :h 46}
                      {:x 178 :y 400 :w 56 :h 46} {:x 726 :y 400 :w 56 :h 46}]
          :hazards [{:x 292 :y 204 :w 105 :h 78 :kind :lava}
                    {:x 563 :y 204 :w 105 :h 78 :kind :lava}
                    {:x 292 :y 318 :w 105 :h 78 :kind :lava}
                    {:x 563 :y 318 :w 105 :h 78 :kind :lava}]
          :spawns spawn-points :drops drop-points}})

(defn arena-id [id] (if (contains? arenas id) id :patio))

(defn terrain-at [arena x y]
  (some (fn [{ox :x oy :y w :w h :h kind :kind}]
          (when (and (<= ox x (+ ox w)) (<= oy y (+ oy h))) kind))
        (get-in arenas [(arena-id arena) :hazards])))
