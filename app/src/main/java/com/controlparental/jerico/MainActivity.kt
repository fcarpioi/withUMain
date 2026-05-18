package com.controlparental.jerico

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val mainHandler = Handler(Looper.getMainLooper())

    // Utilizando el nuevo ActivityResultLauncher para manejar permisos
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Mostrar en logs qué permisos fueron otorgados o denegados
        permissions.entries.forEach {
            Log.d("MainActivity", "${it.key} = ${it.value}")
        }

        val denied = permissions.filterValues { granted -> !granted }.keys
        if (denied.isEmpty()) {
            Log.d("MainActivity", "All permissions granted")
            checkBackgroundLocationAndStartService()
        } else {
            Log.d("MainActivity", "Some permissions not granted: $denied")
            Toast.makeText(this, "Faltan permisos. Te llevamos a Ajustes.", Toast.LENGTH_LONG).show()
            openAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d("WithUBoot", "Boot diagnostics snapshot: ${BootDiagnostics.snapshot(this)}")
        // Esconder la Action Bar
        supportActionBar?.hide()

        // Hacer que la actividad esté en modo inmersivo
        hideSystemUI()

        auth = FirebaseAuth.getInstance()

        // Verificar si el usuario está autenticado
        if (auth.currentUser != null) {
            // Usuario ya autenticado, proceder con permisos y servicio
            checkPermissionsAndStartService()
        } else {
            // Si no está autenticado, redirigir a RegisterActivity para registro o login
            Log.d("MainActivity", "User not authenticated, redirecting to RegisterActivity")
            startActivity(Intent(this, RegisterActivity::class.java))
            finish() // Finaliza la MainActivity para que no se pueda volver atrás sin autenticación
        }
    }

    // Método para verificar permisos y luego iniciar el servicio
    private fun checkPermissionsAndStartService() {
        if (areCorePermissionsGranted()) {
            checkBackgroundLocationAndStartService()
        } else {
            requestPermissionLauncher.launch(requiredRuntimePermissions())
        }
    }

    private fun requiredRuntimePermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun areCorePermissionsGranted(): Boolean {
        return requiredRuntimePermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkBackgroundLocationAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            showBackgroundLocationHelpDialog()
            return
        }
        startService()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openBackgroundLocationPermissionSettings() {
        val directPermissionIntent = Intent("android.settings.APP_PERMISSION_SETTINGS").apply {
            putExtra("android.provider.extra.APP_PACKAGE", packageName)
            putExtra("android.provider.extra.PERMISSION_NAME", Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (directPermissionIntent.resolveActivity(packageManager) != null) {
            startActivity(directPermissionIntent)
            return
        }
        openAppSettings()
    }

    private fun showBackgroundLocationHelpDialog() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_setup_guidance)
        dialog.setCancelable(false)

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.findViewById<Button>(R.id.guidanceConfirmButton).setOnClickListener {
            dialog.dismiss()
            openBackgroundLocationPermissionSettings()
        }
        dialog.findViewById<Button>(R.id.guidanceCancelButton).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // Método para iniciar el servicio en segundo plano
    private fun startService() {
        val serviceIntent = Intent(this, BackgroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d("MainActivity", "Service started")

        mainHandler.postDelayed({
            if (wasServiceHeartbeatSeenRecently()) {
                moveTaskToBack(true)
            } else {
                showBackgroundExecutionHelpDialog()
            }
        }, 2000L)
    }

    private fun wasServiceHeartbeatSeenRecently(): Boolean {
        val prefs = getSharedPreferences("ServiceStatePrefs", Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong("last_service_heartbeat", 0L)
        val isRunning = prefs.getBoolean("service_running", false)
        val elapsed = System.currentTimeMillis() - lastHeartbeat
        return isRunning && elapsed in 0..15000
    }

    private fun openBackgroundExecutionSettings() {
        val candidates = mutableListOf<Intent>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidates += Intent("android.settings.APP_BATTERY_SETTINGS").apply {
                putExtra("app_package", packageName)
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            candidates += Intent("android.settings.APP_BATTERY_USAGE").apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            candidates += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            candidates += Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }

        openAppSettings()
    }

    private fun showBackgroundExecutionHelpDialog() {
        val message = """
            Para que la app funcione en segundo plano:
            
            1) Entra a "Batería" o "Uso de batería".
            2) Busca esta app.
            3) Selecciona "Sin restricciones" / "Permitir en segundo plano".
            4) Desactiva "Optimización de batería" para esta app.
            5) Regresa a la app.
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Activar ejecución en segundo plano")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Abrir configuración") { _, _ ->
                openBackgroundExecutionSettings()
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    // Método para iniciar el Worker
    private fun startWorker() {
        Log.d("MainActivity", "Worker started")
        val workRequest = PeriodicWorkRequestBuilder<BackgroundServiceWorker>(5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "BackgroundServiceWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

    }

    private fun hideSystemUI() {
        // Usar WindowInsetsController para ocultar las barras de navegación y de estado
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }
}
