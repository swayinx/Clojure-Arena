(ns arena.engine-test
  (:require [clojure.test :refer [deftest is testing]] [arena.engine :as e]))
(defn match [] (-> (e/world :arena 0) (e/add-player "a" "A" "brasa")
                  (e/add-player "b" "B" "mora") (e/start-round 0) (assoc :time 2000)))
(deftest immutable-and-deterministic
  (let [w (match) input {"a" (assoc e/empty-input :dx 1)}
        next (e/step w input 0.025 2025)]
    (is (= 132 (get-in w [:players "a" :x])))
    (is (= 139.0 (get-in next [:players "a" :x])))
    (is (= next (e/step w input 0.025 2025)))
    (is (not (identical? w next)))))
(deftest dash-immunity-and-cooldown
  (let [w (e/activate (match) "a" :dash (assoc e/empty-input :dx 1))
        during (e/damage-player (assoc w :time 2179) "a" "b" 20 :shot)
        after (e/damage-player (assoc w :time 2180) "a" "b" 20 :shot)]
    (is (= 100 (get-in during [:players "a" :hp])))
    (is (= 80 (get-in after [:players "a" :hp])))
    (is (= w (e/activate w "a" :dash e/empty-input)))
    (is (= 4500 (get-in w [:players "a" :cooldowns :dash])))))
(deftest shield-before-health-and-heal-clamp
  (let [w (e/activate (match) "a" :shield e/empty-input)
        hit (e/damage-player w "a" "b" 65 :shot)
        healed (e/activate hit "a" :heal e/empty-input)]
    (is (= 95 (get-in hit [:players "a" :hp])))
    (is (zero? (get-in hit [:players "a" :shield])))
    (is (= 100 (get-in healed [:players "a" :hp])))
    (is (= (match) (e/activate (match) "a" :heal e/empty-input)))
    (is (= 12000 (get-in w [:players "a" :cooldowns :shield])))))
(deftest nova-is-telegraphed-and-dodgeable
  (let [w (-> (match) (assoc-in [:players "a" :ulti] 100)
              (assoc-in [:players "a" :x] 450) (assoc-in [:players "a" :y] 300)
              (assoc-in [:players "b" :x] 520) (assoc-in [:players "b" :y] 300))
        cast (e/activate w "a" :ult (assoc e/empty-input :ax 520 :ay 300))
        before (e/resolve-ultimates (assoc cast :time 2649))
        impact (e/resolve-ultimates (assoc cast :time 2650))
        dodged (e/resolve-ultimates (-> cast (assoc :time 2650) (assoc-in [:players "b" :dash-until] 2700)))]
    (is (zero? (get-in cast [:players "a" :ulti])))
    (is (= 100 (get-in before [:players "b" :hp])))
    (is (= 35 (get-in impact [:players "b" :hp])))
    (is (= 100 (get-in dodged [:players "b" :hp])))
    (is (= 100 (get-in impact [:players "a" :hp])))))
(deftest kills-respawn-and-round-end
  (let [w (-> (match) (assoc-in [:players "a" :kills] 11))
        dead (e/damage-player w "b" "a" 100 :shot)
        ended (e/step dead {} 0.025 2025)
        revived (e/lifecycle (assoc dead :time 4500) "b" 0.025)]
    (is (= 12 (get-in dead [:players "a" :kills])))
    (is (= 1 (get-in dead [:players "b" :deaths])))
    (is (false? (get-in dead [:players "b" :alive])))
    (is (= :finished (:phase ended)))
    (is (= "a" (:winner ended)))
    (is (= 100 (get-in revived [:players "b" :hp])))
    (is (true? (get-in revived [:players "b" :alive])))))
(deftest movement-collision-and-capacity
  (let [p (-> (e/player "a" "A" "brasa" 0 0) (assoc :x 274 :y 185))
        moved (e/move-player p (assoc e/empty-input :dx 1) 0.025 2000)
        w (reduce #(e/add-player %1 (str %2) "P" "brasa") (e/world :arena 0) (range 6))]
    (is (= 274 (:x moved)))
    (is (= 4 (count (:players w))))
    (let [{:keys [dx dy]} (e/direction 1 1)]
      (is (< (abs (- 1 (e/distance 0 0 dx dy))) 0.000001)))))
(deftest pickups-expire-and-weapons-revert
  (let [w (-> (match) (assoc :time 8000) e/drop-item)
        item (first (:pickups w))
        picked (-> w (assoc :time 9000) (assoc-in [:players "a" :x] (:x item))
                   (assoc-in [:players "a" :y] (:y item)) e/collect-items)
        expired (e/lifecycle (assoc picked :time 21000) "a" 0.025)]
    (is (= 1 (count (:pickups w))))
    (is (empty? (:pickups picked)))
    (is (= :rapid (get-in picked [:players "a" :weapon])))
    (is (= :pulse (get-in expired [:players "a" :weapon])))))
