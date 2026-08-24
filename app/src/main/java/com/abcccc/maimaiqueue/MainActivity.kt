package com.abcccc.maimaiqueue

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abcccc.maimaiqueue.ui.theme.MaimaiQueueTheme
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val lastUserInteractionElapsedMillis = AtomicLong(SystemClock.elapsedRealtime())

    var foregroundRefreshGeneration by mutableLongStateOf(0L)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = if (BuildConfig.MANAGEMENT_APP) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Compose form screens use IME insets to keep their active content and
        // fixed action areas above the keyboard. ADJUST_RESIZE is also required
        // for those insets to behave consistently on the Android 10 terminals
        // still supported by the app.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            MaimaiQueueTheme(darkTheme = false, dynamicColor = false) {
                if (BuildConfig.MANAGEMENT_APP) {
                    ManagementApp()
                } else {
                    RegistrationApp()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        foregroundRefreshGeneration++
        recordUserInteraction()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        recordUserInteraction()
    }

    fun recordUserInteraction() {
        lastUserInteractionElapsedMillis.set(SystemClock.elapsedRealtime())
    }

    fun elapsedSinceUserInteraction(): Long =
        (SystemClock.elapsedRealtime() - lastUserInteractionElapsedMillis.get()).coerceAtLeast(0L)

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
