# Archivo histórico: prompt del prototipo individual

Este texto se conserva como antecedente. **No describe la versión actual ni debe usarse para modificarla**: ahora hay multijugador, servidor Clojure y compilador oficial de ClojureScript, sin Squint ni esbuild. Las instrucciones vigentes están en [README.md](../README.md) y [docs/ARQUITECTURA.md](../docs/ARQUITECTURA.md).

---

Trabaja SOBRE el proyecto adjunto «Clojure Arena». Es una arena jugable local para una persona. No lo reemplaces por una app nueva ni conviertas el juego a React, TypeScript, JavaScript escrito a mano, Phaser u otro motor. Lee primero README.md, package.json, public/index.html, public/style.css, src/arena/client.cljs y los cuatro sprites en public/art/. Conserva el flujo de compilación ClojureScript con Squint y esbuild. public/client.js es un archivo generado: nunca lo edites como fuente.

OBJETIVO
Haz que esta prueba individual se sienta como un juego de acción pulido. Mantén el nombre «Clojure Arena», los cuatro personajes, la selección, los blancos regenerables, la vida, el escudo, la curación, el daño extra y el marcador. Todavía no implementes salas de varios jugadores, WebSocket, cuentas ni servicios de Google. No muestres jugadores o salas ficticios. No introduzcas imágenes improvisadas, fotos ni recursos externos: mejora los sprites SVG originales o dibuja efectos mediante Canvas desde ClojureScript.

REQUISITO FUNDAMENTAL DE LENGUAJE
Toda la lógica del juego, entrada, estado, física, colisiones, efectos, animaciones y renderizado debe estar escrita en archivos .cljs (ClojureScript). En el futuro, la parte servidora irá en .clj (Clojure). HTML y CSS se permiten solo para estructura y estilos; el JavaScript resultante de compilar ClojureScript se permite como artefacto generado, pero no como código fuente. Los sprites SVG actuales son recursos estáticos: edítalos directamente si mejoras el arte. No agregues dependencias innecesarias.

CONTROLES Y JUGABILIDAD
- WASD: movimiento fluido e independiente de la frecuencia de cuadros, con diagonal normalizada y límites correctos de la arena.
- Ratón: apuntar; mantener clic izquierdo dentro del Canvas para disparar.
- Shift: dash corto y rápido. Dirección: WASD actual; si no se pulsa movimiento, última dirección de movimiento; si nunca hubo movimiento, dirección de apuntado. Cooldown visible de 2,5 segundos.
- Invulnerabilidad SOLO durante los cuadros en que el dash está activo (aprox. 180 ms). Antes y después recibe daño normalmente. La inmunidad del dash se evalúa en el punto central donde se aplica el daño, tanto para balas enemigas como para cualquier otro golpe. El escudo Q sigue siendo una mecánica separada.
- Q: escudo de 60 puntos durante 5 segundos. E: cura 35 sin superar 100. R: aumenta el daño durante 6 segundos. Mantén cooldowns claros y coherentes con el juego actual.
- Los disparos deben colisionar, dañar y destruir blancos. Los blancos responden con proyectiles visibles. Morir, reaparecer y reiniciar deben dejar limpio el estado temporal.

DIRECCIÓN VISUAL Y ANIMACIONES
Mejora con intención los cuatro sprites existentes: idle, caminar, dash, recibir daño y disparar deben ser legibles y coherentes. Añade estela al dash, impulso de velocidad, fogonazo de arma, proyectiles con color y rastro, chispas al impactar, explosión breve al destruir un blanco, indicador de daño recibido y entrada/salida al reaparecer. El escudo debe tener activación, contorno animado, destello al absorber y ruptura o expiración. La curación debe verse claramente y el daño extra debe cambiar de forma visible los tiros y el personaje. Usa una paleta consistente para cada poder. Añade una sacudida breve de la arena al impacto fuerte; no muevas el HUD ni toda la página de forma molesta. Respeta prefers-reduced-motion.

CALIDAD DE IMPLEMENTACIÓN
Separa funciones de actualización del estado y funciones de dibujo para que el ClojureScript sea entendible. Evita efectos que crezcan sin límite: elimina proyectiles y partículas al expirar, limita su cantidad si hace falta y usa requestAnimationFrame con delta acotado. Evita que Shift y otras teclas activen conductas no deseadas del navegador mientras se juega. La experiencia debe funcionar en Brave y en localhost, sin Internet y sin APIs pagadas. Mantén recursos con rutas relativas para que también funcione al abrir public/index.html como archivo después de compilar.

ENTREGA Y VERIFICACIÓN
Edita los archivos reales del proyecto. Ejecuta npm install si hace falta y npm run build. Prueba en navegador al menos: elegir cada personaje; WASD y diagonal; apuntar y disparar; destruir un blanco; Q, E y R; dash con Shift; daño durante dash = 0 y daño inmediatamente después > 0; cooldown del dash; muerte, reaparición y reinicio. Si tu entorno no permite probar algo, indícalo exactamente. Entrega una lista breve de archivos cambiados, controles finales, pruebas realizadas y limitaciones reales. No me entregues pseudocódigo ni solo una explicación: quiero el proyecto funcionando.
