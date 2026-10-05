package app.kira.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kira.AppContainer
import app.kira.R
import app.kira.keys.AnchorKeyDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineDetailsScreen(c: AppContainer, deviceId: String, onBack: () -> Unit, onSession: () -> Unit) {
    val machines by c.machines.machines.collectAsStateWithLifecycle()
    val machine = machines.firstOrNull { it.deviceId == deviceId }
    if (machine == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val s = machine.summary
    val key by produceState<AnchorKeyDetails?>(null, deviceId) {
        value = withContext(Dispatchers.IO) { runCatching { c.anchorKeys.details(deviceId) }.getOrNull() }
    }
    val isDemo = remember(machine.recordJson) { c.sessions.demo.owns(machine.recordJson) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(machine.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Texts.dateTime(s.enrolledAt)?.let { Text(stringResource(R.string.enrolled_at, it)) }
            Text(
                Texts.dateTime(s.lastAttested)?.let { stringResource(R.string.last_verified, it) }
                    ?: stringResource(R.string.never_verified),
            )

            Button(onClick = { c.sessions.startAttest(machine); onSession() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verify_now))
            }

            HorizontalDivider()
            Text(stringResource(R.string.profiles), style = MaterialTheme.typography.titleMedium)
            for (p in s.profiles) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        if (p.addedBy.isNotBlank()) Text(stringResource(R.string.profile_added_by, p.addedBy), style = MaterialTheme.typography.bodySmall)
                        Texts.dateTime(p.validUntil)?.let { Text(stringResource(R.string.profile_valid_until, it), style = MaterialTheme.typography.bodySmall) }
                        p.usesLeft?.let { Text(pluralStringResource(R.plurals.profile_uses_left, it.toInt(), it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.key_storage), style = MaterialTheme.typography.titleMedium)
            val k = key
            if (k == null) {
                Text(stringResource(R.string.key_missing))
            } else {
                Text(stringResource(Texts.keyStorage(k.storage)))
                if (k.authEnforcedByHardware) Text(stringResource(R.string.key_auth_hw), style = MaterialTheme.typography.bodySmall)
                if (k.attestationChainLength > 1) Text(pluralStringResource(R.plurals.key_attestation, k.attestationChainLength, k.attestationChainLength), style = MaterialTheme.typography.bodySmall)
            }

            if (isDemo) {
                HorizontalDivider()
                Text(stringResource(R.string.demo_controls), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.demo_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { c.sessions.demo.nextBoot() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.demo_next_boot)) }
                OutlinedButton(onClick = { c.sessions.demo.changePcr(4) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.demo_change_pcr)) }
                OutlinedButton(onClick = { c.sessions.demo.rollbackResetCount() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.demo_rollback)) }
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.delete_machine)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_machine_title, machine.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_machine_body))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        SelectionContainer {
                            Text(stringResource(R.string.unenrol_command), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        c.machines.delete(deviceId)
                        withContext(Dispatchers.IO) { c.anchorKeys.delete(deviceId) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
