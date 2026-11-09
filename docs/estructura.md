# Estructura

> Movido tal cual desde `CLAUDE.md` el 2026-11-04. El índice que dice cuándo leer este archivo está en `CLAUDE.md`.

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
  AtrilImport.kt    — convierte la bandeja de Atril (USER_KEY::atril) en actividades y sesiones; ver docs/atril.md.
  Surfaces.kt       — refresca notificación + los dos widgets juntos (solo tras un cambio real).
  SyncWorker.kt     — WorkManager, corre cada ~1 h en background aunque la app esté cerrada (red de seguridad del push).
  TimerWidget.kt / ResumenWidget.kt — widgets de home screen.
  Notifs.kt         — notificación persistente (vista custom, swatch de categoría + cronómetro grande).
  RemoteUi.kt       — kit de la notificación y los widgets (swatch `rv_swatch.xml`, contraste, tamaño
                      real del widget, abrir la app). El Ui.kt de las RemoteViews.
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
