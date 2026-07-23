package com.bitacora.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

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

    // Círculo sólido del color de la actividad, para usar como ícono grande.
    private fun circleIcon(colorInt: Int): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colorInt
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bmp
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
        val type = a?.optString("type", "") ?: ""
        val colorInt = Color.parseColor(a?.optString("color", "#2F4B8F") ?: "#2F4B8F")

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val openPi = PendingIntent.getActivity(ctx, 0, openIntent, flags)

        val stopIntent = Intent(ctx, TimerWidget::class.java).apply { action = TimerWidget.ACTION_STOP }
        val stopPi = PendingIntent.getBroadcast(ctx, 1, stopIntent, flags)

        val pauseIntent = Intent(ctx, TimerWidget::class.java).apply { action = TimerWidget.ACTION_PAUSE }
        val pausePi = PendingIntent.getBroadcast(ctx, 2, pauseIntent, flags)

        val paused = Store.runningPaused(ctx)
        // El "when" del chrono se calcula desde el tiempo real corrido (sin pausas).
        val chronoBase = Store.now() - Store.runningElapsedMs(ctx)
        val pauseIcon = if (paused) R.drawable.ic_play else R.drawable.ic_pause
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setLargeIcon(circleIcon(colorInt))
            .setContentTitle(name)
            .setContentText(if (paused) "Pausado" else "Cronometrando…")
            .setColorized(true)
            .setColor(colorInt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(pauseIcon, if (paused) "Resumir" else "Pausar", pausePi)
            .addAction(R.drawable.ic_stop, "Parar", stopPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (type.isNotEmpty()) builder.setSubText(type)
        if (paused) {
            // Android no puede "congelar" el chronometer con animación; mostramos texto estático.
            builder.setUsesChronometer(false)
        } else {
            builder.setUsesChronometer(true).setWhen(chronoBase)
        }
        val n = builder.build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            // sin permiso de notificaciones; la app sigue funcionando igual
        }
    }
}
