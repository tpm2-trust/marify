package app.marify.ui

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marify.R

/**
 * The US keyboard's letters and digits, row by row, and how far each row is set in, in keys: the
 * arrangement the one-time layout is drawn on. Other arrangements are added beside it; the layout
 * itself, which key types which character, comes from the machine's core alone.
 */
internal val keyboardUS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
private val keyboardUSIndent = listOf(0f, 0.5f, 0.75f, 1.25f)

/**
 * The one-time layout, full screen and landscape while it is shown, and kept out of screenshots and
 * screen recordings (FLAG_SECURE). The orientation and the flag are given back when it closes.
 */
@Composable
fun KeyboardLayoutScreen(keys: String, layout: String, onClose: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val orientation = activity?.requestedOrientation
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (orientation != null) activity.requestedOrientation = orientation
        }
    }
    Surface(Modifier.fillMaxSize()) {
        KeyboardLayoutView(keys, layout, onClose, Modifier.safeDrawingPadding().padding(12.dp))
    }
}

/** The keyboard with, on every key, its own letter small and the character it types large. */
@Composable
fun KeyboardLayoutView(keys: String, layout: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag("keyboard_layout"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.keyboard_layout_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onClose, modifier = Modifier.testTag("keyboard_layout_close")) { Text(stringResource(R.string.keyboard_layout_close)) }
        }
        val columns = keyboardUS.maxOf { it.length }
        keyboardUS.forEachIndexed { row, letters ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val indent = keyboardUSIndent[row]
                if (indent > 0f) Spacer(Modifier.weight(indent))
                letters.forEach { key ->
                    val at = keys.indexOf(key)
                    val types = if (at >= 0 && at < layout.length) layout[at] else ' '
                    Key(key, types, Modifier.weight(1f))
                }
                val rest = columns - letters.length - indent
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
        }
    }
}

@Composable
private fun Key(key: Char, types: Char, modifier: Modifier) {
    Card(
        modifier.aspectRatio(1.3f).testTag("key_$key"),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.fillMaxSize().padding(4.dp)) {
            Text(key.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopStart))
            Text(
                types.toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center).testTag("types_$key"),
            )
        }
    }
}

/** On the session's screen: the layout is here, to be shown again. */
@Composable
internal fun KeyboardLayoutCard(onShow: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("keyboard_layout_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.keyboard_layout_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.keyboard_layout_card_body), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onShow, modifier = Modifier.testTag("keyboard_layout_show")) { Text(stringResource(R.string.keyboard_layout_show)) }
        }
    }
}
