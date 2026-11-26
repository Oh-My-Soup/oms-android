package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.AgentInfo
import dev.oms.remote.protocol.api.AgentProgressInfo
import dev.oms.remote.protocol.api.Participant
import dev.oms.remote.protocol.api.PendingRequest
import dev.oms.remote.protocol.api.RequestKind
import dev.oms.remote.protocol.api.RequestOption
import dev.oms.remote.protocol.api.SessionHeaderInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Pure mappings from wire JSON to the UI contract types. */
internal object UiMappers {
    /** `header.title ?: state.sessionName ?: "session"` (spec 8, blank treated as absent). */
    fun title(header: JsonObject?, state: JsonObject?): String =
        header?.str("title")?.takeIf(String::isNotBlank)
            ?: state?.str("sessionName")?.takeIf(String::isNotBlank)
            ?: "session"

    /** Header facts from `welcome.header` and the latest `state` (spec 5.6); null before any welcome. */
    fun header(header: JsonObject?, state: JsonObject?, working: Boolean): SessionHeaderInfo? {
        if (header == null && state == null) return null
        val model = state?.obj("model")
        val usage = state?.obj("contextUsage")
        val provider = model?.str("provider")
        val modelId = model?.str("id")
        val tokens = usage?.long("tokens")
        val window = usage?.long("contextWindow") ?: model?.long("contextWindow")
        val percent = usage?.double("percent")
            ?: if (tokens != null && window != null && window > 0) tokens * 100.0 / window else null
        return SessionHeaderInfo(
            title = title(header, state),
            cwd = state?.str("cwd") ?: header?.str("cwd"),
            modelName = model?.str("name") ?: modelId,
            modelId = if (provider != null && modelId != null) "$provider/$modelId" else modelId,
            thinkingLevel = state?.str("thinkingLevel"),
            contextPercent = percent,
            contextTokens = tokens,
            contextWindow = window,
            isStreaming = working,
            isAborting = state?.bool("isAborting") == true,
            queuedCount = state?.int("queuedMessageCount") ?: 0,
            participants = state?.arr("participants").orEmpty().mapNotNull(::participant),
        )
    }

    private fun participant(element: JsonElement): Participant? {
        val json = element as? JsonObject ?: return null
        return Participant(
            name = json.str("name") ?: return null,
            role = json.str("role") ?: "guest",
            readOnly = json.bool("readOnly") == true,
        )
    }

    /** An `AgentSnapshot` (spec 5.7) merged with its latest bus progress (spec 5.8). */
    fun agent(agent: JsonObject, progress: (String) -> JsonObject?): AgentInfo? {
        val id = agent.str("id") ?: return null
        return AgentInfo(
            id = id,
            displayName = agent.str("displayName") ?: id,
            kind = agent.str("kind") ?: "sub",
            parentId = agent.str("parentId"),
            status = agent.str("status") ?: "idle",
            hasSessionFile = agent.bool("hasSessionFile") == true,
            lastActivityMs = agent.long("lastActivity"),
            progress = progress(id)?.let(::progress),
        )
    }

    private fun progress(p: JsonObject) = AgentProgressInfo(
        task = p.str("task"),
        status = p.str("status") ?: "running",
        currentTool = p.str("currentTool"),
        lastIntent = p.str("lastIntent"),
        toolCount = p.int("toolCount") ?: 0,
        tokens = p.long("tokens") ?: 0,
        cost = p.double("cost") ?: 0.0,
        durationMs = p.long("durationMs") ?: 0,
        recentOutput = p.arr("recentOutput").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { s -> s.isString }?.content },
    )

    /** A `CollabUiRequest` (spec 7.1); null for unknown kinds or a missing reqId. */
    fun request(json: JsonObject): PendingRequest? {
        val reqId = json.long("reqId") ?: return null
        val kind = when (json.str("kind")) {
            "select" -> RequestKind.Select
            "editor" -> RequestKind.Editor
            else -> return null
        }
        val options = json.arr("options").orEmpty().mapNotNull { option ->
            when (option) {
                is JsonPrimitive -> option.takeIf { it.isString }?.let { RequestOption(it.content) }
                is JsonObject -> option.str("label")?.let { RequestOption(it, option.str("description")) }
                else -> null
            }
        }
        return PendingRequest(
            reqId = reqId,
            kind = kind,
            title = json.str("title").orEmpty(),
            options = options,
            initialIndex = json.int("initialIndex"),
            selectionMarker = json.str("selectionMarker"),
            checkedIndices = json.arr("checkedIndices").orEmpty().mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.intOrNull },
            markableCount = json.int("markableCount") ?: 0,
            prefill = json.str("prefill"),
        )
    }
}
