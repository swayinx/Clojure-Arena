(ns arena.client-smoke
  "Ejecuta el bundle real con adaptadores DOM/Canvas simulados, sin navegador.
   Comprueba arranque, controles, cooldown y QR; no valida el aspecto visual."
  (:require [cljs.nodejs :as nodejs]))
(def fs (js/require "node:fs"))
(def vm (js/require "node:vm"))
(def check (js/require "node:assert/strict"))
(def elements (atom {}))
(def listeners (atom {}))
(def clock (atom 0))
(def frames (atom []))
(def draws (atom 0))
(def console-errors (atom []))
(def noop (fn [& _]))
(def context2d (js/Proxy. #js {} #js {:get (fn [target key] (or (aget target key) (fn [& _] (swap! draws inc))))}))
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
                    :callbacks callbacks :properties props}]
        (swap! elements assoc id el) el)))
(def document #js {:getElementById element :querySelectorAll (fn [_] #js []) :querySelector (fn [_] nil)
                   :createElement (fn [_] (element (str (random-uuid))))
                   :createElementNS (fn [& _] (element (str (random-uuid))))
                   :addEventListener noop :execCommand (fn [_] true)
                   :documentElement #js {:requestFullscreen (fn [] (js/Promise.resolve nil))}})
(def sandbox #js {:document document :console #js {:log noop :error #(swap! console-errors conj %)}
                  :location #js {:protocol "file:" :origin "null" :hostname "" :host "" :search ""}
                  :navigator #js {} :performance #js {:now (fn [] @clock)}
                  :localStorage #js {:getItem (fn [_] nil) :setItem noop}
                  :setTimeout (fn [& _] 1) :clearTimeout noop :setInterval (fn [& _] 1)
                  :requestAnimationFrame #(reset! frames [%])
                  :matchMedia (fn [_] #js {:matches false})
                  :addEventListener (fn [kind f] (swap! listeners assoc kind f))
                  :URLSearchParams js/URLSearchParams
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
(set! (.-value (element "player-name")) "Smoke")
(click! "start-game")
(step! 5)
(check (= "100 / 100" (text "health-text")))
(check (= "LISTA" (text "ult-cooldown")))
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
(check (empty? @console-errors))
(println "CLIENTE: arranque del bundle, Canvas, Q, Shift, F, reinicio y QR sin excepciones.")
(println "Esta prueba usa adaptadores simulados; no sustituye la revisión visual en navegador.")
