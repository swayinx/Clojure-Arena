(ns arena.test-runner
  (:require [clojure.test :as t] [arena.engine-test] [arena.server-test]))
(defn -main [& _]
  (let [result (t/run-tests 'arena.engine-test 'arena.server-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
