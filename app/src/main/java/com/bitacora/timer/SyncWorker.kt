package com.bitacora.timer

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// Red de seguridad en segundo plano (cada ~1 h, ver MainActivity.scheduleBackgroundSync):
// sincroniza aunque la app esté cerrada. Baja lo que el push no haya traído y SUBE lo que
// haya quedado pendiente — por ejemplo, una pausa desde el widget hecha sin señal, que
// antes no llegaba a los demás dispositivos hasta volver a abrir la app.
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (Sync.syncNow(applicationContext)) Surfaces.refresh(applicationContext)
        return Result.success()
    }
}
