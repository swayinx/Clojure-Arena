# Recursos y dependencias

## Código y archivos propios del proyecto

- `src/arena/*.clj`, `*.cljc`, `*.cljs`: servidor, motor, presentación y cliente de esta entrega.
- `scripts/arena/build.clj`: herramientas de compilación.
- `public/art/*.svg`: hojas de personaje conservadas del prototipo de Clojure Arena, con ocho cuadros por personaje. No se ha importado código o arte de Ashfall en esta ampliación.
- HTML/CSS y arena dibujada con Canvas: presentación propia del proyecto.
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
| Kenney Sci-fi Sounds y Digital Audio | `public/audio/`, 12 muestras OGG sin modificar | CC0; licencias originales incluidas y créditos en Ajustes |
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

## Audio de combate: autor, procedencia y licencia

**Autor: Kenney / Kenney Vleugels. Licencia: Creative Commons Zero (CC0 1.0).**
Aunque no exige atribución, el juego muestra créditos en Ajustes y conserva las dos licencias originales.

Fuentes directas del autor:

- [Sci-fi Sounds 1.0](https://kenney.nl/assets/sci-fi-sounds).
- [Digital Audio](https://kenney.nl/assets/digital-audio).
- [Condiciones de uso de Kenney](https://kenney.nl/support).

| Archivo OGG | Pack | Uso |
|---|---|---|
| `laserSmall_000`, `laserSmall_001`, `laserSmall_002` | Sci-fi Sounds | Tres variantes de disparo |
| `impactMetal_000` | Sci-fi Sounds | Impacto confirmado |
| `explosionCrunch_000` | Sci-fi Sounds | Eliminación |
| `forceField_000` | Sci-fi Sounds | Activar escudo |
| `lowFrequency_explosion_000` | Sci-fi Sounds | Explosión de Nova |
| `phaseJump1` | Digital Audio | Dash |
| `powerUp3` | Digital Audio | Curación |
| `powerUp7` | Digital Audio | Potencia |
| `phaserUp4` | Digital Audio | Carga de Nova |
| `threeTone1` | Digital Audio | Recoger suministro |

Se descargaron el 26 de octubre de 2026. Los archivos se distribuyen intactos: solo se ajusta la ganancia al reproducirlos. No hay audio generado. La aplicación descarga y decodifica cada muestra una vez por sesión, después de una interacción. Limita a 16 voces y separa eventos repetidos; volumen y silencio afectan también a los sonidos que ya están sonando.

Integridad SHA-256 de las muestras incluidas:

```text
4b597d658d0ae101f0a030fbeea5fc3a4292ab85f017470a8254a8e7959cbd69  explosionCrunch_000.ogg
c2916f2a062c8ddd1aca2826d134fe90847037db31342726ffb0f9097afe339c  forceField_000.ogg
956c6612a256aa1a67a2327fffe2454f6b1d82e4c1c2be28fd66916335d5b1d6  impactMetal_000.ogg
72b589eadd41781257ac859e4f4d030222e623390e4a1a83f6d05329a6e026f1  laserSmall_000.ogg
ab87913aba9790a0c5fa173d086267ba521ad522fcad2252271db49988313cf5  laserSmall_001.ogg
e70139466c3e801358c9041ee17db7ff82c0e9a9cdf44647ad0ea6b9f8458f63  laserSmall_002.ogg
3cb48d86dab63140cfb9aaeea4d4a6846abaca5831d157d8adbec0490da26343  lowFrequency_explosion_000.ogg
c60069ed7108678dd6a1306404d168a93cc2575cda37b06add9dd6c4d46352a3  phaseJump1.ogg
a64a69368af9d85dfce8dd38432fd6c9935a7766621782193102c9cb276571fc  phaserUp4.ogg
59e7591f7a068882767bc1fd61edabf479b78787466d3a70bc49dfa0533777d2  powerUp3.ogg
9048d651b51f528099c384566b0d4c6c597bee8b56c48f3adab7a901feba0fa5  powerUp7.ogg
bdf21eb12e65507f091d074886fdac820e1ccae51d060caa168e5567acadb84b  threeTone1.ogg
```
