package com.bitacora.timer

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recibe los push "timbre": mensajes de datos que no traen contenido, solo avisan que
 * algo cambió en el bucket de este perfil para ir a buscarlo.
 *
 * A propósito NO se muestra ninguna notificación propia acá: lo único que hace el push
 * es refrescar la notificación del cronómetro y los widgets que ya existen, en silencio.
 * Los datos reales nunca viajan por Firebase — solo el aviso.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // El token rota solo a veces (reinstalación, borrado de datos, restore). Si no
        // se re-registra, el dispositivo deja de recibir push en silencio.
        val ctx = applicationContext
        Thread { Sync.syncDevices(ctx, token) }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val ctx = applicationContext

        // Defensa por si un push llega dirigido a otro perfil (la Edge Function ya
        // filtra por perfil, pero el token puede haber cambiado de perfil justo entre
        // que se armó el envío y llegó acá). Sin esto se haría un pull al pedo.
        val target = message.data["profileId"] ?: ""
        if (target.isNotEmpty() && target != Store.currentProfileId(ctx)) return

        // Presupuesto de tiempo: el sistema da del orden de 10 segundos antes de poder
        // matar el proceso, por eso este camino usa un timeout más corto que el normal
        // de 12s. Si igual no llega, no se pierde nada: el SyncWorker lo levanta más
        // tarde — el push es best-effort por diseño y nunca es la única vía.
        if (Sync.pullMerge(ctx, PUSH_TIMEOUT_MS)) {
            Notifs.update(ctx)
            TimerWidget.refresh(ctx)
            ResumenWidget.refresh(ctx)
        }
    }

    companion object {
        private const val PUSH_TIMEOUT_MS = 7000
    }
}
