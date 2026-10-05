package app.kira.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kira.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesScreen(
    vm: MachinesViewModel,
    onEnrol: () -> Unit,
    onOpenMachine: (String) -> Unit,
    onOpenSession: () -> Unit,
) {
    val machines by vm.machines.collectAsStateWithLifecycle()
    val asking by vm.asking.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val activeState = active?.state?.collectAsStateWithLifecycle()?.value

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.machines_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onEnrol,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.enrol_machine)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeState?.finished == false) {
                item {
                    Banner(stringResource(R.string.session_running), null, onOpenSession)
                }
            }
            if (!vm.bluetoothOn()) {
                item { Text(stringResource(R.string.bluetooth_off), color = MaterialTheme.colorScheme.error) }
            }
            items(asking, key = { "asking-" + it.machine.deviceId }) { a ->
                Banner(
                    stringResource(R.string.asking_for_attestation, a.machine.name),
                    stringResource(R.string.asking_tap),
                ) {
                    vm.attest(a)
                    onOpenSession()
                }
            }
            if (machines.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.machines_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
            items(machines, key = { it.deviceId }) { m ->
                val last = Texts.dateTime(m.summary.lastAttested)
                Card(Modifier.fillMaxWidth().clickable { onOpenMachine(m.deviceId) }) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Computer, contentDescription = null) },
                        headlineContent = { Text(m.name) },
                        supportingContent = {
                            Text(if (last != null) stringResource(R.string.last_verified, last) else stringResource(R.string.never_verified))
                        },
                        colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun Banner(title: String, subtitle: String?, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(4.dp)) {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Sensors, contentDescription = null) },
                headlineContent = { Text(title) },
                supportingContent = subtitle?.let { { Text(it) } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    }
}
