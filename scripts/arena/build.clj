(ns arena.build
  (:require [babashka.fs :as fs] [babashka.process :as process]
            [babashka.http-client :as http] [clojure.java.io :as io]
            [clojure.string :as str]))
(def compiler-url "https://github.com/clojure/clojurescript/releases/download/r1.12.145/cljs.jar")
(def compiler-sha "fe33197a3e0e8ca9d2c52c3b4413f216ca309a0d67b4cdffe919c236c7f19f0a")
(defn sha256 [file]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop [] (let [n (.read stream buffer)]
                   (when (pos? n) (.update digest buffer 0 n) (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))
(defn java-path []
  (or (some-> (fs/which "java") str)
      (when-let [home (System/getenv "JAVA_HOME")]
        (let [p (str (fs/path home "bin" (if (fs/windows?) "java.exe" "java")))] (when (fs/exists? p) p)))
      (when (fs/which "mise")
        (let [r @(process/process ["mise" "where" "java@temurin-21"] {:out :string :err :string})]
          (when (zero? (:exit r)) (str (str/trim (:out r)) "/bin/java"))))
      (throw (ex-info "Instala Java 21 y añade java al PATH. Consulta README.md." {}))))
(defn -main [& args]
  (fs/create-dirs ".tools")
  (when-not (fs/exists? ".tools/cljs.jar")
    (println "Descargando compilador oficial de ClojureScript 1.12.145...")
    (with-open [body (:body (http/get compiler-url {:as :stream}))]
      (io/copy body (io/file ".tools/cljs.jar"))))
  (when-not (= compiler-sha (sha256 ".tools/cljs.jar"))
    (throw (ex-info "El checksum de .tools/cljs.jar no coincide. Elimina ese archivo y recompila." {})))
  (let [cp (str ".tools/cljs.jar" java.io.File/pathSeparator "src")
        command (cond-> [(java-path) "-Xmx1g" "-cp" cp "clojure.main" "-m" "cljs.main" "-co" "build.edn"]
                  (some #{"--watch"} args) (into ["-w" "src"]))]
    (println "Compilando ClojureScript con colecciones persistentes...")
    (apply process/shell (into command ["-c" "arena.client"]))
    (println "public/client.js listo.")))

(defn network-test! [& _]
  (let [cp (str ".tools/cljs.jar" java.io.File/pathSeparator "src" java.io.File/pathSeparator "test")]
    (process/shell (java-path) "-Xmx1g" "-cp" cp "clojure.main" "-m" "cljs.main" "-co"
                   "{:target :nodejs :output-to \"build/network-test.cjs\" :output-dir \"build/network\" :optimizations :simple}"
                   "-c" "arena.network-test")
    (process/shell "node" "build/network-test.cjs")))


(defn client-test! [& _]
  (let [cp (str ".tools/cljs.jar" java.io.File/pathSeparator "src" java.io.File/pathSeparator "test")]
    (process/shell (java-path) "-Xmx1g" "-cp" cp "clojure.main" "-m" "cljs.main" "-co"
                   "{:target :nodejs :output-to \"build/client-smoke.cjs\" :output-dir \"build/client-test\" :optimizations :simple}"
                   "-c" "arena.client-smoke")
    (process/shell "node" "build/client-smoke.cjs")))
