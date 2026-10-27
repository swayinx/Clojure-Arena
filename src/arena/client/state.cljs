(ns arena.client.state
  "Shared browser client state, separated from transport and presentation."
  (:require [clojure.string :as str]))

(defonce client
  (atom {:mode :none :screen "inicio" :skin "brasa" :you "you" :online false :rtt 0
         :world nil :previous nil :received-at 0 :pending [] :seq 0 :rooms [] :info {}}))
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
(defonce pending-invite
  (atom (some-> (js/URLSearchParams. (.-search js/location))
                (.get "room") str/upper-case)))
