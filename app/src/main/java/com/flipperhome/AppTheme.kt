package com.flipperhome

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class Appearance(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DE5C5), onPrimary = Color(0xFF10382A), primaryContainer = Color(0xFF244C3E), onPrimaryContainer = Color(0xFFCCF7E4),
    secondary = Color(0xFFB1BED0), onSecondary = Color(0xFF1D2936), secondaryContainer = Color(0xFF222D38), onSecondaryContainer = Color(0xFFE0E8F0),
    background = Color(0xFF0E1217), onBackground = Color(0xFFF0F3F6), surface = Color(0xFF151B22), onSurface = Color(0xFFF0F3F6),
    surfaceVariant = Color(0xFF232C36), onSurfaceVariant = Color(0xFF9EAAB8), outline = Color(0xFF677584), outlineVariant = Color(0xFF2D3844),
    surfaceContainerLowest = Color(0xFF0A0E12), surfaceContainerLow = Color(0xFF151B22), surfaceContainer = Color(0xFF1A222B),
    surfaceContainerHigh = Color(0xFF222D38), surfaceContainerHighest = Color(0xFF2A3642), error = Color(0xFFFFB4AB),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF28654D), onPrimary = Color.White, primaryContainer = Color(0xFFD1EEDD), onPrimaryContainer = Color(0xFF123726),
    background = Color(0xFFF5F7F8), onBackground = Color(0xFF16202A), surface = Color.White, onSurface = Color(0xFF16202A),
    secondaryContainer = Color(0xFFE8EEF0), onSecondaryContainer = Color(0xFF253642), onSurfaceVariant = Color(0xFF5C6977),
    outlineVariant = Color(0xFFDEE5E9), surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFE9EFF2),
)

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Green: Color @Composable get() = MaterialTheme.colorScheme.primary

@Composable internal fun controlColors(choice: ControlColor): Pair<Color,Color> {
    val theme = MaterialTheme.colorScheme
    val dark = theme.background.red < .3f
    return when(choice) {
        ControlColor.DEFAULT -> theme.surfaceContainerHigh to theme.onSurface
        ControlColor.MINT -> if(dark) Color(0xFF254C40) to Color(0xFFCEF4E2) else Color(0xFFD6EFDF) to Color(0xFF214C37)
        ControlColor.BLUE -> if(dark) Color(0xFF293F58) to Color(0xFFD5E8FF) else Color(0xFFDCEAF8) to Color(0xFF294B70)
        ControlColor.LAVENDER -> if(dark) Color(0xFF453957) to Color(0xFFECDBFF) else Color(0xFFEBE0F5) to Color(0xFF584070)
        ControlColor.PEACH -> if(dark) Color(0xFF533C32) to Color(0xFFFFDFCA) else Color(0xFFF9E4D7) to Color(0xFF72472E)
    }
}

@Composable internal fun FlipperTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect { (view.context as? Activity)?.let { activity ->
        WindowCompat.getInsetsController(activity.window,view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        if(Build.VERSION.SDK_INT < 35) {
            val background = (if(dark) DarkColors else LightColors).background.toArgb()
            activity.window.statusBarColor = background
            activity.window.navigationBarColor = background
        }
    } }
    MaterialTheme(colorScheme = if(dark) DarkColors else LightColors,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp),small = RoundedCornerShape(12.dp),medium = RoundedCornerShape(16.dp),large = RoundedCornerShape(20.dp),extraLarge = RoundedCornerShape(24.dp)),content = content)
}
