# Decisiones de arquitectura

> Movido tal cual desde `CLAUDE.md` el 2026-11-04. El índice que dice cuándo leer este archivo está en `CLAUDE.md`.

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
