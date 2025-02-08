package com.controlparental.jerico
import android.app.IntentService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

class DisableIconService : IntentService("DisableIconService") {
    override fun onHandleIntent(intent: Intent?) {
        val packageManager = packageManager
        val aliasActivityComponent = ComponentName(this, "com.controlparental.jerico.MainActivityAlias")

        try {
            packageManager.setComponentEnabledSetting(
                aliasActivityComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("DisableIconService", "Alias deshabilitado")
        } catch (e: Exception) {
            Log.e("DisableIconService", "Error al deshabilitar el alias", e)
        }
    }
}
