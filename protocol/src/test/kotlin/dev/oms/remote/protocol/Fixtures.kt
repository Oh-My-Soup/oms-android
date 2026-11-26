package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.CollabLink
import dev.oms.remote.protocol.api.SessionSignal
import kotlinx.serialization.json.JsonObject

/** Fixtures shared with `collab-vectors.txt`: key 00..1f, token a0..af, roomId bytes 10..1f. */
internal object Fixtures {
    val KEY = ByteArray(32) { it.toByte() }
    val TOKEN = ByteArray(16) { (0xa0 + it).toByte() }
    const val ROOM_ID = "EBESExQVFhcYGRobHB0eHw"

    fun link(roomUrl: String = "wss://relay.test/r/$ROOM_ID", writable: Boolean = true) =
        CollabLink(roomUrl, ROOM_ID, KEY, if (writable) TOKEN else null, "fixture")

    fun json(text: String): JsonObject = requireNotNull(parseJsonObject(text)) { "not a JSON object: $text" }

    fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** The `collab-vectors.txt` file generated from the TypeScript implementation. */
internal object Vectors {
    private val text: String by lazy {
        requireNotNull(Vectors::class.java.getResource("/collab-vectors.txt")) { "collab-vectors.txt missing" }.readText()
    }

    fun section(title: String): String = text.substringAfter("## $title\n").substringBefore("\n## ")

    class LinkCase(val input: String, val fields: Map<String, String>)

    val links: List<LinkCase> by lazy {
        val cases = ArrayList<LinkCase>()
        var input: String? = null
        var fields = LinkedHashMap<String, String>()
        for (line in section("Parsed links (parseCollabLink)").lines()) {
            if (line.startsWith("input : \"")) {
                input?.let { cases += LinkCase(it, fields) }
                input = line.substringAfter('"').substringBeforeLast('"')
                fields = LinkedHashMap()
            } else {
                val match = Regex("^\\s+(\\w+)\\s*: (.*)$").matchEntire(line) ?: continue
                fields[match.groupValues[1]] = match.groupValues[2]
            }
        }
        input?.let { cases += LinkCase(it, fields) }
        cases
    }

    class SealCase(val name: String, val plaintext: String, val sealed: ByteArray, val envelope: ByteArray)

    val seals: List<SealCase> by lazy {
        ("\n" + section("AES-256-GCM seal vectors (no AAD, 12B IV, 16B tag appended)"))
            .split("\n### ")
            .drop(1)
            .map { block ->
                val lines = block.lines()
                fun field(prefix: String) = lines.first { it.startsWith(prefix) }.substringAfter(": ")
                SealCase(
                    name = lines.first(),
                    plaintext = field("plaintext (UTF-8)"),
                    sealed = Fixtures.hex(field("sealed hex").substringBefore(' ')),
                    envelope = Fixtures.hex(field("envelope hex").substringBefore(' ')),
                )
            }
    }

    fun streamLine(name: String): JsonObject =
        Fixtures.json(section("applyCollabStream vector").lines().first { it.startsWith(name) }.substringAfter(": "))
}

internal class RecordingEffects : SessionEffects {
    val sent = ArrayList<JsonObject>()
    val signals = ArrayList<SessionSignal>()
    val timers = HashMap<SessionTimer, Long>()
    var shutdown = false
    var reconnects = 0

    override fun send(frame: JsonObject) {
        sent += frame
    }

    override fun signal(signal: SessionSignal) {
        signals += signal
    }

    override fun schedule(timer: SessionTimer, token: Long, delayMs: Long) {
        timers[timer] = token
    }

    override fun cancel(timer: SessionTimer) {
        timers.remove(timer)
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun reconnect() {
        reconnects++
    }
}
