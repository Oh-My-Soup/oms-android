package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.AssistantBlock
import dev.oms.remote.protocol.api.MarkerKind
import dev.oms.remote.protocol.api.TranscriptItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The in-flight assistant message (spec 6). [key] stays constant for one streamed message. */
internal data class StreamGhost(val key: String, val message: JsonObject, val done: Boolean)

/**
 * Turns session entries plus live state into [TranscriptItem]s following collab-web's
 * `Transcript.tsx` (spec 8). Committed rows and the live tail are built separately so a stream
 * delta only rebuilds the tail.
 */
internal class TranscriptBuilder {
    class Committed(
        val items: List<TranscriptItem>,
        /** toolCallId → toolResult message, for results in the window. */
        val results: Map<String, JsonObject>,
        /** Ids of tool calls in committed assistant messages. */
        val callIds: Set<String>,
    )

    private val tools = ToolViews()

    fun committed(entries: List<JsonObject>, active: Map<String, ActiveTool>, working: Boolean): Committed {
        val results = HashMap<String, JsonObject>()
        val callIds = HashSet<String>()
        var lastAssistant = -1
        entries.forEachIndexed { index, entry ->
            val message = entry.entryMessage() ?: return@forEachIndexed
            when (message.str("role")) {
                "toolResult" -> message.str("toolCallId")?.let { results[it] = message }
                "assistant" -> {
                    lastAssistant = index
                    callIds.addAll(message.toolCallIds())
                }
            }
        }
        val items = ArrayList<TranscriptItem>(entries.size)
        entries.forEachIndexed { index, entry ->
            // Calls of the newest assistant message may still be about to run while the agent works.
            val expectRun = working && index == lastAssistant
            entryItem(entry, results, callIds, active, expectRun)?.let(items::add)
        }
        return Committed(items, results, callIds)
    }

    /** The stream ghost followed by active tools no loaded call refers to. */
    fun live(committed: Committed, ghost: StreamGhost?, active: Map<String, ActiveTool>, working: Boolean): List<TranscriptItem> {
        val items = ArrayList<TranscriptItem>()
        val ghostCalls = ghost?.message?.toolCallIds()?.toSet().orEmpty()
        if (ghost != null) {
            val message = ghost.message
            items += TranscriptItem.Assistant(
                key = ghost.key,
                blocks = blocks(message, committed.results, active, expectRun = !ghost.done || working, committed = false),
                streaming = true,
                stopReason = if (ghost.done) message.str("stopReason") else null,
                errorMessage = if (ghost.done) message.str("errorMessage") else null,
            )
        }
        for (tool in active.values) {
            if (tool.toolCallId in committed.callIds || tool.toolCallId in ghostCalls) continue
            items += TranscriptItem.Tool(
                key = "tool:${tool.toolCallId}",
                call = tools.view(tool.toolCallId, tool.toolName, tool.args, tool.intent, null, tool, expectRun = true, committed = false),
            )
        }
        return items
    }

    private fun entryItem(
        entry: JsonObject,
        results: Map<String, JsonObject>,
        callIds: Set<String>,
        active: Map<String, ActiveTool>,
        expectRun: Boolean,
    ): TranscriptItem? {
        val key = entry.entryId()
        return when (entry.str("type")) {
            "message" -> messageItem(key, entry.obj("message") ?: return null, results, callIds, active, expectRun)
            "custom_message" -> customItem(key, entry)
            "compaction" -> TranscriptItem.Marker(
                key,
                MarkerKind.Compaction,
                "context compacted · ${formatTokens(entry.long("tokensBefore"))} tokens",
            )
            "branch_summary" -> TranscriptItem.Marker(key, MarkerKind.BranchSummary, "branch summary")
            "model_change" -> TranscriptItem.Marker(key, MarkerKind.ModelChange, "model → ${entry.str("model").orEmpty()}")
            "thinking_level_change" -> TranscriptItem.Marker(
                key,
                MarkerKind.ThinkingChange,
                "thinking → ${entry.str("thinkingLevel") ?: "off"}",
            )
            else -> null
        }
    }

    private fun messageItem(
        key: String,
        message: JsonObject,
        results: Map<String, JsonObject>,
        callIds: Set<String>,
        active: Map<String, ActiveTool>,
        expectRun: Boolean,
    ): TranscriptItem? = when (message.str("role")) {
        "user" -> TranscriptItem.User(
            key = key,
            text = Content.text(message["content"], PARAGRAPH),
            imageCount = Content.imageCount(message["content"]),
        )
        "assistant" -> {
            val stopReason = message.str("stopReason")
            val failed = stopReason == "error" || stopReason == "aborted"
            val blocks = blocks(message, results, active, expectRun = expectRun && !failed, committed = true)
            if (blocks.isEmpty() && !failed) {
                null
            } else {
                TranscriptItem.Assistant(key, blocks, stopReason = stopReason, errorMessage = message.str("errorMessage"))
            }
        }
        "toolResult" -> {
            if (message.str("toolCallId") in callIds) null else TranscriptItem.Tool(key, tools.orphanResult(message))
        }
        else -> null
    }

    private fun customItem(key: String, entry: JsonObject): TranscriptItem? {
        val content = entry["content"]
        val customType = entry.str("customType").orEmpty()
        if (customType == COLLAB_PROMPT) {
            return TranscriptItem.User(
                key = key,
                text = Content.text(content, PARAGRAPH),
                imageCount = Content.imageCount(content),
                from = entry.obj("details")?.str("from") ?: "guest",
            )
        }
        if (entry.bool("display") != true) return null
        return TranscriptItem.Custom(key, customType, Content.text(content, PARAGRAPH))
    }

    private fun blocks(
        message: JsonObject,
        results: Map<String, JsonObject>,
        active: Map<String, ActiveTool>,
        expectRun: Boolean,
        committed: Boolean,
    ): List<AssistantBlock> {
        val content = message.arr("content") ?: return emptyList()
        val blocks = ArrayList<AssistantBlock>(content.size)
        for (element in content) {
            if (element is JsonPrimitive) {
                // Elision marker left by host-side shrinking (spec 9.2).
                if (element.isString) blocks += AssistantBlock.Text(element.content)
                continue
            }
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf(String::isNotBlank)?.let { blocks += AssistantBlock.Text(it) }
                "thinking" -> block.str("thinking")?.takeIf(String::isNotBlank)?.let { blocks += AssistantBlock.Thinking(it) }
                "redactedThinking" -> blocks += AssistantBlock.Thinking("", redacted = true)
                "toolCall" -> {
                    val id = block.str("id") ?: continue
                    blocks += AssistantBlock.Tool(
                        tools.view(
                            toolCallId = id,
                            name = block.str("name") ?: "tool",
                            args = block["arguments"],
                            blockIntent = block.str("intent"),
                            result = results[id],
                            active = active[id],
                            expectRun = expectRun,
                            committed = committed,
                        ),
                    )
                }
            }
        }
        return blocks
    }

    private companion object {
        const val COLLAB_PROMPT = "collab-prompt"
        const val PARAGRAPH = "\n\n"
    }
}
