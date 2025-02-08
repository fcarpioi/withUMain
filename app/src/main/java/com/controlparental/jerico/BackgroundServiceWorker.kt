package com.controlparental.jerico

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import android.app.ActivityManager // Importar ActivityManager
import android.os.Build // Importar Build


class BackgroundServiceWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    override fun doWork(): Result {
        // Verifica si el servicio ya está corriendo
        Log.d("BackgrounServiceWorker","Inicio BackgroundServiceWorker")
        if (!isServiceRunning(BackgroundService::class.java)) {
            // Inicia el servicio si no está en ejecución
            Log.d("BackgrounServiceWorker","Inicio Servicio no esta correindo BackgroundService")
            val intent = Intent(applicationContext, MainActivity::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        }
        return Result.success()
    }

    // Método para verificar si el servicio ya está corriendo
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
