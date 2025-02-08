package com.controlparental.jerico

import android.content.Context
import android.content.Intent
import androidx.core.app.JobIntentService
import android.util.Log

class InstallService : JobIntentService() {
    override fun onHandleWork(intent: Intent) {
        val packageName = intent.data?.encodedSchemeSpecificPart
        Log.d("InstallService", "Inicio del servicio")
        if (packageName == applicationContext.packageName) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName!!)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    companion object {
        private const val JOB_ID = 1000

        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, InstallService::class.java, JOB_ID, work)
        }
    }
}
