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

        // Cuántas filas entran enteras (con su barra) en el alto actual del widget.
        // Medidas del layout: padding 14+14, encabezado ~44, chips ~36, cada fila ~36
        // (texto + margen + barra) y la línea de "+N más" ~18 si hace falta.
        private fun rowsThatFit(mgr: AppWidgetManager, id: Int, count: Int): Int {
            // En vertical el alto real es el máximo informado; sin datos, el mínimo del widget.
            val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                .let { if (it > 0) it else 200 }
            val free = h - 28 - 44 - 36
            if (count <= free / 36) return count
            return ((free - 18) / 36).coerceAtLeast(1)
        }

        private fun styleChip(ctx: Context, views: RemoteViews, id: Int, active: Boolean) {
            views.setInt(id, "setBackgroundResource", if (active) R.drawable.chip_on else R.drawable.chip)
            views.setTextColor(
                id,
                if (active) ContextCompat.getColor(ctx, R.color.paper)
                else ContextCompat.getColor(ctx, R.color.inkSoft)
            )
        }

        private fun renderOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_resumen)
            val period = getPeriod(context)
            val (from, to) = Store.periodRange(period, 0)
            val secs = Store.secsByActivity(context, from, to)

            // Incluye archivadas, como el Resumen de la app: su tiempo del período cuenta.
            val per = Store.activities(context, includeArchived = true)
                .map { it to (secs[it.getString("id")] ?: 0L) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            val total = per.sumOf { it.second }

            views.setTextViewText(R.id.w2_total, Store.human(total))

            styleChip(context, views, R.id.w2_day, period == "day")
            styleChip(context, views, R.id.w2_week, period == "week")
            styleChip(context, views, R.id.w2_month, period == "month")
            views.setOnClickPendingIntent(R.id.w2_day, pi(context, ACTION_PERIOD, "day", 10))
            views.setOnClickPendingIntent(R.id.w2_week, pi(context, ACTION_PERIOD, "week", 11))
            views.setOnClickPendingIntent(R.id.w2_month, pi(context, ACTION_PERIOD, "month", 12))
            // Tocar el fondo (fuera de los botones) abre la app; el refresco ya ocurre solo
            // (sync periódico + al cambiar de período).
            val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val openPi = PendingIntent.getActivity(
                context, 13, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.w2_root, openPi)

            val rowBox = intArrayOf(R.id.w2_row0, R.id.w2_row1, R.id.w2_row2, R.id.w2_row3, R.id.w2_row4, R.id.w2_row5)
            val rowN = intArrayOf(R.id.w2_n0, R.id.w2_n1, R.id.w2_n2, R.id.w2_n3, R.id.w2_n4, R.id.w2_n5)
            val rowT = intArrayOf(R.id.w2_t0, R.id.w2_t1, R.id.w2_t2, R.id.w2_t3, R.id.w2_t4, R.id.w2_t5)
            val rowP = intArrayOf(R.id.w2_p0, R.id.w2_p1, R.id.w2_p2, R.id.w2_p3, R.id.w2_p4, R.id.w2_p5)
            // La barra es la parte del total (igual que el Resumen), no relativa a la mayor.
            val safeTotal = total.coerceAtLeast(1L)
            val shown = minOf(rowBox.size, rowsThatFit(mgr, id, per.size))

            for (i in rowBox.indices) {
                if (i < minOf(per.size, shown)) {
                    val a = per[i].first
                    val sec = per[i].second
                    views.setViewVisibility(rowBox[i], View.VISIBLE)
                    views.setTextViewText(rowN[i], a.getString("name"))
                    views.setTextViewText(rowT[i], Store.human(sec))
                    views.setProgressBar(rowP[i], 100, ((sec * 100) / safeTotal).toInt(), false)
                    // Tint dinámico por color de actividad (setColorStateList existe desde API 31).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val c = Color.parseColor(a.optString("color", "#2F4B8F"))
                        views.setColorStateList(rowP[i], "setProgressTintList", ColorStateList.valueOf(c))
                    }
                } else {
                    views.setViewVisibility(rowBox[i], View.GONE)
                }
            }
            // Indicador "+N más" si hay más actividades con tiempo que las filas visibles.
            val extra = per.size - minOf(per.size, shown)
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
