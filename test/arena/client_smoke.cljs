(ns arena.client-smoke
  "Ejecuta el bundle real con adaptadores DOM/Canvas simulados, sin navegador.
   Comprueba arranque, controles, cooldown y QR; no valida el aspecto visual."
  (:require [cljs.nodejs :as nodejs] [cljs.reader :as reader]
            [clojure.string :as str] [arena.engine :as e]))
(def fs (js/require "node:fs"))
(def vm (js/require "node:vm"))
(def check (js/require "node:assert/strict"))
(def elements (atom {}))
(def listeners (atom {}))
(def clock (atom 0))
(def frames (atom []))
(def draws (atom 0))
(def bullet-heads (atom []))
(def canvas-depth (atom 0))
(def reduced-motion-mode (atom false))
(def console-errors (atom []))
(def document-events (atom {}))
(def audio-files (atom #{}))
(def audio-starts (atom 0))
(def volume-values (atom []))
(def fullscreen-count (atom 0))
(def intervals (atom {}))
(def test-socket (atom nil))
(def sent-messages (atom []))
(def noop (fn [& _]))
(defn mock-websocket [_]
  (this-as self
    (set! (.-readyState self) 1)
    (set! (.-send self) #(swap! sent-messages conj (reader/read-string %)))
    ;; Cierre sin callback, como una conexión TCP que aún no detecta la pérdida.
    (set! (.-close self) #(set! (.-readyState self) 3))
    (reset! test-socket self)
    self))
(aset mock-websocket "OPEN" 1)
(defn mock-audio []
  (this-as self
    (set! (.-state self) "running")
    (js/Object.defineProperty self "currentTime" #js {:get #(/ @clock 1000)})
    (set! (.-createGain self) (fn [] #js {:connect noop :disconnect noop :gain #js {:setTargetAtTime (fn [v & _] (swap! volume-values conj v))}}))
    (set! (.-decodeAudioData self) (fn [bytes] (js/Promise.resolve bytes)))
    (set! (.-createBufferSource self) (fn [] #js {:connect noop :disconnect noop :start #(swap! audio-starts inc)}))
    self))
(def context2d
  (js/Proxy. #js {} #js {:get (fn [target key]
                              (or (aget target key)
                                  (fn [& args]
                                    (swap! draws inc)
                                    (doseq [arg args :when (number? arg)]
                                      (check (js/Number.isFinite arg) (str "Canvas " key " recibió " arg)))
                                    (case key
                                      "save" (swap! canvas-depth inc)
                                      "restore" (do (swap! canvas-depth dec) (check (>= @canvas-depth 0)))
                                      "arc" (when (= 3.5 (nth args 2)) (swap! bullet-heads conj (vec (take 2 args))))
                                      nil))))}))
(defn element [id]
  (or (get @elements id)
      (let [callbacks (atom {}) classes (atom #{}) props (atom {}) children (atom [])
            el #js {:id id :textContent "" :className "" :value "" :hidden false :disabled false
                    :style #js {:setProperty (fn [k v] (swap! props assoc k v))}
                    :classList #js {:add #(swap! classes conj %) :remove #(swap! classes disj %)
                                    :toggle (fn [k v] (swap! classes (if v conj disj) k))}
                    :addEventListener (fn [kind f] (swap! callbacks assoc kind f))
                    :getAttribute (fn [k] (get @props k)) :setAttribute (fn [k v] (swap! props assoc k v))
                    :getContext (fn [& _] context2d)
                    :getBoundingClientRect (fn [] #js {:left 0 :top 0 :width 960 :height 600})
                    :focus noop :select noop :close noop :showModal noop :remove noop
                    :appendChild #(swap! children conj %) :prepend #(swap! children conj %)
                    :replaceChildren (fn [& nodes] (reset! children (vec nodes)))
                    :callbacks callbacks :properties props :children children}]
        (swap! elements assoc id el) el)))
(def document #js {:getElementById element :querySelectorAll (fn [_] #js []) :querySelector (fn [_] nil)
                   :createElement (fn [_] (element (str (random-uuid))))
                   :createElementNS (fn [& _] (element (str (random-uuid))))
                   :addEventListener (fn [kind f] (swap! document-events assoc kind f)) :execCommand (fn [_] true)
                   :documentElement #js {}})
(defn fullscreen! [active]
  (aset document "fullscreenElement" (when active #js {}))
  ((get @document-events "fullscreenchange"))
  (js/Promise.resolve nil))
(aset (.-documentElement document) "requestFullscreen" #(do (swap! fullscreen-count inc) (fullscreen! true)))
(aset document "exitFullscreen" #(fullscreen! false))
(def sandbox #js {:document document :console #js {:log noop :error #(swap! console-errors conj %) :warn #(swap! console-errors conj %)}
                  :location #js {:protocol "http:" :origin "http://localhost:8080" :hostname "localhost" :host "localhost:8080" :search ""}
                  :navigator #js {} :performance #js {:now (fn [] @clock)}
                  :localStorage #js {:getItem (fn [_] nil) :setItem noop}
                  :setTimeout (fn [& _] 1) :clearTimeout noop :setInterval (fn [f ms] (swap! intervals assoc ms f) 1)
                  :requestAnimationFrame #(reset! frames [%])
                  :matchMedia (fn [_] (let [m #js {}] (js/Object.defineProperty m "matches" #js {:get #(boolean @reduced-motion-mode)}) m))
                  :addEventListener (fn [kind f] (swap! listeners assoc kind f))
                  :URLSearchParams js/URLSearchParams
                  :AudioContext mock-audio
                  :WebSocket mock-websocket
                  :fetch (fn [url]
                           (let [bytes (.readFileSync fs (str "public/" url))]
                             (check (= "OggS" (.toString (.subarray bytes 0 4))))
                             (swap! audio-files conj url)
                             (js/Promise.resolve #js {:ok true :arrayBuffer #(js/Promise.resolve bytes)})))
                  :Image (fn [] (this-as self (set! (.-complete self) true) (set! (.-naturalWidth self) 256)))})
(aset sandbox "window" sandbox)
(.createContext vm sandbox)
(.runInContext vm (.readFileSync fs "public/vendor/qrcodegen.js" "utf8") sandbox)
(.runInContext vm (.readFileSync fs "public/client.js" "utf8") sandbox)
(defn click! [id] ((get @(aget (element id) "callbacks") "click") #js {}))
(defn key! [key] ((get @listeners "keydown") #js {:key key :repeat false :preventDefault noop}))
(defn step! [n]
  (dotimes [_ n] (swap! clock + 16.667) (when-let [f (first @frames)] (f @clock))))
(defn text [id] (.-textContent (element id)))
(defn tree-text [el]
  (str (.-textContent el) (apply str (map tree-text @(aget el "children")))))
(defn receive! [message] ((.-onmessage @test-socket) #js {:data (pr-str message)}))
(defn animation-checks! []
  (click! "leave-game")
  (receive! {:type :welcome :id "self" :info {:scope :lan} :rooms []})
  (receive! {:type :joined :id "self" :code "ANIM01"})
  (let [w (-> (e/world :arena 1000) (e/add-player "self" "Aquí" "brasa") (e/start-round 1000))
        shot {:id 1 :owner "self" :x 300 :y 300 :origin-x 250 :origin-y 300
              :angle 0 :vx 600 :vy 0 :born 900 :until 2400 :damage 20}
        snap (fn [t x] {:type :snapshot :code "ANIM01" :host "self" :name "Animación" :visibility :lan
                       :world (assoc w :time t :shots [(assoc shot :x x)])})]
    (receive! (snap 1000 300)) (receive! (snap 1050 330)) (receive! (snap 1100 360))
    (reset! bullet-heads []) (step! 4)
    (let [xs (mapv first @bullet-heads)]
      (check (= 4 (count xs)))
      (check (apply < xs) (str "La bala debe avanzar en cada RAF entre instantáneas: " xs))
      (check (< (last xs) 360) "El render no adelanta la bala más allá del servidor"))
    (reset! bullet-heads [])
    (receive! (assoc-in (snap 1150 390) [:world :shots] [])) (step! 1)
    (check (empty? @bullet-heads) "El impacto autoritativo retira la bala inmediatamente")
    (check (= "100 / 100" (text "health-text")))
    (reset! reduced-motion-mode true) (step! 3) (reset! reduced-motion-mode false)
    (check (zero? @canvas-depth) "Los save/restore de Canvas quedan balanceados")
    (println "ANIMACIÓN CLIENTE: balas continuas entre paquetes, retirada al impacto, Canvas finito y movimiento reducido correctos.")))

(defn presence-checks! []
  (click! "leave-game")
  (receive! {:type :welcome :id "self" :info {:scope :lan} :rooms []})
  (receive! {:type :joined :id "self" :code "TEST01"})
  (let [w (-> (e/world :arena 0) (e/add-player "self" "Aquí" "brasa") (e/add-player "gone" "Ausente" "mora"))
        snapshot {:type :snapshot :code "TEST01" :host "gone" :name "Prueba" :visibility :lan :world w}]
    (receive! snapshot)
    (check (str/includes? (tree-text (element "lobby-players")) "Ausente"))
    (receive! (assoc snapshot :host "self" :world (e/remove-player w "gone")))
    (check (not (str/includes? (tree-text (element "lobby-players")) "Ausente")))
    (receive! (assoc snapshot :code "OLD123"))
    (check (not (str/includes? (tree-text (element "lobby-players")) "Ausente"))
           "Una instantánea de otra sala no puede restaurar jugadores anteriores")
    (receive! (assoc snapshot :world (e/start-round w 0))) (step! 5)
    (check (str/includes? (tree-text (element "score-rows")) "Ausente"))
    (receive! (assoc snapshot :host "self" :world (e/remove-player (e/start-round w 0) "gone"))) (step! 5)
    (check (not (str/includes? (tree-text (element "score-rows")) "Ausente")))
    (receive! {:type :heartbeat})
    (check (= :heartbeat (:type (last @sent-messages))) "El cliente debe confirmar que sigue conectado")
    (receive! {:type :rooms :rooms [{:code "TEST01" :name "Sala vieja" :count 2 :phase :waiting :visibility :lan}]})
    (check (str/includes? (tree-text (element "room-list")) "Sala vieja"))
    (swap! clock + 13001)
    (check (fn? (get @intervals 1000)) "Debe existir un detector de conexión silenciosa")
    ((get @intervals 1000))
    (check (= "SIN CONEXIÓN" (text "connection")))
    (check (empty? @(aget (element "lobby-players") "children")))
    (check (empty? @(aget (element "score-rows") "children")))
    (check (not (str/includes? (tree-text (element "room-list")) "Sala vieja")))
    ;; Un paquete tardío del socket anterior no debe restaurar la sala borrada.
    (receive! {:type :joined :id "self" :code "TEST01"}) (receive! snapshot)
    (check (empty? @(aget (element "lobby-players") "children")))
    (println "PRESENCIA CLIENTE: espera, marcador, heartbeat y limpieza sin conexión correctos.")))
(set! (.-value (element "player-name")) "Smoke")
(set! (.-value (element "training-map")) "lab")
(click! "start-game")
(defn run-checks! []
(step! 5)
(check (= "100 / 100" (text "health-text")))
(check (= "LISTA" (text "ult-cooldown")))
(check (= "LABORATORIO AZUL" (text "match-title")))
(check (= "PULSA F · NOVA LISTA" (text "ult-active")))
(key! "q") (step! 3)
(check (= "60 / 60" (text "shield-text")))
(check (.startsWith (text "shield-active") "ACTIVO"))
(check (.endsWith (text "shield-cooldown") " s"))
(key! "Shift") (step! 5)
(check (not= "LISTO" (text "dash-cooldown")))
(key! "f") (step! 60)
(check (not= "LISTA" (text "ult-cooldown")))
(check (> @draws 1000))
(click! "restart-game") (step! 5)
(check (= "LISTA" (text "ult-cooldown")))
(click! "qr-open")
(click! "fullscreen")
(check (= 1 @fullscreen-count))
(check (= "SALIR DE PANTALLA COMPLETA" (text "fullscreen")))
(click! "fullscreen")
(check (= "PANTALLA COMPLETA" (text "fullscreen")))
(check (= 20 (count @audio-files)) (str "Muestras cargadas: " (count @audio-files)))
(check (contains? @audio-files "audio/confirmation_002.ogg"))
(check (pos? @audio-starts))
(set! (.-value (element "volume-setting")) "25")
((get @(aget (element "volume-setting") "callbacks") "input"))
(check (= 0.25 (last @volume-values)))
(set! (.-checked (element "sound-setting")) false)
((get @(aget (element "sound-setting") "callbacks") "change"))
(check (zero? (last @volume-values)))
(animation-checks!)
(presence-checks!)
(check (empty? @console-errors))
(println "CLIENTE: Canvas, mapas, Q, Shift, F, reinicio, QR, pantalla completa, 20 muestras, volumen y silencio correctos.")
(println "Adaptadores simulados: no sustituye revisión visual ni escucha en navegador."))
(js/setImmediate run-checks!)
