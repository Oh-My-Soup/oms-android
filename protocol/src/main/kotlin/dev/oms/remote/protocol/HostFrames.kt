package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.CommandInfo
import dev.oms.remote.protocol.api.CompletionItem
import dev.oms.remote.protocol.api.ModelCatalog
import dev.oms.remote.protocol.api.ModelInfo
import dev.oms.remote.protocol.api.RoleModel
import dev.oms.remote.protocol.api.SubcommandInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Paging facts of a `welcome.history` / `history.page` object (spec 4.5). */
internal data class HistoryPage(val beforeId: String?, val afterId: String?, val total: Long)

/**
 * Host → guest frames (spec 5.2). Variable payloads (entries, events, messages, state) stay as
 * JSON and are interpreted by the reducer; unknown or malformed frames decode to [Unknown].
 */
internal sealed interface HostFrame {
    data class Welcome(
        val proto: Long?,
        val header: JsonObject?,
        val state: JsonObject?,
        val agents: List<JsonObject>,
        val entryCount: Long,
        val readOnly: Boolean,
        val history: HistoryPage?,
        val features: Set<String> = emptySet(),
    ) : HostFrame

    data class SnapshotChunk(val entries: List<JsonObject>, val final: Boolean) : HostFrame

    data class Entry(val entry: JsonObject) : HostFrame

    data class Event(val event: JsonObject) : HostFrame

    data class Stream(val id: Long, val revision: Long, val message: JsonObject) : HostFrame

    data class StreamDelta(val id: Long, val previousRevision: Long, val revision: Long, val patch: JsonObject) : HostFrame

    data class History(val reqId: Long, val entries: List<JsonObject>, val page: HistoryPage, val error: String?) : HostFrame

    data class State(val state: JsonObject) : HostFrame

    data class Bus(val channel: String, val data: JsonObject) : HostFrame

    data class Agents(val agents: List<JsonObject>) : HostFrame

    data class UiRequest(val request: JsonObject) : HostFrame

    data class UiRequestEnd(val reqId: Long) : HostFrame

    data class Transcript(val reqId: Long, val text: String, val newSize: Long, val error: String?) : HostFrame

    /** The host commands this guest may run; sent after `welcome` and whenever the list changes (v5). */
    data class Commands(val commands: List<CommandInfo>) : HostFrame

    /** Markdown output of a command this guest ran; [delta] appends to the previous output without a break. */
    data class CommandOutput(val reqId: Long, val text: String, val delta: Boolean) : HostFrame

    /** The one terminal reply to `command`, `set-model` and `set-thinking`: `done|prompt|cancelled|error`. */
    data class CommandEnd(val reqId: Long, val status: String, val message: String?) : HostFrame

    data class Completions(val reqId: Long, val items: List<CompletionItem>) : HostFrame

    /** Reply to `fetch-models`; [error] marks a refusal (the catalog is then empty). */
    data class Models(val reqId: Long, val catalog: ModelCatalog, val error: String?) : HostFrame

    data class Bye(val reason: String) : HostFrame

    data class Error(val message: String) : HostFrame

    data class Unknown(val type: String?) : HostFrame
}

internal object HostFrames {
    fun decode(json: String): HostFrame = parseJsonObject(json)?.let(::decode) ?: HostFrame.Unknown(null)

    fun decode(frame: JsonObject): HostFrame {
        val type = frame.str("t")
        return when (type) {
            "welcome" -> HostFrame.Welcome(
                proto = frame.long("proto"),
                header = frame.obj("header"),
                state = frame.obj("state"),
                agents = objects(frame.arr("agents")),
                entryCount = frame.long("entryCount") ?: 0,
                readOnly = frame.bool("readOnly") == true,
                history = frame.obj("history")?.let(::page),
                features = strings(frame.arr("features")).toSet(),
            )
            "snapshot-chunk" -> HostFrame.SnapshotChunk(entries(frame.arr("entries")), frame.bool("final") == true)
            "entry" -> frame.obj("entry")?.takeIf(::isEntry)?.let(HostFrame::Entry)
            "event" -> frame.obj("event")?.let(HostFrame::Event)
            "stream" -> {
                val id = frame.long("id")
                val revision = frame.long("revision")
                val message = frame.obj("message")
                if (id != null && revision != null && message != null) HostFrame.Stream(id, revision, message) else null
            }
            "stream-delta" -> {
                val id = frame.long("id")
                val previous = frame.long("previousRevision")
                val revision = frame.long("revision")
                val patch = frame.obj("patch")
                if (id != null && previous != null && revision != null && patch != null) {
                    HostFrame.StreamDelta(id, previous, revision, patch)
                } else {
                    null
                }
            }
            "history" -> frame.long("reqId")?.let { reqId ->
                HostFrame.History(
                    reqId = reqId,
                    entries = entries(frame.arr("entries")),
                    page = frame.obj("page")?.let(::page) ?: HistoryPage(null, null, 0),
                    error = frame.str("error"),
                )
            }
            "state" -> frame.obj("state")?.let(HostFrame::State)
            "bus" -> {
                val channel = frame.str("channel")
                val data = frame.obj("data")
                if (channel != null && data != null) HostFrame.Bus(channel, data) else null
            }
            "agents" -> HostFrame.Agents(objects(frame.arr("agents")))
            "ui-request" -> frame.obj("request")?.let(HostFrame::UiRequest)
            "ui-request-end" -> frame.long("reqId")?.let(HostFrame::UiRequestEnd)
            "transcript" -> frame.long("reqId")?.let { reqId ->
                HostFrame.Transcript(reqId, frame.str("text").orEmpty(), frame.long("newSize") ?: 0, frame.str("error"))
            }
            "commands" -> HostFrame.Commands(objects(frame.arr("commands")).mapNotNull(::command))
            "command-output" -> frame.long("reqId")?.let { reqId ->
                HostFrame.CommandOutput(reqId, frame.str("text").orEmpty(), frame.bool("delta") == true)
            }
            "command-end" -> frame.long("reqId")?.let { reqId ->
                HostFrame.CommandEnd(reqId, frame.str("status") ?: "error", frame.str("message"))
            }
            "completions" -> frame.long("reqId")?.let { reqId ->
                HostFrame.Completions(
                    reqId,
                    objects(frame.arr("items")).mapNotNull { item ->
                        val value = item.str("value") ?: return@mapNotNull null
                        CompletionItem(value, item.str("label") ?: value, item.str("description"))
                    },
                )
            }
            "models" -> frame.long("reqId")?.let { reqId -> HostFrame.Models(reqId, catalog(frame), frame.str("error")) }
            "bye" -> HostFrame.Bye(frame.str("reason") ?: "host ended the session")
            "error" -> HostFrame.Error(frame.str("message") ?: "host error")
            else -> null
        } ?: HostFrame.Unknown(type)
    }

    private fun command(json: JsonObject): CommandInfo? {
        val name = json.str("name")?.takeIf(String::isNotBlank) ?: return null
        return CommandInfo(
            name = name,
            aliases = strings(json.arr("aliases")),
            description = json.str("description"),
            hint = json.str("hint")?.takeIf(String::isNotBlank),
            subcommands = objects(json.arr("subcommands")).mapNotNull { sub ->
                sub.str("name")?.let { SubcommandInfo(it, sub.str("description"), sub.str("usage")) }
            },
            source = json.str("source") ?: "builtin",
        )
    }

    private fun catalog(frame: JsonObject): ModelCatalog {
        val current = frame.obj("current")
        return ModelCatalog(
            models = objects(frame.arr("models")).mapNotNull { model ->
                val provider = model.str("provider") ?: return@mapNotNull null
                val id = model.str("id") ?: return@mapNotNull null
                ModelInfo(
                    provider = provider,
                    id = id,
                    name = model.str("name")?.takeIf(String::isNotBlank) ?: id,
                    reasoning = model.bool("reasoning") == true,
                    contextWindow = model.long("contextWindow"),
                    scoped = model.bool("scoped") == true,
                )
            },
            roles = objects(frame.arr("roles")).mapNotNull { role ->
                val name = role.str("role") ?: return@mapNotNull null
                val provider = role.str("provider") ?: return@mapNotNull null
                val id = role.str("id") ?: return@mapNotNull null
                RoleModel(name, provider, id, role.str("thinkingLevel"))
            },
            currentKey = current?.let { c -> c.str("provider")?.let { p -> c.str("id")?.let { "$p/$it" } } },
            thinkingLevel = frame.str("thinkingLevel"),
            thinkingLevels = strings(frame.arr("thinkingLevels")),
        )
    }

    private fun strings(array: JsonArray?): List<String> =
        array?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty()

    private fun page(json: JsonObject) = HistoryPage(json.str("beforeId"), json.str("afterId"), json.long("total") ?: 0)

    private fun objects(array: JsonArray?): List<JsonObject> = array?.filterIsInstance<JsonObject>().orEmpty()

    /** Session entries must be objects with a string id; anything else is skipped. */
    private fun entries(array: JsonArray?): List<JsonObject> = objects(array).filter(::isEntry)

    private fun isEntry(entry: JsonObject): Boolean = entry.str("id") != null
}
