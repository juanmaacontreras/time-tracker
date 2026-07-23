package com.bitacora.timer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

class TimerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderOne(context, mgr, id)
        val pending = goAsync()
        Thread {
            try {
                Sync.pullMerge(context)
                refresh(context)
                Notifs.update(context); ResumenWidget.refresh(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val actId = intent.getStringExtra("actId") ?: return
                runInBackground(context) {
                    Sync.pullMerge(context)
                    Store.toggle(context, actId)
                    refresh(context)
                    Notifs.update(context); ResumenWidget.refresh(context)
                    Sync.pushOnly(context)
                    refresh(context)
                }
            }
            ACTION_STOP -> {
                runInBackground(context) {
                    Sync.pullMerge(context)
                    Store.stop(context)
                    refresh(context)
                    Notifs.update(context); ResumenWidget.refresh(context)
                    Sync.pushOnly(context)
                }
            }
            ACTION_PAUSE -> {
                runInBackground(context) {
                    Sync.pullMerge(context)
                    if (Store.runningPaused(context)) Store.resume(context) else Store.pause(context)
                    refresh(context)
                    Notifs.update(context); ResumenWidget.refresh(context)
                    Sync.pushOnly(context)
                }
            }
            ACTION_REFRESH -> {
                // Feedback inmediato: RemoteViews no permite animaciones, así que mostramos
                // un texto de estado breve antes de que termine el merge asíncrono.
                showRefreshing(context)
                runInBackground(context) {
                    Sync.pullMerge(context)
                    refresh(context)
                    Notifs.update(context); ResumenWidget.refresh(context)
                }
            }
        }
    }

    private fun runInBackground(context: Context, block: () -> Unit) {
        val pending = goAsync()
        Thread {
            try { block() } finally { pending.finish() }
        }.start()
    }

    companion object {
        const val ACTION_TOGGLE = "com.bitacora.timer.TOGGLE"
        const val ACTION_STOP = "com.bitacora.timer.STOP"
        const val ACTION_PAUSE = "com.bitacora.timer.PAUSE"
        const val ACTION_REFRESH = "com.bitacora.timer.REFRESH"

        private fun renderOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget)
            val runId = Store.runningActId(context)
            // Ordenadas por tiempo acumulado hoy (descendente); mostramos las 6 primeras.
            val acts = Store.activities(context)
                .sortedByDescending { Store.secsFor(context, it.getString("id"), Store.startOfToday()) }

            if (runId.isNotEmpty()) {
                val a = Store.activityById(context, runId)
                val name = (a?.optString("name") ?: "").uppercase()
                val paused = Store.runningPaused(context)
                val elapsed = Store.runningElapsedMs(context)
                views.setTextViewText(R.id.w_status, if (paused) "PAUSADO · $name" else name)
                // Al pausar, el cronómetro se congela (started = false) con el tiempo real corrido.
                views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() - elapsed, null, !paused)
                // Botón "Parar": los botones de actividad solo pausan/resumen, así que el corte va acá.
                views.setViewVisibility(R.id.w_stop, View.VISIBLE)
                val stopIntent = Intent(context, TimerWidget::class.java).apply { action = ACTION_STOP }
                val stopPi = PendingIntent.getBroadcast(
                    context, 201, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.w_stop, stopPi)
            } else {
                views.setTextViewText(R.id.w_status, "EN REPOSO · tocá para actualizar")
                views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime(), null, false)
                views.setViewVisibility(R.id.w_stop, View.GONE)
            }

            // Tocar el cuerpo del widget (fuera de los botones) lo actualiza.
            val refreshIntent = Intent(context, TimerWidget::class.java).apply { action = ACTION_REFRESH }
            val refreshPi = PendingIntent.getBroadcast(
                context, 200, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.w_root, refreshPi)

            val paused = Store.runningPaused(context)
            val btnIds = intArrayOf(R.id.w_b0, R.id.w_b1, R.id.w_b2, R.id.w_b3, R.id.w_b4, R.id.w_b5)
            for (i in btnIds.indices) {
                if (i < acts.size) {
                    val act = acts[i]
                    val aid = act.getString("id")
                    val glyph = when {
                        aid == runId && paused -> "\u2016 "  // pausado
                        aid == runId -> "\u25A0 "            // corriendo
                        else -> "\u25B6 "                    // detenido
                    }
                    val label = glyph + act.getString("name")
                    views.setTextViewText(btnIds[i], label)
                    views.setViewVisibility(btnIds[i], View.VISIBLE)
                    val intent = Intent(context, TimerWidget::class.java).apply {
                        action = ACTION_TOGGLE
                        putExtra("actId", aid)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val pi = PendingIntent.getBroadcast(context, 100 + i, intent, flags)
                    views.setOnClickPendingIntent(btnIds[i], pi)
                } else {
                    views.setViewVisibility(btnIds[i], View.GONE)
                }
            }
            // Indicador "+N m\u00E1s" si hay actividades fuera de las 6 visibles.
            val extra = acts.size - btnIds.size
            if (extra > 0) {
                views.setTextViewText(R.id.w_more, "+$extra m\u00E1s")
                views.setViewVisibility(R.id.w_more, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.w_more, View.GONE)
            }
            mgr.updateAppWidget(id, views)
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TimerWidget::class.java))
            for (id in ids) renderOne(context, mgr, id)
        }

        // Actualización parcial: solo cambia el texto de estado para dar feedback al toque.
        private fun showRefreshing(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TimerWidget::class.java))
            for (id in ids) {
                val v = RemoteViews(context.packageName, R.layout.widget)
                v.setTextViewText(R.id.w_status, "Actualizando…")
                mgr.partiallyUpdateAppWidget(id, v)
            }
        }
    }
}
