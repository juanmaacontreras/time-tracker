package com.bitacora.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object Notifs {
    private const val CHANNEL = "bitacora_timer"
    private const val NOTIF_ID = 1001

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Cronómetro", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    // Blanco o tinta oscura según qué tan claro sea el color de fondo (swatch de la
    // categoría, que es arbitrario) — mismo criterio que el mockup HTML.
    private fun contrastOn(bgColor: Int): Int {
        val r = Color.red(bgColor); val g = Color.green(bgColor); val b = Color.blue(bgColor)
        val yiq = (r * 299 + g * 587 + b * 114) / 1000.0
        return if (yiq >= 150) Color.parseColor("#1B1D24") else Color.WHITE
    }

    // Arma el swatch de identidad (ícono de categoría, o inicial si no tiene) y el
    // anillo de estado — común a la vista chica y la grande. El anillo reemplaza al
    // pulso animado del mockup: RemoteViews no puede arrancar una animación en un
    // proceso remoto, así que "corriendo" se marca con este borde estático.
    private fun bindSwatch(views: RemoteViews, cat: JSONObject, running: Boolean, liveColor: Int) {
        val bgColor = Color.parseColor(cat.optString("color", "#2F4B8F"))
        views.setInt(R.id.n_swatch_bg, "setColorFilter", bgColor)
        views.setInt(R.id.n_swatch_ring, "setColorFilter", liveColor)
        views.setViewVisibility(R.id.n_swatch_ring, if (running) android.view.View.VISIBLE else android.view.View.GONE)

        val fg = contrastOn(bgColor)
        val iconRes = CategoryIcons.resOf(cat.optString("icon", ""))
        if (iconRes != null) {
            views.setImageViewResource(R.id.n_swatch_icon, iconRes)
            views.setInt(R.id.n_swatch_icon, "setColorFilter", fg)
            views.setViewVisibility(R.id.n_swatch_icon, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.n_swatch_letter, android.view.View.GONE)
        } else {
            val name = cat.optString("name", "General")
            views.setTextViewText(R.id.n_swatch_letter, if (name.isNotEmpty()) name.substring(0, 1).uppercase() else "?")
            views.setTextColor(R.id.n_swatch_letter, fg)
            views.setViewVisibility(R.id.n_swatch_letter, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.n_swatch_icon, android.view.View.GONE)
        }
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
        val pauseIcon = if (paused) R.drawable.ic_play else R.drawable.ic_pause
        val pauseLabel = if (paused) "Resumir" else "Pausar"
        val liveColor = ContextCompat.getColor(ctx, R.color.live)
        val mutedColor = ContextCompat.getColor(ctx, R.color.muted)

        fun bindCommon(views: RemoteViews) {
            views.setTextViewText(R.id.n_name, name)
            views.setTextViewText(R.id.n_cat, cat?.optString("name", "General") ?: "General")
            views.setChronometer(R.id.n_chrono, base, null, running)
            if (cat != null) bindSwatch(views, cat, running, liveColor)

            views.setImageViewResource(R.id.n_pause_icon, pauseIcon)
            views.setInt(R.id.n_stop_icon, "setColorFilter", mutedColor)
            views.setOnClickPendingIntent(R.id.n_pause, pausePi)
            views.setOnClickPendingIntent(R.id.n_stop, stopPi)
        }

        val big = RemoteViews(ctx.packageName, R.layout.notif)
        bindCommon(big)
        big.setTextViewText(R.id.n_eyebrow, if (paused) "Pausado" else "Cronometrando")
        big.setTextColor(R.id.n_eyebrow, if (paused) mutedColor else liveColor)
        big.setTextViewText(R.id.n_pause_label, pauseLabel)
        big.setInt(R.id.n_pause, "setBackgroundResource", if (paused) R.drawable.pill_primary_invite else R.drawable.pill_primary)
        big.setInt(R.id.n_stop, "setBackgroundResource", R.drawable.pill_ghost)

        val small = RemoteViews(ctx.packageName, R.layout.notif_small)
        bindCommon(small)
        small.setInt(R.id.n_pause, "setBackgroundResource", if (paused) R.drawable.circle_primary_invite else R.drawable.circle_primary)
        small.setInt(R.id.n_stop, "setBackgroundResource", R.drawable.circle_ghost)

        // Sin .setStyle(DecoratedCustomViewStyle()): esa era justamente la que le pedía
        // al sistema dibujar la fila de ícono + nombre de la app + hora arriba de
        // nuestra vista. Sin ningún Style, Android usa las vistas custom tal cual, de
        // punta a punta, sin agregar nada propio (setContentTitle/Text quedan solo
        // como respaldo para accesibilidad/reloj, no se ven en la notificación en sí).
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(name)
            .setContentText(if (paused) "Pausado" else "Cronometrando…")
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
