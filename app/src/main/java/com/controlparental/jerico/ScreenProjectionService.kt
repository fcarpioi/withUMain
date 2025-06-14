package com.controlparental.jerico

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        initializeProjection()
        listenForTakePictureSignal()

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "screen_projection_service"
        val channelName = "Screen Projection Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle("Control Parental")
            .setContentText("Captura de pantalla activa")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun initializeProjection() {
        val prefs = getSharedPreferences("ScreenCapturePrefs", Context.MODE_PRIVATE)
        val resultCode = prefs.getInt("resultCode", -1)
        val projectionDataUri = prefs.getString("projectionIntentData", null)

        if (resultCode == -1 || projectionDataUri == null) {
            Log.e("ScreenProjection", "❌ No se encontraron permisos guardados")
            stopSelf()
            return
        }

        val projectionIntent = Intent.parseUri(projectionDataUri, 0)
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionIntent)
        Log.d("ScreenProjection", "✅ MediaProjection inicializado correctamente")
    }

    private fun listenForTakePictureSignal() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val deviceId = userId

        val docRef = FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)

        docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("ScreenProjection", "❌ Error escuchando Firestore", error)
                return@addSnapshotListener
            }

            val takePicture = snapshot?.getBoolean("takePicture") ?: false
            if (takePicture) {
                Log.d("ScreenProjection", "📸 Señal para capturar pantalla recibida")
                captureScreen(userId, deviceId) {
                    docRef.update("takePicture", false)
                }
            }
        }
    }

    private fun captureScreen(userId: String, deviceId: String, onFinish: () -> Unit) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenProjectionDisplay",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            uploadToFirebase(bitmap, userId, deviceId)
            onFinish()
        }, android.os.Handler(mainLooper))
    }

    private fun uploadToFirebase(bitmap: Bitmap, userId: String, deviceId: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val path = "screenshots/$userId/$timestamp.jpg"

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val data = baos.toByteArray()

        val storageRef = FirebaseStorage.getInstance().reference.child(path)
        val uploadTask = storageRef.putBytes(data)

        uploadTask.addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { uri ->
                saveMetadata(userId, deviceId, uri)
            }
        }.addOnFailureListener {
            Log.e("ScreenProjection", "❌ Fallo al subir la captura a Storage")
        }
    }

    private fun saveMetadata(userId: String, deviceId: String, uri: Uri) {
        val firestore = FirebaseFirestore.getInstance()
        val metadata = mapOf(
            "url" to uri.toString(),
            "timestamp" to System.currentTimeMillis()
        )

        firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .collection("screenshots")
            .add(metadata)
            .addOnSuccessListener {
                Log.d("ScreenProjection", "✅ Metadata guardada en Firestore")
            }.addOnFailureListener {
                Log.e("ScreenProjection", "❌ Error al guardar metadata")
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }
}