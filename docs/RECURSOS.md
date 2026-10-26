# Recursos y dependencias

## Código y archivos propios del proyecto

- `src/arena/*.clj`, `*.cljc`, `*.cljs`: servidor, motor, presentación y cliente de esta entrega.
- `scripts/arena/build.clj`: herramientas de compilación.
- `public/art/*.svg`: hojas de personaje conservadas del prototipo de Clojure Arena, con ocho cuadros por personaje. No se ha importado código o arte de Ashfall en esta ampliación.
- HTML/CSS y arena dibujada con Canvas: presentación propia del proyecto.
- Sonidos: osciladores Web Audio programados en ClojureScript; no se distribuyen muestras de terceros.

## Dependencias externas

| Recurso | Ubicación / uso | Licencia / fuente |
|---|---|---|
| Clojure / ClojureScript | Lenguaje, runtime y compilador | [Eclipse Public License](https://clojure.org/community/license) |
| Babashka | Intérprete nativo que ejecuta el servidor | [Repositorio oficial](https://github.com/babashka/babashka), EPL-1.0 |
| http-kit | HTTP y WebSocket; integrado en Babashka | [Repositorio oficial](https://github.com/http-kit/http-kit), Apache-2.0 |
| Barlow | Fuente local de títulos y texto | SIL Open Font License; `public/fonts/BARLOW-LICENSE.txt` |
| Space Mono | Fuente local de datos e indicadores | SIL Open Font License; `public/fonts/SPACE-MONO-LICENSE.txt` |
| QR Code generator / Project Nayuki | `public/vendor/qrcodegen.js`, solo invitaciones | MIT, encabezado de licencia conservado; [fuente](https://www.nayuki.io/page/qr-code-generator-library) |
| cloudflared | Túnel opcional, binario no versionado | [Cloudflare](https://github.com/cloudflare/cloudflared), Apache-2.0 |

La biblioteca QR es JavaScript compilado desde TypeScript y se incluye sin modificaciones. No implementa reglas, red ni render del combate. Su SHA-256 en esta entrega es:

```text
2511bc17f40a3c41d4a0578995db956b38997334d3d20113a5d4dc5c49c69480
```

El compilador oficial ClojureScript 1.12.145 se descarga desde:

```text
https://github.com/clojure/clojurescript/releases/download/r1.12.145/cljs.jar
```

Su SHA-256 esperado está fijado en `scripts/arena/build.clj`:

```text
fe33197a3e0e8ca9d2c52c3b4413f216ca309a0d67b4cdffe919c236c7f19f0a
```

No se emplean CDNs en el juego: fuentes, sprites, estilos, bundle y QR se sirven desde la PC anfitriona. Las conexiones externas son opcionales para descargar herramientas o crear el túnel.

Los PDFs académicos se conservan como material proporcionado para el trabajo; no se les atribuye una licencia abierta. Este repositorio se mantiene local por solicitud del usuario.
