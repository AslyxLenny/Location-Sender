package fr.locationsender

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.locationsender.core.Bus
import fr.locationsender.core.SpeedControl
import fr.locationsender.net.NetUtils
import fr.locationsender.net.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.math.roundToInt

enum class Role { SENDER, RECEIVER }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ls", Context.MODE_PRIVATE)

    /** Rôle de l'appareil : null tant qu'il n'a pas été choisi en amont. */
    val role = MutableStateFlow(readRole())
    val port = MutableStateFlow(prefs.getInt("port", Protocol.DEFAULT_PORT).toString())

    /** Facteur de vitesse et activation, contrôlés depuis l'écran receiver. */
    val speedFactor = MutableStateFlow(prefs.getFloat("factor", 1f))
    val speedMockEnabled = MutableStateFlow(prefs.getBoolean("mockEnabled", false))

    /**
     * Bornes du curseur de facteur, réglables dans les paramètres ( ]0 ; 10] ).
     * On assainit les valeurs lues : max plafonné à [FACTOR_LIMIT] (un max trop
     * grand génère trop de crans et fait planter le slider), et min < max.
     */
    val factorMax = MutableStateFlow(
        prefs.getFloat("factorMax", DEFAULT_FACTOR_MAX).coerceIn(2 * STEP, FACTOR_LIMIT),
    )
    val factorMin = MutableStateFlow(
        prefs.getFloat("factorMin", DEFAULT_FACTOR_MIN).coerceIn(STEP, factorMax.value - STEP),
    )

    /** Facteur associé à chaque limitation de vitesse (preset à appliquer d'un toucher). */
    val presetFactors = MutableStateFlow(
        SPEED_LIMITS.associateWith { prefs.getFloat("preset_$it", 1f) },
    )

    /** Blocage de vitesse : force la vitesse simulée autour de [speedBlockKmh] km/h. */
    val speedBlockEnabled = MutableStateFlow(prefs.getBoolean("blockEnabled", false))
    val speedBlockKmh = MutableStateFlow(
        prefs.getFloat("blockKmh", 0f).coerceIn(0f, BLOCK_SPEED_MAX),
    )

    /** Synchronisation receiver↔receiver : interrupteur maître + aspects à partager. */
    val syncEnabled = MutableStateFlow(prefs.getBoolean("syncEnabled", false))
    val syncFactor = MutableStateFlow(prefs.getBoolean("syncFactor", true))
    val syncMockEnabled = MutableStateFlow(prefs.getBoolean("syncMock", true))
    val syncBlock = MutableStateFlow(prefs.getBoolean("syncBlock", true))

    // Signal conflaté : un glissement de curseur ne déclenche qu'une diffusion de
    // son état final. Le paquet est construit au moment de l'envoi (état à jour).
    private val controlSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        // Réaligne le facteur dans les bornes courantes au démarrage.
        speedFactor.value = speedFactor.value.coerceIn(factorMin.value, factorMax.value)
        Bus.speedFactor.value = speedFactor.value
        Bus.factorMin.value = factorMin.value
        Bus.factorMax.value = factorMax.value
        Bus.speedMockEnabled.value = speedMockEnabled.value
        Bus.speedBlockEnabled.value = speedBlockEnabled.value
        Bus.speedBlockKmh.value = speedBlockKmh.value
        Bus.syncEnabled.value = syncEnabled.value
        Bus.syncFactor.value = syncFactor.value
        Bus.syncMockEnabled.value = syncMockEnabled.value
        Bus.syncBlock.value = syncBlock.value

        startControlBroadcaster()
        collectRemoteControl()
    }

    /** Émet les contrôles sortants en broadcast UDP, au plus ~10/s (throttle). */
    private fun startControlBroadcaster() {
        viewModelScope.launch(Dispatchers.IO) {
            val socket = try {
                DatagramSocket().apply { broadcast = true }
            } catch (_: Exception) {
                null
            } ?: return@launch
            try {
                for (signal in controlSignal) {
                    // Construit le paquet à l'instant de l'envoi → toujours l'état courant.
                    val data = buildControlPacket() ?: continue
                    // On vise le port réellement écouté par le service si dispo, sinon le réglage.
                    val port = Bus.receiver.value.port.takeIf { it in 1..65535 } ?: portInt()
                    for (target in NetUtils.broadcastTargets()) {
                        try {
                            socket.send(DatagramPacket(data, data.size, target, port))
                        } catch (_: Exception) {
                        }
                    }
                    delay(100)
                }
            } finally {
                socket.close()
            }
        }
    }

    /** Construit le paquet de contrôle à partir de l'état courant, ou null si rien à partager. */
    private fun buildControlPacket(): ByteArray? {
        if (!syncEnabled.value) return null
        val shareFactor = syncFactor.value
        val shareMock = syncMockEnabled.value
        val shareBlock = syncBlock.value
        if (!shareFactor && !shareMock && !shareBlock) return null
        return Protocol.encodeControl(
            src = Bus.sessionId,
            factor = if (shareFactor) speedFactor.value else null,
            mockEnabled = if (shareMock) speedMockEnabled.value else null,
            blockEnabled = if (shareBlock) speedBlockEnabled.value else null,
            blockKmh = if (shareBlock) speedBlockKmh.value else null,
        )
    }

    /** Applique les contrôles reçus du réseau à l'UI (le service a déjà fait le mock). */
    private fun collectRemoteControl() {
        viewModelScope.launch {
            Bus.remoteControl.collect { c ->
                // Mise à jour directe des StateFlow (pas de rediffusion, pas de persistance :
                // le service s'en est déjà chargé). Le facteur est borné aux limites locales.
                c.factor?.let { speedFactor.value = it.coerceIn(factorMin.value, factorMax.value) }
                c.mockEnabled?.let { speedMockEnabled.value = it }
                c.blockEnabled?.let { speedBlockEnabled.value = it }
                c.blockKmh?.let { speedBlockKmh.value = it }
            }
        }
    }

    /** Signale une diffusion ; le paquet est construit à l'envoi (état le plus récent). */
    private fun broadcastControl() {
        if (!syncEnabled.value) return
        controlSignal.trySend(Unit)
    }

    fun setSyncEnabled(enabled: Boolean) {
        syncEnabled.value = enabled
        Bus.syncEnabled.value = enabled
        prefs.edit().putBoolean("syncEnabled", enabled).apply()
    }

    fun setSyncFactor(enabled: Boolean) {
        syncFactor.value = enabled
        Bus.syncFactor.value = enabled
        prefs.edit().putBoolean("syncFactor", enabled).apply()
    }

    fun setSyncMockEnabled(enabled: Boolean) {
        syncMockEnabled.value = enabled
        Bus.syncMockEnabled.value = enabled
        prefs.edit().putBoolean("syncMock", enabled).apply()
    }

    fun setSyncBlock(enabled: Boolean) {
        syncBlock.value = enabled
        Bus.syncBlock.value = enabled
        prefs.edit().putBoolean("syncBlock", enabled).apply()
    }

    private fun readRole(): Role? = when (prefs.getString("role", null)) {
        Role.SENDER.name -> Role.SENDER
        Role.RECEIVER.name -> Role.RECEIVER
        else -> null
    }

    fun chooseRole(r: Role) {
        role.value = r
        prefs.edit().putString("role", r.name).apply()
    }

    fun setPort(p: String) {
        port.value = p.filter { it.isDigit() }.take(5)
    }

    /** Arrondit au pas de 0,05 (à partir de la borne min) et borne à [min, max]. */
    fun setSpeedFactor(f: Float) {
        val min = factorMin.value
        val snapped = (min + ((f - min) / STEP).roundToInt() * STEP)
            .coerceIn(min, factorMax.value)
        speedFactor.value = snapped
        Bus.speedFactor.value = snapped
        prefs.edit().putFloat("factor", snapped).apply()
        broadcastControl()
    }

    fun setSpeedMockEnabled(enabled: Boolean) {
        speedMockEnabled.value = enabled
        Bus.speedMockEnabled.value = enabled
        prefs.edit().putBoolean("mockEnabled", enabled).apply()
        broadcastControl()
    }

    /**
     * Définit les deux bornes d'un coup ( min et max > 0, min < max ). On les
     * applique ensemble pour éviter tout état intermédiaire invalide.
     * Retourne false (sans rien changer) si la plage est invalide.
     */
    fun setFactorRange(min: Float, max: Float): Boolean {
        if (min <= 0f || max <= min || max > FACTOR_LIMIT) return false
        factorMin.value = min
        factorMax.value = max
        Bus.factorMin.value = min
        Bus.factorMax.value = max
        prefs.edit().putFloat("factorMin", min).putFloat("factorMax", max).apply()
        reclampFactor()
        return true
    }

    /** Définit le facteur d'une limitation ( ]0 ; FACTOR_LIMIT] ). */
    fun setPresetFactor(limit: Int, factor: Float) {
        if (factor <= 0f || factor > FACTOR_LIMIT) return
        presetFactors.value = presetFactors.value + (limit to factor)
        prefs.edit().putFloat("preset_$limit", factor).apply()
    }

    fun setSpeedBlockEnabled(enabled: Boolean) {
        speedBlockEnabled.value = enabled
        Bus.speedBlockEnabled.value = enabled
        prefs.edit().putBoolean("blockEnabled", enabled).apply()
        broadcastControl()
    }

    fun setSpeedBlockKmh(kmh: Float) {
        val c = kmh.coerceIn(0f, BLOCK_SPEED_MAX)
        speedBlockKmh.value = c
        Bus.speedBlockKmh.value = c
        prefs.edit().putFloat("blockKmh", c).apply()
        broadcastControl()
    }

    /** Applique le preset d'une limitation : active le mock et fixe le facteur. */
    fun applyPreset(limit: Int) {
        val f = presetFactors.value[limit] ?: return
        setSpeedMockEnabled(true)
        applyFactor(f)
    }

    /** Fixe le facteur exact (borné à [min, max], sans arrondi au pas). */
    private fun applyFactor(f: Float) {
        val clamped = f.coerceIn(factorMin.value, factorMax.value)
        speedFactor.value = clamped
        Bus.speedFactor.value = clamped
        prefs.edit().putFloat("factor", clamped).apply()
        broadcastControl()
    }

    private fun reclampFactor() {
        val clamped = speedFactor.value.coerceIn(factorMin.value, factorMax.value)
        if (clamped != speedFactor.value) {
            speedFactor.value = clamped
            Bus.speedFactor.value = clamped
            prefs.edit().putFloat("factor", clamped).apply()
        }
    }

    fun portInt(): Int = port.value.toIntOrNull()?.coerceIn(1, 65535) ?: Protocol.DEFAULT_PORT

    fun persist() {
        prefs.edit().putInt("port", portInt()).apply()
    }

    companion object {
        const val STEP = 0.05f
        const val DEFAULT_FACTOR_MIN = 0.05f
        const val DEFAULT_FACTOR_MAX = 1f

        /** Plafond du facteur max : au-delà, le slider aurait trop de crans. */
        const val FACTOR_LIMIT = 10f

        /** Limitations de vitesse (km/h) proposées en presets. */
        val SPEED_LIMITS = listOf(30, 50, 70, 80, 90, 110, 130)

        /** Plafond de la vitesse de blocage (km/h). */
        const val BLOCK_SPEED_MAX = 300f

        /** Formate un facteur sans zéros inutiles : 1.00 → "1", 2.50 → "2,5". */
        fun format(f: Float): String {
            var s = "%.2f".format(f)
            if ('.' in s || ',' in s) s = s.trimEnd('0').trimEnd('.', ',')
            return s
        }
    }
}
