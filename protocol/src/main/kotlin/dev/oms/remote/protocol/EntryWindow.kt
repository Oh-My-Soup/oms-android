package dev.oms.remote.protocol

import kotlinx.serialization.json.JsonObject

/**
 * The loaded transcript window: entries oldest first, unique by id, bounded.
 *
 * Live appends keep the window at [liveCap] entries (or at the size reached by explicit
 * `loadOlder` pages, so reading history is not undone by every new live entry), dropping the
 * oldest. Nothing ever grows past [hardCap].
 */
internal class EntryWindow(private val liveCap: Int = LIVE_CAP, private val hardCap: Int = HARD_CAP) {
    private val list = ArrayDeque<JsonObject>()
    private val ids = HashSet<String>()
    private var cap = liveCap

    val entries: List<JsonObject> get() = list

    val size: Int get() = list.size

    fun firstId(): String? = list.firstOrNull()?.entryId()

    operator fun contains(id: String): Boolean = id in ids

    /** Replaces everything (snapshot). Returns true when old entries had to be dropped. */
    fun replace(entries: List<JsonObject>): Boolean {
        list.clear()
        ids.clear()
        cap = liveCap
        for (entry in entries) {
            if (ids.add(entry.entryId())) list.addLast(entry)
        }
        return trimOldest()
    }

    /** Appends a live entry. Null for a duplicate, else whether old entries were dropped. */
    fun append(entry: JsonObject): Boolean? {
        if (!ids.add(entry.entryId())) return null
        list.addLast(entry)
        return trimOldest()
    }

    /** Prepends an older page (oldest first). Returns whether entries had to be dropped. */
    fun prepend(entries: List<JsonObject>): Boolean {
        for (entry in entries.asReversed()) {
            if (ids.add(entry.entryId())) list.addFirst(entry)
        }
        cap = list.size.coerceIn(liveCap, hardCap)
        return trimOldest()
    }

    private fun trimOldest(): Boolean {
        var dropped = false
        while (list.size > cap) {
            ids.remove(list.removeFirst().entryId())
            dropped = true
        }
        return dropped
    }

    companion object {
        const val LIVE_CAP = 600
        const val HARD_CAP = 3000
    }
}
