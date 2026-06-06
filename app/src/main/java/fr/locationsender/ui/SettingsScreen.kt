package fr.locationsender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.locationsender.MainViewModel
import fr.locationsender.Role
import fr.locationsender.service.ReceiverService
import fr.locationsender.service.SenderService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val role by vm.role.collectAsState()
    val port by vm.port.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.persist()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionCard("Rôle de l'appareil") {
                Text(
                    "Changer de rôle arrête le service en cours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == Role.SENDER,
                        onClick = {
                            if (role != Role.SENDER) {
                                SenderService.stop(context)
                                ReceiverService.stop(context)
                                vm.chooseRole(Role.SENDER)
                            }
                        },
                        label = { Text("Émetteur") },
                    )
                    FilterChip(
                        selected = role == Role.RECEIVER,
                        onClick = {
                            if (role != Role.RECEIVER) {
                                SenderService.stop(context)
                                ReceiverService.stop(context)
                                vm.chooseRole(Role.RECEIVER)
                            }
                        },
                        label = { Text("Receiver") },
                    )
                }
            }

            SectionCard("Réseau") {
                OutlinedTextField(
                    value = port,
                    onValueChange = vm::setPort,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "L'émetteur et le receiver doivent utiliser le même port.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FactorRangeCard(vm)

            FactorPresetsCard(vm)

            SpeedBlockCard(vm)

            SyncCard(vm)

            Text(
                "Le facteur lui-même se règle directement sur l'écran Receiver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun FactorPresetsCard(vm: MainViewModel) {
    SectionCard("Facteur par limitation") {
        Text(
            "Facteur appliqué d'un toucher sur la limitation, sur l'écran Receiver " +
                "( ]0 ; ${MainViewModel.format(MainViewModel.FACTOR_LIMIT)}] ).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MainViewModel.SPEED_LIMITS.forEach { limit ->
            PresetFactorRow(vm, limit)
        }
    }
}

@Composable
private fun SyncCard(vm: MainViewModel) {
    val enabled by vm.syncEnabled.collectAsState()
    val syncFactor by vm.syncFactor.collectAsState()
    val syncMock by vm.syncMockEnabled.collectAsState()
    val syncBlock by vm.syncBlock.collectAsState()

    SectionCard("Synchronisation des receivers") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Synchroniser les receivers")
                Text(
                    "Touchez un preset ou réglez la vitesse sur un appareil → les autres " +
                        "receivers (sync activée, même réseau) appliquent la même chose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = vm::setSyncEnabled)
        }
        if (enabled) {
            Text(
                "Aspects synchronisés :",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SyncToggleRow("Facteur / presets", syncFactor, vm::setSyncFactor)
            SyncToggleRow("Activation du mock", syncMock, vm::setSyncMockEnabled)
            SyncToggleRow("Plafond de blocage", syncBlock, vm::setSyncBlock)
        }
    }
}

@Composable
private fun SyncToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SpeedBlockCard(vm: MainViewModel) {
    val enabled by vm.speedBlockEnabled.collectAsState()
    var text by remember { mutableStateOf(MainViewModel.format(vm.speedBlockKmh.value)) }
    val parsed = text.replace(',', '.').toFloatOrNull()
    val error = parsed == null || parsed < 0f || parsed > MainViewModel.BLOCK_SPEED_MAX

    SectionCard("Blocage de vitesse") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bloquer la vitesse")
                Text(
                    "Plafonne la vitesse simulée à cette valeur (légère variation, jamais " +
                        "figée). En dessous du plafond, le facteur/preset s'applique normalement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = vm::setSpeedBlockEnabled)
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                it.replace(',', '.').toFloatOrNull()?.let(vm::setSpeedBlockKmh)
            },
            label = { Text("Vitesse") },
            suffix = { Text("km/h") },
            singleLine = true,
            isError = error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PresetFactorRow(vm: MainViewModel, limit: Int) {
    var text by remember { mutableStateOf(MainViewModel.format(vm.presetFactors.value[limit] ?: 1f)) }
    val parsed = text.replace(',', '.').toFloatOrNull()
    val error = parsed == null || parsed <= 0f || parsed > MainViewModel.FACTOR_LIMIT

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$limit km/h", modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                it.replace(',', '.').toFloatOrNull()?.let { f -> vm.setPresetFactor(limit, f) }
            },
            prefix = { Text("×") },
            singleLine = true,
            isError = error,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(130.dp),
        )
    }
}

@Composable
private fun FactorRangeCard(vm: MainViewModel) {
    var minText by remember { mutableStateOf(MainViewModel.format(vm.factorMin.value)) }
    var maxText by remember { mutableStateOf(MainViewModel.format(vm.factorMax.value)) }

    // Tente d'appliquer la plage dès que les deux champs forment un couple valide.
    fun tryCommit() {
        val lo = minText.replace(',', '.').toFloatOrNull()
        val hi = maxText.replace(',', '.').toFloatOrNull()
        if (lo != null && hi != null) vm.setFactorRange(lo, hi)
    }

    val minVal = minText.replace(',', '.').toFloatOrNull()
    val maxVal = maxText.replace(',', '.').toFloatOrNull()
    val invalid = minVal == null || maxVal == null || minVal <= 0f ||
        maxVal <= minVal || maxVal > MainViewModel.FACTOR_LIMIT

    SectionCard("Plage du facteur de vitesse") {
        Text(
            "Bornes du curseur (× vitesse). Strictement positives, min < max, " +
                "max ≤ ${MainViewModel.format(MainViewModel.FACTOR_LIMIT)} ; dépassez 1 pour accélérer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = minText,
                onValueChange = { minText = it; tryCommit() },
                label = { Text("Min") },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxText,
                onValueChange = { maxText = it; tryCommit() },
                label = { Text("Max") },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        if (invalid) {
            Text(
                "Plage invalide : deux nombres > 0, min < max, max ≤ " +
                    "${MainViewModel.format(MainViewModel.FACTOR_LIMIT)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
