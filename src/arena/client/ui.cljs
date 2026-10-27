(ns arena.client.ui
  "Browser UI rendering, preferences, notifications, and presentation actions."
  (:require [arena.client.state :as state]
            [arena.engine :as e]
            [arena.view :as view]
            [cljs.reader :as reader]
            [clojure.string :as str]))

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
(defn save-preferences! []
  (try (.setItem js/localStorage "clojure-arena/preferences"
                 (pr-str {:skin (:skin @state/client) :name (.-value (by-id "player-name")) :settings @view/settings}))
       (catch :default _ nil)))
(defn toast! [message]
  (text! "toast" message) (hidden! "toast" false)
  (when @state/toast-timer (js/clearTimeout @state/toast-timer))
  (reset! state/toast-timer (js/setTimeout #(hidden! "toast" true) 3800)))
(defn announce! [message]
  (text! "announcer" message) (.add (.-classList (by-id "announcer")) "show")
  (when @state/announce-timer (js/clearTimeout @state/announce-timer))
  (reset! state/announce-timer (js/setTimeout #(.remove (.-classList (by-id "announcer")) "show") 2000)))
(def ability-descriptions
  {:dash ["Inmune 0,18 s" "DASH EVASIVO"] :shield ["60 puntos · 5 s" "ESCUDO"]
   :heal ["+35 de vida" "CURACIÓN"] :boost ["+15 daño · 6 s" "POTENCIA"] :ult ["Área · 65 daño" "NOVA / ULTI"]})
(def ability-icons
  {:dash "<path d='M3 8h6M2 12h4M3 16h6M11 5l7 7-7 7M17 5l6 7-6 7'/>"
   :shield "<path d='M12 2l8 3v6c0 5-4 9-8 11-4-2-8-6-8-11V5z'/><path d='M12 6v11'/>"
   :heal "<path d='M9 3h6v6h6v6h-6v6H9v-6H3V9h6z'/>"
   :boost "<path d='M13 2L4 14h7l-1 8 10-13h-7z'/><path d='M3 4h4M18 19h4'/>"
   :ult "<circle cx='12' cy='12' r='9'/><path d='M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z'/>"})
(defn build-abilities! [on-action]
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
      (.addEventListener b "click" #(do (on-action power) (view/resume-audio!) (.focus view/canvas)))
      (.appendChild (by-id "abilities") b))))

(defn render-rooms! [rooms on-join]
  (let [container (by-id "room-list")]
    (clear! container)
    (if (empty? rooms)
      (.appendChild container (node "div" "empty-rooms" "Tu próxima partida empieza aquí.\nCrea una sala e invita a tus amigos."))
      (doseq [room rooms]
        (let [b (node "button" "room-entry" "") copy (node "span" "" "")
              full? (>= (:count room) 4) playing? (= (:phase room) :playing)]
          (.appendChild copy (node "b" "" (:name room)))
          (.appendChild copy (node "small" "" (str (:code room) " · " (get-in e/arenas [(:arena room) :name] "Patio Cero") " · " (if (= :lan (:visibility room)) "WI-FI" "INTERNET") (when playing? " · EN JUEGO"))))
          (.appendChild b copy) (.appendChild b (node "span" "" (str (:count room) "/4")))
          (set! (.-disabled b) (or full? playing?))
          (.addEventListener b "click" #(on-join (:code room))) (.appendChild container b))))))

(defn invite-url []
  (let [{:keys [info visibility code]} @state/client
        local-origin (.-origin js/location)
        origin (if (= :internet visibility)
                 (or (:public-url info) local-origin)
                 (if (contains? #{"localhost" "127.0.0.1"} (.-hostname js/location))
                   (or (first (:lan-urls info)) local-origin) local-origin))]
    (str origin "/?room=" code)))
(defn refresh-invite! []
  (when (:code @state/client)
    (let [url (invite-url) online-room? (= :internet (:visibility @state/client))
          public? (or (:public-url (:info @state/client)) (= "https:" (.-protocol js/location)))]
      (set! (.-value (by-id "invite-url")) url)
      (text! "invite-hint" (if (and online-room? (not public?))
                             "Falta el túnel: ejecuta bb internet para obtener un enlace público."
                             (if online-room? "Acceso por Internet mientras el anfitrión esté encendido." "Solo equipos conectados a la misma red Wi-Fi."))))))
(defn refresh-lobby! []
  (let [{:keys [world host you code visibility name]} @state/client
        signature [(:players world) (:arena world) host code visibility]]
    (when (not= signature @state/room-render-key)
      (reset! state/room-render-key signature)
      (text! "lobby-title" (str/upper-case (or name "ARENA")))
      (text! "lobby-map" (get-in e/arenas [(:arena world) :name] "Patio Cero"))
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
  (let [signature [(:phase world) (:host @state/client) (:you @state/client)
                   (mapv #(select-keys % [:id :name :kills :deaths :damage]) (e/ranking world))]]
    (when (not= signature @state/score-render-key)
      (reset! state/score-render-key signature) (clear! (by-id "score-rows"))
      (doseq [p (e/ranking world)]
        (let [row (node "div" (str "score-row" (when (= (:you @state/client) (:id p)) " me")) "")]
          (.appendChild row (node "b" "" (:name p)))
          (doseq [v [(:kills p) (:deaths p) (:damage p)]] (.appendChild row (node "span" "" (str v))))
          (.appendChild (by-id "score-rows") row)))
      (hidden! "round-actions" (not= :finished (:phase world)))
      (text! "scoreboard-title" (if (= :finished (:phase world)) "RONDA TERMINADA" "MARCADOR"))
      (when (= :finished (:phase world))
        (let [winner (get-in world [:players (:winner world)])]
          (text! "winner-text" (if winner (str (:name winner) " gana la ronda") "Empate en la arena"))
          (disable! "rematch" (not= (:host @state/client) (:you @state/client))))))))

(defn update-hud! [world now]
  (when-let [p (get-in world [:players (:you @state/client)])]
    (let [training? (= :training (:mode @state/client))]
      (text! "hud-name" (str/upper-case (:skin p))) (text! "score" (str (:kills p) " BAJAS"))
      (text! "health-text" (str (:hp p) " / 100")) (css! (by-id "health-bar") "width" (str (:hp p) "%"))
      (text! "shield-text" (str (:shield p) " / 60")) (css! (by-id "shield-bar") "width" (str (* (/ (:shield p) 60) 100) "%"))
      (text! "weapon-label" (str (get-in e/weapons [(:weapon p) :name])
                                     (if (= :pulse (:weapon p)) " · CLIC PARA DISPARAR" (str " · " (seconds (- (:weapon-until p) now))))))
      (text! "match-time" (if training? "LIBRE" (match-clock (- (:round-end world) now))))
      (text! "match-title" (str/upper-case (get-in e/arenas [(:arena world) :name] "Patio Cero")))
      (text! "match-mode" (if training? "ENTRENAMIENTO" (str "SALA " (:code @state/client))))
      (text! "match-goal" (if training? "PRUEBA TUS HABILIDADES" "12 BAJAS / 3 MIN"))
      (text! "net-stats" (if training? "LOCAL" (str (js/Math.round (:rtt @state/client)) " ms")))
      (text! "render-stats" (str (:fps @state/frame-stats) " FPS"))
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
                                 ulti? (if ready? "PULSA F · NOVA LISTA" "CARGA AL COMBATIR")
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
  (let [events (filter #(> (:id %) @state/announced-event) (:events world)) you (:you @state/client)]
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
    (when (seq events) (reset! state/announced-event (:event-seq world)))
    (view/consume! world you (perf))))

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

(defn restore-preferences! []
  (try
    (when-let [stored (.getItem js/localStorage "clojure-arena/preferences")]
      (let [{:keys [skin name settings]} (reader/read-string stored)]
        (when (contains? e/skins skin) (swap! state/client assoc :skin skin))
        (when (string? name) (set! (.-value (by-id "player-name")) (subs name 0 (min 18 (count name)))))
        (when (map? settings) (swap! view/settings merge settings))))
    (catch :default _ nil))
  (when (view/reduced-motion?) (swap! view/settings assoc :shake 0 :particles :low))
  (set! (.-value (by-id "shake-setting")) (str (:shake @view/settings)))
  (set! (.-value (by-id "particles-setting")) (name (:particles @view/settings)))
  (set! (.-checked (by-id "sound-setting")) (:sound @view/settings))
  (set! (.-value (by-id "volume-setting")) (str (* 100 (:volume @view/settings))))
  (doseq [card (array-seq (.querySelectorAll js/document "[data-fighter]"))]
    (let [selected? (= (:skin @state/client) (.getAttribute card "data-fighter"))]
      (.toggle (.-classList card) "active" selected?) (.setAttribute card "aria-pressed" (str selected?)))))
