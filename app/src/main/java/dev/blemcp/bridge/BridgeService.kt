package dev.blemcp.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.ktor.server.engine.ApplicationEngine
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

object BridgeState {
    val writesEnabled = AtomicBoolean(false)

    @Volatile
    var running = false

    @Volatile
    var lastError: String? = null
}

object BridgeToken {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    /** Stored on the phone, and grouped so it is practical to type on the Mac. */
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }

        val random = SecureRandom()
        val token = (1..4).joinToString("-") {
            (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }
        prefs.edit().putString("token", token).apply()
        return token
    }
}

class BridgeService : Service() {
    private var server: ApplicationEngine? = null
    private var ble: BleManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
            if (server == null) startBridge()
            BridgeState.lastError = null
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the bridge", e)
            BridgeState.lastError = e.message ?: e.javaClass.simpleName
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startBridge() {
        val manager = BleManager(applicationContext)
        BridgeState.writesEnabled.set(false)
        val protocol = McpProtocol(bleTools(manager, BridgeState.writesEnabled))
        server = startMcpServer(protocol, BridgeToken.get(this))
        ble = manager
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blemcp:bridge")
            .apply { acquire() }
        BridgeState.running = true
    }

    private fun startInForeground() {
        val channel = NotificationChannel(CHANNEL_ID, "Bridge status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE MCP Bridge is running")
            .setContentText("Listening on port $MCP_PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        server?.stop(500, 1000)
        server = null
        ble?.closeAll()
        ble = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        BridgeState.running = false
        BridgeState.writesEnabled.set(false)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BridgeService"
        const val CHANNEL_ID = "bridge"
        const val NOTIFICATION_ID = 1
    }
}
