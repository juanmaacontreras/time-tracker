package com.bitacora.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifs {
    private const val CHANNEL = "bitacora_timer"
    private const val NOTIF_ID = 1001

    private fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Cronómetro", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    // Muestra u oculta la notificacion segun el estado actual (corriendo o no).
    fun update(ctx: Context) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        val runId = Store.runningActId(ctx)
        if (runId.isEmpty()) {
            nm.cancel(NOTIF_ID)
            return
        }
        val a = Store.activityById(ctx, runId)
        val name = a?.optString("name") ?: "Cronómetro"
        val cat = if (a != null) Store.categoryForActivity(ctx, a) else null
        val catName = cat?.optString("name", "General") ?: "General"

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val openPi = PendingIntent.getActivity(ctx, 0, openIntent, flags)

        val stopIntent = Intent(ctx, TimerWidget::class.java).apply { action = TimerWidget.ACTION_STOP }
        val stopPi = PendingIntent.getBroadcast(ctx, 1, stopIntent, flags)

        val pauseIntent = Intent(ctx, TimerWidget::class.java).apply { action = TimerWidget.ACTION_PAUSE }
        val pausePi = PendingIntent.getBroadcast(ctx, 2, pauseIntent, flags)

        val paused = Store.runningPaused(ctx)
        val running = !paused
        val elapsed = Store.runningElapsedMs(ctx)
        val base = SystemClock.elapsedRealtime() - elapsed
        val stateColor = ContextCompat.getColor(ctx, if (paused) R.color.pausedColor else R.color.live)
        // Sobre el acento lleno va onAccent, no blanco fijo: en oscuro el acento es un
        // celeste claro y el blanco encima casi no se leía.
        val onAccent = ContextCompat.getColor(ctx, R.color.onAccent)
        val inkSoft = ContextCompat.getColor(ctx, R.color.inkSoft)
        val actColor = RemoteUi.colorOf(a)

        fun bindCommon(views: RemoteViews, swatchDp: Int) {
            RemoteUi.bindSwatch(ctx, views, a, cat, swatchDp)
            views.setTextViewText(R.id.n_name, name)
            views.setTextColor(R.id.n_eyebrow, stateColor)
            views.setChronometer(R.id.n_chrono, base, null, running)
            views.setImageViewResource(R.id.n_pause_icon, if (paused) R.drawable.ic_play else R.drawable.ic_pause)
            views.setInt(R.id.n_pause_icon, "setColorFilter", onAccent)
            views.setInt(R.id.n_stop_icon, "setColorFilter", inkSoft)
            // Pausado, el botón invita a seguir con el anillo "live" (sustituto estático del pulso).
            views.setInt(R.id.n_pause, "setBackgroundResource", if (paused) R.drawable.circle_primary_invite else R.drawable.circle_primary)
            views.setContentDescription(R.id.n_pause, if (paused) "Seguir" else "Pausar")
            views.setOnClickPendingIntent(R.id.n_pause, pausePi)
            views.setOnClickPendingIntent(R.id.n_stop, stopPi)
        }

        val big = RemoteViews(ctx.packageName, R.layout.notif)
        bindCommon(big, 40)
        big.setTextViewText(R.id.n_eyebrow, (if (paused) "Pausado" else "Cronometrando") + " · " + catName)
        big.setInt(R.id.n_swatch_ring, "setColorFilter", stateColor)
        big.setViewVisibility(R.id.n_swatch_ring, View.VISIBLE)

        val small = RemoteViews(ctx.packageName, R.layout.notif_small)
        bindCommon(small, 34)
        small.setTextViewText(R.id.n_eyebrow, if (paused) "Pausado" else catName)
        // Desde Android 12 el sistema dibuja su propio círculo con el ícono a la
        // izquierda de la vista colapsada (y lo tiñe con setColor, abajo): el swatch
        // propio quedaría duplicado y le robaría al nombre el poco ancho que hay.
        small.setViewVisibility(R.id.n_swatch, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) View.GONE else View.VISIBLE)

        // Sin .setStyle(): con DecoratedCustomViewStyle el sistema agrega siempre la fila
        // de ícono + nombre de la app. Ojo que desde Android 12 (targetSdk >= 31) el
        // sistema igual envuelve las vistas custom en su plantilla: en la colapsada pone
        // su ícono y la flecha de expandir, y en AOSP la expandida lleva el encabezado
        // completo; algunas capas de fabricante lo omiten. Las vistas están pensadas
        // para entrar en los dos casos (ver notif.xml).
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            // Tiñe el círculo del ícono que dibuja el sistema: la identidad de la
            // actividad también en la vista colapsada.
            .setColor(actColor)
            .setContentTitle(name)
            .setContentText(if (paused) "Pausado" else "Cronometrando…")
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val n = builder.build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            // sin permiso de notificaciones; la app sigue funcionando igual
        }
    }
}
