# Preparación para la exposición

## Antes de salir de casa

1. En la PC anfitriona verifica `bb --version` y ejecuta `bb test`.
2. Si cambiaste fuentes, ejecuta `bb build`. Lleva la carpeta completa, incluyendo `public/client.js`, `public/fonts/` y `public/art/`.
3. Prueba `bb serve` y abre `http://localhost:8080`. No abras un enlace `file:///...` para multijugador.
4. Lleva un hotspot como alternativa a un Wi-Fi que aísle clientes.
5. Si usarás Internet, comprueba `cloudflared --version` o `.tools/cloudflared --version`. El QR se genera con la URL de esa sesión; no reutilices el de un túnel anterior.

## En el salón

1. Conecta la PC anfitriona y otras tres PCs a la misma red.
2. Ejecuta `bb serve`. Comparte la dirección `http://IP-DE-LA-PC:8080` que aparece en la terminal.
3. Abre una sala “Misma Wi-Fi”, comparte su QR/enlace y reúne a cuatro jugadores.
4. Explica durante la espera: una PC calcula la partida, cada navegador dibuja y envía controles, cada jugador tiene un agente de conexión y cada sala otro agente.
5. Inicia la partida. Demuestra dash, escudo y el marcador.
6. Para mostrar Nova inmediatamente sin esperar su carga, vuelve al inicio y entra a **Entrenar**; comienza con F cargada.

## Si alguien está fuera de esa red

Detén el servidor y ejecuta `bb internet`. Crea una sala **Internet** y comparte su enlace público. No hace falta una cuenta de Cloudflare para este túnel temporal. Mantén la PC encendida.

## Archivos para explicar

- `src/arena/engine.cljc`: mapas, funciones puras y transición de estado.
- `src/arena/server.clj`: agentes, mensajes y sincronización.
- `docs/ARQUITECTURA.md`: diagrama del sistema.
- `docs/TEORIA_Y_DEFENSA.md`: ámbito léxico, enlaces, tipos, inmutabilidad, funciones de orden superior y guion de defensa.
- `docs/PRUEBAS.md`: evidencia automática y revisión visual que falta completar.

## Si falla algo

| Problema | Acción |
|---|---|
| `bb` no existe | Instalar Babashka y añadirlo al PATH |
| `Address already in use` | Cerrar el servidor anterior o elegir otro PORT |
| Solo conecta el anfitrión | Compartir IP de red, comprobar firewall/aislamiento Wi-Fi |
| Se ve una versión vieja | `bb build` y recargar la página |
| “Internet” muestra una URL local | Falta iniciar el túnel; usar `bb internet` |
| El túnel dejó de funcionar | Crear uno nuevo y compartir el nuevo enlace |
| Se siente pesado | Reducir partículas y apagar cámara; comparar en navegador normal |
| Se perdió una sala | Volver a crearla; el estado es efímero y no se guarda en disco |

El servidor no debe cerrarse mientras los compañeros estén jugando. Cerrar una pestaña y detener la PC anfitriona son acciones distintas.
