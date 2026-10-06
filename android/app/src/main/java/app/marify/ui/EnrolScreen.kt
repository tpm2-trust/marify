package app.marify.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.marify.AppContainer
import app.marify.BuildConfig
import app.marify.R
import app.marify.ble.Advertisement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ENROL-mode advertisements are shown only on this screen (PROTOCOL-BLE.md §2.3). */
class EnrolViewModel(private val c: AppContainer) : ViewModel() {
    /** The simulated machine exists in debug builds only. */
    val demoAvailable = c.sessions.demo.available

    /** Real hardware is the default; the demo machine is listed on request. */
    val showDemo = MutableStateFlow(false)

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

/** A machine advertising for enrolment, as the list shows it. */
data class WaitingMachine(val address: String, val rssi: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrolScreen(vm: EnrolViewModel, onBack: () -> Unit, onSession: () -> Unit) {
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val showDemo by vm.showDemo.collectAsStateWithLifecycle()
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
        EnrolContent(
            waiting = waiting.map { WaitingMachine(it.address, it.rssi) },
            debugBuild = BuildConfig.DEBUG,
            demoAvailable = vm.demoAvailable,
            showDemo = showDemo,
            onShowDemo = { vm.showDemo.value = it },
            onEnrol = { address -> waiting.firstOrNull { it.address == address }?.let { vm.enrol(it); onSession() } },
            onEnrolDemo = { vm.enrolDemo(); onSession() },
            padding = padding,
        )
    }
}

/**
 * The enrol list. Machines found over Bluetooth are always listed; the demo
 * machine of a debug build only after its switch is turned on.
 */
@Composable
fun EnrolContent(
    waiting: List<WaitingMachine>,
    debugBuild: Boolean,
    demoAvailable: Boolean,
    showDemo: Boolean,
    onShowDemo: (Boolean) -> Unit,
    onEnrol: (String) -> Unit,
    onEnrolDemo: () -> Unit,
    padding: PaddingValues = PaddingValues(),
) {
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
        if (debugBuild) {
            // A debug build lists real machines like a release build does,
            // so it has to say what it is (TODO-SEC.md S4.2).
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        stringResource(R.string.debug_build_warning),
                        Modifier.padding(12.dp).testTag("debug_build_warning"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.enrol_searching), style = MaterialTheme.typography.labelLarge)
            }
        }
        items(waiting, key = { it.address }) { machine ->
            Card(Modifier.fillMaxWidth().testTag("waiting_machine").clickable { onEnrol(machine.address) }) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Computer, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.enrol_waiting_machine)) },
                    supportingContent = { Text(stringResource(R.string.enrol_signal, machine.rssi)) },
                    colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
                )
            }
        }
        if (demoAvailable) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.show_demo_machine), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showDemo,
                        onCheckedChange = onShowDemo,
                        modifier = Modifier.testTag("show_demo_machine"),
                    )
                }
            }
            if (showDemo) {
                item {
                    Card(Modifier.fillMaxWidth().testTag("demo_machine").clickable(onClick = onEnrolDemo)) {
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
