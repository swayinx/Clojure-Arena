(ns arena.server
  "Adaptadores de red y concurrencia. Un agente por conexión y otro por sala."
  (:require [arena.engine :as e]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [org.httpkit.server :as http])
  (:import [java.net InetAddress]
           [java.util UUID]))

(def max-rooms 5)
(def max-connections 48)
(defonce running (atom false))
(defonce public-url (atom nil))
(defonce server-port (atom 8080))
(defonce manager (agent {:clients {} :rooms {}}))
(defonce room-busy (atom #{}))
(defonce server-stop (atom nil))
(defonce tunnel-process (atom nil))
(defn now [] (quot (System/nanoTime) 1000000))
(defn uuid [] (str (UUID/randomUUID)))
(defn transmit! [channel message] (when channel (http/send! channel (pr-str message))))
(defn fail! [ch text] (transmit! ch {:type :error :message text}))
(defn watch-errors! [a]
  (set-error-mode! a :continue)
  (set-error-handler! a (fn [_ ex] (binding [*out* *err*] (println "Error en agente:" (.getMessage ex)))))
  a)
(watch-errors! manager)

(defn room-view [room]
  (let [w (:world @(:agent room))]
    {:code (:code room) :name (:name room) :visibility (:visibility room)
     :count (count (:ids room)) :capacity 4 :phase (:phase w)}))
(defn visible? [client room]
  (or (= :lan (:scope client)) (= :internet (:visibility room))))
(defn room-list [s client]
  (->> (vals (:rooms s)) (filter #(visible? client %)) (mapv room-view)))
(defn broadcast-lobby! [s]
  (doseq [[_ c] (:clients s)]
    (when-not (:room c) (transmit! (:ch c) {:type :rooms :rooms (room-list s c)})))
  s)
(defn publish-room! [r]
  (let [message {:type :snapshot :code (:code r) :name (:name r) :visibility (:visibility r)
                 :host (:host r) :world (:world r) :acks (:acks r {})}
        encoded (pr-str message)]
    (doseq [[_ m] (:members r)] (http/send! (:ch m) encoded)))
  r)
(defn receive-input [r id input]
  (if (get-in r [:members id])
    (let [old (get-in r [:inputs id] e/empty-input)
          input (assoc input :at (now) :actions (vec (take 5 (concat (:actions old) (:actions input)))))]
      (assoc-in r [:inputs id] input)) r))
(defn tick-room [r timestamp]
  (try
    (let [previous (:last-tick r timestamp)
          elapsed (min 100 (max 0 (- timestamp previous)))
          inputs (into {} (map (fn [[id input]] [id (if (> (- timestamp (:at input 0)) 300)
                                                     e/empty-input input)])) (:inputs r))
          w (loop [world (:world r) remaining elapsed t (- timestamp elapsed) first? true]
              (if (<= remaining 0) world
                  (let [ms (min e/step-ms remaining)
                        controls (if first? inputs (into {} (map (fn [[id input]] [id (assoc input :actions [])])) inputs))]
                    (recur (e/step world controls (/ ms 1000.0) (+ t ms)) (- remaining ms) (+ t ms) false))))
          r2 (assoc r :world w :last-tick timestamp
                    :acks (into {} (map (fn [[id input]] [id (:seq input -1)])) inputs)
                    :inputs (into {} (map (fn [[id input]] [id (assoc input :actions [])])) (:inputs r)))]
      ;; 40 simulations/s, 20 snapshots/s. Render remains requestAnimationFrame.
      (if (>= (- timestamp (:last-publish r 0)) 50)
        (publish-room! (assoc r2 :last-publish timestamp)) r2))
    (finally (swap! room-busy disj (:code r)))))

(defn clean-name [value fallback]
  (let [v (if (string? value) (str/trim (str/replace value #"[\p{Cntrl}<>]" "")) "")]
    (if (str/blank? v) fallback (subs v 0 (min 18 (count v))))))
(defn valid-skin [skin] (if (contains? e/skins skin) skin "brasa"))
(defn number-in [v lo hi fallback]
  (if (and (number? v) (Double/isFinite (double v))) (e/clamp (double v) lo hi) fallback))
(defn sanitize-input [m]
  {:dx (number-in (:dx m) -1 1 0) :dy (number-in (:dy m) -1 1 0)
   :ax (number-in (:ax m) 0 e/width 480) :ay (number-in (:ay m) 0 e/height 300)
   :fire (true? (:fire m))
   :actions (if (vector? (:actions m)) (vec (take 5 (filter #(contains? e/abilities %) (:actions m)))) [])})

(declare command)
(defn player-message
  "Buzón individual: orden, límite de frecuencia y validación antes de la sala."
  [p message]
  (let [t (now) reset-window? (> (- t (:window p 0)) 1000)
        n (if reset-window? 1 (inc (:messages p 0)))
        p (assoc p :messages n :window (if reset-window? t (:window p)))
        kind (:type message)]
    (cond
      (> n 100) p
      (= kind :input)
      (let [seq-id (:seq message)]
        (if (and (:room-agent p) (integer? seq-id) (> seq-id (:seq p -1)))
          (do (send (:room-agent p) receive-input (:id p) (assoc (sanitize-input message) :seq seq-id))
              (assoc p :seq seq-id)) p))
      (= kind :ping) (do (transmit! (:ch p) {:type :pong :sent (:sent message)}) p)
      (contains? #{:create :join :leave :start :list} kind)
      (do (send manager command (:id p) message) p)
      :else p)))

(defn detach [s id]
  (if-let [code (get-in s [:clients id :room])]
    (let [room (get-in s [:rooms code])
          ids (disj (:ids room) id)
          host (if (= id (:host room)) (first (sort ids)) (:host room))
          client (get-in s [:clients id])]
      (send (:agent client) assoc :room-agent nil :seq -1)
      (when room
        (send (:agent room)
              (fn [r] (publish-room! (-> r (update :world e/remove-player id)
                                          (update :members dissoc id) (update :inputs dissoc id)
                                          (assoc :host host))))))
      (cond-> (assoc-in s [:clients id :room] nil)
        (seq ids) (assoc-in [:rooms code] (assoc room :ids ids :host host))
        (empty? ids) (update :rooms dissoc code))) s))

(defn attach [s id code message]
  (let [s (detach s id) c (get-in s [:clients id]) r (get-in s [:rooms code])
        name (clean-name (:name message) "Jugador") skin (valid-skin (:skin message))]
    (send (:agent c) assoc :room-agent (:agent r) :seq -1)
    (send (:agent r)
          (fn [room]
            (let [next (-> room (assoc-in [:members id] c)
                           (update :world e/add-player id name skin))]
              (transmit! (:ch c) {:type :joined :id id :code code})
              (publish-room! next))))
    (-> s (assoc-in [:clients id :room] code) (update-in [:rooms code :ids] conj id))))

(defn new-code [s]
  (let [code (-> (uuid) (subs 0 6) str/upper-case)]
    (if (get-in s [:rooms code]) (recur s) code)))

(defn command [s id m]
  (if-let [c (get-in s [:clients id])]
    (let [ch (:ch c)
          result
          (case (:type m)
            :create
            (if (>= (count (:rooms s)) max-rooms)
              (do (fail! ch "El anfitrión ya tiene cinco salas. Únete a una existente.") s)
              (let [visibility (if (= :internet (:visibility m)) :internet :lan)]
                (if (and (= :internet (:scope c)) (= :lan visibility))
                  (do (fail! ch "Las salas Wi-Fi solo se crean desde la red local.") s)
                  (let [s (detach s id) code (new-code s) t (now)
                        title (clean-name (:room-name m) "Arena abierta")
                        a (watch-errors! (agent {:code code :name title :visibility visibility :host id
                                                 :members {} :inputs {} :world (e/world :arena t)
                                                 :last-tick t :last-publish 0}))
                        room {:code code :name title :visibility visibility :host id :ids #{} :agent a}]
                    (attach (assoc-in s [:rooms code] room) id code m)))))
            :join
            (let [code (when (string? (:code m)) (str/upper-case (str/trim (:code m))))
                  room (get-in s [:rooms code])]
              (cond
                (= code (:room c)) s
                (or (nil? room) (not (visible? c room))) (do (fail! ch "Sala no disponible desde esta conexión.") s)
                (>= (count (:ids room)) 4) (do (fail! ch "La sala está llena (4/4).") s)
                (= :playing (get-in @(:agent room) [:world :phase])) (do (fail! ch "La partida está en curso. Espera a la siguiente ronda.") s)
                :else (attach s id code m)))
            :leave (let [s2 (detach s id)] (transmit! ch {:type :left}) s2)
            :start
            (if-let [r (get-in s [:rooms (:room c)])]
              (cond
                (not= id (:host r)) (do (fail! ch "Solo el creador de la sala inicia la ronda.") s)
                (< (count (:ids r)) 2) (do (fail! ch "Se necesitan al menos dos jugadores; la sala admite cuatro.") s)
                :else (do (send (:agent r)
                                (fn [room]
                                  (if (= :playing (get-in room [:world :phase])) room
                                      (publish-room! (assoc room :world (e/start-round (:world room) (now))
                                                           :inputs {} :last-tick (now)))))) s)) s)
            :list (do (transmit! ch {:type :rooms :rooms (room-list s c)}) s)
            s)]
      (broadcast-lobby! result)) s))

(defn local-address? [remote]
  (let [ip (or remote "")]
    (or (= ip "::1") (= ip "0:0:0:0:0:0:0:1")
        (boolean (re-matches #"(?i)(fc|fd|fe8|fe9|fea|feb)[0-9a-f:]+" ip))
        (when (re-matches #"[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+" ip)
          (let [[a b c d :as octets] (mapv #(Long/parseLong %) (str/split ip #"\."))]
            (and (every? #(<= 0 % 255) octets)
                 (or (= a 10) (= a 127) (and (= a 192) (= b 168))
                     (and (= a 172) (<= 16 b 31)) (and (= a 169) (= b 254)))))))))
(defn request-scope [req]
  ;; No confiar en X-Forwarded-For para conceder acceso LAN.
  (if (and (local-address? (:remote-addr req))
           (not-any? #(seq (get-in req [:headers %]))
                     ["cf-connecting-ip" "cf-ray" "x-forwarded-for" "forwarded"])) :lan :internet))
(defn lan-addresses []
  ;; hostname -I is available on Linux; the Java fallback is portable.
  (let [addresses (try
                    (let [{:keys [exit out]} (shell/sh "hostname" "-I")]
                      (if (zero? exit) (str/split (str/trim out) #"\s+") []))
                    (catch Exception _ []))
        addresses (if (seq addresses) addresses
                      (try [(.getHostAddress (InetAddress/getLocalHost))] (catch Exception _ [])))]
    (->> addresses (filter #(and (re-matches #"[0-9.]+" %) (local-address? %)
                                 (not (str/starts-with? % "127."))))
         distinct (mapv #(str "http://" % ":" @server-port)))))
(defn info [scope]
  {:type :info :scope scope :public-url @public-url :lan-urls (if (= scope :lan) (lan-addresses) [])
   :max-rooms max-rooms :max-players 4 :tick-rate 40})
(defn connected [s id ch scope]
  (if (>= (count (:clients s)) max-connections)
    (do (fail! ch "El servidor llegó al límite de conexiones.") (http/close ch) s)
    (let [a (watch-errors! (agent {:id id :ch ch :seq -1 :window (now) :messages 0}))
          c {:id id :ch ch :scope scope :agent a :room nil}
          next (assoc-in s [:clients id] c)]
      (transmit! ch {:type :welcome :id id :info (info scope) :rooms (room-list next c)}) next)))
(defn disconnected [s id]
  (broadcast-lobby! (update (detach s id) :clients dissoc id)))

(def mime-types {"html" "text/html; charset=utf-8" "css" "text/css; charset=utf-8"
                 "js" "text/javascript; charset=utf-8" "svg" "image/svg+xml"
                 "ttf" "font/ttf" "png" "image/png" "ico" "image/x-icon" "ogg" "audio/ogg"})
(defn response [status type body]
  {:status status :headers {"Content-Type" type "Cache-Control" "no-cache"
                            "X-Content-Type-Options" "nosniff" "Referrer-Policy" "same-origin"} :body body})
(defn static-file [uri]
  (let [path (if (= uri "/") "/index.html" uri)
        root (.getCanonicalPath (io/file "public"))
        file (io/file "public" (subs path 1))
        canonical (.getCanonicalPath file)
        ext (last (str/split path #"\."))]
    (if (and (str/starts-with? canonical (str root java.io.File/separator))
             (.isFile file) (get mime-types ext))
      (response 200 (get mime-types ext) file)
      (response 404 "text/plain" "No encontrado"))))
(defn origin-allowed? [req]
  (let [origin (get-in req [:headers "origin"]) host (get-in req [:headers "host"])]
    (or (nil? origin) (= origin (str "http://" host)) (= origin (str "https://" host)))))
(defn handler [req]
  (cond
    (not= :get (:request-method req)) (response 405 "text/plain" "Solo GET")
    (= "/health" (:uri req)) (response 200 "application/edn" (pr-str {:status :ok :rooms (count (:rooms @manager))
                                                                    :connections (count (:clients @manager))}))
    (= "/api/info" (:uri req)) (response 200 "application/edn" (pr-str (info (request-scope req))))
    (= "/ws" (:uri req))
    (if-not (and (:websocket? req) (origin-allowed? req))
      (response 403 "text/plain" "WebSocket del mismo origen requerido")
      (let [id (uuid) scope (request-scope req)]
        (http/as-channel req
          {:on-open (fn [ch] (send manager connected id ch scope))
           :on-close (fn [_ _] (send manager disconnected id))
           :on-receive
           (fn [ch raw]
             (when (and (string? raw) (<= (count raw) 2048))
               (try
                 (let [m (edn/read-string {:readers {} :default (fn [_ _] (throw (ex-info "Etiqueta no admitida" {})))} raw)]
                   (when (map? m)
                     (when-let [a (get-in @manager [:clients id :agent])] (send a player-message m))))
                 (catch Exception _ (fail! ch "Mensaje no válido.")))))})))
    :else (static-file (:uri req))))

(defn stop! []
  (reset! running false)
  (when-let [process @tunnel-process] (.destroy process))
  (doseq [[_ c] (:clients @manager)] (http/close (:ch c)))
  (when-let [stop @server-stop] (stop))
  (reset! server-stop nil))

(defn start! [port]
  (reset! server-port port)
  (reset! public-url (System/getenv "ARENA_PUBLIC_URL"))
  (reset! server-stop (http/run-server handler {:ip "0.0.0.0" :port port :max-ws 2048 :max-body 2048
                                               :legacy-unsafe-remote-addr? false}))
  (reset! running true)
  (future
    (while @running
      (let [t (now)]
        (doseq [[code r] (:rooms @manager)]
          ;; Un tick pendiente como máximo por sala: evita colas crecientes.
          (when-not (contains? @room-busy code)
            (swap! room-busy conj code)
            (send (:agent r) tick-room t)))
        (Thread/sleep e/step-ms))))
  (println (str "Clojure Arena: http://localhost:" port))
  (doseq [url (lan-addresses)] (println "Misma Wi-Fi:" url))
  (println "Hasta 5 salas de 4 jugadores. Ctrl+C para cerrar.")
  @server-stop)

(defn start-tunnel! [port]
  (try
    (let [local (io/file ".tools" (if (str/includes? (str/lower-case (System/getProperty "os.name")) "windows") "cloudflared.exe" "cloudflared"))
          executable (if (.isFile local) (.getAbsolutePath local) "cloudflared")
          p (.start (.redirectErrorStream
                    (ProcessBuilder. ^java.util.List [executable "tunnel" "--url" (str "http://localhost:" port)]) true))]
      (reset! tunnel-process p)
      (future
        (with-open [reader (io/reader (.getInputStream p))]
          (doseq [line (line-seq reader)]
            (println line)
            (when-let [url (re-find #"https://[a-z0-9-]+\.trycloudflare\.com" line)]
              (reset! public-url url)
              (println "Enlace para compartir:" url))))))
    (catch Exception ex
      (println "No se pudo iniciar cloudflared. La Wi-Fi sigue disponible.")
      (println "Instalación: https://developers.cloudflare.com/tunnel/downloads/")
      (println (.getMessage ex)))))

(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (start! port)
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    (when (some #{"--internet"} args) (start-tunnel! port))
    @(promise)))
