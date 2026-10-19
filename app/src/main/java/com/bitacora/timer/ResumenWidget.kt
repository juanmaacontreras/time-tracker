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
        syncInBackground(context)
    }

    // Al cambiar de tamaño cambia cuántas filas entran.
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        renderOne(context, mgr, id)
    }

    // Solo redibuja si el sync trajo cambios reales (ver Surfaces).
    private fun syncInBackground(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                if (Sync.syncProfile(context)) Surfaces.refresh(context)
            } finally { pending.finish() }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PERIOD -> {
                val p = intent.getStringExtra("period") ?: "day"
                setPeriod(context, p)
                refresh(context)
                syncInBackground(context)
            }
        }
    }

    companion object {
        const val ACTION_PERIOD = "com.bitacora.timer.W2_PERIOD"
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

        private val TITLES = mapOf("day" to "Hoy", "week" to "Esta semana", "month" to "Este mes")
        private const val ROW_DP = 37     // widget_resumen_row.xml: margen 10 + texto y barra
        private const val HEAD_DP = 84    // título + total + comparación (con sus márgenes)
        private const val MORE_DP = 19

        // Cuántas filas entran enteras en el alto actual del widget.
        private fun rowsThatFit(hDp: Int, count: Int): Int {
            val free = hDp - 28 - HEAD_DP
            if (count <= free / ROW_DP) return count
            return ((free - MORE_DP) / ROW_DP).coerceAtLeast(1)
        }

        // Control segmentado como el del Resumen: la opción activa "levantada".
        private fun styleSeg(ctx: Context, views: RemoteViews, id: Int, active: Boolean) {
            if (active) views.setInt(id, "setBackgroundResource", R.drawable.seg_on)
            else views.setInt(id, "setBackgroundColor", Color.TRANSPARENT)
            views.setTextColor(id, ContextCompat.getColor(ctx, if (active) R.color.ink else R.color.muted))
        }

        private fun renderOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val pkg = context.packageName
            val views = RemoteViews(pkg, R.layout.widget_resumen)
            val period = getPeriod(context)
            val (from, to) = Store.periodRange(period, 0)
            val secs = Store.secsByActivity(context, from, to)

            // Incluye archivadas, como el Resumen de la app: su tiempo del período cuenta.
            val all = Store.activities(context, includeArchived = true)
            val per = all
                .map { it to (secs[it.getString("id")] ?: 0L) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            val total = per.sumOf { it.second }

            views.setTextViewText(R.id.w2_title, TITLES[period] ?: "")
            views.setTextViewText(R.id.w2_total, spaced(total))
            // La misma comparación que el Resumen ("▲ 1 h 10 m vs. ayer a esta hora").
            val cmp = comparison(context, period, 0, from, total, all.map { it.getString("id") }.toSet())
            if (cmp != null) {
                views.setTextViewText(R.id.w2_cmp, cmp.first)
                views.setTextColor(R.id.w2_cmp, ContextCompat.getColor(context, if (cmp.second > 0) R.color.up else R.color.muted))
                views.setViewVisibility(R.id.w2_cmp, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.w2_cmp, View.GONE)
            }

            styleSeg(context, views, R.id.w2_day, period == "day")
            styleSeg(context, views, R.id.w2_week, period == "week")
            styleSeg(context, views, R.id.w2_month, period == "month")
            views.setOnClickPendingIntent(R.id.w2_day, pi(context, ACTION_PERIOD, "day", 10))
            views.setOnClickPendingIntent(R.id.w2_week, pi(context, ACTION_PERIOD, "week", 11))
            views.setOnClickPendingIntent(R.id.w2_month, pi(context, ACTION_PERIOD, "month", 12))
            // Tocar el fondo (fuera de los botones) abre la app; el refresco ya ocurre solo
            // (sync periódico + al cambiar de período).
            views.setOnClickPendingIntent(android.R.id.background, RemoteUi.openApp(context, 13))

            // La barra es la parte del total (igual que el Resumen), no relativa a la mayor.
            val safeTotal = total.coerceAtLeast(1L)
            val (_, hDp) = RemoteUi.sizeDp(context, mgr, id, 250, 200)
            val shown = minOf(per.size, rowsThatFit(hDp, per.size))
            views.removeAllViews(R.id.w2_list)
            for ((a, sec) in per.take(shown)) {
                val row = RemoteViews(pkg, R.layout.widget_resumen_row)
                RemoteUi.bindSwatch(context, row, a, Store.categoryForActivity(context, a), 24)
                row.setTextViewText(R.id.w2_name, a.getString("name"))
                row.setTextViewText(R.id.w2_time, spaced(sec))
                row.setProgressBar(R.id.w2_bar, 100, ((sec * 100) / safeTotal).toInt().coerceAtLeast(2), false)
                // Tinte por color de actividad (setColorStateList existe desde API 31;
                // antes queda el indigo de w_bar.xml).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    row.setColorStateList(R.id.w2_bar, "setProgressTintList", ColorStateList.valueOf(RemoteUi.colorOf(a)))
                }
                views.addView(R.id.w2_list, row)
            }
            // "+N más" si hay más actividades con tiempo que las filas visibles.
            val extra = per.size - shown
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
