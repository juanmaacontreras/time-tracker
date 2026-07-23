package com.bitacora.timer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews

class ResumenWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderOne(context, mgr, id)
        val pending = goAsync()
        Thread {
            try { Sync.pullMerge(context); refresh(context) } finally { pending.finish() }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PERIOD -> {
                val p = intent.getStringExtra("period") ?: "day"
                setPeriod(context, p)
                refresh(context)
                val pending = goAsync()
                Thread {
                    try { Sync.pullMerge(context); refresh(context) } finally { pending.finish() }
                }.start()
            }
            ACTION_REFRESH2 -> {
                val pending = goAsync()
                Thread {
                    try { Sync.pullMerge(context); refresh(context) } finally { pending.finish() }
                }.start()
            }
        }
    }

    companion object {
        const val ACTION_PERIOD = "com.bitacora.timer.W2_PERIOD"
        const val ACTION_REFRESH2 = "com.bitacora.timer.W2_REFRESH"
        private const val PREFS = "bitacora_widget"
        private const val KEY_PERIOD = "w2_period"

        private fun getPeriod(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PERIOD, "day") ?: "day"

        private fun setPeriod(ctx: Context, p: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PERIOD, p).apply()
        }

        private fun pi(ctx: Context, action: String, period: String?, req: Int): PendingIntent {
            val i = Intent(ctx, ResumenWidget::class.java).apply {
                this.action = action
                if (period != null) putExtra("period", period)
            }
            return PendingIntent.getBroadcast(
                ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun styleChip(views: RemoteViews, id: Int, active: Boolean) {
            views.setInt(id, "setBackgroundResource", if (active) R.drawable.chip_on else R.drawable.chip)
            views.setTextColor(id, if (active) Color.WHITE else Color.parseColor("#8592AB"))
        }

        private fun renderOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_resumen)
            val period = getPeriod(context)
            val from = Store.periodStart(period)

            val per = Store.activities(context)
                .map { it to Store.secsFor(context, it.getString("id"), from) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            val total = per.sumOf { it.second }

            views.setTextViewText(R.id.w2_total, Store.exact(total))

            styleChip(views, R.id.w2_day, period == "day")
            styleChip(views, R.id.w2_week, period == "week")
            styleChip(views, R.id.w2_month, period == "month")
            views.setOnClickPendingIntent(R.id.w2_day, pi(context, ACTION_PERIOD, "day", 10))
            views.setOnClickPendingIntent(R.id.w2_week, pi(context, ACTION_PERIOD, "week", 11))
            views.setOnClickPendingIntent(R.id.w2_month, pi(context, ACTION_PERIOD, "month", 12))
            views.setOnClickPendingIntent(R.id.w2_root, pi(context, ACTION_REFRESH2, null, 13))

            val rowBox = intArrayOf(R.id.w2_row0, R.id.w2_row1, R.id.w2_row2)
            val rowN = intArrayOf(R.id.w2_n0, R.id.w2_n1, R.id.w2_n2)
            val rowT = intArrayOf(R.id.w2_t0, R.id.w2_t1, R.id.w2_t2)
            val rowP = intArrayOf(R.id.w2_p0, R.id.w2_p1, R.id.w2_p2)
            val max = (per.firstOrNull()?.second ?: 1L).coerceAtLeast(1L)

            for (i in 0..2) {
                if (i < per.size) {
                    val a = per[i].first
                    val sec = per[i].second
                    views.setViewVisibility(rowBox[i], View.VISIBLE)
                    views.setTextViewText(rowN[i], a.getString("name"))
                    views.setTextViewText(rowT[i], Store.exact(sec))
                    views.setProgressBar(rowP[i], 100, ((sec * 100) / max).toInt(), false)
                } else {
                    views.setViewVisibility(rowBox[i], View.GONE)
                }
            }
            views.setViewVisibility(R.id.w2_empty, if (per.isEmpty()) View.VISIBLE else View.GONE)

            mgr.updateAppWidget(id, views)
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ResumenWidget::class.java))
            for (id in ids) renderOne(context, mgr, id)
        }
    }
}
