(ns arena.server-test
  (:require [clojure.test :refer [deftest is]] [arena.server :as s]))
(deftest trust-boundary
  (is (= {:dx 1 :dy -1 :ax 480 :ay 600 :fire false :actions [:dash]}
         (s/sanitize-input {:dx 999 :dy -8 :ax "wrong" :ay 999 :fire "true" :actions [:fake :dash]})))
  (is (= "Jugador" (s/clean-name "   " "Jugador"))))
(deftest local-vs-tunnel
  (doseq [ip ["192.168.1.3" "127.0.0.1" "10.0.1.5" "172.16.0.1" "::1"]] (is (s/local-address? ip)))
  (doseq [ip ["8.8.8.8" "172.32.1.1" "192.168.1.999" "localhost"]] (is (not (s/local-address? ip))))
  (is (= :lan (s/request-scope {:remote-addr "127.0.0.1" :headers {}})))
  (is (= :internet (s/request-scope {:remote-addr "127.0.0.1" :headers {"cf-connecting-ip" "192.168.1.5"}}))))
(deftest static-root-only
  (is (= 404 (:status (s/static-file "/../README.md"))))
  (is (= 200 (:status (s/static-file "/index.html"))))
  (is (= 403 (:status (s/handler {:request-method :get :uri "/ws" :websocket? true
                                 :headers {"host" "localhost:8080" "origin" "https://example.com"}})))))
