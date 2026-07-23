package com.bitacora.timer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

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

    // El usuario agrandó/achicó el widget: re-renderizar para mostrar 6 o 9 actividades
    // según el espacio disponible.
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

            val paused = Store.runningPaused(context)
            if (runId.isNotEmpty()) {
                val a = Store.activityById(context, runId)
                val name = (a?.optString("name") ?: "").uppercase()
                val elapsed = Store.runningElapsedMs(context)
                views.setTextViewText(R.id.w_status, if (paused) "PAUSADO · $name" else name)
                // Al pausar, el cronómetro se congela (started = false) con el tiempo real corrido.
                views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() - elapsed, null, !paused)

                // Tocar una actividad (arriba) siempre para si ya está corriendo; pausar es
                // solo con este botón dedicado, igual que en la app.
                views.setTextViewText(R.id.w_pause, if (paused) "Resumir" else "Pausar")
                views.setViewVisibility(R.id.w_pause, View.VISIBLE)
                val pauseIntent = Intent(context, TimerWidget::class.java).apply { action = ACTION_PAUSE }
                val pausePi = PendingIntent.getBroadcast(
                    context, 202, pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.w_pause, pausePi)

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
                views.setViewVisibility(R.id.w_pause, View.GONE)
                views.setViewVisibility(R.id.w_stop, View.GONE)
            }

            // Tocar el cuerpo del widget (fuera de los botones) lo actualiza.
            val refreshIntent = Intent(context, TimerWidget::class.java).apply { action = ACTION_REFRESH }
            val refreshPi = PendingIntent.getBroadcast(
                context, 200, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.w_root, refreshPi)

            val allSlotIds = intArrayOf(
                R.id.w_slot0, R.id.w_slot1, R.id.w_slot2, R.id.w_slot3, R.id.w_slot4,
                R.id.w_slot5, R.id.w_slot6, R.id.w_slot7, R.id.w_slot8
            )
            val allIconIds = intArrayOf(
                R.id.w_icon0, R.id.w_icon1, R.id.w_icon2, R.id.w_icon3, R.id.w_icon4,
                R.id.w_icon5, R.id.w_icon6, R.id.w_icon7, R.id.w_icon8
            )
            val allNameIds = intArrayOf(
                R.id.w_name0, R.id.w_name1, R.id.w_name2, R.id.w_name3, R.id.w_name4,
                R.id.w_name5, R.id.w_name6, R.id.w_name7, R.id.w_name8
            )

            // 4x2 (default, ~110dp de alto) muestra 6; al expandir a 4x3 (~180dp) aparece
            // la tercera fila y se muestran 9.
            val opts = mgr.getAppWidgetOptions(id)
            val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val numSlots = if (heightDp >= 150) 9 else 6
            views.setViewVisibility(R.id.w_row3, if (numSlots > 6) View.VISIBLE else View.GONE)

            for (i in allSlotIds.indices) {
                if (i >= numSlots) {
                    views.setViewVisibility(allSlotIds[i], View.GONE)
                    continue
                }
                if (i < acts.size) {
                    val act = acts[i]
                    val aid = act.getString("id")
                    val running = aid == runId
                    // Forma = si tocar para o arranca; color = el propio de la actividad,
                    // salvo si est\u00E1 pausada (\u00E1mbar) para distinguir el estado de un vistazo.
                    views.setImageViewResource(allIconIds[i], if (running) R.drawable.ic_stop else R.drawable.ic_play)
                    val tint = if (running && paused) {
                        ContextCompat.getColor(context, R.color.pausedColor)
                    } else {
                        Color.parseColor(act.optString("color", "#2F4B8F"))
                    }
                    views.setInt(allIconIds[i], "setColorFilter", tint)
                    views.setTextViewText(allNameIds[i], act.getString("name"))
                    views.setViewVisibility(allSlotIds[i], View.VISIBLE)
                    val intent = Intent(context, TimerWidget::class.java).apply {
                        action = ACTION_TOGGLE
                        putExtra("actId", aid)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val pi = PendingIntent.getBroadcast(context, 100 + i, intent, flags)
                    views.setOnClickPendingIntent(allSlotIds[i], pi)
                } else {
                    views.setViewVisibility(allSlotIds[i], View.GONE)
                }
            }
            // Indicador "+N m\u00E1s" si hay actividades fuera de las visibles.
            val extra = acts.size - numSlots
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
