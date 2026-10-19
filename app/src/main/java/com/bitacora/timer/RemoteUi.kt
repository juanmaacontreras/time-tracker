package com.bitacora.timer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Kit de la notificación y los widgets: lo que Ui.kt resuelve en la app, con lo poco que
 * permite RemoteViews (sin Views propias, sin mezclar colores en tiempo de ejecución).
 * El swatch es layout/rv_swatch.xml incluido con el tamaño que haga falta.
 */
object RemoteUi {

    // Blanco o tinta oscura según qué tan claro sea el fondo (el color de la actividad
    // lo elige el usuario: los de Store.COLORS son profundos, pero uno viejo puede no serlo).
    fun contrastOn(bg: Int): Int {
        val yiq = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000.0
        return if (yiq >= 150) Color.parseColor("#2E3440") else Color.WHITE
    }

    fun colorOf(act: JSONObject?): Int = parseColor(act?.optString("color", "") ?: "")

    /**
     * Swatch de identidad: ícono de la categoría (o la inicial) sobre el color de la
     * actividad. sizeDp es el tamaño con el que se incluyó rv_swatch, para dejar el ícono
     * en la misma proporción que el swatch de la app (52%).
     */
    fun bindSwatch(ctx: Context, views: RemoteViews, act: JSONObject?, cat: JSONObject?, sizeDp: Int) {
        val bg = colorOf(act)
        val fg = contrastOn(bg)
        views.setInt(R.id.sw_bg, "setColorFilter", bg)
        val res = CategoryIcons.resOf(cat?.optString("icon", "") ?: "")
        if (res != null) {
            val pad = px(ctx, sizeDp * 0.24f)
            views.setImageViewResource(R.id.sw_icon, res)
            views.setInt(R.id.sw_icon, "setColorFilter", fg)
            views.setViewPadding(R.id.sw_icon, pad, pad, pad, pad)
            views.setViewVisibility(R.id.sw_icon, View.VISIBLE)
            views.setViewVisibility(R.id.sw_letter, View.GONE)
        } else {
            val name = (cat?.optString("name", "") ?: "").ifBlank { act?.optString("name", "") ?: "" }
            views.setTextViewText(R.id.sw_letter, name.trim().take(1).uppercase().ifEmpty { "?" })
            views.setTextViewTextSize(R.id.sw_letter, TypedValue.COMPLEX_UNIT_DIP, sizeDp * 0.42f)
            views.setTextColor(R.id.sw_letter, fg)
            views.setViewVisibility(R.id.sw_letter, View.VISIBLE)
            views.setViewVisibility(R.id.sw_icon, View.GONE)
        }
    }

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Tamaño real del widget en dp. AppWidgetManager informa un rango: en vertical el
    // ancho real es el mínimo y el alto el máximo; en horizontal, al revés.
    fun sizeDp(ctx: Context, mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return (if (w > 0) w else defW) to (if (h > 0) h else defH)
    }

    fun openApp(ctx: Context, req: Int): PendingIntent {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // Cuándo terminó algo, en absoluto ("14:30", "ayer 18:10", "lun 13 oct"): los widgets
    // no se redibujan solos con el paso del tiempo, así que un "hace 2 h" quedaría mintiendo.
    fun whenText(ms: Long): String {
        val hm = SimpleDateFormat("HH:mm", Locale("es")).format(ms)
        val days = Math.round((Store.startOfToday() - dayStart(ms)).toDouble() / 86400000L).toInt()
        return when {
            days <= 0 -> hm
            days == 1 -> "ayer $hm"
            else -> SimpleDateFormat("EEE d MMM", Locale("es")).format(ms).replace(".", "")
        }
    }

    private fun dayStart(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun px(ctx: Context, dp: Float): Int = (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
