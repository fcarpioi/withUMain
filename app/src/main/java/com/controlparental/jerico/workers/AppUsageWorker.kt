package com.controlparental.jerico.workers

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date
import java.util.concurrent.TimeUnit
import android.content.pm.PackageManager
import android.os.PowerManager

class AppUsageWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var userId: String
    private lateinit var deviceId: String

    override fun doWork(): Result {
        // Asignar userId y deviceId desde el inputData en doWork()
        userId = inputData.getString("userId") ?: return Result.failure()
        deviceId = inputData.getString("deviceId") ?: return Result.failure()
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Si el dispositivo está interactuando (la pantalla encendida), reintenta más tarde.
        if (powerManager.isInteractive) {
            Log.d("AppUsageWorker", "El dispositivo está en uso, reintentando más tarde")
            return Result.retry()
        }
        return try {
            val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - TimeUnit.MINUTES.toMillis(30) // Últimos 30 minutos
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                startTime,
                endTime
            )
            if (stats.isEmpty()) {
                Log.w("AppUsageWorker", "No usage stats available.")
                return Result.failure()
            }
            // Filtrar por apps usadas más de 5 minutos (5*60*1000)
            val filteredStats = stats.filter { it.totalTimeInForeground >= 5 * 60 * 1000L }
            val usageRootRef = firestore.collection("users")
                .document(userId)
                .collection("devices")
                .document(deviceId)
                .collection("usage")
            for (usage in filteredStats) {
                // Verificar si la aplicación es de sistema
                val pm = applicationContext.packageManager
                try {
                    val appInfo = pm.getApplicationInfo(usage.packageName, 0)
                    val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                      (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystemApp) {
                        // Si es de sistema, se omite esta iteración
                        continue
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e("AppUsageWorker", "Package not found: ${usage.packageName}")
                    continue
                }

                // Aquí sigue el código para extraer el ID de paquete, construir la referencia y subir los datos.
                val segments = usage.packageName.split(".")
                val packageId = if (segments.size >= 3) segments[2] else usage.packageName

                val packageUsageRef = usageRootRef.document(packageId)
                val data = hashMapOf(
                    "packageName" to usage.packageName,
                    "totalTimeInForeground" to usage.totalTimeInForeground,
                    "lastTimeUsed" to usage.lastTimeUsed,
                    "firstTimeStamp" to usage.firstTimeStamp,
                    "lastTimeStamp" to usage.lastTimeStamp,
                    "timestampUpload" to Date()
                )
                packageUsageRef.set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("AppUsageWorker", "Uploaded usage for package: ${usage.packageName} under id: $packageId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("AppUsageWorker", "Failed to upload usage for ${usage.packageName}: ${e.message}")
                    }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("AppUsageWorker", "Error in doWork: ${e.message}")
            Result.failure()
        }
    }
}