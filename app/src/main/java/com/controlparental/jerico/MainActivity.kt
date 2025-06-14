package com.controlparental.jerico

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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

    // Utilizando el nuevo ActivityResultLauncher para manejar permisos
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Mostrar en logs qué permisos fueron otorgados o denegados
        permissions.entries.forEach {
            Log.d("MainActivity", "${it.key} = ${it.value}")
        }

        // Verificar si todos los permisos fueron otorgados
        val allPermissionsGranted = permissions.values.all { it }
        if (allPermissionsGranted) {
            Log.d("MainActivity", "All permissions granted")
            startService()
            //startWorker() // Iniciar Worker aquí
        } else {
            Log.d("MainActivity", "Some permissions not granted")
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
        if (arePermissionsGranted()) {
            startService()
            //startWorker() // Iniciar Worker aquí también
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.CAMERA
                )
            )
        }
    }

    // Verificando si los permisos han sido otorgados
    private fun arePermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    // Método para iniciar el servicio en segundo plano
    private fun startService() {
        val serviceIntent = Intent(this, BackgroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d("MainActivity", "Service started")

        // Mover la actividad a segundo plano
        moveTaskToBack(true)
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