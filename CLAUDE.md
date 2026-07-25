# Bitácora — estado del proyecto

Cronómetro de estudio/lectura. Android nativo (Kotlin, Views programáticas, sin
Compose) + una versión web espejo (`web/index.html`, un solo archivo). Sincronizan
contra el mismo backend Supabase. Multi-perfil (varias personas, mismo dispositivo o
distintos), 5 temas de color, widgets de home screen, notificación persistente,
auto-actualización desde GitHub Releases.

No hay Gradle/Android SDK en el entorno donde se desarrolla esto — todo el trabajo en
`app/` se hace por lectura/edición cuidadosa + verificación estática (balance de
llaves, grep de referencias). El build real y las pruebas en dispositivo las hace el
usuario, compilando vía GitHub Actions. La parte `web/` sí se puede probar en vivo
con el Browser pane.

## Estructura

```
app/src/main/java/com/bitacora/timer/
  MainActivity.kt   — UI completa de la app (Views armadas por código, sin XML de layout salvo activity_main.xml)
  Store.kt          — modelo de datos del perfil activo (actividades, sesiones, timer). Cache en memoria + @Synchronized.
  ProfileStore.kt   — índice de perfiles (sincronizado), separado del dataset de cada perfil.
  Themes.kt         — 5 paletas de color, resuelve @color/indigo y @color/live según el tema del perfil activo.
  Sync.kt           — pull/push contra Supabase, parametrizado por "bucket key" (perfil).
  SyncWorker.kt     — WorkManager, corre cada ~15 min en background aunque la app esté cerrada.
  TimerWidget.kt / ResumenWidget.kt — widgets de home screen.
  Notifs.kt         — notificación persistente (vista custom con cronómetro grande).
  Updater.kt        — chequea GitHub Releases al abrir la app, ofrece descargar+instalar.
  Config.kt         — credenciales Supabase + helpers de bucket key por perfil.
web/index.html      — versión web, espejo funcional 1:1 de Store/ProfileStore/Sync. Un solo archivo, sin build step.
```

## Decisiones de arquitectura

**Perfiles = buckets distintos en la misma tabla Supabase.** No hay tablas nuevas.
Cada perfil tiene su propio `user_key` derivado: `USER_KEY::<idPerfil>` (el perfil
"default"/Principal usa el `USER_KEY` base tal cual, para adoptar sin migrar los
datos que ya existían antes de que existieran los perfiles). Hay un bucket extra,
`USER_KEY::index`, con el índice de perfiles (id/nombre/tema), que también se
sincroniza y se fusiona igual que actividades/sesiones (gana el `updatedAt` más
nuevo).

**Store tiene cache en memoria + `@Synchronized`.** Antes cada llamada a
`Store.root()` releía y re-parseaba TODO el JSON desde SharedPreferences — con
historial de meses, un solo toque disparaba 30-60 parseos sincrónicos en el hilo
principal (la causa de la lentitud que reportó el usuario). Ahora se cachea un
`JSONObject` en memoria y solo se reparsea si cambia el perfil activo. Como la sync
en background (WorkManager) y la UI pueden tocar el mismo objeto desde hilos
distintos, casi todas las funciones públicas de `Store`/`ProfileStore` están
anotadas `@Synchronized` (reentrante, así que llamadas anidadas entre funciones
sincronizadas no generan deadlock).

**Temas = solo 2 colores por paleta.** `Themes.kt` intercepta únicamente
`@color/indigo` (acento) y `@color/live` (corriendo); los neutros (papel, tinta,
líneas) son iguales en las 5 paletas. Esto mantiene el resto del sistema de diseño
(radios, drawables `chip.xml`, etc.) sin tocar. Los **widgets y la notificación NO
seleccionan el tema del perfil** — quedan con el acento base (indigo #2F4B8F).
Decisión explícita: tematizar RemoteViews por perfil era mucho más trabajo para un
beneficio menor; si se pide más adelante, hay que revisar `TimerWidget.kt` /
`ResumenWidget.kt` / `Notifs.kt`.

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
  vivo muestra un frame con un número gigante. Fix: `ACTION_REFRESH` solo
  re-renderiza si el sync trajo cambios reales (compara el payload antes/después).
- **`setSmallIcon` con el ícono completo de la app** rompía la notificación (cuadrado
  blanco en la barra de estado, fondo negro al expandir). Los íconos de notificación
  tienen que ser una silueta con canal alfa real, sin relleno opaco de fondo — ver
  `ic_notif.xml` vs `ic_launcher.xml`.
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

## Estado actual (todo implementado, nada confirmado en dispositivo real todavía)

- ✅ Cronómetro con pausa/resumen, widgets, notificación, sync, export CSV.
- ✅ Perfiles multi-usuario sincronizados (app + web), con borrado que exige escribir
  "borrar".
- ✅ 5 temas de color por perfil (app + web).
- ✅ Resumen con navegación de períodos pasados (◀ ▶) y gráfico semanal apilado por
  categoría (recién implementado, verificado en el Browser pane para la web).
- ✅ Notificación con cronómetro grande (vista custom, `DecoratedCustomViewStyle`).
- ✅ Auto-actualización: CI publica Release con APK + `versionCode`; la app chequea
  al abrir y ofrece instalar.

## Pendiente / sin confirmar

- **El usuario todavía no probó en el celu** el fix del perfil que se reseteaba, la
  notificación grande, ni la auto-actualización end-to-end (bajar+instalar un APK
  real desde un Release).
- **Cambio de firma del keystore**: la próxima instalación en cada dispositivo va a
  requerir desinstalar la versión vieja una vez (firma distinta). A partir de esa,
  las actualizaciones futuras se instalan una sobre otra sin reinstalar. Falta
  confirmar que el flujo completo (banner "Actualizar" → descarga → instalador de
  Android) funciona en la práctica.
- **Widget/notificación no siguen el tema del perfil** — decisión consciente, pero
  si el usuario lo pide hay que revisar esos tres archivos.
- Nada de esto tiene tests automatizados; toda verificación es lectura/inspección
  manual del código Kotlin (no se puede compilar en este entorno) o ejecución en
  vivo en el Browser pane (solo para `web/`).
