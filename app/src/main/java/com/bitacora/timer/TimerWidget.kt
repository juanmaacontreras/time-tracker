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
            ACTION_REFRESH -> {
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
        const val ACTION_REFRESH = "com.bitacora.timer.REFRESH"

        private fun renderOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget)
            val runId = Store.runningActId(context)
            val acts = Store.activities(context)

            if (runId.isNotEmpty()) {
                val a = Store.activityById(context, runId)
                views.setTextViewText(R.id.w_status, (a?.optString("name") ?: "").uppercase())
                val elapsed = Store.now() - Store.runningStart(context)
                views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime() - elapsed, null, true)
            } else {
                views.setTextViewText(R.id.w_status, "EN REPOSO · tocá para actualizar")
                views.setChronometer(R.id.w_chrono, SystemClock.elapsedRealtime(), null, false)
            }

            // Tocar el cuerpo del widget (fuera de los botones) lo actualiza.
            val refreshIntent = Intent(context, TimerWidget::class.java).apply { action = ACTION_REFRESH }
            val refreshPi = PendingIntent.getBroadcast(
                context, 200, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.w_root, refreshPi)

            val btnIds = intArrayOf(R.id.w_b0, R.id.w_b1, R.id.w_b2)
            for (i in btnIds.indices) {
                if (i < acts.size) {
                    val act = acts[i]
                    val aid = act.getString("id")
                    val label = (if (aid == runId) "\u25A0 " else "\u25B6 ") + act.getString("name")
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
            mgr.updateAppWidget(id, views)
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TimerWidget::class.java))
            for (id in ids) renderOne(context, mgr, id)
        }
    }
}
