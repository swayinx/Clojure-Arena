# Recursos y dependencias

## Código y archivos propios del proyecto

- `src/arena/*.clj`, `*.cljc`, `*.cljs`: servidor, motor, presentación y cliente de esta entrega.
- `scripts/arena/build.clj`: herramientas de compilación.
- `public/art/*.svg`: hojas de personaje conservadas del prototipo de Clojure Arena, con ocho cuadros por personaje. No se ha importado código o arte de Ashfall en esta ampliación.
- HTML/CSS, composición de los mapas, obstáculos y efectos dibujados con Canvas: presentación propia del proyecto. Los dos atlas externos se conservan intactos y acreditados abajo.
- Iconos de habilidades: SVG propios integrados en `client.cljs`, con siluetas de impulso, escudo, cruz, rayo y nova.
- Sonidos: muestras de Kenney CC0 en `public/audio/`, reproducidas desde ClojureScript mediante Web Audio. Se retiró la síntesis por osciladores.

## Dependencias externas

| Recurso | Ubicación / uso | Licencia / fuente |
|---|---|---|
| Clojure / ClojureScript | Lenguaje, runtime y compilador | [Eclipse Public License](https://clojure.org/community/license) |
| Babashka | Intérprete nativo que ejecuta el servidor | [Repositorio oficial](https://github.com/babashka/babashka), EPL-1.0 |
| http-kit | HTTP y WebSocket; integrado en Babashka | [Repositorio oficial](https://github.com/http-kit/http-kit), Apache-2.0 |
| Barlow | Fuente local de títulos y texto | SIL Open Font License; `public/fonts/BARLOW-LICENSE.txt` |
| Space Mono | Fuente local de datos e indicadores | SIL Open Font License; `public/fonts/SPACE-MONO-LICENSE.txt` |
| QR Code generator / Project Nayuki | `public/vendor/qrcodegen.js`, solo invitaciones | MIT, encabezado de licencia conservado; [fuente](https://www.nayuki.io/page/qr-code-generator-library) |
| Kenney Sci-fi Sounds, Digital Audio, Desert Shooter, Impact Sounds e Interface Sounds | `public/audio/`, 20 muestras OGG sin modificar | CC0; licencias originales incluidas y créditos en Ajustes |
| Kenney Desert Shooter | `public/art/desert-tilemap.png` | CC0; `public/art/DESERT-LICENSE.txt` |
| Luis Zuno / Ansimuz, Warped Top-Down Tech Lab 2 | `public/art/lab-tileset.png` | Licencia del autor incluida en `public/art/LAB-LICENSE.txt`; uso, adaptación y redistribución permitidos |
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

Los PDFs académicos originales se conservan solo en la PC de trabajo y se excluyen del repositorio y de su historial. No se les atribuye una licencia abierta.

## Arte de escenarios

Se revisaron atlas pixelados del autor antes de incorporarlos. [Desert Shooter Pack de Kenney](https://kenney.nl/assets/desert-shooter-pack) aporta el atlas original de 16 px para **Puesto del Desierto**. [Warped Top-Down Tech Lab 2 de Luis Zuno / Ansimuz](https://opengameart.org/content/warped-top-down-tech-lab-2) aporta el atlas original de 32 px para **Laboratorio Azul**. **Patio Cero** mantiene arte propio de Canvas. Los PNG no se editaron; la composición, colores de entorno, cobertura y detalles se dibujan en `view.cljs`. Sus licencias originales están junto a los archivos. El servidor usa la misma lista inmutable de rectángulos que el cliente dibuja, en `engine.cljc`.

No se importó arte ni audio de Brawl Stars. Se tomó como referencia la señal legible de habilidad cargada; el icono, el color, el halo y el aviso de Nova son propios.

## Audio de combate

Autor de todas las muestras: **Kenney / Kenney Vleugels**, licencia **CC0 1.0**. Se conservan los OGG sin modificar, las licencias originales en `public/audio/` y los enlaces de crédito en Ajustes.

| Pack oficial | Uso en el juego | Licencia local |
|---|---|---|
| [Sci-fi Sounds](https://kenney.nl/assets/sci-fi-sounds) | Disparos, eliminación, escudo y subgrave de Nova | `KENNEY-SCIFI-LICENSE.txt` |
| [Digital Audio](https://kenney.nl/assets/digital-audio) | Curación, potencia, carga y suministro | `KENNEY-DIGITAL-LICENSE.txt` |
| [Desert Shooter](https://kenney.nl/assets/desert-shooter-pack) | Dash con dos variantes y explosión de Nova | `DESERT-LICENSE.txt` en `public/art/` |
| [Impact Sounds](https://kenney.nl/assets/impact-sounds) | Impacto metálico medio | `IMPACT-LICENSE.txt` |
| [Interface Sounds](https://kenney.nl/assets/interface-sounds) | Confirmación de Nova lista y capas breves de poderes | `INTERFACE-LICENSE.txt` |

Hay **20 OGG utilizados**. Dash, escudo, curación, potencia, carga e impacto de Nova combinan dos muestras del mismo autor a distintas ganancias. El audio se carga una sola vez tras una interacción, limita a 16 voces y separa eventos repetidos. Ajustes controla el volumen maestro y el silencio de las voces activas. No hay síntesis ni audio generado.

Las fuentes del autor y el [resumen de CC0 de Kenney](https://kenney.nl/support) permiten verificar uso, modificación y atribución opcional. La evaluación del timbre con altavoces y volumen reales queda para la prueba en navegador.
