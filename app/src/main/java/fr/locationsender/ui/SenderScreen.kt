package fr.locationsender.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.locationsender.MainViewModel
import fr.locationsender.core.Bus
import fr.locationsender.service.SenderService
import fr.locationsender.util.Perms

@Composable
fun SenderScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by Bus.sender.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.persist()
            SenderService.start(context, vm.portInt())
        }
    }

    fun start() {
        if (Perms.hasLocation(context)) {
            vm.persist()
            SenderService.start(context, vm.portInt())
        } else {
            launcher.launch(Perms.needed)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusHero(
            active = state.running,
            activeTitle = "Diffusion en cours",
            idleTitle = "En attente",
            subtitle = if (state.running) {
                "Broadcast sur le réseau · port ${state.port}"
            } else {
                "Appuyez sur Démarrer pour diffuser votre position"
            },
        )

        Spacer(Modifier.height(4.dp))

        if (!state.running) {
            Button(
                onClick = { start() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Démarrer la diffusion")
            }
        } else {
            Button(
                onClick = { SenderService.stop(context) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Arrêter la diffusion")
            }
        }

        SectionCard("Position diffusée", icon = Icons.AutoMirrored.Filled.Send) {
            LabeledValue("Fix GPS", if (state.hasFix) "Oui" else "En attente…")
            LabeledValue("Latitude", state.lat?.let { "%.6f".format(it) } ?: "—")
            LabeledValue("Longitude", state.lon?.let { "%.6f".format(it) } ?: "—")
            LabeledValue("Précision", state.accuracyM?.let { "%.1f m".format(it) } ?: "—")
            LabeledValue("Vitesse", state.speedKmh?.let { "%.1f km/h".format(it) } ?: "—")
            LabeledValue("Paquets envoyés", state.packetsSent.toString())
        }

        state.lastError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
