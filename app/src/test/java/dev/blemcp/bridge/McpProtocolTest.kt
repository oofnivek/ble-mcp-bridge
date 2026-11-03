package dev.blemcp.bridge

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    private val echo = McpTool("echo", "Echo", JSONObject().put("type", "object"), readOnly = true) { args -> args }
    private val failing = McpTool("fail", "Fail", JSONObject().put("type", "object"), readOnly = false) {
        throw IllegalStateException("boom")
    }
    private val protocol = McpProtocol(listOf(echo, failing))

    private suspend fun call(method: String, params: String = "{}"): JSONObject {
        val response = protocol.handle("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
        assertEquals(200, response.status)
        return JSONObject(response.body!!).also { assertEquals(7, it.getInt("id")) }
    }

    @Test
    fun `initialize echoes a supported protocol version`() = runTest {
        val result = call("initialize", """{"protocolVersion":"2025-06-18"}""").getJSONObject("result")
        assertEquals("2025-06-18", result.getString("protocolVersion"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
    }

    @Test
    fun `initialize falls back to the latest version for an unknown one`() = runTest {
        val result = call("initialize", """{"protocolVersion":"1999-01-01"}""").getJSONObject("result")
        assertEquals("2025-11-25", result.getString("protocolVersion"))
    }

    @Test
    fun `notifications get an accepted response with no body`() = runTest {
        val response = protocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.status)
        assertNull(response.body)
    }

    @Test
    fun `malformed json is a parse error`() = runTest {
        val response = protocol.handle("not json")
        assertEquals(400, response.status)
        assertEquals(-32700, JSONObject(response.body!!).getJSONObject("error").getInt("code"))
    }

    @Test
    fun `tools list includes safety annotations`() = runTest {
        val tools = call("tools/list").getJSONObject("result").getJSONArray("tools")
        assertEquals(2, tools.length())
        val annotations = tools.getJSONObject(1).getJSONObject("annotations")
        assertFalse(annotations.getBoolean("readOnlyHint"))
        assertTrue(annotations.getBoolean("destructiveHint"))
    }

    @Test
    fun `tool call returns the handler result as text`() = runTest {
        val result = call("tools/call", """{"name":"echo","arguments":{"a":1}}""").getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        val text = result.getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals(1, JSONObject(text).getInt("a"))
    }

    @Test
    fun `tool failure is reported as a tool error`() = runTest {
        val result = call("tools/call", """{"name":"fail"}""").getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        assertEquals("boom", result.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `unknown tool and method are protocol errors`() = runTest {
        assertEquals(-32602, call("tools/call", """{"name":"nope"}""").getJSONObject("error").getInt("code"))
        assertEquals(-32601, call("nope").getJSONObject("error").getInt("code"))
    }

    @Test
    fun `hex and uuid helpers handle common forms`() {
        assertEquals("01ff", hexToBytes("01:FF").toHex())
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", parseUuid("180f").toString())
        assertNull(printableText(byteArrayOf(0, 1)))
        assertEquals("ok", printableText("ok".toByteArray()))
    }

    @Test
    fun `unavailable signal strength is unknown and sorts last`() {
        assertNull(knownRssi(127))
        assertEquals(-31, knownRssi(-31))
        assertEquals(listOf(-31, -55, -90, 127), listOf(127, -55, -90, -31).sortedBySignal { it })
    }

    @Test
    fun `characteristics sharing a uuid are chosen by supported operation`() {
        val notify = "notify" to 0x10
        val write = "write" to 0x08
        val candidates = listOf(notify, write)

        assertEquals(write, preferSupporting(candidates, 0x08 or 0x04) { it.second })
        assertEquals(notify, preferSupporting(candidates, 0x10 or 0x20) { it.second })
        assertEquals(notify, preferSupporting(candidates, 0x02) { it.second })
        assertNull(preferSupporting(emptyList<Pair<String, Int>>(), 0x02) { it.second })
    }

    @Test
    fun `disconnect reasons explain common statuses`() {
        assertEquals("The device ended the connection", disconnectReason(19))
        assertEquals("The bridge closed the connection", disconnectReason(null))
        assertEquals("Disconnected for an unrecognized reason", disconnectReason(999))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd length hex is rejected`() {
        hexToBytes("abc")
    }
}
