# Investigación y decisiones de diseño

Consulta: **26 de octubre de 2026**. Las fuentes técnicas son documentación o publicaciones de sus autores. Las experiencias de comunidades se usan como opiniones sobre comodidad, no como evidencia de rendimiento o popularidad.

## 1. Respuesta visual del combate

[Juice It or Lose It, Martin Jonasson y Petri Purho, GDC](https://www.gdcvault.com/play/1016789/Juice-It-or-Lose) demuestra cómo capas de movimiento y efectos pueden dar respuesta a acciones sencillas. Se usa como referencia de técnica, sin copiar recursos visuales.

Aplicación en Clojure Arena:

- El disparo tiene retroceso del cuerpo, arma orientada, fogonazo y estela.
- El impacto tiene partículas, número de daño y marcador en la mira.
- Una eliminación añade un anillo mayor, sonido distinto y aviso de baja.
- El dash conserva la silueta con imágenes residuales y un aro de inmunidad.
- El escudo rota por segmentos y reacciona al impacto. El número de puntos y su duración se muestran por separado.
- La nova avisa antes de explotar: un área marcada y un anillo de carga dejan tiempo para reaccionar.

Los efectos no alteran la simulación. Evitamos detener el tiempo global al impactar: en una arena multijugador un jugador no debe congelar el combate de todos.

## 2. Cámara y comodidad

La charla [Juicing Your Cameras With Math, Squirrel Eiserloh, GDC](https://media.gdcvault.com/gdc2016/Presentations/Eiserloh_Squirrel_JuicingYourCameras.pdf) sirve de referencia para movimiento de cámara y decaimiento de sacudidas.

Se eligió cámara cenital fija que muestra el campo completo. La sacudida se limita al Canvas y decae rápidamente. El HUD y la mira no se desplazan. Hay tres niveles: apagada, suave e intensa. El sistema respeta `prefers-reduced-motion`.

Como contraste, en [esta discusión de desarrolladores sobre screenshake](https://www.reddit.com/r/gamedev/comments/17p2v70/) varios participantes describen molestia con sacudidas fuertes y piden poder apagarlas. En [un foro de GOG sobre GoNNER](https://www.gog.com/forum/gonner/is_there_an_option_to_disable_screen_shaking), un jugador señala que una opción parcial aún dejaba movimiento al disparar. Son experiencias personales; justifican ofrecer una opción realmente en cero, no una afirmación de que toda sacudida sea mala.

## 3. Colores y legibilidad

La guía de Riot [Clarity in League](https://www.leagueoflegends.com/en-us/news/dev/clarity-in-league/) recomienda que la atención visual de una habilidad corresponda a su importancia y que las áreas esquivables sean reconocibles. Aquí la nova tiene el aviso más amplio; el disparo normal usa una estela pequeña. Es una decisión de diseño, no una prueba de que nuestra ejecución ya tenga la calidad de esos juegos.

La paleta reserva funciones claras:

| Color | Significado principal |
|---|---|
| Verde lima claro | Interfaz principal y vida propia |
| Turquesa | Dash / inmunidad |
| Azul | Escudo |
| Verde | Curación |
| Ámbar | Arma mejorada y potencia |
| Violeta | Nova propia y carga de ulti |
| Coral | Proyectiles enemigos y peligro |

El significado no depende solo del color: se acompaña de tecla, texto, forma, aro y número. El escenario tiene menor contraste que personajes, proyectiles y avisos. Los efectos se limitan en cantidad para no esconder el campo.

Los cuatro personajes conservan las mismas estadísticas: la elección es visual. Esto facilita enseñar el sistema y evita que el aspecto implique una ventaja que la interfaz no explique.

## 4. HUD sin desplazamiento

La página es una aplicación de altura de ventana (`100dvh`), con áreas de tamaño flexible y una arena que conserva la proporción 8:5. No se calcula el tamaño del Canvas a partir de todo el ancho si eso supera la altura disponible. En combate se aprovecha la altura disponible: el HUD ocupa una columna lateral de 168 px (150 px en ventanas bajas), en lugar de una fila alta bajo el mapa. El marco se ajusta al mapa y la columna, sin rellenar los lados con grandes paneles vacíos. En ventanas estrechas se mantiene una fila inferior compacta.

Cada habilidad tiene:

1. Tecla visible y nombre.
2. Aro de recarga y segundos hasta poder volver a usarla.
3. Barra de duración activa y su propio contador.

Nova utiliza un porcentaje de carga y muestra **LISTA** cuando alcanza 100%. Los controles completos y los ajustes aparecen en diálogos, sin alargar la página. Las salas se acomodan en dos columnas cuando la ventana es baja. El marcador puede superponerse a la partida.

**Estado de verificación visual:** la estructura y estilos están implementados; la comprobación final en navegador queda registrada por separado en `PRUEBAS.md`. El permiso guardado del navegador de la herramienta impidió inspeccionarla, incluso después de autorización del usuario. No se presenta este diseño como visualmente validado en todas las resoluciones.

## 5. Física y rendimiento

[Fix Your Timestep!, Glenn Fiedler](https://gafferongames.com/post/fix_your_timestep/) explica por qué el delta variable sin acotar causa inestabilidad y por qué se deben limitar los pasos y el trabajo acumulado.

Se aplica física con pasos de hasta **25 ms**, separada del render. En sobrecarga el servidor limita el tiempo recuperado y evita acumular ticks pendientes sin límite. Esto conserva estabilidad; una PC saturada todavía puede perder tiempo de simulación, no se promete capacidad ilimitada.

Además:

- Piso dibujado una vez y reutilizado desde un Canvas auxiliar.
- Sprites compartidos, sin cargar imágenes durante cada cuadro.
- Máximo 160 partículas, 40 anillos y 28 imágenes residuales de dash en el render.
- Máximo 200 balas y 80 eventos recientes en un mundo.
- HUD aproximadamente cada 60 ms, sin reconstruir toda la página por cuadro.
- Sin blur de pantalla completa, grandes filtros por partícula ni recursos externos durante la partida.

## 6. Red: autoridad, predicción e interpolación

La serie de [Gabriel Gambetta sobre arquitectura cliente-servidor](https://www.gabrielgambetta.com/client-server-game-architecture.html), [predicción/reconciliación](https://www.gabrielgambetta.com/client-side-prediction-server-reconciliation.html) e [interpolación](https://www.gabrielgambetta.com/entity-interpolation.html) orienta la separación entre simulación y presentación.

El servidor calcula vida, impactos, puntuación y habilidades. El cliente predice movimiento/dash, conserva entradas pendientes y las repite sobre el estado confirmado. Interpola posiciones remotas entre instantáneas. Esto reduce la sensación de escalones; no elimina la latencia ni implementa rollback o compensación de impactos por ping.

[WebSocket, MDN](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket) documenta la conexión bidireccional del navegador. Se usa un único origen para la página y `/ws`, evitando URLs locales fijas dentro del bundle.

## 7. Agentes y estructuras del curso

La [referencia oficial de agentes](https://clojure.org/reference/agents) define actualizaciones asíncronas y serializadas por identidad. La [referencia de datos](https://clojure.org/reference/data_structures) describe las colecciones persistentes de Clojure.

Se decidió migrar del compilador ligero del prototipo al **compilador oficial de ClojureScript**, para que la explicación de mapas/vectores persistentes sea consistente entre navegador y servidor. Se usa un solo motor `.cljc`, evitando dos conjuntos de reglas que puedan divergir.

## 8. Hosting sencillo

Los [Quick Tunnels de Cloudflare](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) ofrecen un enlace temporal sin cuenta ni dominio. Sus límites publicados incluyen ausencia de garantía de disponibilidad, URL cambiante y un máximo de 200 solicitudes simultáneamente en curso. El soporte de [WebSockets proxied](https://developers.cloudflare.com/network/websockets/) permite transportar la conexión persistente del juego.

La elección para esta exposición es **PC anfitriona + túnel opcional**. No se necesita desplegar Workers, añadir una base de datos ni construir un sistema P2P. La PC debe permanecer encendida mientras dure la sesión. Las salas LAN quedan separadas del acceso por túnel.

## Qué se dejó fuera deliberadamente

No se añadieron cuentas, tienda, progresión persistente, mapas enormes, inventario complejo o habilidades distintas por personaje. El alcance cubre el combate, las salas y los conceptos que exige el curso. Después de probar con compañeros, las mejoras siguientes deberían salir de problemas observados, no de aumentar indiscriminadamente las partículas o las dependencias.

## Ajuste a partir de la prueba del usuario

La captura del 26 de octubre mostró un mapa pequeño dentro de una franja muy ancha, con dos cabeceras y tarjetas grandes bajo la arena. Se compactaron las cabeceras, se retiró el pie durante combate y se movió el HUD al lateral. La geometría preserva la escala uniforme; no deforma el mapa ni cambia las reglas o el apuntado.

Nova conserva 650 ms de aviso y el radio de daño de 125: un borde fijo marca el área, las chispas convergen y la detonación dibuja núcleo, rayo vertical y onda expansiva. El efecto se limita a esa zona, con menor movimiento cuando el sistema lo solicita.

Para sonido se consultaron las publicaciones del autor ([Sci-fi Sounds](https://kenney.nl/assets/sci-fi-sounds), [Digital Audio](https://kenney.nl/assets/digital-audio)), su publicación en [OpenGameArt](https://opengameart.org/content/sci-fi-sounds) y experiencias de desarrolladores en [r/gamedev](https://www.reddit.com/r/gamedev/comments/1tu4lm8/sourcing_sound_effects/). Las recomendaciones comunitarias orientan la búsqueda; la licencia se verificó en las páginas y archivos del autor. Se escogieron muestras cortas, locales y con categorías distintas por acción. No se afirma haber realizado escucha comparativa desde la herramienta.
