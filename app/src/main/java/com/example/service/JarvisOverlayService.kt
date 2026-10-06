package com.example.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.JarvisApp
import com.example.MainActivity
import com.example.SessionOrigin
import com.example.core.gemini.LiveSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Floating JARVIS bubble. It shows the live state (colour + caption + last line of what was said),
 * a tap starts or ends a conversation, a long press opens the full HUD, and it can be dragged.
 * Started from Settings ("Floating bubble"), not implicitly.
 */
class JarvisOverlayService : Service() {

    private val app get() = application as JarvisApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var dot: GradientDrawable? = null
    private var caption: TextView? = null
    private var attached = false

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildBubble()
        scope.launch {
            combine(app.liveSession.sessionState, app.liveSession.activityLog) { s, log -> s to log.lastOrNull() }
                .collect { (state, last) -> render(state, last?.type, last?.content) }
        }
    }

    private fun buildBubble() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
        dot = circle
        val letter = TextView(this).apply {
            text = "J"; setTextColor(Color.BLACK); textSize = 20f; gravity = Gravity.CENTER
            background = circle
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f; gravity = Gravity.CENTER
            maxWidth = (150 * d).toInt(); maxLines = 2
            setBackgroundColor(0xAA000000.toInt()); setPadding(8, 2, 8, 2)
            visibility = View.GONE
        }
        caption = label
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(letter); addView(label)
        }
        root = box

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 60; y = 300 }

        box.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
            var moved = false; var longPressed = false
            val longPress = Runnable { longPressed = true; openHud() }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                        moved = false; longPressed = false
                        main.postDelayed(longPress, 600L)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) { moved = true; main.removeCallbacks(longPress) }
                        if (moved) {
                            params.x = startX + dx; params.y = startY + dy
                            runCatching { windowManager?.updateViewLayout(box, params) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longPress)
                        if (!moved && !longPressed && e.action == MotionEvent.ACTION_UP) toggleConversation()
                    }
                }
                return true
            }
        })

        runCatching { windowManager?.addView(box, params); attached = true }
            .onFailure { Log.e(TAG, "Could not add overlay", it); stopSelf() }
    }

    private fun toggleConversation() {
        if (app.liveSession.isWanted) {
            app.stopAssistantSession()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            app.startAssistantSession(SessionOrigin.OVERLAY)
        } else {
            openHud() // needs the in-app permission prompt
        }
    }

    private fun openHud() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { Log.w(TAG, "Could not open HUD", it) }
    }

    private fun render(state: LiveSessionState, lastType: String?, lastText: String?) {
        dot?.setColor(
            when (state) {
                LiveSessionState.DISCONNECTED -> COLOR_IDLE
                LiveSessionState.CONNECTING, LiveSessionState.CONNECTED -> 0xFFFFC107.toInt()
                LiveSessionState.LISTENING -> 0xFF00E5FF.toInt()
                LiveSessionState.THINKING -> 0xFFB388FF.toInt()
                LiveSessionState.SPEAKING -> 0xFF69F0AE.toInt()
                LiveSessionState.ERROR -> 0xFFFF5252.toInt()
            }
        )
        val label = caption ?: return
        if (state == LiveSessionState.DISCONNECTED) {
            label.visibility = View.GONE
        } else {
            val line = if (lastType == "assistant" || lastType == "user") lastText.orEmpty().take(70) else state.name.lowercase()
            label.text = line
            label.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        root?.let { if (attached) runCatching { windowManager?.removeView(it) } }
        attached = false; root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JarvisOverlayService"
        private const val COLOR_IDLE = 0xFF607D8B.toInt()

        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) context.startService(Intent(context, JarvisOverlayService::class.java))
        }
        fun stop(context: Context) { context.stopService(Intent(context, JarvisOverlayService::class.java)) }
    }
}
