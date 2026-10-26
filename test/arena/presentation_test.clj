(ns arena.presentation-test
  (:require [clojure.test :refer [deftest is]] [arena.presentation :as p]))
(def shot {:id 1 :owner "a" :x 80 :y 40 :vx 600 :vy 0 :born 0 :damage 20})
(def before {:time 100 :players {} :shots [shot]})
(def after (assoc before :time 150 :shots [(assoc shot :x 110)]))

(deftest shots-follow-render-frames
  (is (= [80.0 87.5 95.0 102.5 110.0]
         (mapv #(double (get-in (p/interpolate before after %) [:shots 0 :x])) [0 0.25 0.5 0.75 1])))
  (is (= 110 (get-in after [:shots 0 :x])) "El estado autoritativo permanece intacto")
  (is (= 20 (get-in (p/interpolate before after 0.5) [:shots 0 :damage])))
  (is (empty? (:shots (p/interpolate before (assoc after :shots []) 0.5))) "Un impacto confirmado elimina la bala"))

(deftest births-do-not-run-backward
  (let [old (assoc before :shots [])
        fresh (assoc after :shots [(assoc shot :born 125 :origin-x 100 :origin-y 40 :x 115)])]
    (is (empty? (:shots (p/interpolate old fresh 0))) "No dibujar una bala antes de nacer")
    (is (= 100.0 (double (get-in (p/interpolate old fresh 0.5) [:shots 0 :x]))))
    (is (= 107.5 (get-in (p/interpolate old fresh 0.75) [:shots 0 :x])))))

(def actor {:id "a" :alive true :spawn-until 0 :respawn-at 0 :x 80 :y 40 :angle 3.1 :hp 100})
(defn world-at [t x] {:time t :players {"a" (assoc actor :x x)} :shots []})
(defn near? [a b] (< (abs (- a b)) 0.000001))

(deftest angles-respawn-and-current-authority
  (let [a (world-at 100 80) b (-> (world-at 150 110) (assoc-in [:players "a" :angle] -3.1))
        mid (p/interpolate a b 0.5)]
    (is (near? Math/PI (get-in mid [:players "a" :angle])) "Giro corto al cruzar ±π")
    (is (= 95.0 (get-in mid [:players "a" :x])))
    (is (= 110 (get-in (p/interpolate a (assoc-in b [:players "a" :spawn-until] 1500) 0.5) [:players "a" :x])))
    (is (= 500 (get-in (p/interpolate a (assoc-in b [:players "a" :x] 500) 0.5) [:players "a" :x]))))
  (let [a (assoc before :players {"a" actor}) b (assoc after :players {"a" (assoc actor :x 110)})
        latest (-> b (assoc :time 200 :shots []) (assoc-in [:players "a" :hp] 25))]
    (is (= 25 (get-in (p/sample [a b latest] 125 latest) [:players "a" :hp])))
    (is (empty? (:shots (p/sample [a b latest] 125 latest))) "No resucita proyectiles que ya impactaron")
    (is (empty? (:players (p/sample [a b] 125 (assoc latest :players {})))) "No resucita jugadores desconectados")
    (is (false? (get-in (p/sample [a b] 125 (assoc-in latest [:players "a" :alive] false)) [:players "a" :alive])))))

(deftest jitter-buffer-and-frame-independence
  (let [snapshots (reduce p/remember [] (map #(world-at % %) (range 0 601 50)))]
    (is (= 8 (count snapshots)))
    (is (= 600 (:time (last (p/remember snapshots (world-at 600 700))))))
    (is (= 8 (count (p/remember snapshots (world-at 600 700)))))
    (is (== 375.0 (get-in (p/sample snapshots 375 (last snapshots)) [:players "a" :x]))))
  (let [frames (reductions (fn [cursor [latest age]] (p/advance-clock cursor latest age 16.667))
                          0 [[100 0] [100 16] [100 33] [150 0] [150 17] [150 33] [150 50] [250 0]])]
    (is (apply <= frames) "El cursor no retrocede al llegar mensajes con jitter"))
  (is (= 100 (p/advance-clock 100 100 5000 16)))
  (let [particle {:x 0 :y 0 :vx 600 :vy -100}
        run (fn [fps] (nth (iterate #(p/drift % (/ 1.0 fps)) particle) fps))
        damp (fn [fps] (nth (iterate #(p/damp % 0 9 (/ 1.0 fps)) 10) fps))]
    (doseq [fps [30 60 144]]
      (is (near? (:x (run 60)) (:x (run fps))))
      (is (near? (damp 60) (damp fps))))))

(deftest cosmetic-clock-does-not-rewind-with-ping
  (let [frames (reductions #(p/follow-time %1 %2 16) 1000 [1016 1032 1020 1036 1068 1080])]
    (is (apply <= frames)))
  (is (<= (nth (iterate #(p/follow-time % 1000 16) 1000) 1000) 1030))
  (is (= 5000 (p/follow-time 1000 5000 16)) "Reanudar tras pausa no recupera segundos de efectos"))
