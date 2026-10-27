(ns arena.audio
  "Reproducción de muestras CC0 de Kenney. Sin síntesis ni dependencias de audio.")

(def clips
  {:shot ["laserSmall_000" "laserSmall_001" "laserSmall_002"]
   :hit ["impactMetal_medium_000"] :kill ["explosionCrunch_000"]
   :dash ["jump-a" "jump-b"] :shield ["forceField_000"]
   :heal ["powerUp3"] :boost ["powerUp7"]
   :ult-ready ["confirmation_002"]
   :ult-cast ["phaserUp4"] :ult ["explosion-a"]
   :pickup ["threeTone1"]})
(def layers {:dash "move-a" :shield "confirmation_001" :heal "pluck_001"
             :boost "maximize_001" :ult-cast "switch_001"
             :ult "lowFrequency_explosion_000"})
(def levels {:shot 0.18 :hit 0.24 :kill 0.34 :dash 0.34 :shield 0.28
             :heal 0.28 :boost 0.29 :ult-ready 0.45 :ult-cast 0.38 :ult 0.46 :pickup 0.28})
(defonce context (atom nil))
(defonce master (atom nil))
(defonce buffers (atom {}))
(defonce voices (atom #{}))
(defonce last-played (atom {}))
(defonce sequence-id (atom 0))
(defonce audio-enabled (atom true))
(defonce pending-ready (atom false))
(declare play!)

(defn configure! [enabled volume]
  (reset! audio-enabled enabled)
  (when-not enabled (reset! pending-ready false))
  (when-let [gain @master]
    (.setTargetAtTime (.-gain gain) (if enabled volume 0) (.-currentTime @context) 0.02)))

(defn resume! [enabled volume]
  (try
    (when (and enabled (nil? @context))
      (let [ac (js/AudioContext.) gain (.createGain ac)]
        (reset! context ac) (reset! master gain)
        (.connect gain (.-destination ac))
        (doseq [file (distinct (concat (mapcat val clips) (vals layers)))]
          (-> (js/fetch (str "audio/" file ".ogg"))
              (.then (fn [response]
                       (if (.-ok response) (.arrayBuffer response)
                           (throw (js/Error. (str "Audio HTTP " (.-status response)))))))
              (.then #(.decodeAudioData ac %))
              (.then (fn [decoded]
                       (swap! buffers assoc file decoded)
                       (when (and (= file "confirmation_002") @pending-ready)
                         (reset! pending-ready false)
                         (play! :ult-ready @audio-enabled))))
              (.catch #(js/console.warn (str "No se pudo cargar " file) %))))))
    (configure! enabled volume)
    (when (and enabled @context (= "suspended" (.-state @context)))
      (-> (.resume @context) (.catch (fn [_] nil))))
    (catch :default _ nil)))

(defn start-sample! [ac file level]
  (when-let [buffer (get @buffers file)]
    (when (< (count @voices) 16)
      (let [source (.createBufferSource ac) gain (.createGain ac)]
        (set! (.-buffer source) buffer)
        (set! (.-value (.-gain gain)) level)
        (.connect source gain) (.connect gain @master)
        (swap! voices conj source)
        (set! (.-onended source) #(do (swap! voices disj source) (.disconnect source) (.disconnect gain)))
        (.start source)
        true))))

(defn play! [kind enabled]
  (when (and enabled @context (= "running" (.-state @context)))
    (let [ac @context now (.-currentTime ac) files (get clips kind)
          gap (if (contains? #{:shot :hit} kind) 0.055 0.09)]
      ;; Máximo 16 voces y una separación por evento: el combate no satura el audio.
      (when (and (seq files) (< (count @voices) 16)
                 (> (- now (get @last-played kind -10)) gap))
        (let [file (nth files (mod (swap! sequence-id inc) (count files)))
              level (get levels kind 0.3)]
          (when (and (= kind :ult-ready) (not (get @buffers file)))
            (reset! pending-ready true))
          (when (start-sample! ac file level)
            (swap! last-played assoc kind now)
            (when-let [layer (get layers kind)]
              (start-sample! ac layer (* level 0.42)))))))))
