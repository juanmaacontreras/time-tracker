package com.bitacora.timer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class TimerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderOne(context, mgr, id)
        syncInBackground(context)
    }

    // El usuario agrandó/achicó el widget: re-renderizar con las filas y columnas que
    // entren en el tamaño nuevo.
    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, mgr, appWidgetId, newOptions)
        renderOne(context, mgr, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val actId = intent.getStringExtra("actId") ?: return
                applyThenSync(context) { Store.toggle(context, actId) }
            }
            ACTION_STOP -> applyThenSync(context) { Store.stop(context) }
            ACTION_PAUSE -> applyThenSync(context) {
                if (Store.runningPaused(context)) Store.resume(context) else Store.pause(context)
            }
            ACTION_REFRESH -> syncInBackground(context)
        }
    }

    // Sincroniza fuera del hilo principal y solo redibuja si el sync trajo cambios
    // reales: así no se re-infla el cronómetro en vivo sin motivo (el "flash").
    private fun syncInBackground(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                if (Sync.syncProfile(context)) Surfaces.refresh(context)
            } finally { pending.finish() }
        }.start()
    }

    // Acción del usuario (widget o notificación): primero se muta el estado local y se
    // redibuja — instantáneo —, y recién después se va a la red.
    //
    // Antes la red iba ANTES de mutar, así que cada toque en Pausar/Parar esperaba un
    // round-trip completo (hasta 12s de timeout) para recién ahí aplicar el cambio.
    // Invertir el orden es seguro porque toda mutación sella runChangedAt = now(), así
    // que en el merge posterior le gana a cualquier estado remoto más viejo.
    private fun applyThenSync(context: Context, mutate: () -> Unit) {
        mutate()
        Surfaces.refresh(context)
        syncInBackground(context)
    }

    companion object {
        const val ACTION_TOGGLE = "com.bitacora.timer.TOGGLE"
        const val ACTION_STOP = "com.bitacora.timer.STOP"
        const val ACTION_PAUSE = "com.bitacora.timer.PAUSE"
        const val ACTION_REFRESH = "com.bitacora.timer.REFRESH"

        private const val ROW_DP = 40      // alto mínimo de una fila de chips (widget_row.xml)
        private const val MAX_ROWS = 4

        private fun renderOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val pkg = context.packageName
            val views = RemoteViews(pkg, R.layout.widget)
            val (wDp, hDp) = RemoteUi.sizeDp(context, mgr, id, 250, 110)
            val runId = Store.runningActId(context)
            val paused = Store.runningPaused(context)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun broadcast(action: String, req: Int, extra: String? = null): PendingIntent {
                val i = Intent(context, TimerWidget::class.java).apply {
                    this.action = action
                    if (extra != null) putExtra("actId", extra)
                }
                return PendingIntent.getBroadcast(context, req, i, flags)
            }
            // Ordenadas por tiempo acumulado hoy (descendente): lo que más se usa, arriba.
            val today = Store.secsByActivity(context, Store.startOfToday())
            val acts = Store.activities(context).sortedByDescending { today[it.getString("id")] ?: 0L }

            // Tocar el fondo abre la app, igual que en el widget Resumen. (Antes lo
            // "actualizaba", pero con push y sync de fondo eso ya no hace falta.)
            views.setOnClickPendingIntent(android.R.id.background, RemoteUi.openApp(context, 200))

            val heroDp: Int
            if (runId.isNotEmpty()) {
                heroDp = if (paused) 78 else 64
                val a = Store.activityById(context, runId)
                val cat = a?.let { Store.categoryForActivity(context, it) }
                views.setViewVisibility(R.id.w_run, View.VISIBLE)
                views.setViewVisibility(R.id.w_idle, View.GONE)
                RemoteUi.bindSwatch(context, views, a, cat, 44)
                views.setTextViewText(R.id.w_name, a?.optString("name") ?: "")

                // Al pausar, el cronómetro se congela (started = false) con el tiempo real corrido.
                val elapsed = Store.runningElapsedMs(context)
                views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() - elapsed, null, !paused)
                views.setTextColor(R.id.w_chrono, ContextCompat.getColor(context, if (paused) R.color.inkSoft else R.color.ink))
                // Angosto, "1:23:45" no entra junto al swatch y los dos botones: el swatch
                // se va (el nombre y el chip marcado ya dicen qué corre) y el cronómetro se achica.
                val narrow = wDp < 290
                views.setViewVisibility(R.id.w_run_sw, if (narrow) View.GONE else View.VISIBLE)
                views.setTextViewTextSize(R.id.w_chrono, TypedValue.COMPLEX_UNIT_SP, if (narrow) 23f else 26f)
                views.setViewVisibility(R.id.w_paused, if (paused) View.VISIBLE else View.GONE)

                // Tocar una actividad siempre la para si ya está corriendo; pausar es solo
                // con este botón dedicado, igual que en la app.
                views.setImageViewResource(R.id.w_pause_icon, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
                views.setInt(R.id.w_pause_icon, "setColorFilter", ContextCompat.getColor(context, R.color.onAccent))
                views.setInt(R.id.w_stop_icon, "setColorFilter", ContextCompat.getColor(context, R.color.inkSoft))
                views.setInt(R.id.w_pause, "setBackgroundResource", if (paused) R.drawable.circle_primary_invite else R.drawable.circle_primary)
                views.setContentDescription(R.id.w_pause, if (paused) "Seguir" else "Pausar")
                views.setOnClickPendingIntent(R.id.w_pause, broadcast(ACTION_PAUSE, 202))
                views.setOnClickPendingIntent(R.id.w_stop, broadcast(ACTION_STOP, 201))
            } else {
                heroDp = 46
                views.setViewVisibility(R.id.w_run, View.GONE)
                views.setViewVisibility(R.id.w_idle, View.VISIBLE)
                // Las DOS cosas, aunque w_run esté oculto: setChronometer(started = false)
                // es lo único que realmente DETIENE el Chronometer (escribirle texto no lo
                // frena y al segundo siguiente lo pisa), y el texto fijo cubre el caso en
                // que el host reaplique un estado viejo (por ejemplo tras reiniciar el celu).
                views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime(), null, false)
                views.setTextViewText(R.id.w_chrono, "0:00")

                views.setTextViewText(R.id.w_today, spaced(today.values.sum()))
                val ends = Store.lastEndByActivity(context)
                val last = Store.activities(context, includeArchived = true)
                    .mapNotNull { a -> ends[a.getString("id")]?.let { a to it } }
                    .maxByOrNull { it.second }
                if (last != null) {
                    views.setTextViewText(R.id.w_last_text, last.first.optString("name") + " · " + RemoteUi.whenText(last.second))
                    views.setViewVisibility(R.id.w_last, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.w_last, View.GONE)
                }
            }

            // Grilla: tantas filas como entren en el alto que queda (hasta MAX_ROWS), todas
            // del mismo alto. Si sobran actividades, la última casilla es "+N más" y abre la app.
            views.removeAllViews(R.id.w_grid)
            views.setViewVisibility(R.id.w_hint, if (acts.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.w_grid, if (acts.isEmpty()) View.GONE else View.VISIBLE)
            val cols = if (wDp >= 260) 3 else 2
            val rows = ((hDp - 24 - heroDp - 5) / ROW_DP).coerceIn(0, MAX_ROWS)
            val slots = rows * cols
            val overflow = acts.size > slots
            val shown = if (overflow) (slots - 1).coerceAtLeast(0) else acts.size
            if (acts.isNotEmpty()) for (r in 0 until rows) {
                val row = RemoteViews(pkg, R.layout.widget_row)
                for (c in 0 until cols) {
                    val i = r * cols + c
                    val chip = RemoteViews(pkg, R.layout.widget_chip)
                    when {
                        i < shown -> {
                            val act = acts[i]
                            val aid = act.getString("id")
                            RemoteUi.bindSwatch(context, chip, act, Store.categoryForActivity(context, act), 24)
                            chip.setTextViewText(R.id.w_chip_name, act.getString("name"))
                            // El estado (corriendo / pausado) lo dice el fondo del chip.
                            chip.setInt(R.id.w_chip, "setBackgroundResource", when {
                                aid != runId -> R.drawable.chip
                                paused -> R.drawable.chip_paused
                                else -> R.drawable.chip_running
                            })
                            chip.setOnClickPendingIntent(R.id.w_chip, broadcast(ACTION_TOGGLE, 100 + i, aid))
                        }
                        i == shown && overflow -> {
                            chip.setViewVisibility(R.id.w_chip_sw, View.GONE)
                            chip.setTextViewText(R.id.w_chip_name, "+${acts.size - shown} más")
                            chip.setTextColor(R.id.w_chip_name, ContextCompat.getColor(context, R.color.muted))
                            chip.setInt(R.id.w_chip_name, "setGravity", Gravity.CENTER)
                            chip.setOnClickPendingIntent(R.id.w_chip, RemoteUi.openApp(context, 199))
                        }
                        // Casilla vacía: invisible pero ocupando su lugar, así la última fila
                        // no estira sus chips a todo el ancho.
                        else -> chip.setViewVisibility(R.id.w_chip, View.INVISIBLE)
                    }
                    row.addView(R.id.w_rowbox, chip)
                }
                views.addView(R.id.w_grid, row)
            }
            mgr.updateAppWidget(id, views)
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TimerWidget::class.java))
            for (id in ids) renderOne(context, mgr, id)
        }
    }
}
