package dev.blemcp.bridge

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnly: Boolean,
    val handler: suspend (JSONObject) -> JSONObject,
)

class McpResponse(val status: Int, val body: String?)

/** Stateless MCP JSON-RPC handling, with no Android or HTTP dependencies. */
class McpProtocol(tools: List<McpTool>) {
    private val tools = tools.associateBy { it.name }

    suspend fun handle(body: String): McpResponse {
        val message = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return McpResponse(400, error(JSONObject.NULL, -32700, "Parse error"))
        }

        // Notifications and client responses have no id, so they get no JSON-RPC reply.
        if (!message.has("id")) return McpResponse(202, null)

        val id = message.get("id")
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()

        val reply = when (method) {
            "" -> error(id, -32600, "Invalid request")
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, listTools())
            "tools/call" -> callTool(id, params)
            else -> error(id, -32601, "Method not found: $method")
        }
        return McpResponse(200, reply)
    }

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        val version = if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", "ble-mcp-bridge").put("version", "0.1.0"))
    }

    private fun listTools(): JSONObject {
        val list = JSONArray()
        tools.values.forEach { tool ->
            list.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("inputSchema", tool.inputSchema)
                    .put(
                        "annotations",
                        JSONObject()
                            .put("readOnlyHint", tool.readOnly)
                            .put("destructiveHint", !tool.readOnly),
                    ),
            )
        }
        return JSONObject().put("tools", list)
    }

    private suspend fun callTool(id: Any, params: JSONObject): String {
        val name = params.optString("name")
        val tool = tools[name] ?: return error(id, -32602, "Unknown tool: $name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        val (text, isError) = try {
            tool.handler(arguments).toString() to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (e.message ?: e.javaClass.simpleName) to true
        }

        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        return result(id, JSONObject().put("content", content).put("isError", isError))
    }

    private fun result(id: Any, value: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value).toString()

    private fun error(id: Any, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    private companion object {
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    }
}
