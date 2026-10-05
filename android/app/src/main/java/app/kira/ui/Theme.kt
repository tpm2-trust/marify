package app.kira.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Container/content pairs for the three verdict states, readable in both themes. */
@Immutable
data class VerdictColors(
    val matchContainer: Color, val onMatch: Color,
    val changedContainer: Color, val onChanged: Color,
    val failedContainer: Color, val onFailed: Color,
)

private val LightVerdict = VerdictColors(
    matchContainer = Color(0xFFD3F0DD), onMatch = Color(0xFF0B3D20),
    changedContainer = Color(0xFFFFE8B8), onChanged = Color(0xFF4A3000),
    failedContainer = Color(0xFFFFDAD6), onFailed = Color(0xFF5C0A0A),
)

private val DarkVerdict = VerdictColors(
    matchContainer = Color(0xFF1C4A2E), onMatch = Color(0xFFC4ECD1),
    changedContainer = Color(0xFF5A4100), onChanged = Color(0xFFFFE1A6),
    failedContainer = Color(0xFF7A1C1C), onFailed = Color(0xFFFFDAD6),
)

val LocalVerdictColors = staticCompositionLocalOf { LightVerdict }

private val Brand = Color(0xFF1F5C4A)

@Composable
fun KiraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8FD3B8))
        else -> lightColorScheme(primary = Brand)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalVerdictColors provides if (dark) DarkVerdict else LightVerdict,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
