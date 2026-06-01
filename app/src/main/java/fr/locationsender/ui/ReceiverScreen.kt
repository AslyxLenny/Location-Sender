package fr.locationsender.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.locationsender.MainViewModel
import fr.locationsender.core.Bus
import fr.locationsender.core.ReceiverState
import fr.locationsender.service.ReceiverService
import fr.locationsender.util.Perms

@Composable
fun ReceiverScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by Bus.receiver.collectAsState()
    val factor by vm.speedFactor.collectAsState()
    val mockEnabled by vm.speedMockEnabled.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.persist()
            ReceiverService.start(context, vm.portInt())
        }
    }

    fun start() {
        if (Perms.hasLocation(context)) {
            vm.persist()
            ReceiverService.start(context, vm.portInt())
        } else {
            launcher.launch(Perms.needed)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Deux colonnes sur grand écran (tablette / paysage), une seule sinon.
        val twoColumns = maxWidth >= 600.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusHero(
                active = state.running && state.mockActive,
                activeTitle = "Position fictive active",
                idleTitle = if (state.running) "Écoute (mock inactif)" else "Arrêté",
                subtitle = when {
                    state.running && state.mockActive -> "Écoute sur le port ${state.port}"
                    state.running -> "En écoute — autorisez la position fictive"
                    else -> "Appuyez sur Démarrer pour écouter le réseau"
                },
                accent = MaterialTheme.colorScheme.secondary,
            )

            if (state.running && !state.mockActive) {
                MockWarningCard(context)
            }

            Spacer(Modifier.height(4.dp))

            if (!state.running) {
                Button(
                    onClick = { start() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Démarrer l'écoute")
                }
            } else {
                Button(
                    onClick = { ReceiverService.stop(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Arrêter l'écoute")
                }
            }

            if (twoColumns) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SpeedCard(vm, factor, mockEnabled, Modifier.weight(1f))
                    PositionCard(state, Modifier.weight(1f))
                }
            } else {
                SpeedCard(vm, factor, mockEnabled)
                PositionCard(state)
            }

            TextButton(onClick = { openDeveloperSettings(context) }) {
                Text("Ouvrir les Options pour développeurs")
            }
            Text(
                "Dans Options développeur → « Application de position fictive », sélectionnez Location Sender.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.lastError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SpeedCard(
    vm: MainViewModel,
    factor: Float,
    mockEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard("Vitesse simulée", modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Appliquer un facteur")
                Text(
                    if (mockEnabled) {
                        "Vitesse reçue × %.2f".format(factor)
                    } else {
                        "Désactivé — vitesse réelle"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = mockEnabled, onCheckedChange = vm::setSpeedMockEnabled)
        }

        Slider(
            value = factor,
            onValueChange = vm::setSpeedFactor,
            valueRange = 0f..1f,
            steps = 19,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("×0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("× %.2f".format(factor), fontWeight = FontWeight.Bold)
            Text("×1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "L'activation et la désactivation sont progressives.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PositionCard(
    state: ReceiverState,
    modifier: Modifier = Modifier,
) {
    SectionCard("Position reçue", modifier = modifier, icon = Icons.Default.Place) {
        LabeledValue("Mock GPS", if (state.mockActive) "Actif" else "Inactif")
        LabeledValue("Émetteur", state.lastSenderIp ?: "—")
        LabeledValue("Latitude", state.lat?.let { "%.6f".format(it) } ?: "—")
        LabeledValue("Longitude", state.lon?.let { "%.6f".format(it) } ?: "—")
        LabeledValue("Précision", state.accuracyM?.let { "%.1f m".format(it) } ?: "—")
        LabeledValue("Vitesse reçue", state.speedKmhIn?.let { "%.1f km/h".format(it) } ?: "—")
        LabeledValue("Vitesse mockée", state.speedKmhOut?.let { "%.1f km/h".format(it) } ?: "—")
        LabeledValue("Paquets reçus", state.packetsReceived.toString())
    }
}

@Composable
private fun MockWarningCard(context: Context) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Position fictive non autorisée",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Sélectionnez cette app comme « application de position fictive » dans les Options pour développeurs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = { openDeveloperSettings(context) }) {
                Text("Ouvrir les réglages")
            }
        }
    }
}

private fun openDeveloperSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
