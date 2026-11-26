package dev.oms.remote.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerant readers for message content (spec 5.3, 9.2): content is a string or an array whose
 * elements are blocks, or bare strings left behind by host-side shrinking.
 */
internal object Content {
    /** Joined text of [content]; [separator] goes between text blocks. Images and unknown blocks are skipped. */
    fun text(content: JsonElement?, separator: String = "\n"): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull(::blockText).joinToString(separator)
        is JsonObject -> content.str("text") ?: text(content["content"], separator)
        else -> ""
    }

    fun imageCount(content: JsonElement?): Int =
        (content as? JsonArray)?.count { (it as? JsonObject)?.str("type") == "image" } ?: 0

    private fun blockText(block: JsonElement): String? = when (block) {
        is JsonPrimitive -> if (block.isString) block.content else null
        is JsonObject -> if (block.str("type") == "image") null else block.str("text")
        else -> null
    }
}

/** `{type:"message", message:{…}}` entry → its message. */
internal fun JsonObject.entryMessage(): JsonObject? = if (str("type") == "message") obj("message") else null

internal fun JsonObject.entryId(): String = str("id").orEmpty()

internal fun JsonObject.isAssistantMessageEntry(): Boolean = entryMessage()?.str("role") == "assistant"

/** Ids of the `toolCall` blocks of an assistant message. */
internal fun JsonObject.toolCallIds(): List<String> =
    arr("content")?.mapNotNull { block ->
        (block as? JsonObject)?.takeIf { it.str("type") == "toolCall" }?.str("id")
    }.orEmpty()

/** "950", "12.3k", "1.2M": collab-web's `fmtTokens`. */
internal fun formatTokens(n: Long?): String {
    if (n == null || n <= 0) return "0"
    if (n < 1000) return n.toString()
    if (n < 1_000_000) {
        val k = n / 1000.0
        return if (k >= 100) "${Math.round(k)}k" else "${oneDecimal(k)}k"
    }
    val m = n / 1_000_000.0
    return if (m >= 100) "${Math.round(m)}M" else "${oneDecimal(m)}M"
}

private fun oneDecimal(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)
