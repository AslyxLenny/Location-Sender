package fr.locationsender.net

import java.net.InetAddress
import java.net.NetworkInterface

/** Utilitaires réseau partagés (adresses de broadcast). */
object NetUtils {
    /**
     * Cibles de diffusion : l'adresse de broadcast de chaque interface active
     * (ex. 192.168.1.255) plus le broadcast limité 255.255.255.255 en secours.
     */
    fun broadcastTargets(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.interfaceAddresses) {
                    addr.broadcast?.let { result.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return result.toList()
    }
}
