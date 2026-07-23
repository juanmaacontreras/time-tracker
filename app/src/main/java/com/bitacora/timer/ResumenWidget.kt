package com.bitacora.timer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

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

        private fun styleChip(ctx: Context, views: RemoteViews, id: Int, active: Boolean) {
            views.setInt(id, "setBackgroundResource", if (active) R.drawable.chip_on else R.drawable.chip)
            views.setTextColor(
                id,
                if (active) ContextCompat.getColor(ctx, R.color.paper)
                else ContextCompat.getColor(ctx, R.color.muted)
            )
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

            styleChip(context, views, R.id.w2_day, period == "day")
            styleChip(context, views, R.id.w2_week, period == "week")
            styleChip(context, views, R.id.w2_month, period == "month")
            views.setOnClickPendingIntent(R.id.w2_day, pi(context, ACTION_PERIOD, "day", 10))
            views.setOnClickPendingIntent(R.id.w2_week, pi(context, ACTION_PERIOD, "week", 11))
            views.setOnClickPendingIntent(R.id.w2_month, pi(context, ACTION_PERIOD, "month", 12))
            views.setOnClickPendingIntent(R.id.w2_root, pi(context, ACTION_REFRESH2, null, 13))

            val rowBox = intArrayOf(R.id.w2_row0, R.id.w2_row1, R.id.w2_row2, R.id.w2_row3, R.id.w2_row4, R.id.w2_row5)
            val rowN = intArrayOf(R.id.w2_n0, R.id.w2_n1, R.id.w2_n2, R.id.w2_n3, R.id.w2_n4, R.id.w2_n5)
            val rowT = intArrayOf(R.id.w2_t0, R.id.w2_t1, R.id.w2_t2, R.id.w2_t3, R.id.w2_t4, R.id.w2_t5)
            val rowP = intArrayOf(R.id.w2_p0, R.id.w2_p1, R.id.w2_p2, R.id.w2_p3, R.id.w2_p4, R.id.w2_p5)
            val max = (per.firstOrNull()?.second ?: 1L).coerceAtLeast(1L)

            for (i in rowBox.indices) {
                if (i < per.size) {
                    val a = per[i].first
                    val sec = per[i].second
                    views.setViewVisibility(rowBox[i], View.VISIBLE)
                    views.setTextViewText(rowN[i], a.getString("name"))
                    views.setTextViewText(rowT[i], Store.exact(sec))
                    views.setProgressBar(rowP[i], 100, ((sec * 100) / max).toInt(), false)
                    // Tint dinámico por color de actividad (setColorStateList existe desde API 31).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val c = Color.parseColor(a.optString("color", "#2F4B8F"))
                        views.setColorStateList(rowP[i], "setProgressTintList", ColorStateList.valueOf(c))
                    }
                } else {
                    views.setViewVisibility(rowBox[i], View.GONE)
                }
            }
            // Indicador "+N más" si hay más actividades con tiempo que las 6 visibles.
            val extra = per.size - rowBox.size
            if (extra > 0) {
                views.setTextViewText(R.id.w2_more, "+$extra más")
                views.setViewVisibility(R.id.w2_more, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.w2_more, View.GONE)
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
