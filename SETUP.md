# Bitácora — app Android con widget + sync

Cronómetro de estudio/lectura, nativo, con **widget de un toque** en la pantalla de
inicio y **sincronización** entre celu / tablet / compu vía Supabase (gratis).

---

## Paso 1 — Crear el backend (Supabase, ~3 min, gratis)

1. Entrá a https://supabase.com y creá una cuenta.
2. **New project.** Elegí nombre y contraseña (la DB password no la vas a necesitar
   después). Esperá 1–2 min a que se cree.
3. Menú lateral → **SQL Editor** → **New query**. Pegá el contenido de
   `supabase.sql` y apretá **Run**. Eso crea la tabla `buckets`.
4. Menú lateral → **Project Settings** → **API**. Anotá dos cosas:
   - **Project URL** (algo como `https://abcdefgh.supabase.co`)
   - **anon public** key (una cadena larga que empieza con `eyJ...`)

## Paso 2 — Pegar tus datos en la app

Abrí `app/src/main/java/com/bitacora/timer/Config.kt` y completá:

```kotlin
const val SUPABASE_URL = "https://abcdefgh.supabase.co"   // tu Project URL
const val SUPABASE_KEY = "eyJhbGciOi..."                  // tu anon public key
const val USER_KEY     = "juanma-un-codigo-largo-y-secreto"
```

`USER_KEY` es un código que inventás vos. Poné el **mismo** en todos tus
dispositivos: todo lo que comparta ese código ve los mismos datos. Hacelo largo y
difícil de adivinar (ver nota de seguridad abajo).

> Si dejás `SUPABASE_URL` vacío, la app igual funciona perfecto, pero **sin** sync
> (datos solo en ese teléfono). Podés arrancar así y agregar el backend después.

## Paso 3 — Obtener la APK

### Opción A — GitHub Actions (recomendada, sin instalar nada)

1. Creá un repo en GitHub y subí esta carpeta (o `git push`).
2. GitHub corre solo el flujo de `.github/workflows/build.yml`.
3. Cuando termine (pestaña **Actions** → el run verde), abrí el run y bajá el
   artefacto **`bitacora-apk`**. Adentro está `app-debug.apk`.

### Opción B — Android Studio

1. Abrí la carpeta en Android Studio (gratis). Dejá que sincronice Gradle.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Paso 4 — Instalar

1. Pasá el `app-debug.apk` al teléfono.
2. Al abrirlo, Android te va a pedir permitir **"instalar apps desconocidas"**.
   Aceptá (es normal para apps fuera de Play Store).
3. Abrí **Bitácora**, tocá una actividad para probar el cronómetro.

## Paso 5 — Poner el widget

Mantené apretada la pantalla de inicio → **Widgets** → buscá **Bitácora** →
arrastralo. Vas a ver el cronómetro en vivo y hasta 3 botones (tus primeras 3
actividades). Un toque en un botón arranca/para esa actividad, sin abrir la app.

---

## Cómo funciona

- **Un toque = arranca/para.** Solo una actividad corre a la vez.
- El cronómetro guarda la marca de inicio, así que sigue contando aunque cierres
  la app o reinicies (el widget usa un cronómetro nativo que tickea solo).
- **Sync**: al abrir la app, al tocar el widget y al crear/borrar, sube y baja los
  datos y los fusiona por id (gana la edición más nueva). No perdés sesiones si
  usás dos dispositivos.
- **CSV**: botón para exportar todas las sesiones y compartirlas/guardarlas.

## Nota de seguridad (honesta)

La `anon key` viaja dentro de la APK y la tabla tiene RLS desactivado, así que la
seguridad real la da tu `USER_KEY`: cualquiera que tuviera tu APK y adivinara ese
código podría ver/escribir tus datos. Para un tracker personal de estudio es
riesgo bajo si el código es largo y aleatorio. Si más adelante querés algo más
sólido, se puede migrar a Supabase Auth con políticas RLS por usuario — avisame y
lo armamos.

## Límites conocidos

- El widget muestra tus **primeras 3** actividades. Se puede ampliar a una lista
  desplegable (necesita algo más de código); decime y lo agrego.
- La resolución de conflictos es "gana el más nuevo" por ítem. Para un solo usuario
  con varios dispositivos, alcanza.
- Es una **debug APK** (sin firmar para Play Store). Perfecta para uso personal /
  sideload. Para publicarla habría que firmarla.
