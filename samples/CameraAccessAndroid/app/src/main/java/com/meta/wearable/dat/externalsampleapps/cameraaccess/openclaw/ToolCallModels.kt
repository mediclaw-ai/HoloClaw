package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import org.json.JSONArray
import org.json.JSONObject

// Gemini Tool Call (parsed from server JSON)

data class GeminiFunctionCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>
)

data class GeminiToolCall(
    val functionCalls: List<GeminiFunctionCall>
) {
    companion object {
        fun fromJSON(json: JSONObject): GeminiToolCall? {
            val toolCall = json.optJSONObject("toolCall") ?: return null
            val calls = toolCall.optJSONArray("functionCalls") ?: return null
            val functionCalls = mutableListOf<GeminiFunctionCall>()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val id = call.optString("id", "")
                val name = call.optString("name", "")
                if (id.isEmpty() || name.isEmpty()) continue
                val argsObj = call.optJSONObject("args")
                val args = mutableMapOf<String, Any?>()
                if (argsObj != null) {
                    for (key in argsObj.keys()) {
                        args[key] = argsObj.opt(key)
                    }
                }
                functionCalls.add(GeminiFunctionCall(id, name, args))
            }
            return if (functionCalls.isNotEmpty()) GeminiToolCall(functionCalls) else null
        }
    }
}

// Gemini Tool Call Cancellation

data class GeminiToolCallCancellation(
    val ids: List<String>
) {
    companion object {
        fun fromJSON(json: JSONObject): GeminiToolCallCancellation? {
            val cancellation = json.optJSONObject("toolCallCancellation") ?: return null
            val idsArray = cancellation.optJSONArray("ids") ?: return null
            val ids = mutableListOf<String>()
            for (i in 0 until idsArray.length()) {
                ids.add(idsArray.getString(i))
            }
            return if (ids.isNotEmpty()) GeminiToolCallCancellation(ids) else null
        }
    }
}

// Tool Result

sealed class ToolResult {
    data class Success(val result: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()

    fun toJSON(): JSONObject = when (this) {
        is Success -> JSONObject().put("result", result)
        is Failure -> JSONObject().put("error", error)
    }
}

// Tool Call Status (for UI)

sealed class ToolCallStatus {
    data object Idle : ToolCallStatus()
    data class Executing(val name: String) : ToolCallStatus()
    data class Completed(val name: String) : ToolCallStatus()
    data class Failed(val name: String, val error: String) : ToolCallStatus()
    data class Cancelled(val name: String) : ToolCallStatus()

    val displayText: String
        get() = when (this) {
            is Idle -> ""
            is Executing -> "Running: $name..."
            is Completed -> "Done: $name"
            is Failed -> "Failed: $name - $error"
            is Cancelled -> "Cancelled: $name"
        }

    val isActive: Boolean
        get() = this is Executing
}

// OpenClaw Connection State

sealed class OpenClawConnectionState {
    data object NotConfigured : OpenClawConnectionState()
    data object Checking : OpenClawConnectionState()
    data object Connected : OpenClawConnectionState()
    data class Unreachable(val message: String) : OpenClawConnectionState()
}

// Tool Declarations (for Gemini setup message)

object ToolDeclarations {
    fun allDeclarationsJSON(): JSONArray {
        return JSONArray().put(executeJSON())
    }

    private fun executeJSON(): JSONObject {
        return JSONObject().apply {
            put("name", "execute")
            put(
                "description",
                "Your single tool for taking any action. First DECIDE the kind of action and set \"action\":\n" +
                    "• action=\"render\": display visual widget cards in the user's field of view — provide \"widgets\".\n" +
                    "• action=\"delegate\": perform a real-world action (sending messages, searching, lists, reminders, notes, scheduling, smart-home control, app interactions, research, etc.) — provide a detailed \"task\". This is the only way anything real happens.",
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("delegate").put("render"))
                        put(
                            "description",
                            "delegate = perform a real-world action via the assistant (OpenClaw); render = show widget cards in the field of view.",
                        )
                    })
                    put("task", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "For action=delegate: a clear, detailed description of what to do, with all relevant context (names, content, platforms, quantities, etc.).",
                        )
                    })
                    put("widgets", JSONObject().apply {
                        put("type", "array")
                        put(
                            "description",
                            "For action=render: the widget cards to show, top to bottom. Each render replaces whatever is currently shown.",
                        )
                        put("items", widgetItemSchema())
                    })
                })
                put("required", JSONArray().put("action"))
            })
            put("behavior", "BLOCKING")
        }
    }

    private fun widgetItemSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("type", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().put("text").put("image").put("table"))
                    put("description", "The widget type.")
                })
                put("title", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional heading shown at the top of the widget.")
                })
                put("body", JSONObject().apply {
                    put("type", "string")
                    put("description", "For type=text: the paragraph of text to show.")
                })
                put("imageKind", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().put("map").put("gallery").put("calendar"))
                    put(
                        "description",
                        "For type=image: which fixed image to show — map, gallery, or calendar.",
                    )
                })
                put("caption", JSONObject().apply {
                    put("type", "string")
                    put("description", "For type=image: a short caption under the image.")
                })
                put("columns", JSONObject().apply {
                    put("type", "array")
                    put("items", JSONObject().put("type", "string"))
                    put("description", "For type=table: optional column header labels.")
                })
                put("rows", JSONObject().apply {
                    put("type", "array")
                    put(
                        "description",
                        "For type=table: the rows; each row is an array of cell strings.",
                    )
                    put(
                        "items",
                        JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().put("type", "string"))
                        },
                    )
                })
            })
            put("required", JSONArray().put("type"))
        }
    }
}
