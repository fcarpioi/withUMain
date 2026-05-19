package com.controlparental.jerico

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import android.os.HandlerThread
import android.util.Size
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors
import androidx.core.content.edit
import android.app.AppOpsManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import java.util.concurrent.TimeUnit
import android.media.AudioAttributes
import android.util.SparseIntArray
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider

import androidx.camera.core.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner


import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import android.app.usage.UsageStatsManager
import android.app.usage.UsageStats

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var handler: Handler
    private var filePath: String = ""
    private lateinit var batteryStatusReceiver: BatteryStatusReceiver
    private var lastBatteryPercentage: Float = -1f
    private var audioManager: AudioManager? = null
    private lateinit var handlerThread: HandlerThread
    private var imageReader: ImageReader? = null
    private var scannedDeviceId: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var isListening: Boolean = false
    private var isPausedForAudio: Boolean = false
    private var audioCheckHandler: Handler? = null
    private var audioCheckRunnable: Runnable? = null
    private var pendingSpeechRestart: Runnable? = null
    private val keywordListenerEnabled = true
    private val recordingAudioSource = MediaRecorder.AudioSource.MIC
    private var lastSpeechStartAtMs: Long = 0L
    private var currentSpeechRestartDelayMs: Long = 15_000L
    private var lastKeywordAlarmAtMs: Long = 0L
    private val minSpeechStartIntervalMs: Long = 12_000L
    private val maxSpeechRestartDelayMs: Long = 60_000L
    private val keywordAlarmCooldownMs: Long = 90_000L

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var isCameraActive: Boolean = false
    private var userPreferencesListener: ListenerRegistration? = null
    private var isManualActivationReceiverRegistered = false
    private var isBatteryReceiverRegistered = false
    private var isScreenReceiverRegistered = false
    private val serviceStatePrefs by lazy {
        getSharedPreferences("ServiceStatePrefs", Context.MODE_PRIVATE)
    }
    private val firestoreHeartbeatIntervalMillis = 60_000L
    private val firestoreHeartbeatRunnable = object : Runnable {
        override fun run() {
            updateFirestoreHeartbeat()
            mainHandler.postDelayed(this, firestoreHeartbeatIntervalMillis)
        }
    }


    override fun onCreate() {
        super.onCreate()
        BootDiagnostics.markServiceCreated(this)
        Log.d("BackgroundService", "Service onCreate called")
        updateServiceHeartbeat()
        initializeServiceDependencies()
        registerServiceReceivers()
        startListeningForUserPreferences()
        startBackgroundThread()
        requestUsageStatsPermissionIfNeeded()
        initializeSpeechRecognitionOnCreate()
    }

    private fun initializeServiceDependencies() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        storage = FirebaseStorage.getInstance()
        handler = mainHandler
        cameraExecutor = Executors.newSingleThreadExecutor()
        batteryStatusReceiver = BatteryStatusReceiver()
    }

    private fun registerServiceReceivers() {
        val manualActivationIntentFilter = IntentFilter("com.controlparental.jerico.ACTION_MANUAL_ACTIVATE")
        registerManualActivationReceiver(manualActivationIntentFilter)

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerBatteryReceiver(batteryFilter)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerScreenStateReceiver(screenFilter)
    }

    private fun registerManualActivationReceiver(filter: IntentFilter) {
        ContextCompat.registerReceiver(
            this,
            manualActivationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isManualActivationReceiverRegistered = true
    }

    private fun registerBatteryReceiver(filter: IntentFilter) {
        ContextCompat.registerReceiver(
            this,
            batteryStatusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isBatteryReceiverRegistered = true
    }

    private fun registerScreenStateReceiver(filter: IntentFilter) {
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isScreenReceiverRegistered = true
    }

    private fun requestUsageStatsPermissionIfNeeded() {
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
    }

    private fun initializeSpeechRecognitionOnCreate() {
        if (!keywordListenerEnabled) {
            Log.d("BackgroundService", "Keyword listener deshabilitado para pruebas")
            return
        }
        if (isScreenOff()) {
            tryStartSpeechRecognizer()
        } else {
            Log.d("BackgroundService", "La pantalla está encendida, SpeechRecognizer no se inicia")
        }
    }

    private fun isScreenOff(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isInteractive
    }

    private fun canRunSpeechRecognitionNow(): Boolean {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val canRun = hasAudioPermission && isScreenOff() && !isPausedForAudio && !isRecording
        if (!canRun) {
            logRecognizerState("canRunSpeechRecognitionNow=false")
        }
        return canRun
    }

    private fun logRecognizerState(reason: String) {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val musicActive = audioManager?.isMusicActive ?: false
        val mode = audioManager?.mode ?: -1
        val sysVol = audioManager?.getStreamVolume(AudioManager.STREAM_SYSTEM) ?: -1
        val notiVol = audioManager?.getStreamVolume(AudioManager.STREAM_NOTIFICATION) ?: -1
        val musicVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val ringVol = audioManager?.getStreamVolume(AudioManager.STREAM_RING) ?: -1
        Log.d(
            "SpeechDiag",
            "reason=$reason screenOff=${isScreenOff()} hasAudioPerm=$hasAudioPermission isPausedForAudio=$isPausedForAudio isRecording=$isRecording isListening=$isListening musicActive=$musicActive mode=$mode vol(system=$sysVol notification=$notiVol music=$musicVol ring=$ringVol)"
        )
    }

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
        prefs.edit { putBoolean("usagePermissionRequested", true) }
    }


    private fun requestUsageAccess(context: Context) {
        ComplianceDisclosures.openUsageAccessDisclosure(context)
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_TRIGGER_ALARM = "com.controlparental.jerico.ACTION_TRIGGER_ALARM"
        const val EXTRA_START_REASON = "com.controlparental.jerico.extra.START_REASON"
        const val START_REASON_BOOT = "boot"
        const val START_REASON_WORKER = "worker"
        const val START_REASON_APP = "app"
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BackgroundService", "Service onStartCommand called")
        updateServiceHeartbeat()
        val startReason = intent?.getStringExtra(EXTRA_START_REASON) ?: START_REASON_APP
        BootDiagnostics.markServiceStarted(this, startReason)
        if (!checkPermissions()) {
            Log.w("BackgroundService", "Required permissions not granted; stopping service start")
            BootDiagnostics.markServiceStopped(this, "missing_permissions")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val foregroundTypes = resolveForegroundServiceTypes(startReason)
                startForeground(NOTIFICATION_ID, notification, foregroundTypes)
                BootDiagnostics.markServiceForegroundStarted(this, foregroundTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
                BootDiagnostics.markServiceForegroundStarted(this, 0)
            }
        } catch (securityException: SecurityException) {
            BootDiagnostics.markServiceStartError(this, "BackgroundService.startForeground", securityException)
            BootDiagnostics.markServiceStopped(this, "foreground_security_exception")
            Log.e("BackgroundService", "Unable to start foreground service", securityException)
            stopSelf()
            return START_NOT_STICKY
        }

        // Verifica si se envió la acción para activar la alarma
        if (intent?.action == ACTION_TRIGGER_ALARM) {
            triggerAlarm()
        }

        startServiceRuntimeMonitoring()
        startFirestoreHeartbeat()

        return START_STICKY
    }

    private fun resolveForegroundServiceTypes(startReason: String): Int {
        var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val appIsVisible = ProcessLifecycleOwner.get()
            .lifecycle
            .currentState
            .isAtLeast(Lifecycle.State.STARTED)

        if (!appIsVisible) {
            Log.d(
                "BackgroundService",
                "Foreground start in background ($startReason); using location-only type"
            )
            return foregroundTypes
        }

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCameraPermission) {
            foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasRecordAudioPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        return foregroundTypes
    }

    private fun updateServiceHeartbeat() {
        serviceStatePrefs.edit {
            putLong("last_service_heartbeat", System.currentTimeMillis())
            putBoolean("service_running", true)
        }
    }

    private fun startFirestoreHeartbeat() {
        mainHandler.removeCallbacks(firestoreHeartbeatRunnable)
        updateFirestoreHeartbeat()
        mainHandler.postDelayed(firestoreHeartbeatRunnable, firestoreHeartbeatIntervalMillis)
    }

    private fun stopFirestoreHeartbeat() {
        mainHandler.removeCallbacks(firestoreHeartbeatRunnable)
        val deviceContext = getCurrentDeviceContext() ?: return
        getDeviceDocRef(deviceContext)
            .set(
                mapOf(
                    "serviceOnline" to false,
                    "lastHeartbeatAt" to Date()
                ),
                SetOptions.merge()
            )
    }

    private fun updateFirestoreHeartbeat() {
        val deviceContext = getCurrentDeviceContext() ?: return
        getDeviceDocRef(deviceContext)
            .set(
                mapOf(
                    "serviceOnline" to true,
                    "lastHeartbeatAt" to Date()
                ),
                SetOptions.merge()
            )
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Failed to update service heartbeat: ${e.message}")
            }
    }

    private fun startServiceRuntimeMonitoring() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initializeSpeechRecognizer()
        startListeningForUserPreferences()
        startAudioMonitoring()
        mainHandler.postDelayed({
            tryStartSpeechRecognizer()
        }, 2000)
    }

    private fun createNotification(): Notification {
        val channelId = "BackgroundServiceChannel"

        val channel = NotificationChannel(
            channelId,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

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

    // Aquí recibimos el estado de la batería y actualizamos Firestore
    inner class BatteryStatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Obtener el porcentaje de batería
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            if (level != -1 && scale != -1) {
                // Convertir el porcentaje a un valor entre 0 y 1
                val batteryPercentage = level / scale.toFloat()
                Log.d("BatteryStatus", "Battery level: ${batteryPercentage}%")

                val deviceContext = getCurrentDeviceContext() ?: return

                // Si ambos userId y deviceId son válidos, actualizamos Firestore
                if (hasBatteryChanged(batteryPercentage)) {
                    updateBatteryField(batteryPercentage, deviceContext)
                    lastBatteryPercentage = batteryPercentage
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

    private fun updateBatteryField(batteryPercentage: Float, deviceContext: DeviceContext) {
        val deviceDocRef = getDeviceDocRef(deviceContext)

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
        val deviceContext = getCurrentDeviceContext() ?: return
        val deviceDocRef = getDeviceDocRef(deviceContext)
        Log.d(
            "BackgroundService",
            "Listening device prefs at users/${deviceContext.userId}/devices/${deviceContext.deviceId}"
        )

        userPreferencesListener?.remove()
        userPreferencesListener = deviceDocRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e("BackgroundService", "Error listening for user data changes: ${e.message}")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                handleDevicePreferencesSnapshot(snapshot, deviceDocRef, deviceContext)
            }
        }
    }

    private fun handleDevicePreferencesSnapshot(
        snapshot: DocumentSnapshot,
        deviceDocRef: DocumentReference,
        deviceContext: DeviceContext
    ) {
        val trackingEnabled = snapshot.getBoolean("trackingEnabled") ?: true
        val newRecordingEnabled = snapshot.getBoolean("recordingEnabled") ?: false
        locationUpdateInterval = snapshot.getLong("locationUpdateInterval") ?: 10000L
        recordingCycleDuration = snapshot.getLong("recordingCycleDuration") ?: 60000L
        val takePhoto = snapshot.getBoolean("takePhoto") ?: false
        val soundEnabled = snapshot.getBoolean("sound") ?: false
        val requestUsagePermission = snapshot.getBoolean("requestUsagePermission") ?: false
        val trackApps = snapshot.getBoolean("trackApps") ?: false
        Log.d(
            "BackgroundService",
            "Prefs snapshot for ${deviceContext.deviceId}: takePhoto=$takePhoto recordingEnabled=$newRecordingEnabled trackingEnabled=$trackingEnabled"
        )

        handleTrackingPreference(trackingEnabled)
        handleRecordingPreference(newRecordingEnabled)
        handlePhotoPreference(takePhoto)
        handleSoundPreference(soundEnabled, deviceContext)
        handleTrackAppsPreference(trackApps, deviceDocRef)
        handleUsagePermissionPreference(requestUsagePermission, deviceDocRef)

        verificarPermisoDeUso(this, requestUsagePermission, deviceDocRef)
    }

    private fun handleTrackingPreference(trackingEnabled: Boolean) {
        if (trackingEnabled) {
            stopLocationUpdates()
            startLocationUpdates()
        } else {
            stopLocationUpdates()
        }
    }

    private fun handleRecordingPreference(recordingEnabled: Boolean) {
        if (recordingEnabled && !isRecording) {
            stopListening()
            startRecordingCycle()
        } else if (!recordingEnabled && isRecording) {
            stopRecordingAndUpload(filePath)
        }
    }

    private fun handlePhotoPreference(takePhoto: Boolean) {
        if (takePhoto) {
            takePhotoAndUpload()
            updateTakePhotoField(false)
        }
    }

    private fun handleSoundPreference(soundEnabled: Boolean, deviceContext: DeviceContext) {
        if (soundEnabled) {
            playAlarmSound()
            updateSoundField(false, deviceContext)
        }
    }

    private fun handleTrackAppsPreference(trackApps: Boolean, deviceDocRef: DocumentReference) {
        if (trackApps) {
            handleTrackApps(deviceDocRef)
        }
    }

    private fun handleUsagePermissionPreference(
        requestUsagePermission: Boolean,
        deviceDocRef: DocumentReference
    ) {
        if (!requestUsagePermission) return

        if (!hasUsageStatsPermission(this)) {
            Log.d("Permissions", "🔁 Campo 'requestUsagePermission' es true. Lanzando pantalla de permisos.")
            requestUsageAccess(this)
            deviceDocRef.update("requestUsagePermission", false)
                .addOnSuccessListener {
                    Log.d("Permissions", "🧹 requestUsagePermission actualizado a false después de lanzar pantalla de permiso.")
                }
                .addOnFailureListener { err ->
                    Log.e("Permissions", "❌ Error al actualizar requestUsagePermission: ${err.message}")
                }
        } else {
            Log.d("Permissions", "✅ Permiso USAGE_STATS ya otorgado.")
        }
    }
    private fun handleTrackApps(deviceDocRef: DocumentReference) {
        val deviceContext = getCurrentDeviceContext() ?: return
        if (!hasUsageStatsPermission(this)) {
            updateTrackAppsStatus(
                deviceDocRef = deviceDocRef,
                status = "missing_usage_permission",
                savedCount = 0,
                extraUpdates = mapOf(
                    "requestUsagePermission" to true,
                    "trackAppsLastError" to "Usage Access permission is not granted"
                )
            )
            Log.w("TrackApps", "No se puede recolectar uso de apps: falta Usage Access")
            return
        }

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.DAYS.toMillis(1) // Últimas 24 horas

        val stats = try {
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
        } catch (e: Exception) {
            updateTrackAppsStatus(
                deviceDocRef = deviceDocRef,
                status = "query_error",
                savedCount = 0,
                extraUpdates = mapOf("trackAppsLastError" to (e.message ?: e.javaClass.simpleName))
            )
            Log.e("TrackApps", "Error consultando UsageStats", e)
            return
        }

        if (stats.isEmpty()) {
            Log.w("TrackApps", "No se encontraron estadísticas de uso")
            updateTrackAppsStatus(
                deviceDocRef = deviceDocRef,
                status = "no_usage_stats",
                savedCount = 0,
                extraUpdates = mapOf("trackAppsLastError" to "UsageStatsManager returned no stats")
            )
            return
        }

        val usageRootRef = getUsageRootRef(deviceContext)
        val pm = packageManager
        val minForegroundMillis = 5 * 60 * 1000L
        val allowedUsage = stats.mapNotNull { usage ->
            try {
                if (usage.totalTimeInForeground < minForegroundMillis) return@mapNotNull null
                if (!isTrackAppsPackageAllowed(pm, usage.packageName)) return@mapNotNull null
                usage
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("TrackApps", "App no encontrada: ${usage.packageName}")
                null
            }
        }

        if (allowedUsage.isEmpty()) {
            updateTrackAppsStatus(
                deviceDocRef = deviceDocRef,
                status = "no_matching_apps",
                savedCount = 0,
                extraUpdates = mapOf("trackAppsLastError" to "No non-system app exceeded the 5 minute threshold")
            )
            return
        }

        val batch = firestore.batch()
        allowedUsage.forEach { usage ->
            val packageId = resolvePackageId(usage.packageName)
            batch.set(usageRootRef.document(packageId), buildUsageData(usage), SetOptions.merge())
        }
        batch.update(
            deviceDocRef,
            mapOf(
                "trackApps" to false,
                "trackAppsLastRunAt" to FieldValue.serverTimestamp(),
                "trackAppsLastStatus" to "success",
                "trackAppsLastSavedCount" to allowedUsage.size,
                "trackAppsLastError" to null
            )
        )
        batch.commit()
            .addOnSuccessListener {
                Log.d("TrackApps", "Uso de apps guardado. count=${allowedUsage.size}")
            }
            .addOnFailureListener { e ->
                Log.e("TrackApps", "Error guardando uso de apps: ${e.message}")
                updateTrackAppsStatus(
                    deviceDocRef = deviceDocRef,
                    status = "write_error",
                    savedCount = 0,
                    extraUpdates = mapOf("trackAppsLastError" to (e.message ?: e.javaClass.simpleName))
                )
            }
    }

    private fun updateTrackAppsStatus(
        deviceDocRef: DocumentReference,
        status: String,
        savedCount: Int,
        extraUpdates: Map<String, Any?> = emptyMap()
    ) {
        val updates = mutableMapOf<String, Any?>(
            "trackApps" to false,
            "trackAppsLastRunAt" to FieldValue.serverTimestamp(),
            "trackAppsLastStatus" to status,
            "trackAppsLastSavedCount" to savedCount
        )
        updates.putAll(extraUpdates)

        deviceDocRef.update(updates)
            .addOnSuccessListener {
                Log.d("TrackApps", "Estado trackApps actualizado: $status savedCount=$savedCount")
            }
            .addOnFailureListener { e ->
                Log.e("TrackApps", "Error actualizando estado trackApps: ${e.message}")
            }
    }

    private fun startLocationUpdates() {
        lastUpdateTimestamp = 0L
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, locationUpdateInterval)
            .setMinUpdateIntervalMillis(locationUpdateInterval)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    Log.d("BackgroundService", "Location: ${location.latitude}, ${location.longitude}, ${locationUpdateInterval}")
                    sendLocationToFirestore(location.latitude, location.longitude)
                }
            }
        }
        locationCallback = callback

        try {
            if (checkPermissions()) {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            Log.d("BackgroundService", "Immediate location: ${location.latitude}, ${location.longitude}")
                            sendLocationToFirestore(location.latitude, location.longitude)
                        } else {
                            Log.d("BackgroundService", "Immediate location unavailable; waiting for updates")
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("BackgroundService", "Immediate location failed: ${e.message}")
                    }
                fusedLocationClient.requestLocationUpdates(locationRequest, callback, null)
            } else {
                Log.e("BackgroundService", "Location permission not granted")
            }
        } catch (e: SecurityException) {
            Log.e("BackgroundService", "SecurityException: ${e.message}")
        }
    }

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

    private data class DeviceContext(val userId: String, val deviceId: String)

    private fun getCurrentDeviceContext(): DeviceContext? {
        val userId = auth.currentUser?.uid ?: return null
        return DeviceContext(userId, getDeviceIdAsString())
    }

    private fun getDeviceDocRef(deviceContext: DeviceContext): DocumentReference {
        return firestore.collection("users")
            .document(deviceContext.userId)
            .collection("devices")
            .document(deviceContext.deviceId)
    }

    private fun getUsageRootRef(deviceContext: DeviceContext) =
        getDeviceDocRef(deviceContext).collection("usage")

    private fun sendLocationToFirestore(latitude: Double, longitude: Double) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastUpdateTimestamp >= locationUpdateInterval) {
            lastUpdateTimestamp = currentTime

            val deviceContext = getCurrentDeviceContext()
            if (deviceContext != null) {
                val deviceDocRef = getDeviceDocRef(deviceContext)

                // Crear un objeto GeoPoint para las coordenadas
                val geoPoint = GeoPoint(latitude, longitude)

                // Obtener la fecha y hora actual
                val timestamp = Date()
                val locationRef = deviceDocRef.collection("locations").document()

                // Guardar histórico en subcolección y snapshot en el documento principal.
                val locationData = hashMapOf(
                    "locationId" to locationRef.id,
                    "location" to geoPoint,
                    "timestamp" to timestamp,
                    "latitude" to latitude,
                    "longitude" to longitude
                )

                val deviceUpdates = hashMapOf(
                    "lastCoordinate" to geoPoint as Any,
                    "lastTimeStamp" to timestamp as Any
                )

                firestore.batch()
                    .set(locationRef, locationData)
                    .set(deviceDocRef, deviceUpdates, SetOptions.merge())
                    .commit()
                    .addOnSuccessListener {
                        Log.d(
                            "BackgroundService",
                            "Location saved in devices/${deviceContext.deviceId}/locations/${locationRef.id} and device snapshot updated"
                        )
                    }
                    .addOnFailureListener { e ->
                        Log.e(
                            "BackgroundService",
                            "Error saving location batch for user=${deviceContext.userId} device=${deviceContext.deviceId} locationPath=${locationRef.path}: ${e.message}"
                        )
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
            locationCallback = null
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
                    setAudioSource(recordingAudioSource)
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
                    setAudioSource(recordingAudioSource)
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
            "isRead" to false, // Campo adicional con valor predeterminado
            "audioSource" to recordingAudioSource
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
            finishRecordingMode()
        }
    }

    private fun prepareForRecording() {
        cancelPendingSpeechRecognizerRestart()
        // Libera por completo SpeechRecognizer para evitar que tome el micrófono.
        releaseSpeechRecognizer(cancelFirst = true)
        audioManager?.mode = AudioManager.MODE_NORMAL
        isPausedForAudio = true
        val shouldResumeAudioMonitoring = audioCheckHandler != null
        if (shouldResumeAudioMonitoring) {
            stopAudioMonitoring()
        }
    }

    private fun finishRecordingMode() {
        isPausedForAudio = false
        if (audioCheckHandler == null) {
            startAudioMonitoring()
        }

        if (isScreenOff()) {
            mainHandler.postDelayed({
                tryStartSpeechRecognizer()
            }, 1500L)
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
                mainHandler.post {
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
                cameraProvider.bindToLifecycle(
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
                cameraProvider.bindToLifecycle(
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

    private fun takePhotoAndUpload() {
        val deviceContext = getCurrentDeviceContext() ?: return
        takePhotoAndUpload(getDeviceDocRef(deviceContext))
    }

    @SuppressLint("ServiceCast")
    private fun takePhotoAndUpload(deviceDocRef: DocumentReference) {
        val context = applicationContext
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (auth.currentUser?.uid == null) {
            Log.e("CameraError", "No authenticated user, cannot take photo")
            updateTakePhotoField(deviceDocRef, false)
            return
        }
        getDeviceIdAsString()

        if (cameraManager == null) {
            Log.e("CameraError", "Unable to get CameraManager")
            updateTakePhotoField(deviceDocRef, false)
            return
        }

        try {
            val frontCameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            val backCameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            // Requisito funcional: usar cámara frontal para foto remota.
            val preferBackCamera = false
            val preferredCameraId = if (preferBackCamera) backCameraId else frontCameraId
            val cameraId = preferredCameraId ?: frontCameraId ?: backCameraId ?: cameraManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                Log.e("CameraError", "No camera available on this device")
                updateTakePhotoField(deviceDocRef, false)
                return
            }
            Log.d(
                "CameraDebug",
                "Camera seleccionada=$cameraId preferBackWhenScreenOff=$preferBackCamera front=$frontCameraId back=$backCameraId"
            )

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            // En servicio en segundo plano algunos dispositivos no entregan un display asociado.
            // Usamos ROTATION_0 para evitar crashes por contexto sin display.
            val deviceRotation = Surface.ROTATION_0
            val availableSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            val bestSize = chooseStableJpegSize(availableSizes) ?: Size(1920, 1080)
            Log.d("CameraDebug", "Tamaño JPEG seleccionado: ${bestSize.width}x${bestSize.height}")

            val imageReader = ImageReader.newInstance(bestSize.width, bestSize.height, ImageFormat.JPEG, 2)
            val outputFile = File(getPhotoFilePath())
            val pendingFramesToSkip = AtomicInteger(1)

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image == null) {
                        Log.e("CameraError", "ImageReader returned null image")
                        updateTakePhotoField(deviceDocRef, false)
                        return@setOnImageAvailableListener
                    }

                    if (pendingFramesToSkip.getAndDecrement() > 0) {
                        Log.d("CameraDebug", "Warm-up frame descartado; esperando captura final")
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        Log.e("CameraError", "Bitmap decode failed")
                        // Fallback: persistir JPEG crudo por si el decode del vendor falla.
                        outputFile.writeBytes(bytes)
                        Log.w("CameraDebug", "Fallback a JPEG crudo por decode nulo, size=${outputFile.length()} bytes")
                        uploadPhotoToStorage(outputFile, deviceDocRef)
                        reader.close()
                        updateTakePhotoField(deviceDocRef, false)
                        return@setOnImageAvailableListener
                    }

                    Log.d("CameraDebug", "🖼️ Bitmap resolution: ${bitmap.width}x${bitmap.height}")
                    Log.d("CameraDebug", "🧪 Original byte size: ${bytes.size} bytes")
                    val luma = estimateLuma(bitmap)
                    Log.d("CameraDebug", "🌗 Luma promedio antes de ajuste: $luma")
                    if (luma >= 35f) {
                        // Caso normal: evitar pipeline de reprocesado que en este equipo está degradando la imagen.
                        outputFile.writeBytes(bytes)
                        Log.d("CameraDebug", "✅ Usando JPEG crudo (sin recomprimir), size=${outputFile.length()} bytes")
                        uploadPhotoToStorage(outputFile, deviceDocRef)
                        reader.close()
                        return@setOnImageAvailableListener
                    } else if (luma < 20f) {
                        bitmap = enhanceVeryLowLightBitmap(bitmap, luma)
                        Log.d("CameraDebug", "✨✨ Realce fuerte por muy baja luz (luma=$luma)")
                    } else {
                        bitmap = enhanceLowLightBitmap(bitmap)
                        Log.d("CameraDebug", "✨ Se aplicó realce por baja luz (luma=$luma)")
                    }

                    val rotatedOutputStream = FileOutputStream(outputFile)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, rotatedOutputStream)
                    rotatedOutputStream.close()
                    Log.d("CameraDebug", "📦 Compressed image file size: ${outputFile.length()} bytes")

                    uploadPhotoToStorage(outputFile, deviceDocRef)
                    reader.close()
                } catch (e: Exception) {
                    Log.e("CameraError", "Error processing captured image: ${e.message}")
                    updateTakePhotoField(deviceDocRef, false)
                    reader.close()
                }
            }, handler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e("CameraError", "Camera permission not granted at capture time")
                updateTakePhotoField(deviceDocRef, false)
                imageReader.close()
                return
            }

            val cameraCallbackExecutor = ContextCompat.getMainExecutor(this)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val warmupRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    warmupRequest.addTarget(imageReader.surface)

                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureRequest.addTarget(imageReader.surface)

                    // Asegurar auto exposición / foco / balance para evitar capturas negras.
                    val requestBuilders = listOf(warmupRequest, captureRequest)
                    requestBuilders.forEach { request ->
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        request.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        request.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON
                        )
                        request.set(
                            CaptureRequest.CONTROL_AWB_MODE,
                            CaptureRequest.CONTROL_AWB_MODE_AUTO
                        )
                    }

                    // Frontal sin flash forzado: dejamos AE/AWB converger con preview corto.
                    warmupRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                    val aeRange = characteristics.get(
                        CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
                    )
                    if (aeRange != null && aeRange.upper >= 1) {
                        val compensation = minOf(8, aeRange.upper)
                        requestBuilders.forEach { request ->
                            request.set(
                                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                compensation
                            )
                        }
                    }

                    // 📷 Asegurar que la foto se guarde en vertical
                    val jpegOrientation = getJpegOrientation(deviceRotation, sensorOrientation)
                    requestBuilders.forEach { request ->
                        request.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                    }

                    val outputConfigurations = listOf(OutputConfiguration(imageReader.surface))
                    val sessionConfiguration = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigurations,
                        cameraCallbackExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.setRepeatingRequest(warmupRequest.build(), null, handler)
                                    handler.postDelayed({
                                        try {
                                            session.stopRepeating()
                                            session.abortCaptures()
                                            session.capture(captureRequest.build(), null, handler)
                                        } catch (e: CameraAccessException) {
                                            Log.e("CameraError", "Error during final capture: ${e.message}")
                                            updateTakePhotoField(deviceDocRef, false)
                                        }
                                    }, 1200)
                                } catch (e: CameraAccessException) {
                                    Log.e("CameraError", "Error during capture: ${e.message}")
                                    updateTakePhotoField(deviceDocRef, false)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e("CameraError", "Failed to configure camera session.")
                                updateTakePhotoField(deviceDocRef, false)
                            }
                        }
                    )

                    camera.createCaptureSession(sessionConfiguration)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    updateTakePhotoField(deviceDocRef, false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraError", "Camera error: $error")
                    camera.close()
                    updateTakePhotoField(deviceDocRef, false)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("BackgroundService", "Failed to take photo", e)
            updateTakePhotoField(deviceDocRef, false)
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degree: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degree)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun estimateLuma(bitmap: Bitmap): Float {
        val sampleSize = 48
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        val pixels = IntArray(sampleSize * sampleSize)
        scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
        var sum = 0.0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (0.2126 * r) + (0.7152 * g) + (0.0722 * b)
        }
        return (sum / pixels.size).toFloat()
    }

    private fun enhanceLowLightBitmap(input: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val brighten = 70f
        val contrast = 1.35f
        val scale = contrast
        val translate = brighten
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }

    private fun enhanceVeryLowLightBitmap(input: Bitmap, luma: Float): Bitmap {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val safeLuma = luma.coerceAtLeast(1f)
        val contrast = (110f / safeLuma).coerceIn(2.0f, 8.0f)
        val brighten = (150f - safeLuma).coerceIn(60f, 170f)
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brighten,
                0f, contrast, 0f, 0f, brighten,
                0f, 0f, contrast, 0f, brighten,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }

    private fun getJpegOrientation(deviceRotation: Int, sensorOrientation: Int): Int {
        val orientations = SparseIntArray()
        orientations.append(Surface.ROTATION_0, 90)
        orientations.append(Surface.ROTATION_90, 0)
        orientations.append(Surface.ROTATION_180, 270)
        orientations.append(Surface.ROTATION_270, 180)

        return (orientations.get(deviceRotation) + sensorOrientation + 270) % 360
    }

    private fun chooseStableJpegSize(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        // Forzar captura de baja resolución para mayor estabilidad en background.
        val targetPixels = 1280 * 720
        val nonSquare = sizes.filter { it.width != it.height }
        val pool = if (nonSquare.isNotEmpty()) nonSquare else sizes.toList()

        val lowResPool = pool.filter { it.width <= 1600 && it.height <= 1600 }
        val candidates = if (lowResPool.isNotEmpty()) lowResPool else pool

        val exact = candidates.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: candidates.firstOrNull { it.width == 720 && it.height == 1280 }
        if (exact != null) return exact

        return candidates.minByOrNull { kotlin.math.abs((it.width * it.height) - targetPixels) }
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

    private fun uploadPhotoToStorage(file: File, deviceDocRef: DocumentReference) {
        val userId = auth.currentUser?.uid ?: return
        val storageRef = storage.reference.child("photos/$userId/${file.name}")
        val fileName = file.name
        val fileSize = file.length()
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()
        storageRef.putFile(Uri.fromFile(file), metadata)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { uri ->
                    savePhotoUrlToFirestore(
                        url = uri.toString(),
                        fileName = fileName,
                        fileSizeBytes = fileSize
                    )
                    updateTakePhotoField(deviceDocRef, false)
                    file.delete()
                }
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Photo upload failed: ${e.message}")
                updateTakePhotoField(deviceDocRef, false)
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

    private fun savePhotoUrlToFirestore(url: String, fileName: String, fileSizeBytes: Long) {
        val deviceContext = getCurrentDeviceContext() ?: return
        val deviceDocRef = getDeviceDocRef(deviceContext)

        val photoData = mapOf(
            "url" to url,
            "isView" to false,
            "timestamp" to FieldValue.serverTimestamp(),
            "localTimestamp" to Date(),
            "captureSource" to "service_camera2",
            "fileName" to fileName,
            "fileSizeBytes" to fileSizeBytes
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
        val deviceContext = getCurrentDeviceContext() ?: return
        updateTakePhotoField(getDeviceDocRef(deviceContext), value)
    }

    private fun updateTakePhotoField(deviceDocRef: DocumentReference, value: Boolean) {
        deviceDocRef
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


    private fun updateSoundField(value: Boolean, deviceContext: DeviceContext) {
        val deviceDocRef = getDeviceDocRef(deviceContext)

        deviceDocRef.update("sound", value)
            .addOnSuccessListener {
                Log.d("BackgroundService", "Sound field updated to $value")
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Error updating sound field: ${e.message}")
            }
    }

    // microfono

    // Receptor para cambios en el estado de la pantalla
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("ScreenStateReceiver", "Pantalla apagada: verificando estado de audio")
                    logRecognizerState("screen_off_event")

                    // Solo iniciar escucha si se cumplen todas las condiciones de seguridad.
                    if (canRunSpeechRecognitionNow()) {
                        tryStartSpeechRecognizer()
                    } else {
                        Log.d("ScreenStateReceiver", "No se inicia SpeechRecognizer: condiciones no válidas")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d("ScreenStateReceiver", "Pantalla encendida: deteniendo SpeechRecognizer")
                    cancelPendingSpeechRecognizerRestart()
                    // Detener y liberar el SpeechRecognizer para liberar el micrófono
                    releaseSpeechRecognizer(cancelFirst = true)
                }
            }
        }
    }
    // AudioFocusChangeListener eliminado para evitar pitidos y permitir funcionamiento continuo

    // Inicializa el SpeechRecognizer y solicita audio focus (usando la API moderna en API 26+)
    private fun initializeSpeechRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Evita ciclos cortos que generan pitidos frecuentes por reinicios del recognizer.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("SpeechRecognizer", "Listo para escuchar")
                logRecognizerState("onReadyForSpeech")
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
                var keywordDetected = false
                matches?.forEach { result ->
                    Log.d("SpeechRecognizer", "Palabra detectada: $result")
                    if (!keywordDetected && containsKeyword(result)) {
                        keywordDetected = true
                        triggerKeywordAlarm()
                    }
                }
                currentSpeechRestartDelayMs = 30_000L
                // Evitar reinicios seguidos que producen pitidos molestos.
                scheduleSpeechRecognizerRestart(currentSpeechRestartDelayMs) {
                    tryStartSpeechRecognizer()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                isListening = false
                Log.e("SpeechRecognizer", "Error en reconocimiento de voz: $error")
                logRecognizerState("onError_$error")

                // Determinar el delay según el tipo de error
                val baseDelay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        Log.w("SpeechRecognizer", "No match found (ERROR_NO_MATCH): $error")
                        120_000L
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 20_000L
                    SpeechRecognizer.ERROR_AUDIO -> 20_000L
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 30_000L
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 45_000L
                    SpeechRecognizer.ERROR_SERVER -> 30_000L
                    else -> 20_000L
                }
                currentSpeechRestartDelayMs =
                    (maxOf(baseDelay, currentSpeechRestartDelayMs) * 2).coerceAtMost(maxSpeechRestartDelayMs)

                // Reiniciar solo si el contexto sigue siendo válido para no secuestrar audio.
                scheduleSpeechRecognizerRestart(currentSpeechRestartDelayMs) {
                    if (canRunSpeechRecognitionNow()) {
                        Log.d("SpeechRecognizer", "Reiniciando reconocimiento tras error: $error delay=${currentSpeechRestartDelayMs}ms")
                        tryStartSpeechRecognizer()
                    } else {
                        Log.d("SpeechRecognizer", "No se reinicia por condiciones no válidas (pantalla/audio/grabación)")
                    }
                }
            }
        })

        // Audio focus eliminado para evitar pitidos y permitir funcionamiento continuo
        Log.d("SpeechRecognizer", "Iniciando reconocimiento de voz sin audio focus")
    }

    // Función para iniciar la escucha directamente sin audio focus
    private fun tryStartSpeechRecognizer() {
        if (!keywordListenerEnabled) {
            return
        }
        if (canRunSpeechRecognitionNow()) {
            Log.d("SpeechRecognizer", "Iniciando reconocimiento de voz sin audio focus")
            initializeSpeechRecognizer()
            startListening()
        } else {
            Log.d("SpeechRecognizer", "No se inicia: condiciones no válidas (pantalla/audio/grabación/permisos)")
        }
    }

    private fun scheduleSpeechRecognizerRestart(delayMillis: Long, action: () -> Unit) {
        cancelPendingSpeechRecognizerRestart()
        val restartRunnable = Runnable {
            pendingSpeechRestart = null
            action()
        }
        pendingSpeechRestart = restartRunnable
        mainHandler.postDelayed(restartRunnable, delayMillis)
    }

    private fun cancelPendingSpeechRecognizerRestart() {
        pendingSpeechRestart?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
        pendingSpeechRestart = null
    }

    private fun releaseSpeechRecognizer(cancelFirst: Boolean) {
        cancelPendingSpeechRecognizerRestart()
        stopListening()
        if (cancelFirst) {
            speechRecognizer?.cancel()
        }
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
        suppressRecognizerTones(false)
    }

    // Monitoreo inteligente de audio para evitar conflictos
    private fun startAudioMonitoring() {
        if (!keywordListenerEnabled) {
            cancelPendingSpeechRecognizerRestart()
            releaseSpeechRecognizer(cancelFirst = true)
            isPausedForAudio = true
            return
        }
        if (audioCheckHandler != null) return
        audioCheckHandler = Handler(Looper.getMainLooper())
        audioCheckRunnable = object : Runnable {
            override fun run() {
                checkAudioState()
                audioCheckHandler?.postDelayed(this, 1000) // Verificar cada segundo
            }
        }
        audioCheckRunnable?.let { runnable ->
            audioCheckHandler?.post(runnable)
        }
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
        cancelPendingSpeechRecognizerRestart()
        // Liberar el recognizer por completo evita que el micrófono quede tomado.
        releaseSpeechRecognizer(cancelFirst = true)
        isPausedForAudio = true
        Log.d("AudioMonitor", "Reconocimiento de voz pausado por audio activo")
    }

    private fun resumeSpeechRecognitionAfterAudio() {
        isPausedForAudio = false
        // Esperar un poco antes de reanudar para asegurar que el audio haya terminado completamente
        mainHandler.postDelayed({
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
        val now = System.currentTimeMillis()
        if (now - lastSpeechStartAtMs < minSpeechStartIntervalMs) {
            val waitMs = minSpeechStartIntervalMs - (now - lastSpeechStartAtMs)
            scheduleSpeechRecognizerRestart(waitMs) { tryStartSpeechRecognizer() }
            Log.d("SpeechRecognizer", "Inicio diferido para evitar reinicio agresivo: ${waitMs}ms")
            return
        }
        if (!isListening && canRunSpeechRecognitionNow()) {
            logRecognizerState("before_startListening")
            suppressRecognizerTones(true)
            speechRecognizer?.startListening(recognizerIntent)
            lastSpeechStartAtMs = now
            isListening = true
            Log.d("SpeechRecognizer", "Escucha de voz activada")
            logRecognizerState("after_startListening")
        } else {
            Log.d("SpeechRecognizer", "No se puede iniciar escucha por condiciones no válidas")
        }
    }

    private fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            suppressRecognizerTones(false)
            Log.d("SpeechRecognizer", "Escucha de voz detenida")
        }
    }

    private fun restartListening() {
        Log.d("SpeechRecognizer", "Reiniciando escucha de voz")
        stopListening()
        if (!canRunSpeechRecognitionNow()) {
            Log.d("SpeechRecognizer", "Reinicio omitido: condiciones no válidas")
            return
        }
        mainHandler.postDelayed({
            tryStartSpeechRecognizer()
        }, 3000)
    }

    private fun triggerKeywordAlarm() {
        val now = System.currentTimeMillis()
        if (now - lastKeywordAlarmAtMs < keywordAlarmCooldownMs) {
            Log.d("SpeechRecognizer", "Keyword detectada en cooldown; se omite alarma local")
            return
        }
        lastKeywordAlarmAtMs = now
        // Para palabra clave no reproducimos sonido local para evitar pitidos.
        triggerAlarm(playLocalSound = false, activateTrackingRecording = false)
    }

    private fun suppressRecognizerTones(suppress: Boolean) {
        try {
            val direction = if (suppress) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            val streams = intArrayOf(
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_RING
            )
            streams.forEach { stream ->
                try {
                    audioManager?.adjustStreamVolume(stream, direction, 0)
                } catch (_: Exception) {
                }
            }
            logRecognizerState("suppressRecognizerTones=$suppress")
        } catch (e: Exception) {
            Log.w("SpeechRecognizer", "No se pudo ajustar tono del sistema: ${e.message}")
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun triggerAlarm(
        playLocalSound: Boolean = true,
        activateTrackingRecording: Boolean = true
    ) {
        Log.d("SpeechRecognizer", "Alarma activada por palabra clave detectada")
        if (playLocalSound) {
            playAlarmSound()
        }

        // 🔥 Obtener el ID del usuario y el dispositivo
        val deviceContext = getCurrentDeviceContext() ?: return
        val deviceDocRef = getDeviceDocRef(deviceContext)

        // 🔥 Obtener el nombre del dispositivo desde Firestore
        deviceDocRef.get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val deviceName = document.getString("deviceName") ?: "Desconocido"
                    if (activateTrackingRecording) {
                        activateAlarmFlags(deviceDocRef)
                    }
                    saveAlarmNotification(deviceContext.userId, deviceName)
                } else {
                    Log.e("BackgroundService", "Documento del dispositivo no encontrado.")
                }
            }
            .addOnFailureListener { e ->
                Log.e("BackgroundService", "Error obteniendo el nombre del dispositivo: ${e.message}")
            }
    }

    private fun activateAlarmFlags(deviceDocRef: DocumentReference) {
        updateTrackingAndRecordingFlags(deviceDocRef, includeTakePhoto = true, logTag = "BackgroundService")
    }

    private fun updateTrackingAndRecordingFlags(
        deviceDocRef: DocumentReference,
        includeTakePhoto: Boolean,
        logTag: String
    ) {
        val updates = hashMapOf<String, Any>(
            "trackingEnabled" to true,
            "recordingEnabled" to true
        )
        if (includeTakePhoto) {
            updates["takePhoto"] = true
        }

        deviceDocRef.update(updates)
            .addOnSuccessListener {
                Log.d(logTag, "trackingEnabled y recordingEnabled actualizados correctamente.")
            }
            .addOnFailureListener { e ->
                Log.e(logTag, "Error actualizando tracking y grabación: ${e.message}")
            }
    }

    private fun saveAlarmNotification(userId: String, deviceName: String) {
        val nowMs = System.currentTimeMillis()
        val notificationData = hashMapOf(
            "senderName" to deviceName,
            "message" to getString(R.string.alert_message, deviceName),
            "timestamp" to FieldValue.serverTimestamp(),
            "timestampClient" to Timestamp(Date(nowMs)),
            "timestampMs" to nowMs
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
    }

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

    private fun resolvePackageId(packageName: String): String {
        val segments = packageName.split(".")
        return if (segments.size >= 3) segments[2] else packageName
    }

    private fun buildUsageData(usage: UsageStats): Map<String, Any> {
        return hashMapOf(
            "packageName" to usage.packageName,
            "totalTimeInForeground" to usage.totalTimeInForeground,
            "lastTimeUsed" to usage.lastTimeUsed,
            "firstTimeStamp" to usage.firstTimeStamp,
            "lastTimeStamp" to usage.lastTimeStamp,
            "timestampUpload" to Date()
        )
    }

    private fun isSystemPackage(pm: PackageManager, packageName: String): Boolean {
        val appInfo = pm.getApplicationInfo(packageName, 0)
        return (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun isTrackAppsPackageAllowed(pm: PackageManager, packageName: String): Boolean {
        if (!isSystemPackage(pm, packageName)) return true

        val allowlistedApps = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.facebook.katana"
        )
        return allowlistedApps.contains(packageName)
    }

    private fun verificarPermisoDeUso(context: Context, requestRemote: Boolean, deviceDocRef: DocumentReference) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )

        val hasPermission = mode == AppOpsManager.MODE_ALLOWED

        if (!hasPermission || requestRemote) {
            Log.d("Permissions", "🔐 Lanzando pantalla de permisos de uso...")
            try {
                ComplianceDisclosures.openUsageAccessDisclosure(context)

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
            val deviceContext = getCurrentDeviceContext() ?: return
            val deviceDocRef = getDeviceDocRef(deviceContext)
            updateTrackingAndRecordingFlags(
                deviceDocRef = deviceDocRef,
                includeTakePhoto = false,
                logTag = "ManualActivation"
            )
            // Llama a triggerAlarm() para activar la alarma
            triggerAlarm()
        }
    }
    override fun onDestroy() {
        Log.d("BackgroundService", "Service onDestroy called")
        BootDiagnostics.markServiceStopped(this, "onDestroy")
        serviceStatePrefs.edit {
            putBoolean("service_running", false)
            putLong("last_service_stopped", System.currentTimeMillis())
        }
        stopFirestoreHeartbeat()
        unregisterServiceReceiversSafely()
        userPreferencesListener?.remove()
        userPreferencesListener = null

        stopLocationUpdates() // 🚫 Detener actualizaciones de ubicación
        stopRecording() // 🎤 Detener grabación si está activa
        stopListening() // 🎙️ Detener reconocimiento de voz si está activo
        stopAudioMonitoring() // 🔊 Detener monitoreo de audio

        // 🔊 Restaurar sonido del sistema si se había silenciado
        audioManager?.adjustStreamVolume(
            AudioManager.STREAM_SYSTEM,
            AudioManager.ADJUST_UNMUTE,
            0
        )
        stopBackgroundThread()

        // 🎙️ Destruir `SpeechRecognizer` solo si estaba inicializado
        releaseSpeechRecognizer(cancelFirst = false)
        //usageHandler.removeCallbacks(usageRunnable)

        super.onDestroy()
        cameraExecutor.shutdown() // ✅ Cerrar el executor de la cámara
    }

    override fun onBind(intent: Intent?): IBinder? {
            return null
    }

    private fun unregisterServiceReceiversSafely() {
        if (isManualActivationReceiverRegistered) {
            safeUnregisterReceiver(manualActivationReceiver, "manualActivationReceiver")
            isManualActivationReceiverRegistered = false
        }
        if (isBatteryReceiverRegistered) {
            safeUnregisterReceiver(batteryStatusReceiver, "batteryStatusReceiver")
            isBatteryReceiverRegistered = false
        }
        if (isScreenReceiverRegistered) {
            safeUnregisterReceiver(screenStateReceiver, "screenStateReceiver")
            isScreenReceiverRegistered = false
        }
    }

    private fun safeUnregisterReceiver(receiver: BroadcastReceiver, receiverName: String) {
        try {
            unregisterReceiver(receiver)
        } catch (exception: IllegalArgumentException) {
            Log.w("BackgroundService", "$receiverName was not registered or already unregistered", exception)
        }
    }
}
