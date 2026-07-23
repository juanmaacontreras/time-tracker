package com.bitacora.timer

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// Chequeo liviano en segundo plano (cada ~15 min): trae el estado compartido,
// prende/apaga la notificacion y refresca el widget. Asi el celu "se entera"
// de algo arrancado en otro dispositivo aunque la app este cerrada.
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try {
            Sync.pullMerge(applicationContext)
            Notifs.update(applicationContext)
            TimerWidget.refresh(applicationContext)
            ResumenWidget.refresh(applicationContext)
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }
}
