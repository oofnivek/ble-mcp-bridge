package dev.blemcp.bridge

import java.util.UUID

/** Accepts full UUIDs and short forms such as "180f". */
fun parseUuid(value: String): UUID {
    val trimmed = value.trim()
    val full = when (trimmed.length) {
        4 -> "0000$trimmed-0000-1000-8000-00805f9b34fb"
        8 -> "$trimmed-0000-1000-8000-00805f9b34fb"
        else -> trimmed
    }
    return try {
        UUID.fromString(full)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid UUID: $value")
    }
}

/** Accepts hex with optional spaces, colons or dashes between bytes. */
fun hexToBytes(value: String): ByteArray {
    val clean = value.filterNot { it == ' ' || it == ':' || it == '-' }
    require(clean.length % 2 == 0) { "Hex value must have an even number of digits" }
    return ByteArray(clean.length / 2) { index ->
        val byte = clean.substring(index * 2, index * 2 + 2).toIntOrNull(16)
        requireNotNull(byte) { "Invalid hex value: $value" }.toByte()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Explains a Bluetooth disconnect status. A null status means the bridge closed the connection. */
fun disconnectReason(status: Int?): String = when (status) {
    null -> "The bridge closed the connection"
    8 -> "The connection timed out"
    19 -> "The device ended the connection"
    22 -> "The phone ended the connection"
    62 -> "The connection could not be established"
    133 -> "Android reported a general Bluetooth error"
    else -> "Disconnected for an unrecognized reason"
}

/** Android reports 127 when a scan result has no signal strength. */
private const val RSSI_UNAVAILABLE = 127

fun knownRssi(rssi: Int): Int? = rssi.takeUnless { it == RSSI_UNAVAILABLE }

/** Sorts the strongest signal first, with unknown signal strengths last. */
fun <T> Collection<T>.sortedBySignal(rssi: (T) -> Int): List<T> =
    sortedByDescending { knownRssi(rssi(it)) ?: Int.MIN_VALUE }

/** Returns the bytes as text only when they are entirely printable UTF-8. */
fun printableText(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val text = String(bytes, Charsets.UTF_8)
    val printable = text.none { it == '�' || (it.isISOControl() && it != '\n' && it != '\t') }
    return if (printable) text else null
}
