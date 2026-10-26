# Comprobaciones de la entrega

Fecha: 26 de octubre de 2026. Las pruebas automáticas se ejecutaron contra el código de esta versión. No se infiere validación visual a partir de una compilación correcta.

## Comprobado

| Comprobación | Resultado | Cómo repetir |
|---|---|---|
| Compilación oficial ClojureScript, optimización avanzada | Correcta, bundle de aproximadamente 281 KB sin comprimir | `bb build` |
| Sintaxis del bundle | Sin error | `node --check public/client.js` |
| Cliente compilado con DOM/Canvas simulados | Arranque, poderes, ulti, QR, pantalla completa, carga de 12 muestras, volumen y silencio; sin excepciones | `bb test-client` |
| Reglas y frontera de red | 10 pruebas, 50 aserciones, 0 fallos | `bb test` |
| 20 clientes WebSocket, 5 salas de 4 | 15 comprobaciones correctas | `bb test-network`, con `bb serve` vacío en otra terminal |
| Estados idénticos por tick | Confirmado para los 4 miembros de cada una de las 5 salas | Prueba de integración |
| Límite de salas/plazas | Sexta sala y quinto jugador rechazados | Prueba de integración |
| Inicio exclusivo del creador | Confirmado | Prueba de integración |
| Aislamiento entre salas | Movimiento y membresía separados | Prueba de integración |
| Salida de creador | Miembro eliminado y rol transferido | Prueba de integración |
| Túnel real Cloudflare / HTTPS | `/health` y `/api/info` respondieron correctamente | `bb internet` |
| Upgrade WebSocket con Origin HTTPS | Respuesta HTTP 101 | Prueba del túnel |
| Dos clientes por WSS a través del túnel | Partida iniciada y habilidad sincronizada | Variante Internet de `network_test.cljs` |
| Salas LAN desde Internet | No se listan, no admiten unión ni creación por túnel | 6 comprobaciones de Internet correctas |
| Audio OGG | 12 archivos decodificados con FFmpeg sin errores; HTTP entrega los bytes originales con `audio/ogg` | Archivos en `public/audio/` |
| Procedencia de audio | Bytes idénticos a los ZIP descargados de Kenney; licencias incluidas | `docs/RECURSOS.md` |
| HTML/recursos | Etiquetas balanceadas, 72 IDs únicos, referencias DOM válidas, cuatro SVG válidos | Comprobación estática |

El túnel de prueba es temporal. Su URL no forma parte de la configuración permanente ni debe incluirse en el QR de la exposición. Crea uno nuevo al realizar la demostración.

### Qué cubren las pruebas del motor

- Mapa anterior intacto y resultado determinista.
- Dash inmune antes del límite y vulnerable al terminar.
- Cooldown evita reactivar una habilidad antes de tiempo.
- Escudo absorbe antes que la vida.
- Curación limitada a 100, sin consumirse a vida completa.
- Nova anuncia antes de causar daño y se puede esquivar con dash.
- Bajas, muertes, reaparición y final de ronda.
- Movimiento bloqueado por cobertura y diagonal normalizada.
- Suministros recogidos y arma temporal que expira.
- Controles inválidos y tipos no esperados descartados/acotados.
- Clasificación de acceso LAN/túnel y restricción de origen WebSocket.

## Ejecución reproducible de red

Primera terminal:

```bash
bb serve
```

Segunda terminal, con Java 21 y Node 22+:

```bash
bb test-network
```

No ejecutar durante una partida real: la prueba ocupa las cinco salas. Usa otro puerto si necesitas separar pruebas de demostración.

Para comprobar el túnel:

```bash
# Primera terminal:
PORT=8082 bb internet

# Segunda terminal, usa el enlace que acaba de imprimir:
ARENA_TEST_URL=ws://localhost:8082/ws \
ARENA_TEST_PUBLIC_URL=https://tu-enlace.trycloudflare.com \
bb test-network
```

La variante de Internet crea una sala LAN por conexión directa y comprueba que dos conexiones WSS no puedan verla ni unirse; luego crea una sala Internet, inicia la partida y confirma una acción en el otro cliente.

## Medición acotada del motor

Una ejecución local de 2.400 pasos (60 segundos simulados) de entrenamiento en Babashka dio aproximadamente **0,22 ms por paso**, sin dibujar y sin incluir el coste de red/serialización. Es una medición de ese núcleo en esta máquina, no un benchmark de FPS ni una garantía para otras PCs.

No se ha medido esta versión dentro de AI Studio. La diferencia de rendimiento entre su vista previa y un navegador normal no se puede atribuir solo al código sin medir ambos entornos.

## Revisión visual pendiente

La herramienta de navegador rechazó `http://localhost:8080` por una configuración de permiso guardada o política administrada. Se intentó nuevamente tras la autorización explícita del usuario y siguió bloqueada. No se utilizó otro navegador o ruta para eludir ese bloqueo.

Por ello, **no se afirma haber revisado capturas de esta versión ni verificado visualmente todos los tamaños**. Las siguientes comprobaciones deben completarse en un navegador autorizado:

1. Recargar `http://localhost:8080` tras la mejora del HUD lateral y abrir a 1366×768, 1920×1080, 1280×600 y una ventana baja de 844×390. Revisar inicio, cinco salas, espera de cuatro jugadores, combate, marcador y diálogos. No debe aparecer desplazamiento ni quedar cortado un botón.
2. Entrenar y mover con WASD. Apuntar, mantener clic, usar Shift. El personaje conserva velocidad al cambiar el tamaño de la ventana.
3. Activar Q: observar **duración activa** y **recarga** simultáneas. Recibir un golpe y ver bajar escudo antes que vida. Usar E cuando falte vida.
4. Activar pantalla completa y volver con Esc. Verificar que el mapa crece sin deformarse. Activar R y F. Ver la diferencia entre disparo normal/potenciado, aviso de nova y explosión. Reducir y apagar cámara/partículas en Ajustes; verificar volumen y silencio, también mientras una muestra está sonando.
5. Copiar invitación en HTTP LAN y HTTPS. Escanear el QR y comprobar que no contiene `localhost` cuando se comparte desde la PC anfitriona.
6. Abrir cuatro navegadores/PCs. Confirmar movimiento visible, impactos, bajas, reaparición, cierre de ronda y revancha. Desconectar un cliente y luego el anfitrión.
7. Medir FPS en Brave y, si interesa compararlo, en la vista previa de AI Studio. Las pruebas del servidor no sustituyen una medición de render.

## Límites de la evidencia

Veinte sockets en una PC prueban concurrencia, límites y consistencia. No equivalen a veinte personas con latencias, GPU y Wi-Fi diferentes. La prueba pública sí recorrió Cloudflare, pero no reemplaza ensayar en la red concreta del salón.

### Revisión solicitada a partir de la captura

Se inspeccionó la captura aportada por el usuario: la arena dejaba franjas laterales amplias y el HUD consumía una fila alta. La nueva distribución usa la altura para el mapa y coloca los indicadores en una columna lateral compacta. El cambio se verificó en código y en las pruebas de ejecución simulada; sigue pendiente una captura de esta versión en el navegador autorizado. La prueba del sonido comprueba archivos, carga y mezcla, no una escucha subjetiva.

La prueba WSS por un túnel real se repitió después de incorporar el audio y pasó sus seis comprobaciones. Las 50 aserciones del motor/protocolo también pasaron. El motor y las reglas no cambiaron en esta revisión.
