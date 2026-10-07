package app.marify.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marify.R
import app.marify.core.Decision
import app.marify.core.Event
import app.marify.core.Verdict

/**
 * The verdict as the core produced it (PROTOCOL-BLE.md §10.4, PLAN-BLE.md
 * §6.3). This composable only displays: state, explanation and reasons come
 * from the core, and the user's choice goes back to it unchanged.
 */
@Composable
fun VerdictContent(
    event: Event.VerdictEvent,
    machineName: String,
    lastAttested: String?,
    decisionEnabled: Boolean,
    eventlogRequested: Boolean,
    eventlog: Event.Eventlog?,
    onDecide: (Decision, String) -> Unit,
    onRequestEventlog: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the machine's screen shows, where that screen is simulated (the demo machine). */
    demoScreenCode: String? = null,
) {
    val v = event.verdict
    val colors = LocalVerdictColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val badge: @Composable () -> Unit = { SignatureBadge(v) }
        when (v.state) {
            Verdict.MATCH -> Headline(
                icon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(40.dp), tint = colors.onMatch) },
                title = Texts.date(lastAttested)?.let { stringResource(R.string.verdict_match, machineName, it) }
                    ?: stringResource(R.string.verdict_match_first, machineName),
                body = null,
                container = colors.matchContainer, content = colors.onMatch,
                tag = "verdict_match",
                badge = badge,
            )
            Verdict.CHANGED -> Headline(
                icon = { Icon(Icons.Filled.Warning, null, Modifier.size(40.dp), tint = colors.onChanged) },
                title = v.explanation.ifBlank { stringResource(R.string.verdict_changed_title, machineName) },
                body = if (v.bootKey == Verdict.BOOT_KEY_PROVED) stringResource(R.string.changed_but_signed) else null,
                container = colors.changedContainer, content = colors.onChanged,
                tag = "verdict_changed",
                badge = badge,
            )
            else -> Headline(
                icon = { Icon(Icons.Filled.Error, null, Modifier.size(40.dp), tint = colors.onFailed) },
                title = stringResource(R.string.verdict_failed_title),
                body = stringResource(R.string.verdict_failed_intro),
                container = colors.failedContainer, content = colors.onFailed,
                tag = "verdict_failed",
                badge = badge,
            ) {
                for (r in v.reasons.filter { it.hard }) {
                    Text("• " + reasonText(r.code), color = colors.onFailed, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        BootKeyCard(v, demoScreenCode)

        if (v.state == Verdict.CHANGED && v.pcrDiff.isNotEmpty()) {
            Text(stringResource(R.string.what_changed), style = MaterialTheme.typography.titleMedium)
            for (d in v.pcrDiff) {
                Text("• " + d.description.ifBlank { stringResource(R.string.pcr_label, d.index) })
            }
        }

        if (v.warnings.isNotEmpty()) {
            Text(stringResource(R.string.warnings), style = MaterialTheme.typography.titleMedium)
            for (w in v.warnings) Text("• " + warningText(w.code), style = MaterialTheme.typography.bodyMedium)
        }

        if (event.eventlogAvailable && v.state != Verdict.MATCH) {
            when {
                eventlog != null -> {
                    if (eventlog.total > 0) {
                        LinearProgressIndicator(
                            progress = { eventlog.received.toFloat() / eventlog.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val ctx = LocalContext.current
                    val total = Formatter.formatShortFileSize(ctx, eventlog.total)
                    Text(
                        if (eventlog.complete) stringResource(R.string.eventlog_complete, total)
                        else stringResource(R.string.eventlog_progress, Formatter.formatShortFileSize(ctx, eventlog.received), total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                eventlogRequested -> LinearProgressIndicator(Modifier.fillMaxWidth())
                decisionEnabled -> OutlinedButton(onClick = onRequestEventlog, modifier = Modifier.testTag("show_eventlog")) {
                    Text(stringResource(R.string.show_eventlog))
                }
            }
        }

        if (event.factorOffered) FactorOfferCard()

        if (decisionEnabled && event.needsDecision) {
            when (v.state) {
                Verdict.MATCH -> MatchDecisions(v, onDecide)
                Verdict.CHANGED -> ChangedDecisions(onDecide)
                else -> FailedDecisions(machineName, onDecide)
            }
        }

        TechnicalDetails(v)
    }
}

@Composable
private fun Headline(
    icon: @Composable () -> Unit,
    title: String,
    body: String?,
    container: Color,
    content: Color,
    tag: String,
    badge: @Composable () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    Card(
        Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.weight(1f))
                badge()
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge)
            extra()
        }
    }
}

/**
 * The one thing to see at a glance, on every card: a green check when the
 * machine's TPM signed this boot state as approved, a red cross when it did
 * not - and a grey "after boot" when the key is locked until the next boot
 * by design (a check of the running system, as at a remote salt's
 * enrolment). The words below say why.
 */
@Composable
private fun SignatureBadge(v: Verdict) {
    val colors = LocalVerdictColors.current
    val valid = v.bootKey == Verdict.BOOT_KEY_PROVED
    val locked = v.bootKey == Verdict.BOOT_KEY_LOCKED
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            valid -> colors.matchContainer
            locked -> MaterialTheme.colorScheme.surfaceVariant
            else -> colors.failedContainer
        },
        contentColor = when {
            valid -> colors.onMatch
            locked -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> colors.onFailed
        },
        modifier = Modifier.testTag(
            when {
                valid -> "signature_valid"
                locked -> "signature_locked"
                else -> "signature_invalid"
            },
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                when {
                    valid -> Icons.Filled.CheckCircle
                    locked -> Icons.Filled.Lock
                    else -> Icons.Filled.Cancel
                },
                null, Modifier.size(20.dp),
            )
            Text(
                stringResource(
                    when {
                        valid -> R.string.badge_signed
                        locked -> R.string.badge_after_boot
                        else -> R.string.badge_not_signed
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The code the machine's screen must show, or one line on why there is
 * none. The signature itself is the badge on the headline; this card is
 * for the comparison.
 */
@Composable
private fun BootKeyCard(v: Verdict, demoScreenCode: String?) {
    val colors = LocalVerdictColors.current
    val valid = v.bootKey == Verdict.BOOT_KEY_PROVED
    val locked = v.bootKey == Verdict.BOOT_KEY_LOCKED
    Card(
        Modifier.fillMaxWidth().testTag(
            when {
                valid -> "boot_key_valid"
                locked -> "boot_key_locked"
                else -> "boot_key_invalid"
            },
        ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                valid -> colors.matchContainer
                locked -> MaterialTheme.colorScheme.surfaceVariant
                else -> colors.failedContainer
            },
            contentColor = when {
                valid -> colors.onMatch
                locked -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> colors.onFailed
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (valid) {
                Text(stringResource(R.string.boot_code_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    v.code,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().testTag("boot_code_value"),
                    textAlign = TextAlign.Center,
                )
                Text(
                    demoScreenCode?.let { stringResource(R.string.boot_code_demo_screen, it) }
                        ?: stringResource(R.string.boot_code_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(if (demoScreenCode != null) "demo_screen_code" else "boot_code_hint"),
                )
                if (v.signingKey.isNotEmpty()) {
                    Text(
                        stringResource(R.string.boot_key_signed_by, v.signingKey.takeLast(16).chunked(4).joinToString(" ")),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("boot_key_signer"),
                    )
                }
            } else {
                Text(
                    stringResource(
                        when (v.bootKey) {
                            Verdict.BOOT_KEY_REFUSED -> R.string.boot_key_refused
                            Verdict.BOOT_KEY_INVALID -> R.string.reason_boot_proof_invalid
                            Verdict.BOOT_KEY_LOCKED -> R.string.boot_key_locked
                            else -> R.string.boot_key_unavailable
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The machine asks this phone to keep its remote salt: a wrapped secret only
 * that machine's TPM can open, returned after every accepted verdict so the
 * disk's key can be derived. Kept when this verdict is accepted.
 */
@Composable
private fun FactorOfferCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.testTag("factor_offer")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.factor_offer_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.factor_offer_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A matching boot still waits for the person: they compare the code with the
 * machine's screen and only then let the receipt be signed.
 */
@Composable
private fun MatchDecisions(v: Verdict, onDecide: (Decision, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onDecide(Decision.CONTINUE, "") }, Modifier.fillMaxWidth().testTag("continue")) {
            Text(stringResource(if (v.bootKey == Verdict.BOOT_KEY_PROVED) R.string.codes_match_continue else R.string.continue_to_sign))
        }
        OutlinedButton(onClick = { onDecide(Decision.REJECT, "") }, Modifier.fillMaxWidth().testTag("reject")) {
            Text(stringResource(if (v.bootKey == Verdict.BOOT_KEY_PROVED) R.string.codes_differ_reject else R.string.reject))
        }
    }
}

@Composable
private fun ChangedDecisions(onDecide: (Decision, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onDecide(Decision.APPROVE_ONCE, "") }, Modifier.fillMaxWidth().testTag("approve_once")) {
            Text(stringResource(R.string.approve_once))
        }
        OutlinedButton(onClick = { onDecide(Decision.APPROVE_REMEMBER, "") }, Modifier.fillMaxWidth().testTag("approve_remember")) {
            Text(stringResource(R.string.approve_remember))
        }
        OutlinedButton(onClick = { onDecide(Decision.REJECT, "") }, Modifier.fillMaxWidth().testTag("reject")) {
            Text(stringResource(R.string.reject))
        }
    }
}

@Composable
private fun FailedDecisions(machineName: String, onDecide: (Decision, String) -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onDecide(Decision.REJECT, "") },
            modifier = Modifier.weight(1f).testTag("reject"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text(stringResource(R.string.reject)) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("more")) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.approve_anyway)) },
                    onClick = { menu = false; dialog = true },
                    modifier = Modifier.testTag("approve_anyway"),
                )
            }
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(stringResource(R.string.approve_failed_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.approve_failed_body, machineName))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.machine_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.testTag("confirm_name"),
                    )
                }
            },
            confirmButton = {
                // The core checks the name; the button only avoids an obvious typo round trip.
                TextButton(
                    onClick = { dialog = false; onDecide(Decision.APPROVE_ONCE, typed) },
                    enabled = typed.isNotBlank(),
                    modifier = Modifier.testTag("confirm_approve"),
                ) { Text(stringResource(R.string.approve)) }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** PCR hex and counters, collapsed by default (PROTOCOL-BLE.md §13.7). */
@Composable
private fun TechnicalDetails(v: Verdict) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag("details")) {
        Text(stringResource(if (open) R.string.technical_details else R.string.details))
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (v.diffAgainst.isNotBlank()) Text(stringResource(R.string.compared_with, v.diffAgainst), style = MaterialTheme.typography.bodySmall)
        for (r in v.reasons) {
            Text("${r.code}: ${r.detail}", style = MaterialTheme.typography.bodySmall)
        }
        for (d in v.pcrDiff) {
            HorizontalDivider()
            Text(stringResource(R.string.pcr_label, d.index) + " — " + d.description, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.pcr_expected, d.expected), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.pcr_actual, d.actual), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text(
            stringResource(
                R.string.tpm_counters, v.resetCount, v.restartCount, v.firmwareVersion,
                stringResource(if (v.clockSafe) R.string.yes else R.string.no),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
