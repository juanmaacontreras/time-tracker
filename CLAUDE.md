# Bitácora — estado del proyecto

Cronómetro de estudio/lectura. Android nativo (Kotlin, Views programáticas, sin
Compose) + una versión web espejo (`web/index.html`, un solo archivo). Sincronizan
contra el mismo backend Supabase. Multi-perfil (varias personas, mismo dispositivo o
distintos), paleta Nord con 5 acentos por perfil, categorías con ícono propio, widgets de home screen,
notificación persistente, push silencioso entre dispositivos (FCM) y
auto-actualización desde GitHub Releases.

**Cambios de diseño: primero propuestas, después código.** Antes de implementar un cambio
visual (pantallas, widgets, notificación, web), mostrarle al usuario 2-3 variantes en
imágenes y esperar a que elija. Las capturas salen de la app real con el armado de
Robolectric de abajo (o de la web en Chromium), aplicando cada variante en la copia, no en
el repo. Pedido explícito del usuario.


**Este archivo es un índice.** El detalle, el porqué y los casos testigo están en
`docs/`: leé el archivo que indica la tabla de abajo *antes* de esa tarea. Un
«ver "X"» es el título de una sección: `grep -rn "X" docs/`.

## Reglas que valen siempre

- **Nunca subir un bucket si el contenido no cambió** (`Merge.behind` / `isBehind`):
  cada escritura dispara un push a los demás dispositivos. **Si la bajada falla, no
  se sube.** Todo camino de escritura pasa por `Sync.syncBucket` / `syncBucket`.
- **La web es un espejo funcional, no un puerto automático.** Un cambio a
  `Store.payload()`/`Store.merge()` se refleja a mano en `web/index.html`, y todo
  cambio a sync/merge/stats lleva un caso nuevo en `tests/fixtures/shared.json`.
- **Las limpiezas son idempotentes**, nunca reescriben ids existentes y nunca
  resucitan una categoría borrada.
- **Primero mutar local, después la red** (widget y notificación).
- **`Sync.syncDevices()`: primero bajar y fusionar, después registrar el token.**
- **El service worker nunca cachea las llamadas a Supabase.**
- **La Edge Function se despliega con "Verify JWT" desactivado.**
  `supabase/trigger.sql` nunca se commitea con el secreto.
- **Compilar en una copia del repo, nunca adentro.** El `versionCode` lo pone CI.

## Comandos

```bash
gradle testDebugUnitTest          # app: SharedFixturesTest contra tests/fixtures/shared.json
node tests/web.test.js            # web: la misma batería contra index.html
gradle assembleDebug lintDebug    # en una copia, con ANDROID_HOME temporal
claude-budget                     # contexto al arrancar, meta < 3000 tokens
```

## Cuándo leer qué

| Antes de… | Leé |
|---|---|
| compilar, testear o sacar capturas sin dispositivo | `docs/verificar.md` |
| ubicar un archivo o sumar uno | `docs/estructura.md` |
| tocar perfiles, categorías, push, Store, diseño, temas, la web o la firma | `docs/arquitectura.md` |
| tocar sync, widgets, notificación, service worker, Realtime o dedupe | `docs/gotchas.md` |
| saber qué está confirmado en dispositivo | `docs/estado.md` |
| proponer trabajo nuevo (seguridad del repo público, tokens FCM, datos por fila) | `docs/pendiente.md` |

Lo nuevo va al `docs/` que corresponda. Sumá una línea acá solo si vale para
cualquier tarea, y mantené `claude-budget` en verde.
