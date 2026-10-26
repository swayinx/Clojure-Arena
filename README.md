# Clojure Arena — Patio Cero

Arena de combate para **2–4 jugadores por sala**, con hasta **cinco salas independientes** en una PC anfitriona. Incluye entrenamiento individual, dash con inmunidad, escudos, curación, armas temporales, nova, marcador e invitaciones por enlace/QR.

El código de aplicación está en **Clojure y ClojureScript**. La simulación es un núcleo funcional compartido (`.cljc`); el servidor usa **agentes reales de Clojure** y el navegador usa el compilador oficial de ClojureScript y sus colecciones persistentes.

## 1. Lo mínimo para jugar

**Solo la PC anfitriona instala el proyecto y Babashka.** Las otras PCs abren un enlace en Brave, Chrome, Firefox o Edge con teclado y ratón. No instalan Clojure, Node ni el ZIP.

1. Instala [Babashka](https://github.com/babashka/babashka#installation). Versión comprobada: **1.13.227**. Descarga el ejecutable de tu sistema y pon `bb` en el `PATH`.
2. Abre una terminal en la carpeta del proyecto.
3. Ejecuta:

```bash
bb serve
```

4. Abre **http://localhost:8080/** en la PC anfitriona. Mantén la terminal abierta.

`public/client.js` ya está compilado e incluido en Git. **No necesitas Java ni npm para ejecutar esa versión.** La primera compilación desde fuentes sí necesita Java e Internet; luego también puede hacerse sin red con el compilador descargado.

Para detener todo: **Ctrl+C**. Si el puerto está ocupado, cierra el otro servidor o cambia `PORT`:

```bash
# Linux / macOS
PORT=8082 bb serve
```

```powershell
# Windows PowerShell
$env:PORT="8082"
bb serve
```

### Instalación rápida opcional con un gestor

- Linux/macOS con mise: `mise install babashka@1.13.227`, después `mise use -g babashka@1.13.227`.
- macOS con Homebrew: `brew install borkdude/brew/babashka`.
- Windows: descarga `bb.exe` desde las [releases oficiales](https://github.com/babashka/babashka/releases) y añade su carpeta al `PATH`; verifica con `bb --version`.

## 2. Jugar en la misma Wi-Fi

1. El anfitrión ejecuta `bb serve`. La terminal muestra una dirección como **http://192.168.1.37:8080**. Cada red puede asignar una IP distinta.
2. Todos abren **esa dirección**. `localhost` siempre significa la propia PC: no es el enlace que se comparte.
3. Escribe un nombre, elige personaje y crea una sala con acceso **Misma Wi-Fi**.
4. Copia la invitación o muestra el QR. Los amigos eligen nombre y pulsan **UNIRME**, o seleccionan la sala abierta.
5. El creador inicia con 2–4 personas. Para demostrar la consigna del curso, inicia con **cuatro**.

El listado muestra las salas de **ese anfitrión**. No hay un directorio mundial ni descubrimiento automático de todas las PCs de la red. Otra PC puede ejecutar su propio `bb serve`; tendrá su propia dirección y sus propias salas.

Si no conecta: revisa que todos usen la misma red, que el firewall permita TCP 8080 desde la red local y que el Wi-Fi no aísle dispositivos entre sí (algunas redes de invitados o universidades lo hacen). Prueba un hotspot si el salón tiene aislamiento.

## 3. Jugar por Internet con enlace temporal

Instala [cloudflared desde su fuente oficial](https://developers.cloudflare.com/tunnel/downloads/). Ponlo en el `PATH`, o guarda el ejecutable como `.tools/cloudflared` (`.tools/cloudflared.exe` en Windows).

Detén el servidor anterior para liberar el puerto y ejecuta:

```bash
bb internet
```

Este comando inicia **el mismo servidor** y un Quick Tunnel de Cloudflare. La terminal imprime un enlace como `https://nombre-aleatorio.trycloudflare.com`. La aplicación detecta ese enlace y lo usa para las invitaciones de salas **Internet**. Espera a que el túnel termine de conectarse.

1. Crea una sala con acceso **Internet**.
2. Comparte su enlace/QR. Un amigo puede entrar desde otra red.
3. Mantén encendida la PC, el servidor y el túnel.
4. Al pulsar Ctrl+C, el enlace deja de funcionar. En el siguiente arranque cambiará.

**Elegir “Internet” en la sala no crea un túnel por sí solo.** Necesitas `bb internet`, o un túnel iniciado por separado. Las salas **Misma Wi-Fi** no se anuncian ni aceptan jugadores a través del túnel.

Para usar un dominio/túnel ya existente:

```bash
ARENA_PUBLIC_URL=https://tu-dominio.example bb serve
# En otra terminal:
cloudflared tunnel --url http://localhost:8080
```

Con un Quick Tunnel no necesitas una cuenta de Cloudflare ni comprar un dominio. Está pensado para pruebas, no garantiza disponibilidad. El tráfico de la página **y del WebSocket** pasa por el túnel; **la PC sigue calculando las partidas**. No se despliega un servidor de juego en Cloudflare. [Documentación oficial de Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).

## 4. Controles y reglas

| Acción | Tecla | Qué sucede | Disponibilidad |
|---|---|---|---|
| Movimiento | WASD | Velocidad constante; diagonal normalizada | Continuo |
| Apuntar/disparar | Ratón / mantener clic izquierdo | Proyectiles contra jugadores y cobertura | Según el arma |
| Dash | Shift | Impulso, inmunidad solo durante sus 180 ms activos | Recarga de 2,5 s |
| Escudo | Q | 60 puntos de absorción, máximo 5 s | Recarga de 10 s |
| Curación | E | +35 vida, máximo 100; no se gasta con vida completa | Recarga de 12 s |
| Potencia | R | +15 daño por proyectil durante 6 s | Recarga de 13 s |
| Nova / ulti | F | Apunta al suelo; explota tras 0,65 s, radio 125, daño 65, alcance 280 | Carga 100% |
| Marcador | Tab | Ver bajas, muertes y daño | Mantener; también botón |

El **aro** de cada habilidad muestra su recarga. El número dice cuánto falta para reutilizarla. La **barra inferior** indica cuánto dura el efecto activo. Ambas cosas pueden ocurrir a la vez: Q dura 5 s y recarga 10 s desde su activación.

Nova empieza al 20% en multijugador y al 100% en entrenamiento. Gana 2 puntos/s, 0,2 por punto de daño infligido y 22 por eliminación; una nova no genera carga por su propio daño, pero sí por una baja. Una muerte conserva el 65% de la carga que tenías. Reapareces tras 2,5 s con 1,2 s de protección de aparición, señalada por un aro; esa protección es distinta de la inmunidad del dash.

Gana quien llegue a **12 bajas**, o quien lidere al terminar **3 minutos**. Desempate: menos muertes, después más daño. Si todo sigue igual, empate. La cobertura detiene balas y desplazamiento; la nova afecta el área marcada incluso alrededor de cobertura.

### Suministros

El primer suministro se anuncia a los 8 s; después cae uno cada 9 s. Tarda 1 s en aterrizar. Recógelo caminando sobre él. Rotan por cinco ubicaciones y tipos, de forma determinista.

| Suministro | Efecto |
|---|---|
| Ráfaga | Arma de disparo rápido durante 12 s |
| Dispersor | Cinco proyectiles en abanico durante 12 s |
| Vida | +35 vida |
| Escudo | 60 puntos durante 6 s |
| Energía | +35 de carga para Nova |

El arma básica **Pulso** vuelve al terminar la mejora. No hay munición ni recarga de cargadores en esta versión.

### Pantalla completa, interfaz y sonido

Pulsa **PANTALLA COMPLETA** arriba a la derecha. **Esc** vuelve a la ventana; **F11** es la alternativa del navegador. Durante combate se compactan las barras superiores y las habilidades pasan a una columna lateral, conservando la proporción del mapa. En ventanas estrechas aparecen en una barra inferior compacta.

Los iconos indican dash (impulso), escudo, curación (cruz), potencia (rayo) y nova (estrella). El aro conserva la recarga y la barra pequeña muestra la duración activa. Los detalles completos están en **CÓMO JUGAR** y en la descripción al posar el cursor sobre una habilidad.

**AJUSTES** permite regular volumen, silenciar y reducir cámara/partículas. Se usan 12 archivos de audio de Kenney con licencia CC0, incluidos localmente: [créditos y archivos](docs/RECURSOS.md). No necesitas Internet para oírlos en LAN. Abre siempre el juego mediante `http://…`, no como `file://`.

## 5. Modificar el código

Instala **Java 21**. Node es opcional: se usa para las pruebas automatizadas del cliente y de conexiones, y como atajo de comandos npm.

```bash
java -version
bb build
bb serve
```

`bb build` descarga el compilador oficial **ClojureScript 1.12.145** en `.tools/cljs.jar`, comprueba su SHA-256 y genera `public/client.js` con optimizaciones avanzadas. No edites ese archivo generado. En este equipo también puedes usar el Java configurado por `mise.toml`.

Durante desarrollo:

```bash
# Compila antes de iniciar:
bb dev

# O usa dos terminales:
bb watch
bb serve
```

Después de recompilar, recarga la página. `bb serve` sirve la compilación existente; no observa archivos. `bb watch` sí recompila los cambios de fuentes.

Si tienes Node, los atajos `npm run dev`, `npm run build`, `npm start` y `npm test` llaman a los mismos comandos de Babashka. **npm install no instala el lenguaje ni Java**.

### Alternativa: Clojure en la JVM

Se incluye `deps.edn` para usar Java 21 y la [CLI oficial de Clojure](https://clojure.org/guides/install_clojure):

```bash
clojure -M:build
clojure -M:serve
clojure -M:test
```

La ruta de ejecución comprobada para la exposición es **Babashka 1.13.227**. El servidor usa APIs comunes de Clojure/http-kit; Babashka es un intérprete nativo de un subconjunto de Clojure, no otra lógica de juego.

## 6. Pruebas

```bash
bb test
# Compilación + reglas + cliente con DOM simulado (requiere Java 21 y Node 22+):
bb verify
# En otra terminal debe estar ejecutándose bb serve:
bb test-network
```

`bb test-network` requiere Java 21 y **Node 22+** con WebSocket incorporado. Crea veinte clientes en cinco salas, juega, comprueba la sincronización y cierra sus conexiones. Ejecútalo con el anfitrión vacío; no lo mezcles con una exposición activa. Para otro destino, configura `ARENA_TEST_URL=ws://localhost:8082/ws`.

`bb test-client` ejecuta el bundle real con adaptadores DOM/Canvas simulados. Comprueba controles, indicadores y QR sin abrir un navegador. No sustituye la revisión visual ni mide FPS.

Resultados y límites de las comprobaciones: [docs/PRUEBAS.md](docs/PRUEBAS.md).

## 7. Archivos y entrega del curso

| Archivo | Responsabilidad |
|---|---|
| `src/arena/engine.cljc` | Reglas puras e inmutables, compartidas entre Clojure y ClojureScript |
| `src/arena/server.clj` | Agentes, salas, HTTP, WebSocket y túnel |
| `src/arena/client.cljs` | Controles, interfaz, sincronización y predicción de movimiento |
| `src/arena/view.cljs` | Canvas, partículas, sprites y cámara |
| `src/arena/audio.cljs` | Muestras de sonido, caché, mezcla y volumen |
| `public/index.html`, `public/style.css`, `public/combat.css` | Estructura y presentación de la página |
| `public/art/` | Cuatro personajes y ocho cuadros por personaje |
| `test/arena/` | Pruebas de reglas, protocolo e integración |
| `scripts/arena/build.clj` | Compilación reproducible y comprobación del compilador |

- [Preparación rápida para la exposición](docs/INICIO_RAPIDO_EXPOSICION.md).
- [Teoría, manual del lenguaje y guion de defensa](docs/TEORIA_Y_DEFENSA.md).
- [Arquitectura, diagrama y correspondencia con el PDF](docs/ARQUITECTURA.md).
- [Investigación y decisiones visuales/técnicas](docs/INVESTIGACION_Y_DISENO.md).
- [Pruebas realizadas y revisión manual](docs/PRUEBAS.md).
- [Licencias de dependencias y recursos](docs/RECURSOS.md).

El proyecto se conserva en **Git local**, sin remoto ni publicación en GitHub. Usa `git log --oneline` para ver sus versiones. `.tools/`, `node_modules/`, `build/` y paquetes `dist/` no se versionan. El bundle del navegador sí se versiona para poder ejecutar la entrega sin instalar herramientas de compilación.

## Límites explícitos

Esta entrega está orientada a una demostración de hasta veinte personas. No tiene cuentas, persistencia de resultados, matchmaking global, recuperación de una partida después de apagar el servidor ni migración de la simulación a otra PC. Al desconectarse el creador, cambia el creador de la sala; si se apaga la **PC anfitriona**, todas sus salas terminan.

Los jugadores pueden crear salas dentro del anfitrión compartido. Eso no convierte sus navegadores en servidores. Para hospedar desde otra PC se ejecuta otra copia del servidor y se comparte su enlace.

El diseño usa una ventana de altura fija y arena ajustable, con menús modales. Es un juego para teclado y ratón; no incluye controles táctiles para teléfonos. El QR facilita la invitación, no convierte el juego en uno táctil.
