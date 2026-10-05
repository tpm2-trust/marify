package app.kira.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kira.R
import app.kira.core.Decision
import app.kira.core.Event
import app.kira.core.KiraCore
import app.kira.session.FailureKind
import app.kira.session.Outcome
import app.kira.session.Phase
import app.kira.session.SessionManager
import app.kira.session.SessionMode
import app.kira.session.SessionState

@Composable
fun SessionScreen(manager: SessionManager, onClose: () -> Unit) {
    val runner by manager.active.collectAsStateWithLifecycle()
    val r = runner
    if (r == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val state by r.state.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }

    BackHandler {
        if (state.finished) {
            manager.dismiss(); onClose()
        } else {
            confirmLeave = true
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.leave_session_title)) },
            text = { Text(stringResource(R.string.leave_session_body)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; manager.cancel() }) { Text(stringResource(R.string.stop)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.continue_session)) } },
        )
    }

    SessionContent(
        state = state,
        canRetry = manager.canRetry,
        onConfirmSas = r::confirmSas,
        onConfirmBind = r::confirmBind,
        onDecide = r::decide,
        onRequestEventlog = r::requestEventlog,
        onRetry = { manager.dismiss(); manager.retry() },
        onDone = { manager.dismiss(); onClose() },
        onStop = { confirmLeave = true },
    )
}

@Composable
fun SessionContent(
    state: SessionState,
    canRetry: Boolean,
    onConfirmSas: (Boolean) -> Unit,
    onConfirmBind: (Boolean, Boolean) -> Unit = { _, _ -> },
    onDecide: (Decision, String) -> Unit,
    onRequestEventlog: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onStop: () -> Unit = {},
) {
    val name = state.machineName ?: stringResource(R.string.a_machine)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val verdict = state.verdict
        when {
            state.phase == Phase.CONFIRM_SAS && state.sas != null -> SasContent(state.sas, onConfirmSas)
            state.phase == Phase.CONFIRM_BIND && state.bind != null -> BindContent(state.bind, onConfirmBind)
            verdict != null -> {
                VerdictContent(
                    event = verdict,
                    machineName = name,
                    lastAttested = (state.mode as? SessionMode.Attest)?.lastAttested,
                    decisionEnabled = !state.decisionSent && !state.finished && state.phase == Phase.DECIDING,
                    eventlogRequested = state.eventlogRequested,
                    eventlog = state.eventlog,
                    onDecide = onDecide,
                    onRequestEventlog = onRequestEventlog,
                )
                if (!state.finished) {
                    when (state.phase) {
                        Phase.SIGNING -> Progress(stringResource(R.string.phase_signing))
                        Phase.DELIVERING -> Progress(stringResource(R.string.phase_delivering))
                        else -> Unit
                    }
                }
            }
            !state.finished && state.unreachable != null -> {
                Unreachable(state.machineName, state.unreachable)
                StopButton(onStop)
            }
            !state.finished -> {
                Progress(
                    when (state.phase) {
                        Phase.CONNECTING -> stringResource(R.string.phase_connecting)
                        Phase.SECURING -> stringResource(R.string.phase_securing)
                        Phase.SIGNING -> stringResource(R.string.phase_signing)
                        Phase.ENROLLING, Phase.CONFIRM_SAS -> stringResource(R.string.phase_enrolling)
                        else -> stringResource(R.string.phase_verifying, name)
                    },
                )
                StopButton(onStop)
            }
        }

        state.notice?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("notice"))
        }

        state.receiptAck?.let { ack ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(stringResource(R.string.receipt_delivered), style = MaterialTheme.typography.titleMedium)
                    Text(receiptResultText(ack.result), modifier = Modifier.testTag("receipt_ack"))
                    if (ack.message.isNotBlank()) Text(ack.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        when (val o = state.outcome) {
            null -> Unit
            Outcome.Succeeded -> {
                if (state.mode is SessionMode.Enrol) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.enrolled_title, name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("enrolled"))
                    Text(stringResource(R.string.enrolled_body))
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("done")) { Text(stringResource(R.string.done)) }
            }
            is Outcome.Failed -> if (o.kind == FailureKind.MACHINE_REFUSED) {
                Refused(state.machineName ?: stringResource(R.string.the_machine), o.message)
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("done")) { Text(stringResource(R.string.done)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.session_failed), style = MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(Texts.failure(o.kind)), modifier = Modifier.testTag("failure"))
                if (o.code != 0 && o.kind == FailureKind.PROTOCOL) Text(errorCodeText(o.code))
                if (o.message.isNotBlank()) Text(o.message, style = MaterialTheme.typography.bodySmall)
                val retryable = o.kind == FailureKind.LINK_LOST || o.kind == FailureKind.CONNECT ||
                    o.kind == FailureKind.AUTH_CANCELLED
                if (retryable && canRetry && state.mode is SessionMode.Attest) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.try_again)) }
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("done")) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

/**
 * What the phone learnt about the machine during enrolment, before the user
 * binds it (TODO-SEC.md S3, S5). Nothing here comes from the app: the core
 * decided it, the screen only shows it.
 */
@Composable
private fun BindContent(b: Event.NeedAnchorKey, onConfirm: (Boolean, Boolean) -> Unit) {
    val colors = LocalVerdictColors.current
    val ctx = LocalContext.current
    val canBiometric = remember { Requirements.hasStrongBiometric(ctx) }
    var biometricOnly by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.bind_title, b.friendlyName), style = MaterialTheme.typography.headlineSmall)
    val verified = b.ekVerifiedBy.isNotEmpty()
    Card(
        Modifier.fillMaxWidth().testTag(if (verified) "tpm_verified" else "tpm_unverified"),
        colors = CardDefaults.cardColors(
            containerColor = if (verified) colors.matchContainer else colors.changedContainer,
            contentColor = if (verified) colors.onMatch else colors.onChanged,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (verified) stringResource(R.string.bind_tpm_verified, b.ekVerifiedBy)
                else stringResource(R.string.bind_tpm_unverified),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (verified) stringResource(R.string.bind_tpm_verified_body)
                else stringResource(R.string.bind_tpm_unverified_body, b.ekNote),
            )
            if (!verified) {
                val vendors = remember { runCatching { KiraCore.ekVendors() }.getOrDefault(emptyList()) }
                if (vendors.isNotEmpty()) {
                    Text(
                        stringResource(R.string.bind_tpm_known_vendors, vendors.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("known_vendors"),
                    )
                }
            }
        }
    }
    val pcrs = b.initrdPcrs.joinToString(" or ")
    when (b.initrdCoverage) {
        Event.INITRD_NOT_COVERED -> Card(
            Modifier.fillMaxWidth().testTag("initrd_not_covered"),
            colors = CardDefaults.cardColors(containerColor = colors.changedContainer, contentColor = colors.onChanged),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bind_initrd_not_covered), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.bind_initrd_not_covered_body, pcrs))
            }
        }
        Event.INITRD_COVERED -> Text(stringResource(R.string.bind_initrd_covered, pcrs), style = MaterialTheme.typography.bodyMedium)
        Event.INITRD_NONE -> Text(stringResource(R.string.bind_initrd_none), style = MaterialTheme.typography.bodyMedium)
        else -> Text(stringResource(R.string.bind_initrd_unknown), style = MaterialTheme.typography.bodyMedium)
    }
    if (canBiometric) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bind_biometric_only), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.bind_biometric_only_body), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = biometricOnly, onCheckedChange = { biometricOnly = it }, modifier = Modifier.testTag("biometric_only"))
        }
    }
    Button(onClick = { onConfirm(true, biometricOnly) }, modifier = Modifier.fillMaxWidth().testTag("bind")) {
        Text(stringResource(R.string.bind_confirm))
    }
    OutlinedButton(onClick = { onConfirm(false, false) }, modifier = Modifier.fillMaxWidth().testTag("bind_cancel")) {
        Text(stringResource(R.string.cancel))
    }
}

@Composable
private fun SasContent(sas: String, onConfirm: (Boolean) -> Unit) {
    Text(stringResource(R.string.sas_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        sas.chunked(3).joinToString(" "),
        fontSize = 56.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("sas"),
    )
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.sas_question), style = MaterialTheme.typography.titleMedium)
    Button(onClick = { onConfirm(true) }, modifier = Modifier.fillMaxWidth().testTag("sas_match")) {
        Text(stringResource(R.string.sas_matches))
    }
    OutlinedButton(onClick = { onConfirm(false) }, modifier = Modifier.fillMaxWidth().testTag("sas_no_match")) {
        Text(stringResource(R.string.sas_no_match))
    }
}

/**
 * The machine advertised as enrolled but refused this phone (TODO-SEC.md S1):
 * shown with the same weight as a failed attestation, and without a retry.
 */
@Composable
private fun Refused(name: String, detail: String) {
    val colors = LocalVerdictColors.current
    Card(
        Modifier.fillMaxWidth().testTag("refused"),
        colors = CardDefaults.cardColors(containerColor = colors.failedContainer, contentColor = colors.onFailed),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Error, null, Modifier.size(40.dp), tint = colors.onFailed)
            Text(stringResource(R.string.refused_title, name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.refused_body), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.refused_action), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Connecting keeps failing because the machine cannot be reached; the runner retries. */
@Composable
private fun Unreachable(machineName: String?, u: app.kira.session.Unreachable) {
    val name = machineName ?: stringResource(R.string.the_machine)
    Column(Modifier.testTag("unreachable"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Text(
                if (u.bluetoothOff) stringResource(R.string.bluetooth_off_title)
                else stringResource(R.string.unreachable_title, name),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            if (u.bluetoothOff) stringResource(R.string.bluetooth_off_body, name)
            else stringResource(R.string.unreachable_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.unreachable_attempt, u.attempt, u.reason), style = MaterialTheme.typography.bodySmall)
    }
}

/** Leaves a session that is waiting; the caller asks for confirmation first. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    OutlinedButton(onClick = onStop, modifier = Modifier.testTag("stop")) { Text(stringResource(R.string.stop)) }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
