package dev.blemcp.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n", "BatteryLife")
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var startStop: Button
    private lateinit var allowWrites: Switch
    private lateinit var battery: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 18f }
        connection = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(0, PADDING, 0, PADDING)
        }
        startStop = Button(this).apply { setOnClickListener { toggleBridge() } }
        allowWrites = Switch(this).apply {
            text = "Allow writes (can change device state)"
            setOnCheckedChangeListener { _, checked -> BridgeState.writesEnabled.set(checked) }
        }
        battery = Button(this).apply {
            text = "Disable battery optimization"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            }
        }
        val refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { render() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING * 2, PADDING, PADDING)
            listOf(status, startStop, allowWrites, connection, refresh, battery).forEach(::addView)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun toggleBridge() {
        if (BridgeState.running) {
            stopService(Intent(this, BridgeService::class.java))
            BridgeState.running = false
            render()
            return
        }

        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBridge()
        } else {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return

        val bluetoothGranted = requiredBluetoothPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (bluetoothGranted) {
            startBridge()
        } else {
            BridgeState.lastError = "Bluetooth permissions are required"
            render()
        }
    }

    private fun startBridge() {
        startForegroundService(Intent(this, BridgeService::class.java))
        // The service starts asynchronously, so give it a moment before refreshing the screen.
        status.postDelayed({ render() }, RENDER_DELAY_MS)
    }

    private fun render() {
        val running = BridgeState.running
        val error = BridgeState.lastError
        status.text = when {
            error != null -> "Error: $error"
            running -> "Bridge is running"
            else -> "Bridge is stopped"
        }
        startStop.text = if (running) "Stop bridge" else "Start bridge"

        allowWrites.isEnabled = running
        allowWrites.isChecked = BridgeState.writesEnabled.get()

        val ignoringOptimization = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        battery.isEnabled = !ignoringOptimization
        battery.text = if (ignoringOptimization) "Battery optimization is off" else "Disable battery optimization"

        connection.text = connectionText(running)
    }

    private fun connectionText(running: Boolean): String {
        if (!running) return "Start the bridge to see its address."

        val addresses = localAddresses()
        if (addresses.isEmpty()) return "No network found. Turn on the hotspot, then tap Refresh."

        val token = BridgeToken.get(this)
        return buildString {
            appendLine("Token: $token")
            addresses.forEach { (name, ip) ->
                appendLine()
                appendLine("Interface $name:")
                appendLine(
                    "claude mcp add --transport http ble-bridge http://$ip:$MCP_PORT/mcp " +
                        "--header \"Authorization: Bearer $token\"",
                )
            }
        }
    }

    private fun localAddresses(): List<Pair<String, String>> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { network.name to it.hostAddress.orEmpty() }
            }

    private fun requiredBluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredBluetoothPermissions() + Manifest.permission.POST_NOTIFICATIONS
        } else {
            requiredBluetoothPermissions()
        }

    private companion object {
        const val PERMISSION_REQUEST = 1
        const val PADDING = 32
        const val RENDER_DELAY_MS = 500L
    }
}
