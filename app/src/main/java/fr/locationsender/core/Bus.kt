package fr.locationsender.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** État live de l'émetteur, observé par l'UI. */
data class SenderState(
    val running: Boolean = false,
    val port: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val speedKmh: Float? = null,
    val packetsSent: Long = 0,
    val hasFix: Boolean = false,
    val lastError: String? = null,
)

/** État live du receiver, observé par l'UI. */
data class ReceiverState(
    val running: Boolean = false,
    val port: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val speedKmhIn: Float? = null,
    val speedKmhOut: Float? = null,
    val bearingDeg: Float? = null,
    val packetsReceived: Long = 0,
    val lastSenderIp: String? = null,
    val mockActive: Boolean = false,
    val lastError: String? = null,
)

/**
 * Bus d'état partagé entre les services (foreground) et l'UI Compose.
 * Tout vit dans le même process : les services écrivent, l'UI collecte.
 */
object Bus {
    private val _sender = MutableStateFlow(SenderState())
    val sender: StateFlow<SenderState> = _sender.asStateFlow()

    private val _receiver = MutableStateFlow(ReceiverState())
    val receiver: StateFlow<ReceiverState> = _receiver.asStateFlow()

    /** Facteur multiplicateur de vitesse (0..1), ajustable en direct par l'UI. */
    val speedFactor = MutableStateFlow(1f)

    /** Si false, la vitesse reçue est appliquée telle quelle (facteur effectif = 1). */
    val speedMockEnabled = MutableStateFlow(false)

    fun updateSender(block: (SenderState) -> SenderState) {
        _sender.value = block(_sender.value)
    }

    fun updateReceiver(block: (ReceiverState) -> ReceiverState) {
        _receiver.value = block(_receiver.value)
    }
}
