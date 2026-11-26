package dev.oms.remote.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.oms.remote.protocol.api.CollabLink
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Saved hosts, persisted as JSON in private SharedPreferences. Raw links are sealed with [LinkCipher]
 * (key alias `oms-remote-links`) and are never logged. Thread-safe; writes are applied asynchronously.
 */
class LinkStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = LinkCipher(KEY_ALIAS)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<SavedHost>> = _hosts.asStateFlow()

    operator fun get(id: String): SavedHost? = _hosts.value.firstOrNull { it.id == id }

    /**
     * Saves [link]. A host with the same room keeps its id (and its name unless [name] is set) but
     * takes the new link. A blank [name] defaults to the relay host. The entry moves to the top.
     */
    fun upsert(link: CollabLink, name: String): SavedHost = synchronized(lock) {
        val current = _hosts.value
        val existing = current.firstOrNull { it.roomId == link.roomId }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val host = SavedHost(
            id = id,
            name = name.trim().ifEmpty { existing?.name ?: link.relayHost },
            relayHost = link.relayHost,
            roomId = link.roomId,
            canWrite = link.canWrite,
            lastConnectedMs = existing?.lastConnectedMs,
            encryptedLink = cipher.encrypt(link.raw, associatedData = id),
        )
        commit(listOf(host) + current.filterNot { it.id == id })
        host
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.relayHost }) }

    fun markConnected(id: String, atMs: Long) = update(id) { it.copy(lastConnectedMs = atMs) }

    fun delete(id: String) = synchronized(lock) {
        commit(_hosts.value.filterNot { it.id == id })
    }

    /** The decrypted raw link. @throws java.security.GeneralSecurityException if it can't be unsealed. */
    fun rawLink(host: SavedHost): String = cipher.decrypt(host.encryptedLink, associatedData = host.id)

    private fun update(id: String, change: (SavedHost) -> SavedHost) = synchronized(lock) {
        val current = _hosts.value
        if (current.none { it.id == id }) return@synchronized
        commit(current.map { if (it.id == id) change(it) else it })
    }

    private fun commit(hosts: List<SavedHost>) {
        _hosts.value = hosts
        prefs.edit { putString(KEY_HOSTS, json.encodeToString(hosts)) }
    }

    private fun load(): List<SavedHost> {
        val text = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SavedHost>>(text)
        } catch (e: SerializationException) {
            Log.w(TAG, "Saved hosts are unreadable; starting empty (${e.javaClass.simpleName})")
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Saved hosts are unreadable; starting empty (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    private companion object {
        const val TAG = "LinkStore"
        /** Excluded from backup/device transfer in res/xml/data_extraction_rules.xml (the key can't follow). */
        const val PREFS_NAME = "oms_hosts"
        const val KEY_HOSTS = "hosts"
        const val KEY_ALIAS = "oms-remote-links"
    }
}
