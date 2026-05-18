package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        BootDiagnostics.markReceiverSeen(context, action)
        try {
            when (action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    Log.d("WithUBoot", "Boot/package restart detected, starting service...")

                    val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                        putExtra(
                            BackgroundService.EXTRA_START_REASON,
                            BackgroundService.START_REASON_BOOT
                        )
                    }
                    BootDiagnostics.markServiceStartAttempt(context, "BootReceiver")
                    ContextCompat.startForegroundService(context, serviceIntent)

                    val workerData = Data.Builder()
                        .putString(
                            BackgroundService.EXTRA_START_REASON,
                            BackgroundService.START_REASON_BOOT
                        )
                        .build()

                    val recoveryWork = OneTimeWorkRequestBuilder<BackgroundServiceWorker>()
                        .setInitialDelay(20, TimeUnit.SECONDS)
                        .setInputData(workerData)
                        .build()

                    BootDiagnostics.markWorkerEnqueued(context)
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "BootBackgroundServiceRecovery",
                        ExistingWorkPolicy.REPLACE,
                        recoveryWork
                    )
                }
            }
        } catch (e: Exception) {
            BootDiagnostics.markServiceStartError(context, "BootReceiver", e)
            Log.e("WithUBoot", "Error during boot: ${e.message}")
        }
    }
}
