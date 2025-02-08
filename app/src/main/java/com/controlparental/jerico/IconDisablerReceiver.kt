package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.util.Log
import android.content.pm.PackageManager


class IconDisablerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val packageManager = context.packageManager
        val componentName = ComponentName(context, MainActivity::class.java)

        try {
            Log.d("IconDisablerReceiver", "Deshabilitando ícono del launcher desde BroadcastReceiver")
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("IconDisablerReceiver", "Ícono deshabilitado correctamente.")
        } catch (e: Exception) {
            Log.e("IconDisablerReceiver", "Error al intentar deshabilitar el ícono del launcher", e)
        }
    }
}
