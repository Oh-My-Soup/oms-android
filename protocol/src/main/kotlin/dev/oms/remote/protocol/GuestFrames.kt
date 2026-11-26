package dev.oms.remote.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Guest → host frames (spec 5.1). Optional fields are omitted, never `null`; field order matches
 * the TypeScript guests so plaintexts are byte-identical to the vectors.
 */
internal object GuestFrames {
    /** Latest collab protocol (oms 18.20.3 and newer); sessions start with it. */
    const val PROTOCOL_VERSION = 5

    /** v3 (oms ≤ 18.13.1): no paged history and no `stream`/`stream-delta` frames. */
    const val PROTOCOL_V3 = 3

    val SUPPORTED_VERSIONS = PROTOCOL_V3..PROTOCOL_VERSION

    /**
     * `welcome.features` entry for structured model control (`fetch-models`, `set-model`,
     * `set-thinking`). An oh-my-soup addition, additive to the protocol version: send these frames
     * only to a host whose welcome listed it.
     */
    const val FEATURE_MODELS = "models"

    /** Paged history (`hello.history`, `fetch-history`) exists from v4. */
    fun hasPagedHistory(proto: Int): Boolean = proto >= 4

    /** Host commands (`commands`, `command`, `command-cancel`, `complete`) exist from v5. */
    fun hasCommands(proto: Int): Boolean = proto >= 5

    /** v5 hosts pace `snapshot-chunk` frames on the guest's `snapshot-ack`s; without them the join stalls. */
    fun hasSnapshotAck(proto: Int): Boolean = proto >= 5

    class Image(val data: String, val mimeType: String)

    sealed interface HistoryCursor {
        data object Latest : HistoryCursor

        data class Before(val id: String) : HistoryCursor

        data class After(val id: String) : HistoryCursor
    }

    fun hello(name: String, writeToken: ByteArray?, proto: Int = PROTOCOL_VERSION): JsonObject = buildJsonObject {
        put("t", "hello")
        put("proto", proto)
        put("name", name)
        if (hasPagedHistory(proto)) put("history", "paged")
        if (writeToken != null) put("writeToken", Base64Url.encode(writeToken))
    }

    fun prompt(text: String, images: List<Image> = emptyList()): JsonObject = buildJsonObject {
        put("t", "prompt")
        put("text", text)
        if (images.isNotEmpty()) {
            put(
                "images",
                buildJsonArray {
                    for (image in images) {
                        add(
                            buildJsonObject {
                                put("type", "image")
                                put("data", image.data)
                                put("mimeType", image.mimeType)
                            },
                        )
                    }
                },
            )
        }
    }

    fun abort(): JsonObject = buildJsonObject { put("t", "abort") }

    /** A null [value] cancels: the key is omitted (spec 7.2). */
    fun uiResponse(reqId: Long, value: String?): JsonObject = buildJsonObject {
        put("t", "ui-response")
        put("reqId", reqId)
        if (value != null) put("value", value)
    }

    /** [cmd] is `chat`, `kill` or `revive`. */
    fun agentCmd(cmd: String, agentId: String, text: String?): JsonObject = buildJsonObject {
        put("t", "agent-cmd")
        put("cmd", cmd)
        put("agentId", agentId)
        if (text != null) put("text", text)
    }

    fun fetchHistory(reqId: Long, cursor: HistoryCursor): JsonObject = buildJsonObject {
        put("t", "fetch-history")
        put("reqId", reqId)
        put(
            "cursor",
            buildJsonObject {
                when (cursor) {
                    HistoryCursor.Latest -> put("kind", "latest")
                    is HistoryCursor.Before -> {
                        put("kind", "before")
                        put("id", cursor.id)
                    }
                    is HistoryCursor.After -> {
                        put("kind", "after")
                        put("id", cursor.id)
                    }
                }
            },
        )
    }

    fun fetchTranscript(reqId: Long, agentId: String, fromByte: Long): JsonObject = buildJsonObject {
        put("t", "fetch-transcript")
        put("reqId", reqId)
        put("agentId", agentId)
        put("fromByte", fromByte)
    }

    /** [chunks] counts every `snapshot-chunk` received since the last `welcome`, cumulatively. */
    fun snapshotAck(chunks: Long): JsonObject = buildJsonObject {
        put("t", "snapshot-ack")
        put("chunks", chunks)
    }

    /** Run a host command; [text] starts with `/`. Answered by `command-output`* and one `command-end`. */
    fun command(reqId: Long, text: String): JsonObject = buildJsonObject {
        put("t", "command")
        put("reqId", reqId)
        put("text", text)
    }

    fun commandCancel(reqId: Long): JsonObject = buildJsonObject {
        put("t", "command-cancel")
        put("reqId", reqId)
    }

    /** Argument completions for the draft [text] (`/name argument-prefix`); answered by `completions`. */
    fun complete(reqId: Long, text: String): JsonObject = buildJsonObject {
        put("t", "complete")
        put("reqId", reqId)
        put("text", text)
    }

    fun fetchModels(reqId: Long): JsonObject = buildJsonObject {
        put("t", "fetch-models")
        put("reqId", reqId)
    }

    fun setModel(reqId: Long, provider: String, id: String): JsonObject = buildJsonObject {
        put("t", "set-model")
        put("reqId", reqId)
        put("provider", provider)
        put("id", id)
    }

    fun setThinking(reqId: Long, level: String): JsonObject = buildJsonObject {
        put("t", "set-thinking")
        put("reqId", reqId)
        put("level", level)
    }

    /** Compact UTF-8 JSON, the plaintext that gets sealed. */
    fun encode(frame: JsonObject): ByteArray = encodeJson(frame).toByteArray(Charsets.UTF_8)
}
