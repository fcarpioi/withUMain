package com.controlparental.jerico

import android.content.Context
import androidx.core.content.edit

object BootDiagnostics {
    private const val PREFS_NAME = "BootDiagnostics"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markReceiverSeen(context: Context, action: String?) {
        prefs(context).edit {
            putLong("boot_receiver_seen_at", System.currentTimeMillis())
            putString("boot_receiver_action", action ?: "unknown")
        }
    }

    fun markServiceStartAttempt(context: Context, source: String) {
        prefs(context).edit {
            putLong("boot_service_start_attempted_at", System.currentTimeMillis())
            putString("boot_service_start_source", source)
        }
    }

    fun markServiceStartError(context: Context, source: String, error: Throwable) {
        prefs(context).edit {
            putLong("boot_service_start_error_at", System.currentTimeMillis())
            putString("boot_service_start_error_source", source)
            putString("boot_service_start_error", "${error::class.java.simpleName}: ${error.message}")
        }
    }

    fun markWorkerEnqueued(context: Context) {
        prefs(context).edit {
            putLong("boot_worker_enqueued_at", System.currentTimeMillis())
        }
    }

    fun markServiceCreated(context: Context) {
        prefs(context).edit {
            putLong("background_service_on_create_at", System.currentTimeMillis())
        }
    }

    fun markServiceStarted(context: Context, startReason: String) {
        prefs(context).edit {
            putLong("background_service_on_start_command_at", System.currentTimeMillis())
            putString("background_service_start_reason", startReason)
        }
    }

    fun markServiceForegroundStarted(context: Context, types: Int) {
        prefs(context).edit {
            putLong("background_service_foreground_at", System.currentTimeMillis())
            putInt("background_service_foreground_types", types)
        }
    }

    fun markServiceStopped(context: Context, reason: String) {
        prefs(context).edit {
            putLong("background_service_stopped_at", System.currentTimeMillis())
            putString("background_service_stopped_reason", reason)
        }
    }

    fun snapshot(context: Context): String {
        val prefs = prefs(context)
        return buildString {
            append("receiverAt=")
            append(prefs.getLong("boot_receiver_seen_at", 0L))
            append(", action=")
            append(prefs.getString("boot_receiver_action", null))
            append(", startAttemptAt=")
            append(prefs.getLong("boot_service_start_attempted_at", 0L))
            append(", startSource=")
            append(prefs.getString("boot_service_start_source", null))
            append(", startErrorAt=")
            append(prefs.getLong("boot_service_start_error_at", 0L))
            append(", startError=")
            append(prefs.getString("boot_service_start_error", null))
            append(", workerAt=")
            append(prefs.getLong("boot_worker_enqueued_at", 0L))
            append(", serviceCreateAt=")
            append(prefs.getLong("background_service_on_create_at", 0L))
            append(", serviceStartAt=")
            append(prefs.getLong("background_service_on_start_command_at", 0L))
            append(", serviceReason=")
            append(prefs.getString("background_service_start_reason", null))
            append(", foregroundAt=")
            append(prefs.getLong("background_service_foreground_at", 0L))
            append(", foregroundTypes=")
            append(prefs.getInt("background_service_foreground_types", 0))
            append(", stoppedAt=")
            append(prefs.getLong("background_service_stopped_at", 0L))
            append(", stoppedReason=")
            append(prefs.getString("background_service_stopped_reason", null))
        }
    }
}
