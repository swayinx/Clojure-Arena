(ns arena.client
  "Entrada del navegador, HUD, predicción local y transporte WebSocket/EDN."
  (:require [arena.engine :as e] [arena.view :as view] [arena.presentation :as visual]
            [cljs.reader :as reader] [clojure.string :as str]))

(def by-id (memoize #(.getElementById js/document %)))
(defn text! [id value] (when-let [el (by-id id)] (when (not= (.-textContent el) (str value)) (set! (.-textContent el) (str value)))))
(defn hidden! [id hide?] (set! (.-hidden (by-id id)) (boolean hide?)))
(defn disable! [id v] (set! (.-disabled (by-id id)) (boolean v)))
(defn on! [id event f] (.addEventListener (by-id id) event f))
(defn css! [el prop value] (.setProperty (.-style el) prop (str value)))
(defn node [tag class text]
  (let [el (.createElement js/document tag)] (set! (.-className el) class) (set! (.-textContent el) text) el))
(defn clear! [el] (.replaceChildren el))
(defn perf [] (js/performance.now))
(defonce client (atom {:mode :none :screen "inicio" :skin "brasa" :you "you" :online false :rtt 0
                       :world nil :previous nil :received-at 0 :pending [] :seq 0 :rooms [] :info {}}))
(defonce socket (atom nil))
(defonce last-message (atom 0))
(defonce keys-down (atom #{}))
(defonce mouse (atom {:x 650 :y 300 :down false}))
(defonce actions (atom []))
(defonce toast-timer (atom nil))
(defonce announce-timer (atom nil))
(defonce last-frame (atom 0))
(defonce accumulator (atom 0))
(defonce last-hud (atom 0))
(defonce frame-stats (atom {:since 0 :frames 0 :fps 0}))
(defonce room-render-key (atom nil))
(defonce score-render-key (atom nil))
(defonce announced-event (atom 0))
(defonce pending-invite (atom (some-> (js/URLSearchParams. (.-search js/location)) (.get "room") str/upper-case)))
(defn save-preferences! []
  (try (.setItem js/localStorage "clojure-arena/preferences"
                 (pr-str {:skin (:skin @client) :name (.-value (by-id "player-name")) :settings @view/settings}))
       (catch :default _ nil)))
(defn toast! [message]
  (text! "toast" message) (hidden! "toast" false)
  (when @toast-timer (js/clearTimeout @toast-timer))
  (reset! toast-timer (js/setTimeout #(hidden! "toast" true) 3800)))
(defn announce! [message]
  (text! "announcer" message) (.add (.-classList (by-id "announcer")) "show")
  (when @announce-timer (js/clearTimeout @announce-timer))
  (reset! announce-timer (js/setTimeout #(.remove (.-classList (by-id "announcer")) "show") 2000)))
(defn clear-input! [] (reset! keys-down #{}) (reset! actions []) (swap! mouse assoc :down false))
(defn screen! [id]
  (clear-input!) (swap! client assoc :screen id)
  (doseq [el (array-seq (.querySelectorAll js/document ".screen"))]
    (.toggle (.-classList el) "active" (= (.-id el) id)))
  (when (= id "juego") (.focus view/canvas)))
(defn send! [message]
  (when (and @socket (= (.-readyState @socket) js/WebSocket.OPEN)) (.send @socket (pr-str message))))
(defn identity-message [] {:name (let [n (str/trim (.-value (by-id "player-name")))] (if (str/blank? n) "Jugador" n)) :skin (:skin @client)})
(defn join! [code] (save-preferences!) (send! (merge (identity-message) {:type :join :code code})))
(defn controls []
  {:dx (+ (if (contains? @keys-down "d") 1 0) (if (contains? @keys-down "a") -1 0))
   :dy (+ (if (contains? @keys-down "s") 1 0) (if (contains? @keys-down "w") -1 0))
   :ax (:x @mouse) :ay (:y @mouse) :fire (:down @mouse) :actions @actions})
(defn server-time []
  (let [s @client] (+ (get-in s [:world :time] 0) (min 180 (- (perf) (:received-at s))) (* 0.5 (:rtt s)))))

(def ability-descriptions
  {:dash ["Inmune 0,18 s" "DASH EVASIVO"] :shield ["60 puntos · 5 s" "ESCUDO"]
   :heal ["+35 de vida" "CURACIÓN"] :boost ["+15 daño · 6 s" "POTENCIA"] :ult ["Área · 65 daño" "NOVA / ULTI"]})
(def ability-icons
  {:dash "<path d='M3 8h6M2 12h4M3 16h6M11 5l7 7-7 7M17 5l6 7-6 7'/>"
   :shield "<path d='M12 2l8 3v6c0 5-4 9-8 11-4-2-8-6-8-11V5z'/><path d='M12 6v11'/>"
   :heal "<path d='M9 3h6v6h6v6h-6v6H9v-6H3V9h6z'/>"
   :boost "<path d='M13 2L4 14h7l-1 8 10-13h-7z'/><path d='M3 4h4M18 19h4'/>"
   :ult "<circle cx='12' cy='12' r='9'/><path d='M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z'/>"})
(defn build-abilities! []
  (doseq [power [:dash :shield :heal :boost :ult]]
    (let [a (get e/abilities power) b (node "button" (str "ability " (when (= power :ult) "ult")) "")
          suffix (name power) [description title] (get ability-descriptions power)]
      (set! (.-id b) (str "power-" suffix)) (.setAttribute b "data-power" suffix)
      (.setAttribute b "aria-label" (str (:name a) ", tecla " (:key a) ". " description))
      (set! (.-title b) (str title " · " description)) (css! b "--ability" (:color a))
      (set! (.-innerHTML b)
            (str "<span class='ability-ring'><svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round' aria-hidden='true'>"
                 (get ability-icons power) "</svg></span><kbd class='ability-key'>" (:key a) "</kbd>"
                 "<span class='ability-copy'><b>" (:name a) "</b><strong id='" suffix "-cooldown'>LISTO</strong><small>" description "</small></span>"
                 "<span class='ability-duration'><span id='" suffix "-active'>DISPONIBLE</span><span class='active-track'><i id='" suffix "-bar'></i></span></span>"))
      (.addEventListener b "click" #(do (swap! actions conj power) (view/resume-audio!) (.focus view/canvas)))
      (.appendChild (by-id "abilities") b))))

(defn render-rooms! [rooms]
  (let [container (by-id "room-list")]
    (clear! container)
    (if (empty? rooms)
      (.appendChild container (node "div" "empty-rooms" "Tu próxima partida empieza aquí.\nCrea una sala e invita a tus amigos."))
      (doseq [room rooms]
        (let [b (node "button" "room-entry" "") copy (node "span" "" "")
              full? (>= (:count room) 4) playing? (= (:phase room) :playing)]
          (.appendChild copy (node "b" "" (:name room)))
          (.appendChild copy (node "small" "" (str (:code room) " · " (if (= :lan (:visibility room)) "WI-FI" "INTERNET") (when playing? " · EN JUEGO"))))
          (.appendChild b copy) (.appendChild b (node "span" "" (str (:count room) "/4")))
          (set! (.-disabled b) (or full? playing?))
          (.addEventListener b "click" #(join! (:code room))) (.appendChild container b))))))

(defn invite-url []
  (let [{:keys [info visibility code]} @client
        local-origin (.-origin js/location)
        origin (if (= :internet visibility)
                 (or (:public-url info) local-origin)
                 (if (contains? #{"localhost" "127.0.0.1"} (.-hostname js/location))
                   (or (first (:lan-urls info)) local-origin) local-origin))]
    (str origin "/?room=" code)))
(defn refresh-invite! []
  (when (:code @client)
    (let [url (invite-url) online-room? (= :internet (:visibility @client))
          public? (or (:public-url (:info @client)) (= "https:" (.-protocol js/location)))]
      (set! (.-value (by-id "invite-url")) url)
      (text! "invite-hint" (if (and online-room? (not public?))
                             "Falta el túnel: ejecuta bb internet para obtener un enlace público."
                             (if online-room? "Acceso por Internet mientras el anfitrión esté encendido." "Solo equipos conectados a la misma red Wi-Fi."))))))
(defn refresh-lobby! []
  (let [{:keys [world host you code visibility name]} @client
        signature [(:players world) host code visibility]]
    (when (not= signature @room-render-key)
      (reset! room-render-key signature)
      (text! "lobby-title" (str/upper-case (or name "ARENA")))
      (text! "room-mode" (if (= :internet visibility) "SALA DE INTERNET" "MISMA WI-FI"))
      (text! "lobby-code" code)
      (clear! (by-id "lobby-players"))
      (doseq [slot (range 4)]
        (let [p (first (filter #(= slot (:slot %)) (vals (:players world))))
              card (node "div" (str "lobby-player" (when-not p " empty")) "")]
          (.appendChild card (node "span" "seat-number" (str "0" (inc slot))))
          (if p
            (do (.appendChild card (node "span" (str "sprite " (:skin p)) ""))
                (.appendChild card (node "h3" "" (:name p)))
                (.appendChild card (node "p" "" (str (when (= you (:id p)) "TÚ · ") (if (= host (:id p)) "CREADOR" "CONECTADO")))))
            (do (.appendChild card (node "span" "empty-seat" "+"))
                (.appendChild card (node "p" "" "ESPERANDO JUGADOR"))))
          (.appendChild (by-id "lobby-players") card)))
      (let [count (count (:players world)) mine? (= you host)]
        (disable! "start-round" (or (not mine?) (< count 2)))
        (text! "lobby-status" (if mine? (str count "/4 conectados. " (if (< count 2) "Invita a alguien para empezar." "Puedes iniciar la ronda.")) "El creador iniciará la partida."))
        (text! "start-round" (if mine? "INICIAR PARTIDA" "ESPERANDO AL CREADOR")))
      (refresh-invite!))))

(defn seconds [ms] (str (.toFixed (max 0 (/ ms 1000)) 1) " s"))
(defn match-clock [ms]
  (let [s (max 0 (js/Math.ceil (/ ms 1000)))] (str (js/Math.floor (/ s 60)) ":" (.padStart (str (mod s 60)) 2 "0"))))
(defn refresh-score! [world]
  (let [signature [(:phase world) (:host @client) (:you @client)
                   (mapv #(select-keys % [:id :name :kills :deaths :damage]) (e/ranking world))]]
    (when (not= signature @score-render-key)
      (reset! score-render-key signature) (clear! (by-id "score-rows"))
      (doseq [p (e/ranking world)]
        (let [row (node "div" (str "score-row" (when (= (:you @client) (:id p)) " me")) "")]
          (.appendChild row (node "b" "" (:name p)))
          (doseq [v [(:kills p) (:deaths p) (:damage p)]] (.appendChild row (node "span" "" (str v))))
          (.appendChild (by-id "score-rows") row)))
      (hidden! "round-actions" (not= :finished (:phase world)))
      (text! "scoreboard-title" (if (= :finished (:phase world)) "RONDA TERMINADA" "MARCADOR"))
      (when (= :finished (:phase world))
        (let [winner (get-in world [:players (:winner world)])]
          (text! "winner-text" (if winner (str (:name winner) " gana la ronda") "Empate en la arena"))
          (disable! "rematch" (not= (:host @client) (:you @client))))))))

(defn update-hud! [world now]
  (when-let [p (get-in world [:players (:you @client)])]
    (let [training? (= :training (:mode @client))]
      (text! "hud-name" (str/upper-case (:skin p))) (text! "score" (str (:kills p) " BAJAS"))
      (text! "health-text" (str (:hp p) " / 100")) (css! (by-id "health-bar") "width" (str (:hp p) "%"))
      (text! "shield-text" (str (:shield p) " / 60")) (css! (by-id "shield-bar") "width" (str (* (/ (:shield p) 60) 100) "%"))
      (text! "weapon-label" (str (get-in e/weapons [(:weapon p) :name])
                                     (if (= :pulse (:weapon p)) " · CLIC PARA DISPARAR" (str " · " (seconds (- (:weapon-until p) now))))))
      (text! "match-time" (if training? "LIBRE" (match-clock (- (:round-end world) now))))
      (text! "match-mode" (if training? "ENTRENAMIENTO" (str "SALA " (:code @client))))
      (text! "match-goal" (if training? "PRUEBA TUS HABILIDADES" "12 BAJAS / 3 MIN"))
      (text! "net-stats" (if training? "LOCAL" (str (js/Math.round (:rtt @client)) " ms")))
      (text! "render-stats" (str (:fps @frame-stats) " FPS"))
      (hidden! "restart-game" (not training?))
      (hidden! "death-overlay" (:alive p))
      (when-not (:alive p) (text! "respawn-time" (.toFixed (max 0 (/ (- (:respawn-at p) now) 1000)) 1)))
      (doseq [power [:dash :shield :heal :boost :ult]]
        (let [a (get e/abilities power) suffix (name power) card (by-id (str "power-" suffix))
              ulti? (= power :ult) cd (max 0 (- (get-in p [:cooldowns power] 0) now))
              active-until (case power :dash (:dash-until p) :shield (if (pos? (:shield p)) (:shield-until p) 0)
                                 :heal (:heal-until p) :boost (:boost-until p) :ult (:ult-at p))
              active-ms (max 0 (- active-until now))
              ready? (if ulti? (>= (:ulti p) 100) (zero? cd))
              progress (if ulti? (/ (:ulti p) 100) (- 1 (/ cd (:cooldown a))))
              active-pct (if ulti? (/ active-ms 650) (/ active-ms (:duration a)))
              status (cond ulti? (if ready? "LISTA" (str (js/Math.floor (:ulti p)) "%"))
                           ready? "LISTO" :else (seconds cd))
              active-label (cond (pos? active-ms) (str (if ulti? "IMPACTO " "ACTIVO ") (seconds active-ms))
                                 ulti? "CARGA AL COMBATIR"
                                 (and (= power :heal) (= 100 (:hp p))) "VIDA COMPLETA"
                                 ready? "DISPONIBLE" :else "RECARGANDO")]
          (css! card "--progress" (e/clamp progress 0 1))
          (.toggle (.-classList card) "ready" ready?)
          (.toggle (.-classList card) "cooling" (not ready?))
          (.toggle (.-classList card) "is-active" (pos? active-ms))
          (.setAttribute card "aria-label" (str (:name a) ", " (:key a) ", " status ", " active-label))
          (text! (str suffix "-cooldown") status) (text! (str suffix "-active") active-label)
          (css! (by-id (str suffix "-bar")) "width" (str (* 100 (e/clamp (if (and ulti? (zero? active-ms)) progress active-pct) 0 1)) "%"))))
      (refresh-score! world))))

(defn present-events! [world now]
  (let [events (filter #(> (:id %) @announced-event) (:events world)) you (:you @client)]
    (doseq [event events]
      (case (:kind event)
        :round-start (announce! "LA ARENA ES TUYA")
        :drop (announce! "SUMINISTRO EN CAMINO")
        :kill (do
                (let [attacker (get-in world [:players (:attacker event) :name] "Arena")
                      victim (get-in world [:players (:player event) :name] "Jugador")
                      line (node "div" "kill-line" (str attacker "  /  " victim))]
                  (.prepend (by-id "kill-feed") line)
                  (when (> (.-childElementCount (by-id "kill-feed")) 3) (.remove (.-lastElementChild (by-id "kill-feed"))))
                  (js/setTimeout #(.remove line) 4500))
                (when (= you (:attacker event)) (announce! "BAJA CONFIRMADA")))
        :ult-cast (when (= you (:player event)) (text! "game-message" "Nova fijada. El área marcada explota en 0,65 s."))
        :pickup (when (= you (:player event)) (text! "game-message" "Mejora recogida. Mira tu arma y tus recursos."))
        :dash (when (= you (:player event)) (text! "game-message" "Dash: inmunidad durante 0,18 s. Recarga de 2,5 s."))
        :shield (when (= you (:player event)) (text! "game-message" "Escudo activo 5 s. La recarga de 10 s ya comenzó."))
        :heal (when (= you (:player event)) (text! "game-message" "Vida recuperada. Curación vuelve en 12 s."))
        :boost (when (= you (:player event)) (text! "game-message" "Potencia activa 6 s. +15 de daño por proyectil."))
        nil))
    (when (seq events) (reset! announced-event (:event-seq world)))
    (view/consume! world you (perf))))

(defn predicted-player [p input dt now]
  (if (and p (:alive p))
    (let [w {:players {(:id p) p} :time now :events [] :event-seq 0}
          w (if (some #{:dash} (:actions input)) (e/activate w (:id p) :dash input) w)]
      (e/move-player (get-in w [:players (:id p)]) input dt now)) p))
(defn begin-match! []
  (view/reset!fx) (reset! frame-stats {:since (perf) :frames 0 :fps 0}) (reset! announced-event 0) (reset! accumulator 0) (reset! score-render-key nil)
  (clear! (by-id "kill-feed")) (hidden! "scoreboard" true) (screen! "juego") (view/resume-audio!))
(defn start-training! []
  (when (= :online (:mode @client)) (send! {:type :leave}))
  (save-preferences!)
  (let [world (e/training (:name (identity-message)) (:skin @client) 0)]
    (swap! client assoc :mode :training :world world :previous world :you "you" :code nil :pending [] :predicted nil :snapshots [] :render-time nil :display-time nil :correction {:x 0 :y 0})
    (begin-match!) (update-hud! world 0) (announce! "ENTRENAMIENTO · NOVA LISTA EN F")))
(defn clear-room! []
  (swap! client assoc :mode :none :world nil :previous nil :code nil :host nil :pending [] :predicted nil :snapshots [] :render-time nil :display-time nil :correction {:x 0 :y 0})
  (reset! room-render-key nil) (reset! score-render-key nil)
  (doseq [id ["lobby-players" "score-rows" "kill-feed"]] (clear! (by-id id)))
  (hidden! "scoreboard" true))
(defn leave! []
  (when (= :online (:mode @client)) (send! {:type :leave}))
  (clear-room!)
  (screen! "inicio") (send! {:type :list}))

(declare connect!)
(defn receive! [message]
  (case (:type message)
    :welcome
    (do (swap! client assoc :online true :connection-id (:id message) :info (:info message) :rooms (:rooms message))
        (text! "connection" "ANFITRIÓN CONECTADO") (.add (.-classList (by-id "connection")) "online")
        (disable! "create-room" false) (disable! "join-room" false)
        (when (= :internet (get-in message [:info :scope]))
          (set! (.-value (by-id "room-visibility")) "internet")
          (set! (.-disabled (.-firstElementChild (by-id "room-visibility"))) true))
        (render-rooms! (:rooms message))
        (when @pending-invite
          (set! (.-value (by-id "room-code")) @pending-invite)
          (toast! "Invitación cargada. Elige tu nombre y pulsa UNIRME.")
          (reset! pending-invite nil)))
    :rooms (do (swap! client assoc :rooms (:rooms message)) (render-rooms! (:rooms message)))
    :joined (do (clear-room!) (swap! client assoc :you (:id message) :code (:code message) :mode :online :pending [] :seq 0)
                (screen! "sala"))
    :snapshot
    (when (and (= :online (:mode @client)) (= (:code message) (:code @client)))
      (let [s @client w (:world message) t (perf) old (:world s)
            starting? (and (= :playing (:phase w)) (not= :playing (:phase old)))
            ack (get (:acks message) (:you s) -1)
            pending (filterv #(> (:seq %) ack) (:pending s))
            p (reduce (fn [p h] (predicted-player p (:input h) 0.025 (:time h)))
                      (get-in w [:players (:you s)]) pending)
            old-p (:predicted s)
            correction (if (and (not starting?) (visual/same-life? old-p p)
                                (< (e/distance (:x old-p) (:y old-p) (:x p) (:y p)) 64))
                         {:x (e/clamp (+ (get-in s [:correction :x] 0) (- (:x old-p) (:x p))) -64 64)
                          :y (e/clamp (+ (get-in s [:correction :y] 0) (- (:y old-p) (:y p))) -64 64)} {:x 0 :y 0})]
        (swap! client assoc :world w :previous (or old w) :received-at t :pending pending :predicted p
               :code (:code message) :host (:host message) :name (:name message) :visibility (:visibility message)
               :snapshots (visual/remember (if starting? [] (:snapshots s)) w)
               :render-time (when-not starting? (:render-time s))
               :display-time (when-not starting? (:display-time s)) :correction correction)
        (when starting? (begin-match!))
        (when (or (= :waiting (:phase w)) (= "sala" (:screen @client))) (refresh-lobby!))
        (when (= :finished (:phase w))
          (clear-input!) (refresh-score! w)
          (when (= "juego" (:screen @client)) (hidden! "scoreboard" false)))
        (when (= "juego" (:screen @client)) (present-events! w (server-time)))))
    :left (when (= :online (:mode @client)) (clear-room!) (screen! "inicio"))
    :heartbeat (send! {:type :heartbeat})
    :pong (when (number? (:sent message)) (swap! client assoc :rtt (- (perf) (:sent message))))
    :error (toast! (:message message))
    nil))
(defn connection-lost! [ws]
  (when (identical? ws @socket)
    ;; Un cierre tardío de un socket anterior no puede borrar la conexión nueva.
    (reset! socket nil)
    (swap! client assoc :online false :rooms []) (text! "connection" "SIN CONEXIÓN")
    (.remove (.-classList (by-id "connection")) "online")
    (disable! "create-room" true) (disable! "join-room" true)
    (render-rooms! [])
    (when (= :online (:mode @client))
      (leave!) (toast! "Se perdió la conexión. Vuelve a unirte a una sala al reconectar."))
    (js/setTimeout connect! 3000)))
(defn check-connection! []
  (when-let [ws @socket]
    (when (> (- (perf) @last-message) 12000)
      (connection-lost! ws) (.close ws))))
(defn connect! []
  (when (and (nil? @socket) (contains? #{"http:" "https:"} (.-protocol js/location)))
    (let [ws (js/WebSocket. (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://") (.-host js/location) "/ws"))]
      (reset! socket ws)
      (reset! last-message (perf))
      (set! (.-onmessage ws)
        (fn [event]
          (when (identical? ws @socket)
            (try (let [message (reader/read-string (.-data event))]
                   (reset! last-message (perf)) (receive! message))
                 (catch :default err (js/console.error "Protocolo de sala:" err))))))
      (set! (.-onclose ws) (fn [_] (connection-lost! ws)))
      (set! (.-onerror ws) (fn [_] nil)))))

(defn copy-invite! []
  (let [url (invite-url)]
    (if (and (.-clipboard js/navigator) (.-isSecureContext js/window))
      (-> (.writeText (.-clipboard js/navigator) url) (.then #(toast! "Enlace copiado."))
          (.catch #(toast! "Selecciona y copia el enlace de invitación.")))
      (do (.select (by-id "invite-url"))
          (if (.execCommand js/document "copy") (toast! "Enlace copiado.") (toast! "Pulsa Ctrl+C para copiar el enlace seleccionado."))))))
(defn show-qr! []
  (let [library (aget js/window "qrcodegen") klass (aget library "QrCode")
        qr (.call (aget klass "encodeText") klass (invite-url) (aget (aget klass "Ecc") "MEDIUM"))
        size (aget qr "size") get-module (aget qr "getModule")
        path (apply str (for [y (range size) x (range size) :when (.call get-module qr x y)]
                          (str "M" (+ x 4) "," (+ y 4) "h1v1h-1z")))
        svg (.createElementNS js/document "http://www.w3.org/2000/svg" "svg")
        shape (.createElementNS js/document "http://www.w3.org/2000/svg" "path")]
    (.setAttribute svg "viewBox" (str "0 0 " (+ size 8) " " (+ size 8)))
    (.setAttribute svg "shape-rendering" "crispEdges") (.setAttribute svg "aria-label" "Código QR de invitación")
    (.setAttribute shape "d" path) (.setAttribute shape "fill" "#102027") (.appendChild svg shape)
    (.replaceChildren (by-id "qr-code") svg) (text! "qr-link" (invite-url)) (.showModal (by-id "qr-dialog"))))

(defn toggle-fullscreen! []
  (if (.-fullscreenElement js/document)
    (.exitFullscreen js/document)
    (if (.-requestFullscreen (.-documentElement js/document))
      (-> (.requestFullscreen (.-documentElement js/document))
          (.catch (fn [_] (toast! "El navegador no permitió pantalla completa. Usa F11."))))
      (toast! "Usa F11 para activar pantalla completa en tu navegador."))))

(defn bind! []
  (on! "start-game" "click" start-training!) (on! "restart-game" "click" start-training!)
  (on! "leave-game" "click" leave!) (on! "leave-room" "click" leave!)
  (on! "create-room" "click" #(do (save-preferences!) (send! (merge (identity-message) {:type :create :room-name (.-value (by-id "room-name"))
                                                                                     :visibility (keyword (.-value (by-id "room-visibility")))}))))
  (on! "join-form" "submit" (fn [event] (.preventDefault event) (join! (.-value (by-id "room-code")))))
  (on! "refresh-rooms" "click" #(send! {:type :list}))
  (doseq [id ["start-round" "rematch"]] (on! id "click" #(do (view/resume-audio!) (send! {:type :start}))))
  (on! "return-room" "click" #(do (screen! "sala") (reset! room-render-key nil) (refresh-lobby!)))
  (on! "copy-invite" "click" copy-invite!) (on! "qr-open" "click" show-qr!)
  (on! "score-toggle" "click" #(hidden! "scoreboard" (not (.-hidden (by-id "scoreboard")))))
  (on! "score-close" "click" #(hidden! "scoreboard" true))
  (doseq [[button dialog] [["help-open" "help-dialog"] ["settings-open" "settings-dialog"] ["host-help" "host-dialog"]]]
    (on! button "click" #(do (clear-input!) (.showModal (by-id dialog)))))
  (doseq [el (array-seq (.querySelectorAll js/document "[data-close]"))]
    (.addEventListener el "click" #(.close (by-id (.getAttribute el "data-close")))))
  (doseq [el (array-seq (.querySelectorAll js/document "dialog"))]
    (.addEventListener el "close" #(when (= "juego" (:screen @client)) (.focus view/canvas))))
  (on! "fullscreen" "click" toggle-fullscreen!)
  (.addEventListener js/document "fullscreenchange"
    #(do (text! "fullscreen" (if (.-fullscreenElement js/document) "SALIR DE PANTALLA COMPLETA" "PANTALLA COMPLETA"))
         (.setAttribute (by-id "fullscreen") "aria-pressed" (str (boolean (.-fullscreenElement js/document))))))
  (doseq [card (array-seq (.querySelectorAll js/document "[data-fighter]"))]
    (.addEventListener card "click"
      (fn [] (let [skin (.getAttribute card "data-fighter")]
               (swap! client assoc :skin skin)
               (doseq [c (array-seq (.querySelectorAll js/document "[data-fighter]"))]
                 (let [active? (= skin (.getAttribute c "data-fighter"))]
                   (.toggle (.-classList c) "active" active?) (.setAttribute c "aria-pressed" (str active?))))
               (save-preferences!)))))
  (on! "player-name" "change" save-preferences!)
  (on! "shake-setting" "change" #(do (swap! view/settings assoc :shake (js/parseFloat (.-value (by-id "shake-setting")))) (save-preferences!)))
  (on! "particles-setting" "change" #(do (swap! view/settings assoc :particles (keyword (.-value (by-id "particles-setting")))) (save-preferences!)))
  (on! "volume-setting" "input" #(do (swap! view/settings assoc :volume (/ (js/parseFloat (.-value (by-id "volume-setting"))) 100))
                                    (view/resume-audio!) (save-preferences!)))
  (on! "sound-setting" "change" #(do (swap! view/settings assoc :sound (.-checked (by-id "sound-setting"))) (view/resume-audio!) (save-preferences!))))

(defn input-events! []
  (.addEventListener js/window "keydown"
    (fn [event]
      (when (and (= "juego" (:screen @client)) (nil? (.querySelector js/document "dialog[open]")))
        (let [key (.toLowerCase (.-key event)) power (get {"shift" :dash "q" :shield "e" :heal "r" :boost "f" :ult} key)]
          (when (contains? #{"w" "a" "s" "d" "shift" "q" "e" "r" "f" "tab" " "} key)
            (.preventDefault event) (swap! keys-down conj key)
            (when-not (.-repeat event)
              (view/resume-audio!)
              (when power
                (let [p (get-in @client [:world :players (:you @client)])]
                  (cond
                    (and (= :ult power) (< (:ulti p 0) 100)) (toast! "Nova se carga al hacer daño, eliminar y con el tiempo.")
                    (and (= :heal power) (= 100 (:hp p))) (text! "game-message" "Tu vida está completa. La curación no se consume.")
                    :else (swap! actions conj power))))
              (when (= key "tab") (hidden! "scoreboard" false))))))))
  (.addEventListener js/window "keyup"
    (fn [event] (let [key (.toLowerCase (.-key event))]
                  (swap! keys-down disj key)
                  (when (and (= key "tab") (not= :finished (get-in @client [:world :phase]))) (hidden! "scoreboard" true)))))
  (.addEventListener js/window "blur" clear-input!)
  (.addEventListener js/document "visibilitychange" clear-input!)
  (let [aim! (fn [event]
               (let [r (.getBoundingClientRect view/canvas)]
                 (swap! mouse assoc :x (e/clamp (* 960 (/ (- (.-clientX event) (.-left r)) (.-width r))) 0 960)
                        :y (e/clamp (* 600 (/ (- (.-clientY event) (.-top r)) (.-height r))) 0 600))))]
    (.addEventListener view/canvas "pointermove" aim!)
    (.addEventListener view/canvas "pointerdown"
      (fn [event] (when (= 0 (.-button event)) (aim! event) (swap! mouse assoc :down true) (view/resume-audio!) (.focus view/canvas))))
    (.addEventListener js/window "pointerup" #(swap! mouse assoc :down false))
    (.addEventListener view/canvas "contextmenu" #(.preventDefault %))))

(defn simulation-step! []
  (let [s @client input (controls)]
    (reset! actions [])
    (case (:mode s)
      :training (let [w (:world s) t (+ (:time w) e/step-ms) next (e/step w {"you" input} 0.025 t)]
                  (swap! client assoc :previous w :world next) (present-events! next t))
      :online (when (and (:online s) (= :playing (get-in s [:world :phase])))
                (let [seq-id (inc (:seq s)) t (server-time) p (predicted-player (:predicted s) input 0.025 t)]
                  (send! (assoc input :type :input :seq seq-id))
                  (swap! client assoc :seq seq-id :predicted p
                         :pending (view/cap (conj (:pending s) {:seq seq-id :input input :time t}) 40))))
      nil)))
(defn tick [now]
  (let [elapsed (min 100 (max 0 (- now @last-frame)))]
    (reset! last-frame now)
    (when (and (= "juego" (:screen @client)) (:world @client))
      (swap! frame-stats update :frames inc)
      (when (>= (- now (:since @frame-stats)) 1000)
        (swap! frame-stats #(assoc % :fps (js/Math.round (/ (* 1000 (:frames %)) (- now (:since %)))) :since now :frames 0)))
      (swap! accumulator + elapsed)
      (loop [] (when (>= @accumulator e/step-ms) (simulation-step!) (swap! accumulator - e/step-ms) (recur)))
      (let [{:keys [world previous mode you predicted received-at snapshots render-time display-time correction]} @client
            training? (= mode :training)
            t (if training? (+ (:time world) @accumulator) (visual/follow-time display-time (server-time) elapsed))
            cursor (when-not training? (visual/advance-clock render-time (:time world) (- now received-at) elapsed))
            display (if training? (visual/interpolate previous world (/ @accumulator e/step-ms))
                        (visual/sample snapshots cursor world))
            correction (when-not training? (into {} (map (fn [[k v]] [k (visual/damp v 0 22 (/ elapsed 1000))]) correction)))
            ;; Fracción de tick solo visual: la simulación sigue a 40 Hz.
            local (when (and (not training?) predicted (= :playing (:phase world)))
                    (predicted-player predicted (assoc (controls) :actions []) (/ @accumulator 1000) t))
            display (if local
                      (update-in display [:players you] merge
                                 (select-keys local [:x :y :angle :moving :dash-until :dash-dir])
                                 {:x (+ (:x local) (:x correction 0)) :y (+ (:y local) (:y correction 0))}) display)
            display (if (get-in display [:players you :alive])
                      (assoc-in display [:players you :angle]
                                (e/atan2 (- (:y @mouse) (get-in display [:players you :y]))
                                         (- (:x @mouse) (get-in display [:players you :x])))) display)]
        (when-not training? (swap! client assoc :render-time cursor :display-time t :correction correction))
        (view/draw! display you @mouse t (/ elapsed 1000) now)
        (when (> (- now @last-hud) 60) (update-hud! world t) (reset! last-hud now))))
    (js/requestAnimationFrame tick)))

(defn restore-preferences! []
  (try
    (when-let [stored (.getItem js/localStorage "clojure-arena/preferences")]
      (let [{:keys [skin name settings]} (reader/read-string stored)]
        (when (contains? e/skins skin) (swap! client assoc :skin skin))
        (when (string? name) (set! (.-value (by-id "player-name")) (subs name 0 (min 18 (count name)))))
        (when (map? settings) (swap! view/settings merge settings))))
    (catch :default _ nil))
  (when (view/reduced-motion?) (swap! view/settings assoc :shake 0 :particles :low))
  (set! (.-value (by-id "shake-setting")) (str (:shake @view/settings)))
  (set! (.-value (by-id "particles-setting")) (name (:particles @view/settings)))
  (set! (.-checked (by-id "sound-setting")) (:sound @view/settings))
  (set! (.-value (by-id "volume-setting")) (str (* 100 (:volume @view/settings))))
  (doseq [card (array-seq (.querySelectorAll js/document "[data-fighter]"))]
    (let [selected? (= (:skin @client) (.getAttribute card "data-fighter"))]
      (.toggle (.-classList card) "active" selected?) (.setAttribute card "aria-pressed" (str selected?)))))

(build-abilities!) (restore-preferences!) (bind!) (input-events!) (connect!)
(when (= "file:" (.-protocol js/location))
  (text! "connection" "ENTRENAMIENTO SIN RED")
  (text! "host-hint" "Para salas compartidas ejecuta bb serve y abre http://localhost:8080."))
(js/setInterval #(when (:online @client) (send! {:type :ping :sent (perf)})) 2000)
(js/setInterval check-connection! 1000)
(js/setInterval #(when (:online @client)
                   (-> (js/fetch "/api/info") (.then (fn [r] (.text r)))
                       (.then (fn [s] (swap! client assoc :info (reader/read-string s)) (refresh-invite!)))
                       (.catch (fn [_] nil)))) 5000)
(js/requestAnimationFrame tick)
