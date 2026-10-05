package app.kira.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kira.AppContainer
import app.kira.R
import app.kira.ble.Advertisement
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ENROL-mode advertisements are shown only on this screen (PROTOCOL-BLE.md §2.3). */
class EnrolViewModel(private val c: AppContainer) : ViewModel() {
    val demoAvailable = c.sessions.demo.available

    val waiting: StateFlow<List<Advertisement>> = c.scanner.scan()
        .catch { }
        .filter { it.isEnrol }
        .runningFold(LinkedHashMap<String, Advertisement>()) { acc, adv ->
            LinkedHashMap(acc).apply { put(adv.address, adv) }
        }
        .map { m ->
            val now = SystemClock.elapsedRealtime()
            m.values.filter { now - it.seenAtMs < 15_000 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptyList())

    fun enrol(adv: Advertisement) = c.sessions.startEnrol(adv)
    fun enrolDemo() = c.sessions.startDemoEnrol()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrolScreen(vm: EnrolViewModel, onBack: () -> Unit, onSession: () -> Unit) {
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.enrol_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.enrol_instructions), style = MaterialTheme.typography.bodyLarge) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    SelectionContainer {
                        Text(
                            stringResource(R.string.enrol_command),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            item { Text(stringResource(R.string.enrol_instructions_2), style = MaterialTheme.typography.bodyMedium) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.enrol_searching), style = MaterialTheme.typography.labelLarge)
                }
            }
            items(waiting, key = { it.address }) { adv ->
                Card(Modifier.fillMaxWidth().clickable { vm.enrol(adv); onSession() }) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Computer, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.enrol_waiting_machine)) },
                        supportingContent = { Text(stringResource(R.string.enrol_signal, adv.rssi)) },
                        colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
                    )
                }
            }
            if (vm.demoAvailable) {
                item {
                    Card(Modifier.fillMaxWidth().clickable { vm.enrolDemo(); onSession() }) {
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.demo_machine)) },
                            supportingContent = { Text(stringResource(R.string.demo_machine_sub)) },
                            colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
                        )
                    }
                }
            }
        }
    }
}
