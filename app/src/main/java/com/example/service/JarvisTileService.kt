package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.JarvisApp
import com.example.MainActivity
import com.example.SessionOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** "JARVIS Voice" tile: tap to start or end a conversation; the tile mirrors the real session. */
@RequiresApi(Build.VERSION_CODES.N)
class JarvisTileService : TileService() {

    private val app get() = application as JarvisApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch { app.liveSession.sessionState.collect { refresh() } }
    }

    override fun onStopListening() {
        watch?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        super.onClick()
        if (app.liveSession.isWanted) {
            app.stopAssistantSession()
            refresh()
            return
        }
        // Starts the mic host service and the session; false when Android or a missing
        // permission refuses a background start, in which case the HUD opens instead.
        if (app.startAssistantSession(SessionOrigin.TILE)) { refresh(); return }
        openHud()
    }

    private fun openHud() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_START_LISTENING, true)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
        }.onFailure { Log.w("JarvisTile", "Could not open HUD", it) }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = app.liveSession.isWanted
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (on) "JARVIS on" else "JARVIS Voice"
        tile.updateTile()
    }
}
