package dev.blemcp.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.PowerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BleException(message: String) : Exception(message)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val scanMutex = Mutex()
    private val recentDisconnects = ArrayDeque<JSONObject>()

    private val adapter: BluetoothAdapter
        get() {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw BleException("This device has no Bluetooth adapter")
            if (!adapter.isEnabled) throw BleException("Bluetooth is turned off on the phone")
            return adapter
        }

    fun status(): JSONObject {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return JSONObject()
            .put("bluetooth_enabled", adapter?.isEnabled == true)
            .put("connected_devices", JSONArray(connections.keys.toList()))
            .put("recent_disconnects", synchronized(recentDisconnects) { JSONArray(recentDisconnects.toList()) })
    }

    private fun recordDisconnect(address: String, status: Int?, connectedAtMs: Long?) {
        val now = System.currentTimeMillis()
        val event = JSONObject()
            .put("address", address)
            .put("time_ms", now)
            .put("status", status ?: JSONObject.NULL)
            .put("reason", disconnectReason(status))
            .put("connected_ms", connectedAtMs?.let { now - it } ?: JSONObject.NULL)
        synchronized(recentDisconnects) {
            if (recentDisconnects.size >= MAX_DISCONNECT_EVENTS) recentDisconnects.removeFirst()
            recentDisconnects.addLast(event)
        }
    }

    private fun isScreenOn(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive

    suspend fun scan(
        durationMs: Long,
        nameContains: String?,
        address: String? = null,
        serviceUuid: String? = null,
    ): JSONObject = scanMutex.withLock {
        val scanner = adapter.bluetoothLeScanner ?: throw BleException("BLE scanner is unavailable")
        val filters = scanFilters(address, serviceUuid)

        // Android holds unfiltered scans while the screen is off and reports no error.
        if (filters == null && !isScreenOn()) {
            throw BleException(
                "The phone's screen is off, and Android blocks unfiltered scans while it is off. " +
                    "Turn the screen on, or scan with address or service_uuid.",
            )
        }

        val found = ConcurrentHashMap<String, ScanResult>()
        val failure = AtomicInteger(0)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = result
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { found[it.device.address] = it }
            }

            override fun onScanFailed(errorCode: Int) {
                failure.set(errorCode)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)
        try {
            delay(durationMs)
        } finally {
            scanner.stopScan(callback)
        }
        val screenTurnedOff = filters == null && !isScreenOn()

        if (failure.get() != 0) throw BleException("Scan failed with error ${failure.get()}")

        val devices = JSONArray()
        found.values
            .sortedBySignal { it.rssi }
            .forEach { result ->
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (nameContains != null && name?.contains(nameContains, ignoreCase = true) != true) {
                    return@forEach
                }
                val uuids = JSONArray()
                result.scanRecord?.serviceUuids?.forEach { uuids.put(it.uuid.toString()) }
                devices.put(
                    JSONObject()
                        .put("address", result.device.address)
                        .put("name", name ?: JSONObject.NULL)
                        .put("rssi", knownRssi(result.rssi) ?: JSONObject.NULL)
                        .put("connectable", result.isConnectable)
                        .put("service_uuids", uuids)
                        .put("manufacturer_data", manufacturerData(result.scanRecord))
                        .put("service_data", serviceData(result.scanRecord)),
                )
            }
        JSONObject().put("devices", devices).apply {
            if (screenTurnedOff) {
                put("warning", "The screen turned off during the scan, so these results may be incomplete.")
            }
        }
    }

    private fun scanFilters(address: String?, serviceUuid: String?): List<ScanFilter>? {
        if (address == null && serviceUuid == null) return null

        val filter = ScanFilter.Builder()
        address?.uppercase()?.let {
            if (!BluetoothAdapter.checkBluetoothAddress(it)) {
                throw BleException("Invalid Bluetooth address: $address")
            }
            filter.setDeviceAddress(it)
        }
        serviceUuid?.let { filter.setServiceUuid(ParcelUuid(parseUuid(it))) }
        return listOf(filter.build())
    }

    suspend fun connect(rawAddress: String): JSONObject {
        val address = rawAddress.uppercase()
        val bluetooth = adapter
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw BleException("Invalid Bluetooth address: $rawAddress")
        }

        val connection = connections[address] ?: Connection(address).also { created ->
            connections[address] = created
            created.gatt = bluetooth.getRemoteDevice(address)
                .connectGatt(context, false, created, BluetoothDevice.TRANSPORT_LE)
                ?: run {
                    connections.remove(address)
                    throw BleException("Could not start a connection to $address")
                }
        }

        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connection.ready.await() }
            ?: run {
                connection.close()
                throw BleException("Timed out connecting to $address")
            }
        return listServices(address)
    }

    /** Connects and reads in one call, for devices that end connections after a few seconds. */
    suspend fun readAll(rawAddress: String): JSONObject {
        val startedAt = System.currentTimeMillis()
        val address = rawAddress.uppercase()
        connect(address)

        val connection = connection(address)
        val gatt = connection.requireGatt()
        val values = JSONArray()
        var connectionLost = false

        gatt.services.toList().forEach { service ->
            service.characteristics
                .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                .forEach { characteristic ->
                    connectionLost = connectionLost || connections[address] !== connection
                    val entry = JSONObject()
                        .put("service_uuid", service.uuid.toString())
                        .put("characteristic_uuid", characteristic.uuid.toString())
                    if (connectionLost) {
                        entry.put("error", "Not read because the connection was lost")
                    } else {
                        try {
                            val value = connection.runOperation { gatt.readCharacteristic(characteristic) }
                            entry.put("hex", value.toHex()).put("text", printableText(value) ?: JSONObject.NULL)
                        } catch (e: BleException) {
                            entry.put("error", e.message)
                        }
                    }
                    values.put(entry)
                }
        }

        return JSONObject()
            .put("address", address)
            .put("values", values)
            .put("connection_lost", connectionLost || connections[address] !== connection)
            .put("elapsed_ms", System.currentTimeMillis() - startedAt)
    }

    fun listServices(address: String): JSONObject {
        val services = JSONArray()
        connection(address).requireGatt().services.forEach { service ->
            val characteristics = JSONArray()
            service.characteristics.forEach { characteristic ->
                characteristics.put(
                    JSONObject()
                        .put("uuid", characteristic.uuid.toString())
                        .put("properties", JSONArray(propertyNames(characteristic.properties))),
                )
            }
            services.put(
                JSONObject()
                    .put("uuid", service.uuid.toString())
                    .put("characteristics", characteristics),
            )
        }
        return JSONObject().put("address", address.uppercase()).put("services", services)
    }

    suspend fun read(address: String, serviceUuid: String, characteristicUuid: String): JSONObject {
        val connection = connection(address)
        val gatt = connection.requireGatt()
        val characteristic = connection.characteristic(
            serviceUuid,
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
        )
        val value = connection.runOperation { gatt.readCharacteristic(characteristic) }
        return JSONObject()
            .put("hex", value.toHex())
            .put("text", printableText(value) ?: JSONObject.NULL)
    }

    @Suppress("DEPRECATION")
    suspend fun write(
        address: String,
        serviceUuid: String,
        characteristicUuid: String,
        hex: String,
        withResponse: Boolean,
    ): JSONObject {
        val connection = connection(address)
        val gatt = connection.requireGatt()
        val characteristic = connection.characteristic(
            serviceUuid,
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        )
        val bytes = hexToBytes(hex)
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        connection.runOperation {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, bytes, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }
        return JSONObject().put("written_bytes", bytes.size)
    }

    @Suppress("DEPRECATION")
    suspend fun subscribe(
        address: String,
        serviceUuid: String,
        characteristicUuid: String,
        enable: Boolean,
    ): JSONObject {
        val connection = connection(address)
        val gatt = connection.requireGatt()
        val characteristic = connection.characteristic(
            serviceUuid,
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
        )
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: throw BleException("This characteristic does not support notifications")
        val properties = characteristic.properties

        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> throw BleException("This characteristic does not support notifications")
        }

        if (!gatt.setCharacteristicNotification(characteristic, enable)) {
            throw BleException("Android refused to change notifications for this characteristic")
        }
        try {
            connection.runOperation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
                } else {
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor)
                }
            }
        } catch (e: Exception) {
            // The device did not accept the subscription, so stop listening locally too.
            if (enable) gatt.setCharacteristicNotification(characteristic, false)
            throw e
        }
        return JSONObject().put("subscribed", enable)
    }

    fun readNotifications(address: String): JSONObject = connection(address).drainNotifications()

    fun disconnect(address: String): JSONObject {
        connection(address).close()
        return JSONObject().put("disconnected", address.uppercase())
    }

    fun closeAll() {
        connections.values.toList().forEach { it.close() }
    }

    private fun connection(address: String): Connection =
        connections[address.uppercase()]
            ?: throw BleException("Not connected to $address. Call ble_connect first.")

    private inner class Connection(val address: String) : BluetoothGattCallback() {
        @Volatile
        var gatt: BluetoothGatt? = null

        @Volatile
        private var pending: CompletableDeferred<ByteArray>? = null

        @Volatile
        private var connectedAtMs: Long? = null

        val ready = CompletableDeferred<Unit>()
        private val operationMutex = Mutex()
        private val notifications = ArrayDeque<JSONObject>()
        private var droppedNotifications = 0

        fun requireGatt(): BluetoothGatt =
            gatt ?: throw BleException("Connection to $address is not ready")

        /** Some devices reuse a UUID, so this prefers the characteristic that supports the operation. */
        fun characteristic(
            serviceUuid: String,
            characteristicUuid: String,
            requiredProperties: Int,
        ): BluetoothGattCharacteristic {
            val service = requireGatt().getService(parseUuid(serviceUuid))
                ?: throw BleException("Service not found: $serviceUuid")
            val uuid = parseUuid(characteristicUuid)
            return preferSupporting(service.characteristics.filter { it.uuid == uuid }, requiredProperties) {
                it.properties
            } ?: throw BleException("Characteristic not found: $characteristicUuid")
        }

        /** Android allows one GATT operation at a time, so each one waits for the previous result. */
        suspend fun runOperation(start: () -> Boolean): ByteArray = operationMutex.withLock {
            val result = CompletableDeferred<ByteArray>()
            pending = result
            try {
                if (!start()) throw BleException("Android refused to start the Bluetooth operation")
                withTimeoutOrNull(OPERATION_TIMEOUT_MS) { result.await() }
                    ?: throw BleException("Timed out waiting for $address to respond")
            } finally {
                pending = null
            }
        }

        fun drainNotifications(): JSONObject = synchronized(notifications) {
            val values = JSONArray()
            notifications.forEach { values.put(it) }
            val dropped = droppedNotifications
            notifications.clear()
            droppedNotifications = 0
            JSONObject().put("notifications", values).put("dropped", dropped)
        }

        fun close(status: Int? = null) {
            if (connections.remove(address, this)) recordDisconnect(address, status, connectedAtMs)
            val closed = BleException("Connection to $address was closed")
            ready.completeExceptionally(closed)
            pending?.completeExceptionally(closed)
            gatt?.let {
                it.disconnect()
                it.close()
            }
            gatt = null
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectedAtMs = System.currentTimeMillis()
                if (!gatt.discoverServices()) {
                    ready.completeExceptionally(BleException("Could not start service discovery"))
                    close()
                }
            } else {
                val failure = BleException("${disconnectReason(status)} ($address, status $status)")
                ready.completeExceptionally(failure)
                pending?.completeExceptionally(failure)
                close(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready.complete(Unit)
            } else {
                ready.completeExceptionally(BleException("Service discovery failed (GATT status $status)"))
                close()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = finishOperation(status, value)

        @Deprecated("Used on Android 12 and earlier")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = finishOperation(status, characteristic.value ?: ByteArray(0))

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = finishOperation(status, ByteArray(0))

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) = finishOperation(status, ByteArray(0))

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = recordNotification(characteristic, value)

        @Deprecated("Used on Android 12 and earlier")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) = recordNotification(characteristic, characteristic.value ?: ByteArray(0))

        private fun finishOperation(status: Int, value: ByteArray) {
            val operation = pending ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                operation.complete(value)
            } else {
                operation.completeExceptionally(BleException("Bluetooth operation failed (GATT status $status)"))
            }
        }

        private fun recordNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            synchronized(notifications) {
                if (notifications.size >= MAX_NOTIFICATIONS) {
                    notifications.removeFirst()
                    droppedNotifications++
                }
                notifications.addLast(
                    JSONObject()
                        .put("time_ms", System.currentTimeMillis())
                        .put("characteristic", characteristic.uuid.toString())
                        .put("hex", value.toHex()),
                )
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val OPERATION_TIMEOUT_MS = 10_000L
        const val MAX_NOTIFICATIONS = 500
        const val MAX_DISCONNECT_EVENTS = 10
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun manufacturerData(record: ScanRecord?): JSONArray {
            val entries = JSONArray()
            val data = record?.manufacturerSpecificData ?: return entries
            for (index in 0 until data.size()) {
                entries.put(
                    JSONObject()
                        .put("company_id", "0x%04x".format(data.keyAt(index)))
                        .put("hex", data.valueAt(index).toHex()),
                )
            }
            return entries
        }

        fun serviceData(record: ScanRecord?): JSONArray {
            val entries = JSONArray()
            record?.serviceData?.forEach { (uuid, value) ->
                entries.put(JSONObject().put("uuid", uuid.uuid.toString()).put("hex", value.toHex()))
            }
            return entries
        }

        fun propertyNames(properties: Int): List<String> = listOf(
            BluetoothGattCharacteristic.PROPERTY_READ to "read",
            BluetoothGattCharacteristic.PROPERTY_WRITE to "write",
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to "write_no_response",
            BluetoothGattCharacteristic.PROPERTY_NOTIFY to "notify",
            BluetoothGattCharacteristic.PROPERTY_INDICATE to "indicate",
        ).filter { (flag, _) -> properties and flag != 0 }.map { (_, name) -> name }
    }
}
