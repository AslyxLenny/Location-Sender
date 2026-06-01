package fr.locationsender.net

import org.json.JSONObject

/**
 * Protocole réseau (UDP) entre l'émetteur et le receiver.
 *
 * L'émetteur diffuse en broadcast un message "loc" sur le réseau local ; tout
 * receiver à l'écoute sur le même port le reçoit — aucune IP à saisir.
 *
 * La vitesse circule en km/h sur le réseau ; la conversion en m/s pour le
 * Mock Location est faite côté receiver.
 */
object Protocol {
    const val DEFAULT_PORT = 8080

    const val TYPE_LOCATION = "loc"

    fun encodeLocation(lat: Double, lon: Double, accuracyM: Float, speedKmh: Float): ByteArray =
        JSONObject()
            .put("t", TYPE_LOCATION)
            .put("lat", lat)
            .put("lon", lon)
            .put("acc", accuracyM.toDouble())
            .put("spd", speedKmh.toDouble())
            .toString()
            .toByteArray(Charsets.UTF_8)

    /** Parse un datagramme reçu, ou null s'il n'est pas un JSON valide. */
    fun parse(bytes: ByteArray, length: Int): JSONObject? =
        try {
            JSONObject(String(bytes, 0, length, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
}
