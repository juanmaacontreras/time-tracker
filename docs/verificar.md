# Compilar, tests y capturas sin dispositivo

> Movido tal cual desde `CLAUDE.md` el 2026-11-04. El índice que dice cuándo leer este archivo está en `CLAUDE.md`.

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
