package dev.oms.remote.data

import kotlinx.serialization.Serializable

/**
 * A paired computer. The raw collab link grants control of that computer's agent, so it is only
 * ever stored encrypted ([encryptedLink]); everything else is display metadata.
 */
@Serializable
data class SavedHost(
    /** Random UUID; also the AES-GCM associated data of [encryptedLink]. */
    val id: String,
    val name: String,
    val relayHost: String,
    val roomId: String,
    val canWrite: Boolean,
    /** Epoch millis of the last successful connect, or null if never connected. */
    val lastConnectedMs: Long? = null,
    /** Base64 of `IV ‖ AES-256-GCM(raw link)`; the key lives in AndroidKeyStore. */
    val encryptedLink: String,
)
