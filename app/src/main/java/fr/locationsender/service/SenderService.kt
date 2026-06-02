package fr.locationsender.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import fr.locationsender.core.Bus
import fr.locationsender.net.NetUtils
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

/**
 * Service foreground émetteur : lit le GPS réel et diffuse la position en UDP
 * broadcast une fois par seconde. Aucun receiver à cibler — tout appareil à
 * l'écoute sur le même port reçoit la position.
 */
class SenderService : Service(), LocationListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var socket: DatagramSocket? = null

    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastGpsElapsedMs = 0L
    private var port: Int = Protocol.DEFAULT_PORT

    private lateinit var locationManager: LocationManager

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
        port = intent?.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT) ?: Protocol.DEFAULT_PORT

        try {
            startForeground(
                Notifications.SENDER_NOTIF_ID,
                Notifications.build(this, "Diffusion de position", "Broadcast · port $port"),
            )
        } catch (e: Exception) {
            Bus.updateSender { it.copy(running = false, lastError = "Service : ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }

        Bus.updateSender {
            it.copy(running = true, port = port, packetsSent = 0, lastError = null)
        }
        startLocationUpdates()
        startSendLoop()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Bus.updateSender { it.copy(lastError = "Permission de localisation refusée") }
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            try {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.getLastKnownLocation(p)?.let {
                        if (lastLocation == null) lastLocation = it
                    }
                    // Cadence maximale (le matériel plafonne en général à ~1 Hz) :
                    // le receiver lisse ensuite par dead-reckoning.
                    locationManager.requestLocationUpdates(p, 0L, 0f, this)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startSendLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            socket = try {
                DatagramSocket().apply { broadcast = true }
            } catch (e: Exception) {
                Bus.updateSender { it.copy(lastError = "Socket : ${e.message}") }
                stopSelf()
                return@launch
            }
            var count = 0L
            while (isActive) {
                val loc = lastLocation
                if (loc != null) {
                    val speedKmh = loc.speed * 3.6f
                    val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
                    val bearing = if (loc.hasBearing()) loc.bearing else 0f
                    val data = Protocol.encodeLocation(loc.latitude, loc.longitude, acc, speedKmh, bearing)
                    // Diffusion sur toutes les cibles de broadcast disponibles
                    // (recalculées à chaque envoi pour suivre les changements de réseau).
                    var sentToAny = false
                    for (target in NetUtils.broadcastTargets()) {
                        try {
                            socket?.send(DatagramPacket(data, data.size, target, port))
                            sentToAny = true
                        } catch (_: Exception) {
                        }
                    }
                    if (sentToAny) {
                        count++
                        Bus.updateSender {
                            it.copy(
                                lat = loc.latitude, lon = loc.longitude,
                                accuracyM = acc, speedKmh = speedKmh,
                                packetsSent = count, hasFix = true, lastError = null,
                            )
                        }
                        updateNotif(
                            "Diffusion · port $port",
                            "Lat %.5f  Lon %.5f  %.1f km/h".format(loc.latitude, loc.longitude, speedKmh),
                        )
                    } else {
                        Bus.updateSender { it.copy(lastError = "Aucun réseau pour diffuser") }
                    }
                }
                delay(SEND_INTERVAL_MS)
            }
        }
    }

    private fun updateNotif(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.SENDER_NOTIF_ID, Notifications.build(this, title, text))
    }

    override fun onLocationChanged(location: Location) {
        // On privilégie le GPS (cap + vitesse + meilleure précision). Un fix
        // réseau n'est retenu que si le GPS est silencieux depuis un moment.
        val now = SystemClock.elapsedRealtime()
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsElapsedMs = now
            lastLocation = location
        } else if (now - lastGpsElapsedMs > GPS_STALE_MS) {
            lastLocation = location
        }
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        scope.cancel()
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        socket?.close()
        Bus.updateSender { it.copy(running = false) }
    }

    companion object {
        const val ACTION_STOP = "fr.locationsender.STOP_SENDER"
        const val EXTRA_PORT = "port"

        // Diffusion à 2 Hz : recalages plus fréquents pour le dead-reckoning.
        private const val SEND_INTERVAL_MS = 500L

        // Délai au-delà duquel on accepte un fix réseau faute de GPS récent.
        private const val GPS_STALE_MS = 5_000L

        fun start(context: Context, port: Int) {
            val intent = Intent(context, SenderService::class.java).apply {
                putExtra(EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SenderService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
