package com.controlparental.jerico

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class BackgroundServiceWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    override fun doWork(): Result {
        Log.d("BackgrounServiceWorker", "Inicio BackgroundServiceWorker")
        return try {
            val startReason = inputData.getString(BackgroundService.EXTRA_START_REASON)
                ?: BackgroundService.START_REASON_WORKER

            val serviceIntent = Intent(applicationContext, BackgroundService::class.java).apply {
                putExtra(BackgroundService.EXTRA_START_REASON, startReason)
            }
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
            Result.success()
        } catch (securityException: SecurityException) {
            Log.e(
                "BackgrounServiceWorker",
                "No se pudo arrancar el servicio en foreground",
                securityException
            )
            Result.retry()
        } catch (exception: Exception) {
            Log.e(
                "BackgrounServiceWorker",
                "Fallo inesperado al arrancar el servicio",
                exception
            )
            Result.retry()
        }
    }
}
