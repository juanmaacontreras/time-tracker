# Pendiente / sin confirmar

> Movido tal cual desde `CLAUDE.md` el 2026-11-04. El índice que dice cuándo leer este archivo está en `CLAUDE.md`.

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
