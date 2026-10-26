(ns arena.audio
  "Reproducción de muestras CC0 de Kenney. Sin síntesis ni dependencias de audio.")

(def clips
  {:shot ["laserSmall_000" "laserSmall_001" "laserSmall_002"]
   :hit ["impactMetal_000"] :kill ["explosionCrunch_000"]
   :dash ["phaseJump1"] :shield ["forceField_000"]
   :heal ["powerUp3"] :boost ["powerUp7"]
   :ult-cast ["phaserUp4"] :ult ["lowFrequency_explosion_000"]
   :pickup ["threeTone1"]})
(def levels {:shot 0.20 :hit 0.24 :kill 0.38 :dash 0.36 :shield 0.30
             :heal 0.30 :boost 0.32 :ult-cast 0.42 :ult 0.65 :pickup 0.30})
(defonce context (atom nil))
(defonce master (atom nil))
(defonce buffers (atom {}))
(defonce voices (atom #{}))
(defonce last-played (atom {}))
(defonce sequence-id (atom 0))

(defn configure! [enabled volume]
  (when-let [gain @master]
    (.setTargetAtTime (.-gain gain) (if enabled volume 0) (.-currentTime @context) 0.02)))

(defn resume! [enabled volume]
  (try
    (when (and enabled (nil? @context))
      (let [ac (js/AudioContext.) gain (.createGain ac)]
        (reset! context ac) (reset! master gain)
        (.connect gain (.-destination ac))
        (doseq [file (distinct (mapcat val clips))]
          (-> (js/fetch (str "audio/" file ".ogg"))
              (.then (fn [response]
                       (if (.-ok response) (.arrayBuffer response)
                           (throw (js/Error. (str "Audio HTTP " (.-status response)))))))
              (.then #(.decodeAudioData ac %))
              (.then #(swap! buffers assoc file %))
              (.catch #(js/console.warn (str "No se pudo cargar " file) %))))))
    (configure! enabled volume)
    (when (and enabled @context (= "suspended" (.-state @context)))
      (-> (.resume @context) (.catch (fn [_] nil))))
    (catch :default _ nil)))

(defn play! [kind enabled]
  (when (and enabled @context (= "running" (.-state @context)))
    (let [ac @context now (.-currentTime ac) files (get clips kind)
          gap (if (contains? #{:shot :hit} kind) 0.055 0.09)]
      ;; Máximo 16 voces y una separación por evento: el combate no satura el audio.
      (when (and (seq files) (< (count @voices) 16)
                 (> (- now (get @last-played kind -10)) gap))
        (when-let [buffer (get @buffers (nth files (mod (swap! sequence-id inc) (count files))))]
          (let [source (.createBufferSource ac) gain (.createGain ac)]
            (set! (.-buffer source) buffer)
            (set! (.-value (.-gain gain)) (get levels kind 0.3))
            (.connect source gain) (.connect gain @master)
            (swap! voices conj source) (swap! last-played assoc kind now)
            (set! (.-onended source) #(do (swap! voices disj source) (.disconnect source) (.disconnect gain)))
            (.start source)))))))
