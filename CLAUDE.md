# Bitácora — estado del proyecto

Cronómetro de estudio/lectura. Android nativo (Kotlin, Views programáticas, sin
Compose) + una versión web espejo (`web/index.html`, un solo archivo). Sincronizan
contra el mismo backend Supabase. Multi-perfil (varias personas, mismo dispositivo o
distintos), paleta Nord con 5 acentos por perfil, categorías con ícono propio, widgets de home screen,
notificación persistente, push silencioso entre dispositivos (FCM) y
auto-actualización desde GitHub Releases.

El build oficial lo hace GitHub Actions y las pruebas en dispositivo las hace el
usuario. En una sesión en la nube de Claude Code sí se puede compilar: hay Java y
Gradle, y el Android SDK se baja con `sdkmanager` (`platforms;android-34`,
`build-tools;34.0.0`) a un directorio temporal; `gradle assembleDebug lintDebug` con
`ANDROID_HOME` apuntando ahí. Compilar en una copia del repo, nunca dentro: no hay
`.gitignore` y los `build/` quedarían sin trackear. La parte `web/` se prueba
ejecutando su lógica en Node o en Chromium (Playwright).

**Tests:** `tests/fixtures/shared.json` tiene casos de fusión, dedupe de categorías,
estadísticas e ids de categoría que corren LOS DOS lados contra su propio código:
`gradle testDebugUnitTest` (app, `SharedFixturesTest.kt`, sobre las funciones puras
`Store.mergeData`/`remoteBehind`/`msByActivity`/`stableCategoryId`/`sessionsBetween`/`lastEndByActivity`) y
`node tests/web.test.js` (web, extrae el código real de `index.html`). CI los corre
antes de publicar la APK y la web: si un lado deja de coincidir con el otro, no se
publica. Todo cambio a la lógica de sync/merge/stats lleva un caso nuevo acá.

**Capturas de la app sin dispositivo:** Robolectric con `@GraphicsMode(NATIVE)` dibuja
las Views reales (pantallas, diálogos, widgets vía `ShadowAppWidgetManager`, la
notificación vía `ShadowNotificationManager`) y se guardan como PNG con
`view.draw(Canvas)`. Se arma en una copia aparte, nunca en el repo: agregar
`robolectric`, `androidx.test:core` y `work-testing` como `testImplementation` +
`unitTests.isIncludeAndroidResources = true`, y **vaciar `Config.SUPABASE_URL` en la
copia** — si no, la app sincroniza los datos de prueba contra el Supabase real. `Store`
es un singleton: entre pruebas hay que invalidar su cache (`Store.setCurrentProfile`).

## Estructura

```
app/src/main/java/com/bitacora/timer/
  MainActivity.kt   — ciclo de vida, sync, encabezado y pestañas.
  Ui.kt             — kit de componentes (superficies, swatch de identidad, botones, encabezados de
                      sección, campos, selector de color, estilo de diálogos). Usarlo en vez de armar a mano.
  TimerTab.kt / ResumenTab.kt / ProfilesUi.kt / ActivitySheets.kt — cada pantalla, como funciones
                      de extensión de MainActivity (comparten su estado vía campos `internal`).
                      Views armadas por código, sin XML de layout salvo activity_main.xml.
  Store.kt          — modelo de datos del perfil activo (actividades, sesiones, timer). Cache en memoria + @Synchronized.
  ProfileStore.kt   — índice de perfiles (sincronizado), separado del dataset de cada perfil.
  Themes.kt         — 5 paletas de color, resuelve @color/indigo y @color/live según el tema del perfil activo.
  Sync.kt           — pull → merge → push-si-hace-falta contra Supabase, por "bucket key". Todo devuelve "¿cambió algo?".
  Merge.kt          — fusión por updatedAt (Merge.list) y detección de "el remoto quedó atrás" (Merge.behind), común a los 3 buckets.
  Surfaces.kt       — refresca notificación + los dos widgets juntos (solo tras un cambio real).
  SyncWorker.kt     — WorkManager, corre cada ~1 h en background aunque la app esté cerrada (red de seguridad del push).
  TimerWidget.kt / ResumenWidget.kt — widgets de home screen.
  Notifs.kt         — notificación persistente (vista custom, swatch de categoría + cronómetro grande).
  Devices.kt        — registro de tokens FCM por dispositivo, con el perfil que cada uno tiene activo.
  PushService.kt    — recibe el push silencioso y refresca notificación + widgets.
  Live.kt           — Supabase Realtime (OkHttp WebSocket) entre onResume y onPause: con la app
                      en pantalla los cambios llegan en <1 s sin esperar el push. Espejo de `Live` en la web.
  CategoryIcons.kt  — set curado de 10 íconos de categoría (claves compartidas con la web).
  Updater.kt        — chequea GitHub Releases al abrir la app, ofrece descargar+instalar.
  Config.kt         — credenciales Supabase + helpers de bucket key por perfil.
web/index.html      — versión web, espejo funcional 1:1 de Store/ProfileStore/Sync. Un solo archivo, sin build step.
web/manifest.json   — PWA: la vuelve instalable (ventana propia, ícono). Rutas relativas: el sitio cuelga de /time-tracker/.
web/sw.js           — service worker: cache offline + detección de versión nueva. VERSION la inyecta el CI.
web/icons/          — íconos PNG 192/512, generados replicando ic_launcher (reloj blanco sobre #2F4B8F).
supabase/
  functions/push-on-change/index.ts — Edge Function (Deno) que manda el push FCM al cambiar un bucket.
  trigger.sql       — trigger de Postgres + pg_net que llama a esa función. Se corre a mano en el SQL Editor.
  realtime.sql      — agrega `buckets` a la publicación de Realtime (para la web en vivo). Idem, a mano.
tests/              — casos compartidos app ↔ web (ver "Tests" arriba).
```

`supabase/` queda fuera de las rutas que disparan el workflow de CI, así que se puede
editar sin gatillar builds de APK. Hay dos workflows y no se pisan: `build.yml` filtra
por `app/**` y compila la APK; `pages.yml` filtra por `web/**` y publica la web.

## Decisiones de arquitectura

**Perfiles = buckets distintos en la misma tabla Supabase.** No hay tablas nuevas.
Cada perfil tiene su propio `user_key` derivado: `USER_KEY::<idPerfil>` (el perfil
"default"/Principal usa el `USER_KEY` base tal cual, para adoptar sin migrar los
datos que ya existían antes de que existieran los perfiles). Hay un bucket extra,
`USER_KEY::index`, con el índice de perfiles (id/nombre/tema), que también se
sincroniza y se fusiona igual que actividades/sesiones (gana el `updatedAt` más
nuevo).

**Categorías = entidad propia, no texto libre.** El campo `type` de cada actividad
guarda un **id de categoría**, no un nombre. Las categorías viven en el array
`categories` del mismo bucket del perfil (`{id, name, icon, color}`) y se fusionan
como todo lo demás. Hay una migración automática que corre una sola vez y convierte
los `type` de texto libre que existían antes. El id de una categoría nueva se deriva
del nombre con un hash estable (FNV-1a, mismo algoritmo bit a bit en Kotlin y en la
web): si dos dispositivos migran por separado, "Materia" da el mismo id en los dos y
no se duplica. `Store.categoryForActivity()` nunca devuelve null — cae a un "General"
virtual si el id no resuelve.

**Push silencioso vía FCM.** Un trigger de Postgres sobre la tabla `buckets` llama
(con `pg_net`) a una Edge Function, que busca en el bucket `USER_KEY::devices` qué
dispositivos tienen activo **ese** perfil y les manda un mensaje de datos con
`priority: HIGH`. El mensaje es solo un "timbre": no lleva ningún dato, solo el
`profileId`, y la app sale a buscar el resto por su cuenta — así los datos reales
nunca pasan por Google. Los buckets `::index` y `::devices` se saltean explícitamente
en la función; saltear `::devices` es lo que corta la realimentación (registrar un
token escribe ese bucket).

**El sistema de tokens se repara solo.** Si FCM contesta `UNREGISTERED` (token muerto
por reinstalación o cambio de firma), la Edge Function marca esa entrada como borrada,
y la app lo detecta al abrirse y se registra de nuevo. Para que eso funcione el orden
en `Sync.syncDevices()` es crítico: **primero baja y fusiona el registro remoto, y
recién después registra el token local**. Al revés, el dispositivo nunca se entera de
su propia baja y el push queda roto en silencio para siempre.

**Store tiene cache en memoria + `@Synchronized`.** Antes cada llamada a
`Store.root()` releía y re-parseaba TODO el JSON desde SharedPreferences — con
historial de meses, un solo toque disparaba 30-60 parseos sincrónicos en el hilo
principal (la causa de la lentitud que reportó el usuario). Ahora se cachea un
`JSONObject` en memoria y solo se reparsea si cambia el perfil activo. Como la sync
en background (WorkManager) y la UI pueden tocar el mismo objeto desde hilos
distintos, casi todas las funciones públicas de `Store`/`ProfileStore` están
anotadas `@Synchronized` (reentrante, así que llamadas anidadas entre funciones
sincronizadas no generan deadlock).

**Sistema de diseño (app y web, mismos tokens).** Paleta **Nord**, elegida por el
usuario: claro = "Snow Storm" (`#ECEFF4` fondo, `#2E3440` texto), oscuro = "Polar
Night" (`#2E3440` fondo, `#3B4252` tarjetas); acento = Frost, pausado / borrar =
Aurora. Superficies **sin bordes** (la jerarquía la dan los tonos `paper < card` y
`surface2` para pistas y campos), esquinas amplias (16–24), números con **dígitos
tabulares** (`fontFeatureSettings = "tnum"` / `font-variant-numeric`), nunca
monoespaciada. Elementos firma: la tarjeta de lo que corre con el lenguaje de la
notificación (swatch lleno con el ícono de la categoría, cronómetro grande, botón
principal lleno + "Parar" tonal, fondo teñido con el color de la actividad) y el
*swatch de identidad* (ícono de la categoría sobre el color de la actividad) en cada
fila. "Borrar" siempre en rojo (`danger`), nunca en el acento. Los colores que se
ofrecen para actividades/categorías (`Store.COLORS` / `COLORS` en la web) son tonos
Nord profundos para que un ícono blanco se lea encima.

**Temas = solo 2 colores por paleta.** `Themes.kt` intercepta únicamente
`@color/indigo` (acento) y `@color/live` (corriendo); los neutros son iguales en las 5
paletas (azul y grafito = Frost; verde, violeta y arcilla = Aurora). Los colores que se
mezclan en tiempo de ejecución (fondos tenues) salen de `blend()` en `Ui.kt`; en la web,
de `color-mix()`. Los **widgets y la notificación NO
seleccionan el tema del perfil** — quedan con el acento base (indigo #2F4B8F).
Decisión explícita: tematizar RemoteViews por perfil era mucho más trabajo para un
beneficio menor; si se pide más adelante, hay que revisar `TimerWidget.kt` /
`ResumenWidget.kt` / `Notifs.kt`. Como RemoteViews no puede mezclar colores, los
fondos tenues de los widgets son colores fijos (`liveSoft`, `pausedSoft`).

**La web es un espejo funcional, no un puerto automático.** Cualquier cambio al
formato de datos (`Store.payload()`/`Store.merge()` en Kotlin) tiene que reflejarse
a mano en el objeto `Store` de `web/index.html` — los nombres de campo son un
contrato fijo entre ambos lados. Mismo criterio para `ProfileStore`. No hay tests
automáticos de esto: si se cambia un lado y no el otro, sync silenciosamente
empieza a perder datos o a duplicar.

**`web/` vive fuera del módulo Gradle a propósito.** `settings.gradle.kts` solo
incluye `:app`, así que nada de `web/` es visible para `gradle assembleDebug`. Se
puede agregar/editar libremente sin tocar el build de la APK.

**Firma de APK fija (`app/app.keystore`, commiteada).** Necesaria para que las
actualizaciones automáticas se puedan instalar una sobre otra (Android exige que
compartan firma). Contraseña/alias `bitacora` en `build.gradle.kts`. `versionCode`
se inyecta desde CI como el número de commits (`git rev-list --count HEAD`), no se
edita a mano.

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

## Estado actual

Confirmado funcionando en dispositivo real:

- ✅ Cronómetro con pausa/resumen, widgets, notificación, sync, export CSV.
- ✅ Perfiles multi-usuario sincronizados (app + web), con borrado que exige escribir
  "borrar".
- ✅ 5 temas de color por perfil (app + web).
- ⏳ Rediseño visual con paleta Nord (app, widgets, notificación y web): verificado
  con capturas (Robolectric + Chromium) en claro y oscuro, **sin confirmar todavía en
  dispositivo real**.
- ✅ Resumen con navegación de períodos pasados (◀ ▶) y gráfico semanal apilado por
  categoría, con bordes redondeados en las barras apiladas.
- ⏳ **Resumen "stats a mano"** (app + web + widget, verificado con capturas, sin
  confirmar en dispositivo): total en formato "56 h 05 m" con comparación contra el
  MISMO tramo del período anterior (verde si sube, apagado si baja, nunca rojo), KPIs
  (Día: sesiones / la más larga / de·hasta; Semana y Mes: promedio por día / días con
  actividad / mejor día), Día = línea de tiempo de sesiones, Semana = barras con techo
  en horas enteras + línea del promedio + días futuros contorneados (con filtro de
  categoría apila por actividad), Mes = calendario de intensidad (tocar un día abre su
  Día), "Por categoría" siempre abierta como leyenda, "Sin tiempo este período" con la
  última vez, swatch de identidad en las filas, se refresca por minuto con algo
  corriendo, cambiar Día/Semana/Mes conserva la fecha (`offsetFor`), tocar la fecha
  vuelve a hoy, ←/→ en la web. Metas diarias: descartadas por el usuario, no proponer.
- ✅ Categorías como entidad, con ícono y color propios, desplegable de selección y
  alta/edición/borrado (app + web). Migración desde los `type` de texto libre.
- ✅ Notificación rediseñada estilo reproductor: swatch de identidad, cronómetro
  grande, botones propios (no `.addAction()`) presentes también sin expandir, y sin
  la fila de ícono/nombre de la app (se sacó `DecoratedCustomViewStyle`).
- ✅ **Push silencioso end-to-end**: cambiar algo en un dispositivo actualiza los
  demás en segundos, con la app cerrada. Verificado.
- ✅ Auto-actualización: CI publica Release con APK + `versionCode`; la app chequea
  al abrir y ofrece instalar.
- ✅ **Backup completo en JSON** (`Store.fullBackup`, tercera opción del diálogo de
  exportar). Vuelca el dataset crudo de cada perfil guardado localmente más el índice.
  Existe porque el CSV solo exporta sesiones de un período y no sirve para reconstruir
  el estado — y porque las limpiezas automáticas ya corrompieron datos dos veces.

## Pendiente / sin confirmar

- **Tokens FCM muertos que quedaron de antes**: la Edge Function ahora los da de baja
  sola, pero si aparecieran entradas viejas huérfanas en `USER_KEY::devices` (de
  instalaciones que ya no existen y nunca se vuelven a abrir) nadie las borra. Con
  pocos dispositivos no molesta; si crece, conviene una limpieza por antigüedad.
- **La web no recibe push con la pestaña cerrada** (necesitaría Web Push en el service
  worker). Abierta, se entera al instante por Realtime. Decisión consciente.
- **Datos por fila en vez de un bloque por perfil**: evaluado y postergado. En
  octubre de 2026 el bucket más grande pesa ~44 KB (186 sesiones), así que bajarlo
  entero cuesta poco y la latencia la dominaba el sondeo de la web, que Realtime ya
  resolvió. Conviene retomarlo si un bucket pasa de unos cientos de KB: tablas por
  entidad, subida de solo lo cambiado y bajada por "cambios desde".
- **Widget/notificación no siguen el tema del perfil** — decisión consciente, pero
  si el usuario lo pide hay que revisar esos tres archivos.
- **`supabase/trigger.sql` está commiteado con el placeholder** `PONER_ACA_EL_PUSH_SECRET`.
  El valor real vive solo en la base y en los secrets de Supabase — no volver a
  commitearlo con el secreto adentro.
- **El repo es público y `Config.kt` / `web/index.html` traen `SUPABASE_URL`,
  `SUPABASE_KEY` y `USER_KEY` commiteados.** Cualquiera que encuentre el repo puede
  leer y escribir todo el historial. No se arregla rotando la clave (la nueva quedaría
  igual de expuesta): una app sin login que habla directo contra el backend siempre
  tiene la credencial del lado del cliente. La salida que preserva la auto-actualización
  sería repo de código privado + un repo público aparte solo con los APK de los
  releases, y `Updater.LATEST_URL` apuntando a ese segundo. Decisión pendiente del
  usuario, informada y consciente.
- Nada de esto tiene tests automatizados en el repo. La verificación es compilar +
  lint (ver arriba) y ejecutar la lógica de `web/` a mano en Node o Chromium.
