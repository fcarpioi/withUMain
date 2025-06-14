package com.controlparental.jerico

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import android.widget.ImageView

class SplashActivity : AppCompatActivity() {
    private val mainScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val logoImageView = findViewById<ImageView>(R.id.logo)
        val swing = ObjectAnimator.ofFloat(logoImageView, "rotation", -5f, 5f)
        swing.duration = 1500
        swing.repeatCount = ObjectAnimator.INFINITE
        swing.repeatMode = ObjectAnimator.REVERSE
        swing.interpolator = LinearInterpolator()
        swing.start()
        // Esconder la Action Bar
        supportActionBar?.hide()

        // Hacer que la actividad esté en modo inmersivo
        hideSystemUI()

        // Aquí puedes personalizar la animación si lo necesitas
        //val lottieAnimationView = findViewById<LottieAnimationView>(R.id.splash_animation)

        // Iniciar MainActivity después de un retraso
        mainScope.launch {
            delay(5000) // Espera 5 segundos
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish() // Cierra la actividad Splash
        }
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
