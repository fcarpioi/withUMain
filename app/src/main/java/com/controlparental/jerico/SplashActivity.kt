package com.controlparental.jerico

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.animation.ObjectAnimator
import android.app.Activity
import android.view.animation.LinearInterpolator
import android.widget.ImageView

@SuppressLint("CustomSplashScreen")
class SplashActivity : Activity() {
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }
}
