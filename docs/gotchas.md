# Gotchas ya resueltos (no reintroducir)

> Movido tal cual desde `CLAUDE.md` el 2026-11-04. El índice que dice cuándo leer este archivo está en `CLAUDE.md`.

## Gotchas ya resueltos (no reintroducir)

- **Reset del perfil "Principal" al reinstalar**: el seed del perfil default se
  creaba con `updatedAt = now()`, así que en el merge le ganaba a un rename/cambio
  de tema real hecho antes. Fix: sembrar con `updatedAt = 0L` (Android y web) para
  que cualquier edición real siempre gane.
- **Flash del cronómetro del widget** al refrescar: re-inflar un `Chronometer` en
  vivo muestra un frame con un número gigante. Fix: redibujar solo si el sync trajo
  cambios reales (ver "Redibujar solo si el sync trajo cambios", abajo).
- **`setSmallIcon` con el ícono completo de la app** rompía la notificación (cuadrado
  blanco en la barra de estado, fondo negro al expandir). Los íconos de notificación
  tienen que ser una silueta con canal alfa real, sin relleno opaco de fondo — ver
  `ic_notif.xml` vs `ic_launcher_foreground.xml`.
- **`secsFor`/`totalBetween` sin tope superior**: originalmente solo tomaban un
  `from`, así que no servían para navegar a períodos pasados (sumaban de más).
  Ahora aceptan `to` opcional (default `Long.MAX_VALUE`, retrocompatible).
- **El Browser pane no es confiable para recargar archivos `file://` locales** — la
  propia herramienta avisa "render as static snapshots". Para verificar cambios en
  `web/index.html`, mejor ejecutar la lógica directamente vía `javascript_tool`
  (llamar las funciones a mano) que confiar en que un `navigate`/reload refleje el
  archivo editado más reciente.
- **`MainActivity.kt` tiene glyphs guardados como escapes literales** (`"\u25A0"`
  en vez del carácter ■ real) en algunas líneas viejas. El tool de edición falla si
  se busca el carácter Unicode real en vez del escape textual — conviene `grep` la
  línea exacta antes de armar un `old_string`.
- **No hay `keytool` en el PATH** de este entorno; está en
  `C:\Program Files\Java\jre1.8.0_471\bin\keytool.exe` (usado para generar
  `app.keystore`).
- **Las limpiezas de categorías NO deben reescribir ids existentes.** Una versión
  anterior del dedupe le asignaba a la categoría conservada un "id canónico" derivado
  del nombre. Como la tabla de remapeo es común a todos los grupos, el id viejo de un
  grupo podía coincidir con el id canónico de otro, el remapeo se pisaba y **las
  actividades terminaban en la categoría equivocada** (pasó de verdad: "Scout" saltó
  de Otros a Libro). Ahora se conserva el id que la categoría ya tenía, así un id que
  se conserva nunca es origen de un remapeo y la colisión es imposible.
- **Nunca resucitar una categoría borrada.** El dedupe tenía una rama que, si todas
  las entradas de un nombre estaban borradas, revivía la primera. Con nombres
  duplicados en el historial eso hacía que **borrar una categoría la hiciera
  reaparecer**. Si todo el grupo está borrado, se consolida pero queda borrado.
- **Las pasadas de limpieza no deben escribir si no cambió nada.** Reescribían
  `updatedAt` de entradas ya borradas en cada carga, generando escrituras y sync
  constantes de la nada. Toda limpieza tiene que ser idempotente: dos cargas
  seguidas no pueden producir ningún cambio.
- **Acciones del widget/notificación: primero mutar local, después la red.** Antes
  cada botón hacía la sync ANTES de tocar el estado, así que pausar
  esperaba un round-trip completo (hasta 12s de timeout). Es seguro invertirlo porque
  toda mutación sella `runChangedAt = now()` y le gana a cualquier estado remoto más
  viejo en el merge. Ver `TimerWidget.applyThenSync()`.
- **La UI abierta necesita detectar cambios externos.** `MainActivity` solo se
  enteraba de lo que hacían la notificación o el widget en su ciclo de sync de 10s.
  El ticker de 1s ahora compara una firma barata del estado (`stateSig()`) y
  redibuja apenas cambia.
- **`onMessageReceived` tiene ~10s de presupuesto** y el timeout normal de `Sync` es
  de 12s: el camino del push usa uno más corto (`PUSH_TIMEOUT_MS`). Si no llega a
  tiempo no se pierde nada, lo levanta el `SyncWorker`.
- **`ExistingPeriodicWorkPolicy.KEEP` ignora cambios de intervalo.** Al pasar el
  worker de 15 min a 1 h hubo que cambiarlo a `UPDATE`; con `KEEP`, WorkManager
  conserva el trabajo ya encolado y el cambio no tiene efecto donde la app ya estaba
  instalada.
- **La Edge Function tiene que desplegarse con "Verify JWT" DESACTIVADO.** El trigger
  se autentica con el header propio `x-push-secret`, no con un JWT de Supabase. Con
  la verificación activada el trigger nunca la puede llamar. Diagnóstico rápido: un
  POST sin el header debe devolver `401 unauthorized` en texto plano (si devuelve un
  JSON de error de Supabase, la verificación sigue prendida).
- **`net.http_post` es fire-and-forget**: si el trigger falla al llamar la función, la
  escritura al bucket igual funciona y nadie se entera. Para diagnosticar hay que
  mirar `net._http_response` en el SQL Editor y los logs de la Edge Function.
- **NUNCA subir un bucket si el contenido no cambió.** Es la regla más importante desde
  que existe el push: cada ESCRITURA dispara el trigger, que despierta a los demás
  dispositivos. Los ciclos de sync antes subían siempre, así que tener la app abierta en
  un dispositivo le mandaba un push a los otros **cada 10 segundos**. La guarda es
  `Merge.behind` (Kotlin) / `isBehind` (web): después de fusionar, se sube SOLO si lo
  local tiene alguna entrada que el remoto no tiene o tiene más vieja. Reemplazó a un
  `lastPushed` en memoria, que se perdía al reiniciar el proceso y no detectaba cuando
  otro dispositivo pisaba la fila con una versión sin lo nuestro. Todo camino de
  escritura pasa por `Sync.syncBucket` / `syncBucket` en la web. **Leer no dispara
  nada**: sondear seguido es barato, lo caro es escribir de más.
- **Si la bajada falla, no se sube.** `pull` lanza ante cualquier error HTTP en vez de
  devolver null ("no existe"); antes un 5xx al bajar terminaba en una subida que pisaba
  la fila remota con una versión que no incluía lo de los otros dispositivos.
- **Cambiar de perfil a mitad de un sync mezclaba los datos de dos perfiles.** El ciclo
  bajaba el bucket del perfil A, el usuario pasaba a B, y la fusión se aplicaba sobre
  B — y la subida llevaba la mezcla al bucket de A. Como la fusión nunca borra, la
  contaminación era permanente. Ahora el perfil se fija una vez al empezar y
  `Store.merge(ctx, pid, remote)` devuelve null (descarta el ciclo) si el perfil activo
  ya no es ese; el chequeo vive dentro del lock de `Store`, el mismo de
  `setCurrentProfile`. En la web, `Store.merge(pid, remote)` hace lo mismo.
- **Los intervalos de sync de app y web NO son intercambiables.** La app sondea cada
  60 s porque tiene push y se entera en segundos por esa vía. **La web se entera por
  Supabase Realtime** (`Live` en `index.html`: WebSocket con el protocolo Phoenix a
  mano, sin supabase-js) y con eso sondea cada 60 s; **si Live no está conectado
  (Realtime apagado en la tabla, conexión caída) vuelve sola a 10 s**, que es su única
  forma de enterarse. Ya se cometió el error de subir el sondeo a 60 s sin un canal en
  vivo: la web pasó a tardar hasta un minuto en reflejar cambios del celular. Si en la
  web parece que "no se actualiza", mirar primero si Live está conectado
  (`Live.ok` en la consola) y si `supabase/realtime.sql` se corrió. Detalle que
  confunde el diagnóstico: la web sincroniza al instante cuando la ventana gana foco
  (`focus` / `visibilitychange`), así que al hacerle clic parece instantánea aunque el
  ciclo esté mal configurado.
- **Realtime nunca estuvo prendido hasta octubre de 2026.** `supabase/realtime.sql` estaba
  en el repo pero no se había corrido, y nadie lo notó: la web caía en silencio al sondeo
  de 10 s y "funcionaba", solo que con hasta 10 s de demora. Diagnóstico directo: al
  suscribirse, el servidor contesta un `system` con `"status": "error"` y "Unable to
  subscribe to changes". Ya está corrido (el 2026-10-19).
- **Un rechazo de la suscripción tiene que reintentar.** `Live` solo ponía `ok = false` y
  la pestaña quedaba en el sondeo lento hasta recargarla — una ventana abierta antes de
  prender Realtime nunca llegó a usarlo. Ahora (web y app) el rechazo cierra el socket y
  reconecta con espera creciente; el contador de reintentos se resetea **con la
  suscripción confirmada, no al abrir el socket** (si no, un servidor que acepta la
  conexión y rechaza la suscripción haría reintentar cada 1 s para siempre). Al
  confirmarse se sincroniza una vez, por lo que cambió sin conexión.
- **Latencias medidas (2026-10-19):** escritura de un dispositivo → aviso Realtime en
  0,2-1 s. Celu → web abierta: ~1 s. Web → celu con la app cerrada: 3-4 s, el camino
  trigger → `pg_net` → Edge Function → FCM → pull. Ese tramo no baja mucho más con la app
  en segundo plano: Android no deja sostener un socket ahí, por eso `Live.kt` existe solo
  con la app en pantalla y el push sigue siendo la vía para widget y notificación.
- **Redibujar solo si el sync trajo cambios.** Mismo motivo, versión local: `render()`
  reconstruye la lista, la notificación y los dos widgets, y refrescar el widget
  reaplica `setChronometer` sobre un cronómetro en vivo (el "flash del número
  gigante"). `Store.merge` solo escribe (y sube `Store.revision`) si algo cambió de
  verdad, y todos los caminos de sync devuelven ese "¿cambió?": `doSync()`,
  `PushService`, `SyncWorker` y los widgets redibujan solo si es true. Antes
  `Store.merge` escribía siempre, así que la pantalla abierta se reconstruía entera
  en cada sync; y el push también le llega al dispositivo que escribió.
- **La elección de qué categoría conservar en el dedupe tiene que ser determinística.**
  Con "la primera de la lista local", dos dispositivos que creaban "Libro" a la vez se
  borraban mutuamente la suya y la categoría desaparecía. Ahora gana el id menor
  (dentro de: viva con ícono → viva → cualquiera).
- **Un perfil borrado en otro dispositivo no puede quedar activo acá.** Al llegar el
  borrado por sync (o al abrir la app, por si llegó con la app cerrada vía
  `SyncWorker`), `MainActivity.leaveDeletedProfile()` / `onRemoteChange()` en la web
  pasan al Principal.
- **Varias pestañas de la web comparten localStorage pero no el cache en memoria.** Sin
  el listener de `storage`, una pestaña con cache viejo pisaba al escribir lo que había
  hecho la otra. El listener invalida los caches y redibuja.
- **`SyncWorker` hace sync completo (baja Y sube)**, no solo baja: una acción desde el
  widget hecha sin señal quedaba sin subir hasta volver a abrir la app.
- **Las sesiones en curso se cuentan como van a quedar guardadas**: `[runStart,
  runStart + tiempo real]` (ver `Store.runningSpan`). Antes se restaban todas las
  pausas aunque la ventana del período cortara la sesión, y un día podía sumar de menos.
- **El service worker NUNCA debe cachear las llamadas a Supabase.** El handler de
  `fetch` en `sw.js` deja pasar derecho todo lo que no sea del propio origen. Si se
  cachearan, el sync devolvería datos viejos y la app mostraría un estado que ya no
  existe — el peor bug posible acá. El HTML va network-first (siempre fresco online,
  cache solo como respaldo offline) y `sw.js` se excluye explícitamente del cache,
  porque cachearlo impediría detectar versiones nuevas.
- **El estado "waiting" del service worker hay que manejarlo a mano.** Un SW nuevo se
  instala pero no se activa hasta que se cierren TODAS las pestañas de la app. Como
  esta web se usa siempre abierta, sin el flujo explícito (avisar → `SKIP_WAITING` →
  `controllerchange` → recargar) te quedás con la versión vieja indefinidamente,
  sintiendo que "no se actualiza".
- **La pantalla abierta necesita `Store.revision` para captar cambios que NO son del
  cronómetro.** `PushService` fusiona lo que llega por push en un hilo de fondo pero no
  redibuja `MainActivity`. El ticker de 1 s compara `stateSig()`, que incluye ese
  contador (sube en cada `Store.write`), así que una actividad renombrada en otro
  dispositivo aparece en ~1 s en vez de esperar el ciclo de 60 s.
