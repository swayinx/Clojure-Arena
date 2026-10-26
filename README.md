# Clojure Arena

Prototipo de acción jugable **para una persona** escrito en ClojureScript (`src/arena/client.cljs`). La sala de entrenamiento permite elegir entre cuatro personajes originales (**Brasa**, **Chispa**, **Mora** y **Bastión**) y probar movimiento diagonal normalizado, apuntado con ratón, disparos con rastro, evasión rápida con **Dash** (`Shift`), escudo (`Q`), curación (`E`), daño aumentado (`R`) y blancos tácticos regenerables que contraatacan.

## Abrir y compilar la prueba

```bash
npm install
npm run build
npm run preview
```

Abrir <http://localhost:8080/> (o el puerto 3000 con `npm run dev`). También puedes abrir `public/index.html` directamente en Brave o cualquier navegador moderno después de ejecutar `npm run build`. Las fuentes y los sprites SVG están incluidos localmente con rutas relativas; la prueba funciona completamente sin Internet.

`npm run dev` y `npm run preview` compilan primero el ClojureScript, para que el navegador cargue los cambios recientes y no un `public/client.js` anterior.

## Controles finales

| Acción | Control | Detalles |
|---|---|---|
| Moverse | `W`, `A`, `S`, `D` | Movimiento fluido independiente de FPS con diagonal normalizada y límites de arena |
| Apuntar | `Ratón` | Apunta hacia el cursor dentro del Canvas |
| Disparar | `Clic izquierdo` (mantener) | Disparo continuo dentro del Canvas con fogonazo, rastro e impactos |
| Dash evasivo | `Shift` | Impulso corto y rápido (~180 ms) con inmunidad total al daño mientras dura; recarga de 2,5 s. Dirección: WASD actual → última dirección de movimiento → dirección de apuntado |
| Escudo | `Q` | Absorbe hasta 60 puntos de daño durante 5 s; recarga de 10 s |
| Curación | `E` | Recupera 35 de vida (sin superar 100) cuando falta vida; recarga de 12 s |
| Daño extra | `R` | Aumenta el daño de 20 a 35 durante 6 s con proyectiles potenciados; recarga de 13 s |

Los blancos se regeneran tras ser destruidos y el marcador cuenta las eliminaciones de blancos. Al perder toda la vida, el personaje reaparece limpiando cualquier estado temporal.

## Próximo paso: multijugador local

1. Una PC ejecutará el servidor Clojure (`.clj`) y compartirá su dirección de Wi-Fi, por ejemplo `http://192.168.1.37:8080`.
2. Los demás abrirán esa dirección sin instalar el proyecto. El servidor ofrecerá la página y el WebSocket en un mismo puerto.
3. Ese servidor podrá tener varias salas independientes, cada una con hasta cuatro jugadores. Creación, unión y estado de partida aún no están implementados.
4. Después de probar cuatro dispositivos en la misma Wi-Fi, se podrá probar acceso desde Internet mediante un túnel temporal si hace falta.
