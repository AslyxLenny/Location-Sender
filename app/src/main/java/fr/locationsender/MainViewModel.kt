package fr.locationsender

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import fr.locationsender.core.Bus
import fr.locationsender.net.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

enum class Role { SENDER, RECEIVER }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ls", Context.MODE_PRIVATE)

    /** Rôle de l'appareil : null tant qu'il n'a pas été choisi en amont. */
    val role = MutableStateFlow(readRole())
    val port = MutableStateFlow(prefs.getInt("port", Protocol.DEFAULT_PORT).toString())

    /** Facteur de vitesse (0..1) et activation, contrôlés depuis l'écran receiver. */
    val speedFactor = MutableStateFlow(prefs.getFloat("factor", 1f))
    val speedMockEnabled = MutableStateFlow(prefs.getBoolean("mockEnabled", false))

    init {
        Bus.speedFactor.value = speedFactor.value
        Bus.speedMockEnabled.value = speedMockEnabled.value
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

    /** Arrondit au pas de 0,05 et borne à [0, 1]. */
    fun setSpeedFactor(f: Float) {
        val snapped = ((f / STEP).roundToInt() * STEP).coerceIn(0f, 1f)
        speedFactor.value = snapped
        Bus.speedFactor.value = snapped
        prefs.edit().putFloat("factor", snapped).apply()
    }

    fun setSpeedMockEnabled(enabled: Boolean) {
        speedMockEnabled.value = enabled
        Bus.speedMockEnabled.value = enabled
        prefs.edit().putBoolean("mockEnabled", enabled).apply()
    }

    fun portInt(): Int = port.value.toIntOrNull()?.coerceIn(1, 65535) ?: Protocol.DEFAULT_PORT

    fun persist() {
        prefs.edit().putInt("port", portInt()).apply()
    }

    companion object {
        const val STEP = 0.05f
    }
}
