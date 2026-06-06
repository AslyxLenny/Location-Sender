package fr.locationsender.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import fr.locationsender.core.Bus
import fr.locationsender.core.SpeedControl
import fr.locationsender.net.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import org.json.JSONObject

/**
 * Service foreground receiver : écoute l'UDP, applique un facteur de vitesse
 * (avec transition progressive à l'activation/désactivation), puis injecte la
 * position via le Mock Location d'Android.
 */
class ReceiverService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var mockJob: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var port: Int = Protocol.DEFAULT_PORT

    private lateinit var locationManager: LocationManager
    private val mockProviders = ArrayList<String>()
    private val prefs by lazy { getSharedPreferences("ls", Context.MODE_PRIVATE) }

    // Dernier fix reçu, servant de base au dead-reckoning de la boucle de mock.
    @Volatile private var hasLocation = false
    @Volatile private var baseLat = 0.0
    @Volatile private var baseLon = 0.0
    @Volatile private var baseAcc = 0f
    @Volatile private var baseSpeedKmh = 0f
    @Volatile private var baseBearingDeg = 0f
    @Volatile private var baseTimeNanos = 0L

    // Vitesse mesurée entre les deux derniers fixes (degrés/s) : sert à
    // extrapoler la position en continu, sans dépendre du cap transmis.
    @Volatile private var velLatPerSec = 0.0
    @Volatile private var velLonPerSec = 0.0

    // Facteur réellement appliqué, qui « rampe » doucement vers la cible.
    private var currentFactor = 1f

    // Petite oscillation (marche aléatoire bornée [0,1] km/h) pour que la vitesse
    // bloquée ne soit jamais une constante parfaite.
    private var speedJitter = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        port = (intent?.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT) ?: Protocol.DEFAULT_PORT)
            .let { if (it in 1..65535) it else Protocol.DEFAULT_PORT }

        try {
            startForeground(
                Notifications.RECEIVER_NOTIF_ID,
                Notifications.build(this, "Écoute de position", "Port $port"),
            )
        } catch (e: Exception) {
            Bus.updateReceiver { it.copy(running = false, lastError = "Service : ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }

        Bus.updateReceiver {
            it.copy(running = true, port = port, packetsReceived = 0, lastError = null)
        }
        acquireMulticastLock()
        setupMockProviders()
        startReceiveLoop()
        startMockLoop()
        return START_STICKY
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ls_discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun setupMockProviders() {
        mockProviders.clear()
        val props = ProviderProperties.Builder()
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build()
        var anyOk = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                try {
                    locationManager.removeTestProvider(p)
                } catch (_: Exception) {
                }
                locationManager.addTestProvider(p, props)
                locationManager.setTestProviderEnabled(p, true)
                mockProviders.add(p)
                anyOk = true
            } catch (_: SecurityException) {
                Bus.updateReceiver {
                    it.copy(lastError = "Activez l'app comme « position fictive » (Options développeur)")
                }
            } catch (_: Exception) {
            }
        }
        Bus.updateReceiver { it.copy(mockActive = anyOk) }
    }

    private fun pushMock(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        speedMs: Float,
        bearingDeg: Float,
    ) {
        // Plancher de précision : une précision à 0 est rejetée/déclassée par
        // certaines apps ; on garantit une valeur plausible.
        val safeAccuracy = if (accuracyM > 1f) accuracyM else 5f
        for (p in mockProviders) {
            try {
                val loc = Location(p).apply {
                    latitude = lat
                    longitude = lon
                    accuracy = safeAccuracy
                    speed = speedMs
                    bearing = bearingDeg
                    altitude = 0.0
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        speedAccuracyMetersPerSecond = 1f
                        bearingAccuracyDegrees = 1f
                        verticalAccuracyMeters = 1f
                    }
                }
                locationManager.setTestProviderLocation(p, loc)
            } catch (e: Exception) {
                Bus.updateReceiver { it.copy(lastError = "Mock : ${e.message}") }
            }
        }
    }

    /**
     * Ouvre le socket d'écoute lié au port. On tente d'abord la liaison
     * explicite (avec SO_REUSEADDR pour permettre un redémarrage rapide), et on
     * se rabat sur la liaison directe si l'idiome unbound échoue selon la
     * version d'Android.
     */
    private fun openReceiveSocket(port: Int): DatagramSocket =
        try {
            DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
            }
        } catch (_: Exception) {
            DatagramSocket(port)
        }

    private fun startReceiveLoop() {
        job?.cancel()
        job = scope.launch {
            socket = try {
                openReceiveSocket(port)
            } catch (e: Exception) {
                Bus.updateReceiver { it.copy(lastError = "Port $port indisponible : ${e.message}") }
                stopSelf()
                return@launch
            }
            val buf = ByteArray(2048)
            var count = 0L
            while (isActive) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket?.receive(pkt)
                } catch (e: Exception) {
                    if (isActive) Bus.updateReceiver { it.copy(lastError = "Réception : ${e.message}") }
                    break
                }
                val json = Protocol.parse(pkt.data, pkt.length) ?: continue
                if (json.optString("t") == Protocol.TYPE_CONTROL) {
                    handleControl(json)
                    continue
                }
                if (json.optString("t") == Protocol.TYPE_LOCATION) {
                    val lat = json.optDouble("lat", Double.NaN)
                    val lon = json.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    val acc = json.optDouble("acc", 0.0).toFloat()
                    val spdIn = json.optDouble("spd", 0.0).toFloat()
                    val brg = json.optDouble("brg", 0.0).toFloat()
                    // Nouveau fix = nouvelle base pour le dead-reckoning. On
                    // mesure la vitesse réelle (deg/s) depuis le fix précédent
                    // avant d'écraser la base ; l'injection est faite par la
                    // boucle dédiée.
                    val nowFix = SystemClock.elapsedRealtimeNanos()
                    if (hasLocation) {
                        val dtFix = (nowFix - baseTimeNanos) / 1_000_000_000.0
                        if (dtFix > 0.05) {
                            velLatPerSec = (lat - baseLat) / dtFix
                            velLonPerSec = (lon - baseLon) / dtFix
                        }
                    }
                    baseLat = lat
                    baseLon = lon
                    baseAcc = acc
                    baseSpeedKmh = spdIn
                    baseBearingDeg = brg
                    baseTimeNanos = nowFix
                    hasLocation = true
                    count++
                    Bus.updateReceiver {
                        it.copy(
                            lat = lat, lon = lon, accuracyM = acc,
                            speedKmhIn = spdIn, bearingDeg = brg,
                            packetsReceived = count, lastSenderIp = pkt.address?.hostAddress,
                        )
                    }
                }
            }
        }
    }

    /**
     * Applique un contrôle de synchronisation reçu d'un autre receiver : met à
     * jour le mock (Bus) et la persistance, puis notifie l'UI via Bus.remoteControl.
     * On ignore l'écho de sa propre diffusion et on ne rediffuse jamais (pas de boucle).
     */
    private fun handleControl(json: JSONObject) {
        if (!Bus.syncEnabled.value) return
        if (json.optInt("src", 0) == Bus.sessionId) return

        val editor = prefs.edit()
        var factor: Float? = null
        var mock: Boolean? = null
        var blockEnabled: Boolean? = null
        var blockKmh: Float? = null

        if (json.has("factor") && Bus.syncFactor.value) {
            // Borné aux limites locales (un facteur hors plage casserait l'UI/le mock).
            val f = json.optDouble("factor").toFloat()
                .coerceIn(Bus.factorMin.value, Bus.factorMax.value)
            Bus.speedFactor.value = f
            editor.putFloat("factor", f)
            factor = f
        }
        if (json.has("mock") && Bus.syncMockEnabled.value) {
            val m = json.optBoolean("mock")
            Bus.speedMockEnabled.value = m
            editor.putBoolean("mockEnabled", m)
            mock = m
        }
        // On exige block ET blockKmh : un paquet tronqué ne doit pas brider à 0.
        if (json.has("block") && json.has("blockKmh") && Bus.syncBlock.value) {
            val b = json.optBoolean("block")
            val bk = json.optDouble("blockKmh", 0.0).toFloat()
            // La valeur AVANT le drapeau : si la boucle de mock lit entre les deux,
            // elle voit au pire l'ancien état (drapeau off), jamais une valeur incohérente.
            Bus.speedBlockKmh.value = bk
            Bus.speedBlockEnabled.value = b
            editor.putBoolean("blockEnabled", b).putFloat("blockKmh", bk)
            blockEnabled = b
            blockKmh = bk
        }

        if (factor != null || mock != null || blockEnabled != null) {
            editor.apply()
            Bus.remoteControl.tryEmit(SpeedControl(factor, mock, blockEnabled, blockKmh))
        }
    }

    /**
     * Boucle d'injection du mock à fréquence fixe. Le facteur appliqué rampe
     * progressivement vers sa cible (facteur du curseur si activé, sinon 1),
     * pour que l'activation/désactivation ne change pas la vitesse d'un coup.
     */
    private fun startMockLoop() {
        mockJob?.cancel()
        mockJob = scope.launch {
            currentFactor = targetFactor()
            var lastNanos = SystemClock.elapsedRealtimeNanos()
            var lastNotifNanos = 0L
            while (isActive) {
                delay(MOCK_INTERVAL_MS)
                val now = SystemClock.elapsedRealtimeNanos()
                val dt = (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                currentFactor = approach(currentFactor, targetFactor(), RAMP_PER_SEC * dt)
                if (!hasLocation) continue

                // Dead-reckoning : on prolonge la position depuis le dernier fix
                // selon la vitesse mesurée. L'âge est borné pour figer la
                // position si les paquets s'arrêtent (pas de dérive infinie).
                val age = min((now - baseTimeNanos) / 1_000_000_000.0, MAX_EXTRAPOLATION_SEC)
                val predLat = baseLat + velLatPerSec * age
                val predLon = baseLon + velLonPerSec * age

                val factored = baseSpeedKmh * currentFactor
                val spdOutKmh = if (Bus.speedBlockEnabled.value && factored > Bus.speedBlockKmh.value) {
                    // Au-dessus du plafond → on bride à la vitesse bloquée, avec
                    // une marche aléatoire douce [0,1] km/h (réflexion aux bornes)
                    // pour ne jamais afficher une constante parfaite.
                    var j = speedJitter + (Random.nextFloat() - 0.5f) * 0.15f
                    if (j < 0f) j = -j
                    if (j > 1f) j = 2f - j
                    speedJitter = j
                    Bus.speedBlockKmh.value + j
                } else {
                    // En dessous du plafond (ou blocage off) : le facteur/preset s'applique.
                    factored
                }
                pushMock(predLat, predLon, baseAcc, spdOutKmh / 3.6f, baseBearingDeg)
                Bus.updateReceiver { it.copy(speedKmhOut = spdOutKmh) }
                // Notification rafraîchie au plus une fois par seconde.
                if (now - lastNotifNanos >= 1_000_000_000L) {
                    lastNotifNanos = now
                    updateNotif(
                        "Écoute :$port",
                        "%.5f, %.5f — %.1f→%.1f km/h".format(predLat, predLon, baseSpeedKmh, spdOutKmh),
                    )
                }
            }
        }
    }

    /** Cible du facteur : le curseur si la transformation est active, sinon 1. */
    private fun targetFactor(): Float =
        if (Bus.speedMockEnabled.value) Bus.speedFactor.value else 1f

    /** Rapproche [current] de [target] d'au plus [maxDelta]. */
    private fun approach(current: Float, target: Float, maxDelta: Float): Float {
        val diff = target - current
        return when {
            abs(diff) <= maxDelta -> target
            diff > 0 -> current + maxDelta
            else -> current - maxDelta
        }
    }

    private fun updateNotif(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.RECEIVER_NOTIF_ID, Notifications.build(this, title, text))
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        mockJob?.cancel()
        scope.cancel()
        socket?.close()
        for (p in mockProviders) {
            try {
                locationManager.setTestProviderEnabled(p, false)
            } catch (_: Exception) {
            }
            try {
                locationManager.removeTestProvider(p)
            } catch (_: Exception) {
            }
        }
        mockProviders.clear()
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        Bus.updateReceiver { it.copy(running = false, mockActive = false) }
    }

    companion object {
        const val ACTION_STOP = "fr.locationsender.STOP_RECEIVER"
        const val EXTRA_PORT = "port"

        // Injection du mock à 5 Hz ; le facteur évolue de 0,06/s
        // (soit 5 s pour passer de 0,7 à 1,0 ; ~16 s pour parcourir 0→1).
        private const val MOCK_INTERVAL_MS = 200L
        private const val RAMP_PER_SEC = 0.06f

        // Dead-reckoning : durée max d'extrapolation sans nouveau fix (au-delà,
        // la position se fige au lieu de dériver à l'infini).
        private const val MAX_EXTRAPOLATION_SEC = 2.0

        fun start(context: Context, port: Int) {
            val intent = Intent(context, ReceiverService::class.java).apply {
                putExtra(EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ReceiverService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
