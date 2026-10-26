# Clojure Arena: teoría del lenguaje y guía de defensa

Este documento relaciona el código con el sílabo de **Lenguajes de Programación**: análisis léxico/sintáctico/semántico, nombres y ámbitos, tipos, expresiones, control, subprogramas, excepciones, concurrencia y paradigmas. Los ejemplos corresponden a la implementación; se distingue entre lo que usamos y lo que solo comparamos teóricamente.

## 1. Qué construimos y por qué elegimos Clojure

Construimos una arena de acción para cuatro jugadores. El estado del mundo es un valor formado por mapas y vectores. Una función calcula el siguiente estado a partir del estado anterior, las entradas y el tiempo. El servidor mantiene ese valor en un agente por sala y comparte el resultado por WebSocket.

Clojure pertenece a la familia Lisp y favorece programación funcional, colecciones persistentes y composición de funciones. Permite interoperabilidad con su plataforma anfitriona. Aquí usamos:

- **Clojure (`.clj`)** en el servidor. La ejecución sencilla usa Babashka, un intérprete nativo de un subconjunto de Clojure que incluye agentes y http-kit. `deps.edn` ofrece otra entrada para Clojure en la JVM.
- **ClojureScript (`.cljs`)** en el navegador, compilado a JavaScript mediante el compilador oficial. Tiene colecciones persistentes; no tiene los agentes de Clojure JVM.
- **Código compartido (`.cljc`)** para las reglas. Las expresiones `#?(:clj ... :cljs ...)` seleccionan la interoperabilidad matemática de cada plataforma al leer/compilar el archivo.

HTML describe la estructura y CSS la presentación. El JavaScript de `public/client.js` es **salida del compilador**, no una implementación del juego escrita a mano en otro lenguaje. El generador de QR es una dependencia externa MIT, separada de las reglas.

Fuentes: [ClojureScript y sus diferencias con Clojure](https://clojurescript.org/about/differences), [Babashka: alcance y ejecución](https://book.babashka.org/#_setting_expectations).

## 2. Léxico, sintaxis y semántica

Considera esta expresión del estilo utilizado en el motor:

```clojure
(assoc-in mundo [:players id :hp] 80)
```

**Análisis léxico:** identifica paréntesis, corchetes, el símbolo `assoc-in`, el símbolo `mundo`, las keywords `:players` y `:hp`, el símbolo `id` y el literal numérico `80`.

**Sintaxis:** es una lista en notación prefija: operador primero, argumentos después. El vector `[:players id :hp]` representa una ruta dentro de mapas anidados. Los delimitadores deben estar balanceados.

**Semántica:** se evalúa `mundo`, se obtiene el ID y se produce un nuevo mapa donde esa ruta vale 80. El mapa previo conserva su valor. El significado no es “reescribir el campo en el objeto original”.

La forma `(if condición expresión-verdadera expresión-falsa)` es una **forma especial**, no una función ordinaria: solo evalúa la rama elegida. Los macros como `->` transforman formas antes de ejecutarlas.

## 3. Nombres, enlaces y ámbito

### Símbolo, keyword y valor

- `world`, `id` y `now` son símbolos utilizados como nombres.
- `:players`, `:hp` y `:dash` son keywords utilizadas como claves/identificadores de datos.
- `"brasa"` es una cadena. No es lo mismo que la keyword `:brasa`.
- Un **enlace** asocia un nombre con un valor en un contexto.

### Ámbito léxico

Los parámetros de una función y los nombres definidos con `let` tienen ámbito léxico: su significado depende de dónde está escrito el código.

```clojure
(defn vida-restante [vida daño]
  (let [resultado (- vida daño)]
    (max 0 resultado)))
```

`vida` y `daño` existen dentro del cuerpo de la función. `resultado` existe dentro del cuerpo de ese `let`. Fuera de él no queda una variable global llamada `resultado`.

En `engine/damage-player`, el `let` enlaza `p`, `now`, `shield`, `absorbed` y `lost`. Estos nombres describen pasos del cálculo y no exponen variables temporales compartidas entre jugadores.

### Shadowing / ocultamiento

Un enlace interno puede usar el mismo nombre que uno externo. El interno se refiere a su propio valor; no modifica el externo.

```clojure
(let [vida 100]
  [(let [vida 35] vida)
   vida])
;; => [35 100]
```

En el servidor se usa un nuevo enlace `s` después de una transición, por ejemplo tras `detach`. Es un nuevo enlace al siguiente valor, no una asignación destructiva sobre el mapa anterior.

### Namespaces, Vars y alcance global

`(ns arena.engine)` organiza nombres. `engine/step` y `client/tick` pueden convivir sin confundir sus responsabilidades. `def` crea una Var dentro de un namespace en Clojure; `defn` define una función asociada a una Var. Esto no significa que todas las variables del programa tengan alcance global.

### Cierres / closures

Una función puede capturar enlaces del lugar donde se creó:

```clojure
(fn [] (join! (:code room)))
```

El callback conserva el valor de `room` de esa iteración y puede usarlo cuando ocurra el clic. Es la relación entre ámbito léxico y vida del enlace: la función puede seguir utilizándolo después de terminar el bloque que creó el callback.

### Ámbito dinámico: contraste

Clojure admite Vars dinámicas declaradas con `^:dynamic`, que se pueden enlazar temporalmente con `binding`. No usamos ámbito dinámico para posiciones o vida. Sí se usa `binding` de `*out*` a `*err*` al escribir errores del servidor: cambia temporalmente el destino de impresión en ese contexto.

Fuente para las formas de enlace: [Special Forms: let, fn, loop y binding forms](https://clojure.org/reference/special_forms).

## 4. Tipos de datos y representación

Clojure es de **tipado dinámico**: no declaramos un tipo estático obligatorio para cada variable. Eso no vuelve equivalentes una cadena y un número. Validamos los datos externos antes de operar con ellos.

| Tipo / estructura | Ejemplo real | Uso |
|---|---|---|
| Número | `100`, `0.025` | Vida, delta y coordenadas |
| Booleano | `true`, `false` | Disparar, vivo/muerto |
| Cadena | `"Ana"`, `"brasa"` | Nombre e identificador de aspecto |
| Keyword | `:dash`, `:playing` | Tipo de acción y estado de ronda |
| Mapa persistente | `{:hp 100 :shield 60}` | Estado de jugador |
| Mapa ordenado | `(sorted-map)` | Jugadores con recorrido estable por ID |
| Vector persistente | `[:dash :shield]` | Acciones, proyectiles y eventos |
| Conjunto | `#{"w" "d"}` | Teclas activas y plazas ocupadas |
| `nil` | Jugador no encontrado | Ausencia de un valor |

En Clojure solo `nil` y `false` son falsos en una condición; `0` y la cadena vacía son verdaderos. Por eso usamos comprobaciones explícitas como `pos?`, `zero?` o `str/blank?`.

Clojure JVM distingue enteros, doubles, ratios y otros tipos numéricos; JavaScript no comparte todas esas representaciones. El motor usa coordenadas y deltas compatibles, y tiempos numéricos de milisegundos. No depende de aritmética racional exacta para sincronizar plataformas.

## 5. Datos inmutables, persistencia y cambio de estado

**Inmutabilidad** significa que un valor no se modifica después de crearse. **Persistencia de estructuras** significa que las versiones anteriores siguen siendo válidas al construir versiones nuevas; pueden compartir partes internas que no cambiaron. No significa guardar automáticamente el juego en disco.

```clojure
(def antes {:hp 100 :shield 0})
(def despues (assoc antes :hp 80))

antes   ;; => {:hp 100 :shield 0}
despues ;; => {:hp 80  :shield 0}
```

`assoc`, `assoc-in`, `update`, `update-in`, `conj` y `dissoc` devuelven valores. Ninguna de estas llamadas convierte por sí sola a `antes` en el nuevo mapa.

El juego necesita evolucionar. Distinguimos:

1. **Valor:** un mapa concreto del mundo en un instante.
2. **Identidad:** la sala que continúa siendo la misma sala a través del tiempo.
3. **Estado:** el valor que esa identidad tiene en un instante.

Un **agente** conserva la referencia al estado vigente de una sala. Una acción produce un nuevo valor y el agente pasa a apuntar a él. Los valores almacenados son inmutables; la referencia evoluciona de forma controlada.

En el navegador, un **átomo** guarda el estado local de interfaz/entrenamiento. `swap!` aplica una función al valor actual y cambia la referencia atómicamente. Un átomo no equivale a un agente: la actualización del átomo es síncrona; la acción del agente se despacha asíncronamente.

Fuentes: [estructuras de datos](https://clojure.org/reference/data_structures), [átomos](https://clojure.org/reference/atoms).

## 6. Funciones puras y transparencia referencial

Una función pura produce el mismo resultado con los mismos argumentos y no genera efectos externos observables.

La función central tiene esta interfaz:

```clojure
(step mundo entradas delta tiempo)
;; => nuevo-mundo
```

El tiempo se recibe como argumento; el motor no llama al reloj del sistema. Las posiciones de suministros siguen una secuencia definida; la aleatoriedad visual está fuera del motor. El resultado puede repetirse en una prueba sin navegador ni red.

Ejemplo ejecutable desde la carpeta del proyecto:

```bash
bb -cp src -e '(require (quote [arena.engine :as e]))
  (let [w (e/training "Demo" "brasa" 0)
        input {"you" (assoc e/empty-input :dx 1)}
        a (e/step w input 0.025 25)
        b (e/step w input 0.025 25)]
    (println "Iguales:" (= a b))
    (println "X anterior:" (get-in w [:players "you" :x]))
    (println "X nueva:" (get-in a [:players "you" :x])))'
```

La transparencia referencial permite razonar sustituyendo una llamada pura por su valor sin cambiar el comportamiento del resto del cálculo. Facilita probar daño, escudo e inmunidad con tiempos concretos.

**No todas las funciones del programa son puras.** Dibujar, reproducir audio, leer teclas, generar IDs, consultar el reloj y enviar WebSockets son efectos. Se concentran en `server.clj`, `client.cljs` y `view.cljs`. Presentar una aplicación interactiva como “sin efectos secundarios” sería incorrecto. El diseño es un núcleo funcional con adaptadores de entrada/salida.

## 7. Expresiones, composición y control

Clojure favorece expresiones que devuelven valores. `if`, `let`, `case`, `cond` y `->` ayudan a describir cómo producir el siguiente estado.

```clojure
(-> mundo
    (assoc-in [:players id :shield] 60)
    (assoc-in [:players id :shield-until] (+ ahora 5000)))
```

El macro `->` inserta el valor como primer argumento de la forma siguiente. Es equivalente a anidar esas llamadas; no es un operador que muta el estado. `cond->` encadena transformaciones cuando se cumplen condiciones, y `case` selecciona una habilidad entre casos conocidos.

`doseq` se usa cuando interesa realizar efectos para cada elemento (enviar mensajes, dibujar). `mapv` se usa cuando interesa construir un vector con los resultados. Elegirlos según la intención vuelve más legible el código.

## 8. Subprogramas y funciones de orden superior

Las funciones son valores de primera clase: se pueden pasar, devolver o almacenar. Una función de orden superior recibe o devuelve otras funciones.

- `mapv` construye proyectiles a partir de ángulos.
- `filterv` conserva efectos que no expiraron.
- `reduce` recorre impactos y acumula un nuevo mundo.
- `sort-by` construye el marcador.
- `update` recibe la función que transforma un campo.
- `send` recibe la función que transforma el estado de un agente.

Ejemplo simplificado de reducción:

```clojure
(reduce
  (fn [mundo bala]
    (advance-shot mundo bala dt))
  mundo-sin-balas
  balas-anteriores)
```

Cada paso recibe el resultado del anterior. No hay una variable global `daño-acumulado` compartida por todos los jugadores.

**Desestructuración:** `{:keys [x y]}` y `[x y]` enlazan componentes de mapas y vectores. No destruyen ni extraen físicamente datos de la estructura.

## 9. Recursión y evaluación

`loop` y `recur` implementan iteración por recursión en posición de cola. El servidor subdivide el tiempo en pasos de hasta 25 ms. `recur` reutiliza el punto de repetición sin hacer crecer la pila como una llamada recursiva ordinaria.

No afirmamos que Clojure optimice automáticamente toda recursión. La forma explícita `recur` exige posición de cola y número correcto de argumentos.

Las secuencias creadas por `map` o `filter` pueden ser perezosas: el trabajo se realiza cuando se consume la secuencia. En estado de juego preferimos `mapv`, `filterv` y `vec` para obtener colecciones concretas. La pereza no debe usarse para esconder efectos que esperamos ejecutar inmediatamente.

## 10. Concurrencia: agentes, actores y mensajes

**Concurrencia** permite gestionar tareas cuyos períodos de ejecución se solapan. **Paralelismo** implica ejecución al mismo tiempo en recursos distintos. El modelo de agentes permite concurrencia y puede usar varios hilos; no garantiza que cada acción vaya a un núcleo exclusivo.

Un agente se crea así:

```clojure
(def sala (agent {:tick 0 :players {}}))
(send sala update :tick inc)
;; send retorna sin esperar a que la acción termine
```

Un agente procesa una acción a la vez y sustituye su estado por el valor retornado. Las acciones de distintos agentes pueden ejecutarse concurrentemente. No debemos tratar `@sala` inmediatamente después de `send` como prueba de que ya terminó la acción; en pruebas podemos usar `await`/`await-for`.

Se parece al modelo de actores porque serializa mensajes por entidad, pero hay diferencias: un agente de Clojure recibe funciones y argumentos, permite leer su estado con `deref` y no ofrece por sí solo el mismo aislamiento o supervisión que un proceso de Erlang/Elixir. Lo defendible es decir **modelo de agentes de Clojure**, no fingir que se implementó Erlang.

En el proyecto:

- Un agente gestor decide plazas y pertenencia a salas.
- Un agente por conexión valida mensajes del jugador.
- Un agente por sala contiene el mundo compartido y procesa ticks.
- Un planificador envía ticks sin escribir directamente el mundo.

La sala agrupa vida, balas y puntuación de todos sus jugadores. Así un disparo se resuelve coherentemente en una sola transición. La interfaz de red nunca acepta “me asigno 1000 de vida”; acepta “quiero moverme/disparar/activar Q”.

Fuente: [Agents and Asynchronous Actions](https://clojure.org/reference/agents).

## 11. Excepciones, validación y recuperación

`try`/`catch` maneja mensajes mal formados o errores de red. Los mensajes se leen como **datos EDN**, no como código ejecutable. Se validan tipos, valores finitos, límites de coordenadas, lista de acciones, número de plazas y quién puede iniciar una ronda.

El servidor configura `set-error-handler!` y `:continue` en agentes para registrar un fallo sin dejar todo el gestor inutilizable. Esto no sustituye las pruebas: una transición errónea se descarta y debe investigarse; no es una promesa de recuperación perfecta.

`finally` libera la marca de tick pendiente aunque la simulación lance una excepción. Al perder foco, el cliente limpia teclas y disparo. El servidor deja de aplicar controles que llevan más de 300 ms sin renovarse, evitando un jugador que camine indefinidamente por una tecla perdida.

## 12. OOP y programación lógica: qué comparar sin inventar

El paradigma dominante del juego es funcional. No hay clases `Jugador`, `Bala` y `Escudo` con campos mutables que se actualizan mediante métodos.

Sí existe interoperabilidad con APIs orientadas a objetos: Canvas, WebSocket, `Image`, Web Audio y clases Java. Invocar un método de una plataforma anfitriona no cambia el diseño del núcleo. Separamos el cálculo funcional de esas llamadas con efectos.

No usamos programación lógica ni un motor de inferencias. Podemos comparar que Prolog expresa relaciones y consultas, mientras que aquí escribimos transformaciones de estado. No conviene presentar `cond` o un mapa de reglas como “programación lógica” solo porque contiene condiciones.

## 13. Rendimiento sin renunciar a la semántica

El motor usa un mapa agregado por sala y colecciones persistentes. Limita balas, eventos y jugadores. El render limita partículas, reutiliza sprites y guarda el piso en un Canvas auxiliar. El HUD se actualiza aproximadamente cada 60 ms y la física a pasos acotados; no se reconstruye la página entera a cada cuadro.

La inmutabilidad no significa copia profunda de todo el mundo por cada cambio. Tampoco significa coste cero: los valores nuevos y la serialización asignan memoria. La prueba con veinte conexiones verifica el objetivo de esta exposición; no permite extrapolar a miles de jugadores.

## 14. Guion de exposición de 6–8 minutos

1. **Objetivo (30 s).** Arena de cuatro jugadores. Mostrar cuatro equipos en la misma sala y explicar quién es la PC anfitriona.
2. **Demostración (1 min).** WASD/clic, Q, Shift y F. Señalar diferencia entre duración activa y recarga. Mostrar que la nova anuncia su área y se puede esquivar.
3. **Estado y pureza (1 min).** Abrir `engine.cljc`, mostrar `player`, `damage-player` y `step`. Ejecutar el ejemplo que conserva el mundo anterior.
4. **Ámbito y tipos (1 min).** Mostrar parámetros, `let`, desestructuración y keywords. Explicar por qué `:hp` no es una variable global.
5. **Concurrencia (1,5 min).** Usar el diagrama. Mostrar los agentes del gestor, jugador y sala; describir un mensaje `:input` hasta `:snapshot`.
6. **Validación (1 min).** Ejecutar `bb test`; mostrar el resultado registrado de `bb test-network`. Intentar entrar con una quinta persona a una sala llena.
7. **Límites (30 s).** La partida depende de la PC anfitriona. El túnel permite acceder desde otra red; no mueve la simulación a Cloudflare.

## 15. Preguntas probables

**¿Cómo cambia la vida si los datos son inmutables?** Se construye otro mapa y el agente conserva la referencia al nuevo estado. El mapa anterior no se altera.

**¿Todo el programa es puro?** No. Las reglas son puras; red, dibujo y entrada/salida son efectos en módulos separados.

**¿Por qué agentes para jugadores y para la sala?** Los primeros ordenan/validan las entradas de cada conexión. La sala decide las interacciones entre todos con una transición coherente.

**¿Hay un hilo por jugador?** No. Los agentes usan un pool de hilos.

**¿ClojureScript es JavaScript disfrazado?** Es un lenguaje de la familia Clojure que se compila a JavaScript, como otros lenguajes compilan a código para una plataforma. El código escrito conserva sintaxis funcional y colecciones persistentes. El servidor ejecuta Clojure.

**¿El navegador puede hacer trampa cambiando su vida?** Puede alterar su pantalla local, pero el servidor ignora vida y posiciones enviadas por el cliente: calcula el estado válido. Esto no equivale a un sistema antitrampas comercial.

**¿Por qué no se usa una base de datos?** Las rondas son efímeras para una exposición; no necesitamos conservar cuentas ni resultados. Agregar persistencia no ayuda a cumplir los requisitos actuales.

**¿Por qué no alcanza con abrir index.html para multijugador?** Un archivo local no ejecuta el servidor ni ofrece un WebSocket a otros equipos. Para salas usamos `bb serve` y compartimos la dirección del anfitrión.

**¿Qué pasa si dos balas impactan al mismo tiempo?** La sala procesa una secuencia de colisiones de forma serial. Una baja se registra una vez; una víctima ya eliminada no vuelve a recibir daño en esa transición. No existe simultaneidad matemática perfecta en un tick discreto.
