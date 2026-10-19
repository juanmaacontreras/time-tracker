package com.bitacora.timer

import android.content.Context

/**
 * Todo lo que muestra el estado fuera de la app: notificación y los dos widgets.
 * Se refresca entero tras cualquier cambio; nunca sin cambio, porque re-aplicar
 * setChronometer sobre un cronómetro en vivo produce el "flash del número gigante".
 */
object Surfaces {
    private const val PREFS = "bitacora_widget"
    private const val KEY_DAY = "rendered_day"

    fun refresh(ctx: Context) {
        Notifs.update(ctx)
        TimerWidget.refresh(ctx)
        ResumenWidget.refresh(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DAY, Store.startOfToday()).apply()
    }

    // Los widgets muestran "hoy" (el total, la comparación, la última actividad) y no se
    // redibujan solos con el paso del tiempo: pasada la medianoche seguían mostrando lo de
    // ayer hasta el siguiente cambio. SyncWorker llama a esto cuando el sync no trajo
    // nada; redibuja una sola vez por día.
    fun refreshIfNewDay(ctx: Context) {
        val day = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DAY, 0L)
        if (day != Store.startOfToday()) refresh(ctx)
    }
}
