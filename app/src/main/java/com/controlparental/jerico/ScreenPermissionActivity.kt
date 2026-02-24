package com.controlparental.jerico

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi

class ScreenPermissionActivity : Activity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ScreenCapturePrefs", Context.MODE_PRIVATE)
        val savedResultCode = prefs.getInt("resultCode", -1)
        val savedIntentData = prefs.getString("projectionIntentData", null)

        if (savedResultCode != -1 && !savedIntentData.isNullOrEmpty()) {
            Log.d("ScreenPermission", "🟢 Permiso ya guardado, no es necesario pedirlo otra vez")
            finish()
            return
        }

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val permissionIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(permissionIntent, REQUEST_MEDIA_PROJECTION)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK) {
                val projectionData = data ?: run {
                    Log.e("ScreenPermission", "❌ Permiso concedido pero intent de proyección nulo")
                    finish()
                    return
                }
                Log.d("ScreenPermission", "✅ Permiso de captura concedido")
                Log.d("ScreenPermission", "🔍 resultCode == RESULT_OK? ${resultCode == RESULT_OK}")
                Log.d("ScreenPermission", "resultCode: $resultCode")
                Log.d("ScreenPermission", "Intent data: $projectionData")
                Log.d("ScreenPermission", "Intent extras: ${projectionData.extras}")

                // Guardar en memoria para uso inmediato
                ProjectionPermissionHolder.resultCode = resultCode
                ProjectionPermissionHolder.projectionData = projectionData

                val launchIntent = Intent(this, SilentCaptureActivity::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("projectionData", projectionData)
                }
                startActivity(launchIntent)
            } else {
                Log.e("ScreenPermission", "❌ Permiso de captura denegado o datos nulos")
            }
            finish()
        } else {
            Log.e("ScreenPermission", "❌ Permiso de captura fallido: resultCode=$resultCode, data=$data")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
