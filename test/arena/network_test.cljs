(ns arena.network-test
  "Prueba de integración contra un servidor real: 20 jugadores, cinco salas.
   Se ejecuta en Node 22+; el código de prueba también es ClojureScript."
  (:require [cljs.reader :as reader] [clojure.string :as str]))
(def origin (or (aget (.-env js/process) "ARENA_TEST_URL") "ws://localhost:8080/ws"))
(def clients (atom []))
(def rooms (atom []))
(def checks (atom 0))
(defn check! [value message]
  (when-not value (throw (js/Error. message)))
  (swap! checks inc) (println "OK" message))
(defn sleep [ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))
(defn await! [f description]
  (js/Promise.
    (fn [resolve reject]
      (let [started (js/Date.now)]
        (letfn [(poll [] (try
                          (if-let [v (f)] (resolve v)
                            (if (> (- (js/Date.now) started) 6000)
                              (reject (js/Error. (str "Timeout: " description))) (js/setTimeout poll 15)))
                          (catch :default ex (reject ex))))] (poll))))))
(defn message [c kind] (last (filter #(= kind (:type %)) @(:messages c))))
(defn send! [c m] (.send (:ws c) (pr-str m)))
(defn connect!
  ([] (connect! origin))
  ([url]
  (let [ws (js/WebSocket. url) c {:ws ws :messages (atom [])}]
    (swap! clients conj c)
    (set! (.-onmessage ws) (fn [event] (swap! (:messages c) #(vec (take-last 200 (conj % (reader/read-string (.-data event))))))))
    (set! (.-onerror ws) (fn [_] nil))
    (-> (await! #(message c :welcome) "welcome") (.then (fn [_] c))))))
(defn connect-n! [n] (js/Promise.all (into-array (repeatedly n connect!))))
(defn setup-room! [index]
  (-> (connect-n! 4)
      (.then
        (fn [arr]
          (let [members (vec (array-seq arr)) host (first members)]
            (send! host {:type :create :name (str "P" index "-0") :skin "brasa"
                         :room-name (str "Test " index) :visibility (if (even? index) :lan :internet)})
            (-> (await! #(message host :joined) "room created")
                (.then (fn [joined]
                         (let [code (:code joined)]
                           (doseq [[i c] (map-indexed vector (rest members))]
                             (send! c {:type :join :code code :name (str "P" index "-" (inc i))
                                       :skin (nth ["chispa" "mora" "bastion"] i)}))
                           (swap! rooms conj {:code code :members members :host host})
                           (await! #(every? (fn [c] (= 4 (count (get-in (message c :snapshot) [:world :players])))) members)
                                   "four players synchronized"))))))))))
(defn close-all! [] (doseq [c @clients] (.close (:ws c))))
(defn common-snapshot [members]
  (let [sets (map #(set (keep (fn [m] (when (= :snapshot (:type m)) (get-in m [:world :tick]))) @(:messages %))) members)
        common (filter #(every? (fn [s] (contains? s %)) sets) (first sets))
        tick (when (seq common) (apply max common))]
    (when tick (mapv (fn [c] (last (filter #(and (= :snapshot (:type %)) (= tick (get-in % [:world :tick]))) @(:messages c)))) members))))

(defn run-network! []
  (-> (reduce (fn [promise index] (.then promise (fn [_] (setup-room! index)))) (js/Promise.resolve nil) (range 5))
      (.then (fn [_]
               (check! (= 5 (count @rooms)) "Cinco salas, cuatro jugadores por sala")
               (connect!)))
      (.then (fn [extra]
               (send! extra {:type :create :room-name "Exceso" :visibility :lan})
               (-> (await! #(message extra :error) "sixth room refused")
                   (.then (fn [_]
                            (check! true "Se rechaza una sexta sala")
                            (reset! (:messages extra) [])
                            (send! extra {:type :join :code (:code (first @rooms))})
                            (await! #(message extra :error) "fifth player refused")))
                   (.then (fn [_] (check! true "Se rechaza el quinto jugador de una sala"))))))
      (.then (fn [_]
               (let [nonhost (second (:members (first @rooms)))]
                 (send! nonhost {:type :start})
                 (-> (await! #(message nonhost :error) "non-host start refused")
                     (.then (fn [_] (check! true "Solo el creador inicia la ronda")))))))
      (.then (fn [_]
               (doseq [r @rooms] (send! (:host r) {:type :start}))
               (await! #(every? (fn [r] (= :playing (get-in (message (:host r) :snapshot) [:world :phase]))) @rooms)
                       "five games running")))
      (.then (fn [_]
               (let [host (:host (first @rooms)) seq-id (atom 0)
                     interval (js/setInterval #(send! host {:type :input :seq (swap! seq-id inc)
                                                           :dx 1 :dy 0 :ax 800 :ay 112 :fire true
                                                           :actions (if (= @seq-id 1) [:shield :boost] [])}) 25)]
                 (-> (sleep 650) (.then (fn [_] (js/clearInterval interval) (send! host {:type :input :seq 100 :dx 0 :dy 0})))))))
      (.then (fn [_] (sleep 120)))
      (.then (fn [_]
               (doseq [r @rooms]
                 (let [snapshots (common-snapshot (:members r)) worlds (map :world snapshots)]
                   (check! (and (= 4 (count worlds)) (apply = worlds)) (str "Estado idéntico en los cuatro clientes: " (:code r)))))
               (let [r (first @rooms) id (:id (message (:host r) :welcome))
                     p (get-in (message (:host r) :snapshot) [:world :players id])
                     other (:host (second @rooms)) other-id (:id (message other :welcome))
                     other-p (get-in (message other :snapshot) [:world :players other-id])]
                 (check! (> (:x p) 160) "El servidor procesa movimiento y disparo")
                 (check! (= 60 (:shield p)) "Escudo sincronizado")
                 (check! (not (contains? (get-in (message other :snapshot) [:world :players]) id)) "Los jugadores no se filtran a otras salas")
                 (check! (= 132 (:x other-p)) "Mover una sala no altera otra")
                 (send! (:host r) {:type :leave})
                 (await! #(= 3 (count (get-in (message (second (:members r)) :snapshot) [:world :players]))) "leave propagates"))))
      (.then (fn [_]
               (let [r (first @rooms) follower (second (:members r)) snapshot (message follower :snapshot)]
                 (check! (not= (:host snapshot) (:id (message (:host r) :welcome))) "Creador transferido al salir")
                 (check! (= 3 (count (get-in snapshot [:world :players]))) "Salida propagada sin bloquear la partida"))
               (close-all!) (println (str "INTEGRACIÓN COMPLETA: " @checks " comprobaciones."))
               (js/setTimeout #(js/process.exit 0) 150)))
      (.catch (fn [ex] (js/console.error ex) (close-all!) (js/setTimeout #(js/process.exit 1) 100)))))
(defn run-internet! []
  (let [public-origin (str (str/replace (aget (.-env js/process) "ARENA_TEST_PUBLIC_URL") "https://" "wss://") "/ws")
        private-code (atom nil) internet-host (atom nil) internet-guest (atom nil)]
    (-> (connect!)
        (.then (fn [lan]
                 (send! lan {:type :create :name "LAN" :room-name "Solo Wi-Fi" :visibility :lan})
                 (await! #(message lan :joined) "local room")))
        (.then (fn [joined] (reset! private-code (:code joined)) (connect! public-origin)))
        (.then (fn [public]
                 (reset! internet-host public)
                 (check! (= :internet (get-in (message public :welcome) [:info :scope])) "El túnel se reconoce como Internet")
                 (check! (not-any? #(= @private-code (:code %)) (:rooms (message public :welcome))) "La sala LAN no se lista en Internet")
                 (send! public {:type :join :code @private-code})
                 (await! #(message public :error) "LAN inaccessible through tunnel")))
        (.then (fn [_]
                 (check! true "El túnel no permite entrar a la sala LAN")
                 (reset! (:messages @internet-host) [])
                 (send! @internet-host {:type :create :visibility :lan :room-name "No debe crearse"})
                 (await! #(message @internet-host :error) "LAN creation denied")))
        (.then (fn [_]
                 (check! true "No se crean salas LAN desde Internet")
                 (send! @internet-host {:type :create :visibility :internet :name "Internet A" :skin "mora" :room-name "Prueba pública"})
                 (await! #(message @internet-host :joined) "Internet room created")))
        (.then (fn [joined]
                 (-> (connect! public-origin)
                     (.then (fn [guest]
                              (reset! internet-guest guest)
                              (send! guest {:type :join :code (:code joined) :name "Internet B" :skin "chispa"})
                              (await! #(= 2 (count (get-in (message guest :snapshot) [:world :players]))) "two Internet clients"))))))
        (.then (fn [_]
                 (send! @internet-host {:type :start})
                 (await! #(= :playing (get-in (message @internet-guest :snapshot) [:world :phase])) "Internet round")))
        (.then (fn [_]
                 (check! true "Partida creada e iniciada mediante WSS por Cloudflare")
                 (send! @internet-host {:type :input :seq 1 :dx 1 :dy 0 :ax 800 :ay 112 :actions [:shield]})
                 (await! #(some (fn [p] (= 60 (:shield p))) (vals (get-in (message @internet-guest :snapshot) [:world :players]))) "Internet action synchronized")))
        (.then (fn [_]
                 (check! true "Escudo visible en el otro cliente a través del túnel")
                 (close-all!) (println (str "INTERNET COMPLETO: " @checks " comprobaciones.")) (js/setTimeout #(js/process.exit 0) 150)))
        (.catch (fn [ex] (js/console.error ex) (close-all!) (js/setTimeout #(js/process.exit 1) 100))))))
(if (aget (.-env js/process) "ARENA_TEST_PUBLIC_URL") (run-internet!) (run-network!))
