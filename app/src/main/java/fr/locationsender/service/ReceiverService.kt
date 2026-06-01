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

    // Dernière position reçue (poussée en continu par la boucle de mock).
    @Volatile private var hasLocation = false
    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0
    @Volatile private var lastAcc = 0f
    @Volatile private var lastSpeedKmhIn = 0f

    // Facteur réellement appliqué, qui « rampe » doucement vers la cible.
    private var currentFactor = 1f

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

    private fun pushMock(lat: Double, lon: Double, accuracyM: Float, speedMs: Float) {
        for (p in mockProviders) {
            try {
                val loc = Location(p).apply {
                    latitude = lat
                    longitude = lon
                    accuracy = accuracyM
                    speed = speedMs
                    bearing = 0f
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
                if (json.optString("t") == Protocol.TYPE_LOCATION) {
                    val lat = json.optDouble("lat", Double.NaN)
                    val lon = json.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    val acc = json.optDouble("acc", 0.0).toFloat()
                    val spdIn = json.optDouble("spd", 0.0).toFloat()
                    // On mémorise la dernière position ; l'injection mock est faite
                    // par la boucle dédiée (qui rampe le facteur en douceur).
                    lastLat = lat
                    lastLon = lon
                    lastAcc = acc
                    lastSpeedKmhIn = spdIn
                    hasLocation = true
                    count++
                    Bus.updateReceiver {
                        it.copy(
                            lat = lat, lon = lon, accuracyM = acc,
                            speedKmhIn = spdIn,
                            packetsReceived = count, lastSenderIp = pkt.address?.hostAddress,
                        )
                    }
                }
            }
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
                val spdInKmh = lastSpeedKmhIn
                val spdOutKmh = spdInKmh * currentFactor
                pushMock(lastLat, lastLon, lastAcc, spdOutKmh / 3.6f)
                Bus.updateReceiver { it.copy(speedKmhOut = spdOutKmh) }
                // Notification rafraîchie au plus une fois par seconde.
                if (now - lastNotifNanos >= 1_000_000_000L) {
                    lastNotifNanos = now
                    updateNotif(
                        "Écoute :$port",
                        "%.5f, %.5f — %.1f→%.1f km/h".format(lastLat, lastLon, spdInKmh, spdOutKmh),
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
