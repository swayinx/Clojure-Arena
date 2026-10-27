(ns arena.client
  "Browser entry point for input, simulation, match lifecycle, and WebSocket transport."
  (:require [arena.client.state :as state]
            [arena.client.ui :as ui]
            [arena.engine :as e]
            [arena.view :as view]
            [arena.presentation :as visual]
            [cljs.reader :as reader]
            [clojure.string :as str]))

;; Keep the public entry-point vars pointed at the shared state atoms.
(def client state/client)
(def keys-down state/keys-down)
(def mouse state/mouse)
(def actions state/actions)
(def toast-timer state/toast-timer)
(def announce-timer state/announce-timer)
(def last-frame state/last-frame)
(def accumulator state/accumulator)
(def last-hud state/last-hud)
(def frame-stats state/frame-stats)
(def room-render-key state/room-render-key)
(def score-render-key state/score-render-key)
(def announced-event state/announced-event)
(def pending-invite state/pending-invite)
(defonce socket (atom nil))
(defonce last-message (atom 0))

(def by-id ui/by-id)
(def text! ui/text!)
(def hidden! ui/hidden!)
(def disable! ui/disable!)
(def on! ui/on!)
(def css! ui/css!)
(def node ui/node)
(def clear! ui/clear!)
(def perf ui/perf)
(def save-preferences! ui/save-preferences!)
(def toast! ui/toast!)
(def announce! ui/announce!)
(def ability-descriptions ui/ability-descriptions)
(def ability-icons ui/ability-icons)
(def build-abilities! ui/build-abilities!)
(def render-rooms! ui/render-rooms!)
(def invite-url ui/invite-url)
(def refresh-invite! ui/refresh-invite!)
(def refresh-lobby! ui/refresh-lobby!)
(def seconds ui/seconds)
(def match-clock ui/match-clock)
(def refresh-score! ui/refresh-score!)
(def update-hud! ui/update-hud!)
(def present-events! ui/present-events!)
(def copy-invite! ui/copy-invite!)
(def show-qr! ui/show-qr!)
(def toggle-fullscreen! ui/toggle-fullscreen!)
(def restore-preferences! ui/restore-preferences!)

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


(defn predicted-player [p input dt now]
  (if (and p (:alive p))
    (let [w {:players {(:id p) p} :time now :events [] :event-seq 0}
          w (if (some #{:dash} (:actions input)) (e/activate w (:id p) :dash input) w)]
      (e/move-player (get-in w [:players (:id p)]) input dt now)) p))
(defn begin-match! []
  (view/reset!fx) (reset! frame-stats {:since (perf) :frames 0 :fps 0}) (reset! announced-event 0) (reset! accumulator 0) (reset! score-render-key nil)
  (clear! (by-id "kill-feed")) (hidden! "scoreboard" true) (screen! "juego") (view/resume-audio!))
(defn announce-ultimate! [old world you]
  (when (and (get-in world [:players you :alive])
             (< (get-in old [:players you :ulti] 0) 100)
             (>= (get-in world [:players you :ulti] 0) 100))
    (view/ultimate-ready!) (announce! "NOVA LISTA · PULSA F")))

(defn start-training! []
  (when (= :online (:mode @client)) (send! {:type :leave}))
  (save-preferences!)
  (let [world (e/training (:name (identity-message)) (:skin @client) 0
                          (keyword (.-value (by-id "training-map"))))]
    (swap! client assoc :mode :training :world world :previous world :you "you" :code nil :pending [] :predicted nil :snapshots [] :render-time nil :display-time nil :correction {:x 0 :y 0})
    (begin-match!) (update-hud! world 0) (view/ultimate-ready!) (announce! "ENTRENAMIENTO · NOVA LISTA EN F")))
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
        (render-rooms! (:rooms message) join!)
        (when @pending-invite
          (set! (.-value (by-id "room-code")) @pending-invite)
          (toast! "Invitación cargada. Elige tu nombre y pulsa UNIRME.")
          (reset! pending-invite nil)))
    :rooms (do (swap! client assoc :rooms (:rooms message)) (render-rooms! (:rooms message) join!))
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
        (when (and (= :playing (:phase w)) (not starting?)) (announce-ultimate! old w (:you s)))
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
    ;; A delayed close from an old socket must not clear a new connection.
    (reset! socket nil)
    (swap! client assoc :online false :rooms []) (text! "connection" "SIN CONEXIÓN")
    (.remove (.-classList (by-id "connection")) "online")
    (disable! "create-room" true) (disable! "join-room" true)
    (render-rooms! [] join!)
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


(defn bind! []
  (on! "start-game" "click" start-training!) (on! "restart-game" "click" start-training!)
  (on! "leave-game" "click" leave!) (on! "leave-room" "click" leave!)
  (on! "create-room" "click" #(do (save-preferences!) (send! (merge (identity-message) {:type :create :room-name (.-value (by-id "room-name"))
                                                                                     :visibility (keyword (.-value (by-id "room-visibility")))
                                                                                     :arena (keyword (.-value (by-id "room-map")))}))))
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
                  (swap! client assoc :previous w :world next)
                  (announce-ultimate! w next "you") (present-events! next t))
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
            ;; The fractional tick affects rendering only; simulation remains at 40 Hz.
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


(build-abilities! #(swap! actions conj %)) (restore-preferences!) (bind!) (input-events!) (connect!)
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
