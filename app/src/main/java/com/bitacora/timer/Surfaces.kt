package com.bitacora.timer

import android.content.Context

/**
 * Todo lo que muestra el estado fuera de la app: notificación y los dos widgets.
 * Se refresca entero tras cualquier cambio; nunca sin cambio, porque re-aplicar
 * setChronometer sobre un cronómetro en vivo produce el "flash del número gigante".
 */
object Surfaces {
    fun refresh(ctx: Context) {
        Notifs.update(ctx)
        TimerWidget.refresh(ctx)
        ResumenWidget.refresh(ctx)
    }
}
