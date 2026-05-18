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
        val splashComponent = ComponentName(context, SplashActivity::class.java)
        val mainComponent = ComponentName(context, MainActivity::class.java)

        try {
            Log.d("IconDisablerReceiver", "Forzando componentes launcher habilitados")
            packageManager.setComponentEnabledSetting(
                splashComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("IconDisablerReceiver", "Componentes launcher habilitados correctamente.")
        } catch (e: Exception) {
            Log.e("IconDisablerReceiver", "Error al actualizar componentes launcher", e)
        }
    }
}
