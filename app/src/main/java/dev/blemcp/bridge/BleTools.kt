package dev.blemcp.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private class Param(val name: String, val type: String, val description: String, val required: Boolean = true)

private val ADDRESS = Param("address", "string", "Device address from ble_scan, such as AA:BB:CC:DD:EE:FF")
private val SERVICE = Param("service_uuid", "string", "Service UUID, full or short such as 180f")
private val CHARACTERISTIC = Param("characteristic_uuid", "string", "Characteristic UUID, full or short such as 2a19")

private fun schema(vararg params: Param): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    params.forEach { param ->
        properties.put(param.name, JSONObject().put("type", param.type).put("description", param.description))
        if (param.required) required.put(param.name)
    }
    return JSONObject().put("type", "object").put("properties", properties).put("required", required)
}

private fun JSONObject.requireString(name: String): String =
    optString(name).ifEmpty { throw IllegalArgumentException("Missing required argument: $name") }

/** Writes stay off until they are enabled on the phone, and the setting resets when the service restarts. */
fun bleTools(ble: BleManager, writesEnabled: AtomicBoolean): List<McpTool> = listOf(
    McpTool(
        name = "ble_status",
        description = "Show whether Bluetooth is on, which devices are connected, whether writes are enabled, " +
            "and the reasons for the last 10 disconnects.",
        inputSchema = schema(),
        readOnly = true,
    ) { ble.status().put("writes_enabled", writesEnabled.get()) },

    McpTool(
        name = "ble_scan",
        description = "Scan for nearby BLE devices, strongest signal first, including their broadcast " +
            "manufacturer_data and service_data as hex. rssi is null when Android does not " +
            "report it. Fails if the screen is off, unless address or service_uuid is set. " +
            "Android may return nothing after about 5 scans in 30 seconds.",
        inputSchema = schema(
            Param("duration_seconds", "number", "Scan length from 1 to 30 seconds. Defaults to 5.", required = false),
            Param("name_contains", "string", "Only return devices whose name contains this text.", required = false),
            Param("address", "string", "Only find this device address. Works with the screen off.", required = false),
            Param("service_uuid", "string", "Only find devices advertising this service. Works with the screen off.", required = false),
        ),
        readOnly = true,
    ) { args ->
        val seconds = args.optDouble("duration_seconds", 5.0).let { if (it.isNaN()) 5.0 else it }.coerceIn(1.0, 30.0)
        ble.scan(
            (seconds * 1000).toLong(),
            args.optString("name_contains").ifEmpty { null },
            args.optString("address").ifEmpty { null },
            args.optString("service_uuid").ifEmpty { null },
        )
    },

    McpTool(
        name = "ble_connect",
        description = "Connect to a device and return its services and characteristics.",
        inputSchema = schema(ADDRESS),
        readOnly = true,
    ) { args -> ble.connect(args.requireString("address")) },

    McpTool(
        name = "ble_read_all",
        description = "Connect if needed and read every readable characteristic in one call. Use this for devices " +
            "that end connections quickly. A protected value may make the phone show a pairing prompt.",
        inputSchema = schema(ADDRESS),
        readOnly = true,
    ) { args -> ble.readAll(args.requireString("address")) },

    McpTool(
        name = "ble_list_services",
        description = "List the services and characteristics of a connected device.",
        inputSchema = schema(ADDRESS),
        readOnly = true,
    ) { args -> ble.listServices(args.requireString("address")) },

    McpTool(
        name = "ble_read",
        description = "Read a characteristic. Returns hex, plus text when the value is printable.",
        inputSchema = schema(ADDRESS, SERVICE, CHARACTERISTIC),
        readOnly = true,
    ) { args ->
        ble.read(
            args.requireString("address"),
            args.requireString("service_uuid"),
            args.requireString("characteristic_uuid"),
        )
    },

    McpTool(
        name = "ble_write",
        description = "Write to a characteristic, which can change a device's state. " +
            "Fails unless the user has turned on 'Allow writes' in the phone app.",
        inputSchema = schema(
            ADDRESS,
            SERVICE,
            CHARACTERISTIC,
            Param("hex", "string", "Bytes to write, as hex"),
            Param("with_response", "boolean", "Wait for the device to acknowledge. Defaults to true.", required = false),
        ),
        readOnly = false,
    ) { args ->
        if (!writesEnabled.get()) {
            throw IllegalStateException("Writes are off. The user must turn on 'Allow writes' in the phone app.")
        }
        ble.write(
            args.requireString("address"),
            args.requireString("service_uuid"),
            args.requireString("characteristic_uuid"),
            args.requireString("hex"),
            args.optBoolean("with_response", true),
        )
    },

    McpTool(
        name = "ble_subscribe",
        description = "Turn notifications on or off for a characteristic. Use ble_read_notifications to collect them.",
        inputSchema = schema(
            ADDRESS,
            SERVICE,
            CHARACTERISTIC,
            Param("enable", "boolean", "True to subscribe, false to unsubscribe. Defaults to true.", required = false),
        ),
        readOnly = true,
    ) { args ->
        ble.subscribe(
            args.requireString("address"),
            args.requireString("service_uuid"),
            args.requireString("characteristic_uuid"),
            args.optBoolean("enable", true),
        )
    },

    McpTool(
        name = "ble_read_notifications",
        description = "Return and clear buffered notifications for a connected device. Keeps the latest 500.",
        inputSchema = schema(ADDRESS),
        readOnly = true,
    ) { args -> ble.readNotifications(args.requireString("address")) },

    McpTool(
        name = "ble_disconnect",
        description = "Disconnect from a device.",
        inputSchema = schema(ADDRESS),
        readOnly = true,
    ) { args -> ble.disconnect(args.requireString("address")) },
)
