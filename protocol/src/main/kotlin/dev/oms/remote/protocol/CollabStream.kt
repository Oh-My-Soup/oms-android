package dev.oms.remote.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Port of `applyCollabStream` (spec 6, `packages/wire/src/collab-stream.ts`).
 * Offsets are UTF-16 code units, which is what Kotlin's [String.length] counts.
 */
internal object CollabStream {
    /** The patched message, or null when the patch does not apply to [previous]. */
    fun apply(previous: JsonObject, patch: JsonObject): JsonObject? {
        val metadata = patch.obj("metadata") ?: return null
        val contentLength = patch.int("contentLength")?.takeIf { it >= 0 } ?: return null
        val changes = patch.arr("changes") ?: return null
        val previousContent = previous.arr("content") ?: return null
        val content = ArrayList<JsonElement?>(contentLength)
        content.addAll(previousContent.take(contentLength))
        for (element in changes) {
            val change = element as? JsonObject ?: return null
            val index = change.int("index") ?: return null
            if (index < 0 || index >= contentLength) return null
            when (change.str("kind")) {
                "replace" -> content.setPadded(index, change["value"] ?: return null)
                "append" -> content.setPadded(index, appended(content.getOrNull(index), change) ?: return null)
                else -> return null
            }
        }
        if (content.size != contentLength || content.any { it == null }) return null
        return JsonObject(metadata + ("content" to JsonArray(content.map { requireNotNull(it) })))
    }

    private fun appended(before: JsonElement?, change: JsonObject): JsonObject? {
        val block = before as? JsonObject ?: return null
        val field = change.str("field") ?: return null
        val old = block.str(field) ?: return null
        val offset = change.long("offset") ?: return null
        if (old.length.toLong() != offset) return null
        val text = change.str("text") ?: return null
        val properties = change.obj("properties") ?: EmptyJsonObject
        return JsonObject(properties + (field to JsonPrimitive(old + text)))
    }

    private fun MutableList<JsonElement?>.setPadded(index: Int, value: JsonElement) {
        while (size <= index) add(null)
        this[index] = value
    }
}
