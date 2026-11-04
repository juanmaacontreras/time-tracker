# Estado actual

> Movido tal cual desde `CLAUDE.md` el 2026-11-04. El índice que dice cuándo leer este archivo está en `CLAUDE.md`.

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
- ⏳ **Notificación y widgets pulidos (octubre de 2026)**, verificado con capturas en claro y
  oscuro, sin confirmar en dispositivo: swatch de identidad (ícono de la categoría sobre el
  color de la actividad) en la notificación, los chips del widget y las filas del Resumen;
  widget del cronómetro con la tarjeta de lo que corre con el color de la actividad lleno y texto
  blanco (elegida entre teñida / neutra / lleno) y,
  en reposo, "Hoy" + la última actividad; widget Resumen con el título del período, total en
  "5 h 20 m", la misma comparación que la app (`comparison()` en ResumenTab.kt) y control
  segmentado; tocar el fondo de cualquiera de los dos abre la app; vistas previas en el
  selector (`drawable-nodpi/widget_preview_*.png`, salen de las capturas de Robolectric).
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

- **Desde Android 12 (targetSdk ≥ 31) el sistema envuelve SIEMPRE las vistas custom de la
  notificación**, aunque no se use `DecoratedCustomViewStyle`: en la colapsada pone su círculo
  con el ícono y la flecha de expandir; en AOSP la expandida lleva además el encabezado
  completo y la sangría, y quedan ~270dp de ancho. Por eso `notif.xml` va apilado: nombre,
  cronómetro y las píldoras Pausar / Parar con texto, elegidas por el usuario (en una sola
  fila el nombre se partía letra a letra) y la colapsada oculta su swatch en API ≥ 31: lo
  reemplaza el círculo del sistema, teñido con el color de la actividad vía `setColor`.
  Para ver lo que dibuja SystemUI en Robolectric: `Notification.Builder.recoverBuilder(ctx,
  n).createContentView()` / `createBigContentView()`.
- **El anillo de "corriendo" de la notificación no se veía nunca**: medía lo mismo que el
  swatch, que se dibujaba encima y lo tapaba. Ahora el marco es de 48dp con el swatch de 40.
- **Sobre el acento lleno va `onAccent`, nunca blanco fijo**: en oscuro el acento es celeste
  claro (#81A1C1) y el blanco encima no se leía.
- **En RemoteViews no hay `android:tint` (lint lo rechaza) ni mezcla de colores**: los íconos
  se tiñen con `setColorFilter` desde el código. Un fondo de color variable se arma con una forma
  blanca en un ImageView + `setColorFilter` (así es la tarjeta del widget), y `setImageAlpha`
  si tiene que ser tenue.
- **Las esquinas del swatch son un vector, no un `<shape>`**: un `<shape>` tiene radio fijo en
  dp y no escala con el tamaño del `<include>`; el vector mantiene el 31% del lado.
- **Los widgets no se redibujan solos con el paso del tiempo.** Muestran "hoy" y, pasada la
  medianoche, seguían con lo de ayer: `SyncWorker` llama a `Surfaces.refreshIfNewDay()` cuando
  el sync no trajo nada. Por lo mismo, nada de "hace 2 h" en un widget: horas absolutas
  (`RemoteUi.whenText`).
