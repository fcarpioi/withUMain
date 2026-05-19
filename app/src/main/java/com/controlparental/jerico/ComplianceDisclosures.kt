package com.controlparental.jerico

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

object ComplianceDisclosures {
    private const val PREFS_NAME = "CompliancePrefs"
    private const val CORE_MONITORING_ACCEPTED = "core_monitoring_accepted"
    private const val QR_CAMERA_ACCEPTED = "qr_camera_accepted"

    fun showCoreMonitoringDisclosureIfNeeded(activity: Activity, onAccepted: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(CORE_MONITORING_ACCEPTED, false)) {
            onAccepted()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.disclosure_monitoring_title)
            .setMessage(R.string.disclosure_monitoring_message)
            .setCancelable(false)
            .setPositiveButton(R.string.disclosure_accept) { _, _ ->
                prefs.edit().putBoolean(CORE_MONITORING_ACCEPTED, true).apply()
                onAccepted()
            }
            .setNegativeButton(R.string.disclosure_decline, null)
            .show()
    }

    fun showQrCameraDisclosureIfNeeded(activity: Activity, onAccepted: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(QR_CAMERA_ACCEPTED, false)) {
            onAccepted()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.disclosure_qr_camera_title)
            .setMessage(R.string.disclosure_qr_camera_message)
            .setCancelable(false)
            .setPositiveButton(R.string.disclosure_accept) { _, _ ->
                prefs.edit().putBoolean(QR_CAMERA_ACCEPTED, true).apply()
                onAccepted()
            }
            .setNegativeButton(R.string.disclosure_decline, null)
            .show()
    }

    fun openUsageAccessDisclosure(context: Context) {
        val intent = Intent(context, UsageAccessDisclosureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openUsageAccessSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}
