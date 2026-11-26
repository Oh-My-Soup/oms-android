package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.ToolCallView
import dev.oms.remote.protocol.api.ToolStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A running tool from `tool_execution_*` events (spec 5.5). */
internal data class ActiveTool(
    val toolCallId: String,
    val toolName: String,
    val args: JsonElement?,
    val intent: String?,
    val partialResult: JsonElement?,
)

/**
 * Builds [ToolCallView]s: merges a call's arguments with its result message and live execution
 * state (spec 8, "Tool grouping"). Argument rendering is cached per call because it is the
 * expensive part and is recomputed on every transcript rebuild.
 */
internal class ToolViews {
    private class Args(val source: JsonElement?, val intent: String?, val summary: String, val json: String)

    private val argsCache = HashMap<String, Args>()

    fun view(
        toolCallId: String,
        name: String,
        args: JsonElement?,
        blockIntent: String?,
        result: JsonObject?,
        active: ActiveTool?,
        expectRun: Boolean,
        committed: Boolean,
    ): ToolCallView {
        val rendered = args(toolCallId, name, active?.args ?: args)
        val running = result == null && (active != null || expectRun)
        val status = when {
            result != null -> if (result.bool("isError") == true) ToolStatus.Error else ToolStatus.Done
            running -> ToolStatus.Running
            else -> ToolStatus.Unknown
        }
        val partial = active?.partialResult.takeIf { result == null }
        val output = when {
            result != null -> Content.text(result["content"])
            partial != null -> Content.text(partial).ifEmpty { null }
            else -> null
        }
        return ToolCallView(
            toolCallId = toolCallId,
            name = name,
            intent = clean(blockIntent) ?: clean(active?.intent) ?: rendered.intent,
            summary = rendered.summary,
            argsJson = rendered.json,
            status = status,
            output = output,
            imageCount = Content.imageCount((result ?: partial as? JsonObject)?.get("content")),
            resultMissing = committed && result == null && !running,
        )
    }

    /** A standalone result whose call is outside the loaded window. */
    fun orphanResult(result: JsonObject): ToolCallView = view(
        toolCallId = result.str("toolCallId").orEmpty(),
        name = result.str("toolName") ?: "tool",
        args = null,
        blockIntent = null,
        result = result,
        active = null,
        expectRun = false,
        committed = false,
    )

    private fun args(toolCallId: String, name: String, source: JsonElement?): Args {
        argsCache[toolCallId]?.takeIf { it.source === source }?.let { return it }
        if (argsCache.size >= MAX_CACHE) argsCache.clear()
        val obj = source as? JsonObject
        val stripped = obj?.let { JsonObject(it - INTENT_KEY) }
        val shown: JsonElement = stripped ?: source ?: EmptyJsonObject
        val args = Args(
            source = source,
            intent = clean(obj?.str(INTENT_KEY)),
            summary = summary(name, shown),
            json = if (source == null) "{}" else prettyJson(shown),
        )
        argsCache[toolCallId] = args
        return args
    }

    private fun summary(name: String, args: JsonElement): String {
        val value = when (args) {
            is JsonObject -> SUMMARY_KEYS[name]?.firstNotNullOfOrNull(args::str) ?: firstString(args)
            is JsonPrimitive -> args.takeIf { it.isString }?.content
            else -> null
        }
        return value?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_SUMMARY).orEmpty()
    }

    private fun firstString(args: JsonObject): String? =
        args.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }

    private fun clean(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val INTENT_KEY = "i"
        const val MAX_SUMMARY = 300
        const val MAX_CACHE = 2048
        val PATH_KEYS = listOf("path", "file_path")
        val PATTERN_KEYS = listOf("pattern")
        val SUMMARY_KEYS = mapOf(
            "bash" to listOf("command"),
            "read" to PATH_KEYS,
            "edit" to PATH_KEYS,
            "write" to PATH_KEYS,
            "grep" to PATTERN_KEYS,
            "glob" to PATTERN_KEYS,
            "find" to PATTERN_KEYS,
        )
    }
}
