package app.cayresim.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Tea = Color(0xFFB5542B)      // Cay: Teefarbe als Akzent
private val TeaLight = Color(0xFFF2B48C)

private val Dark = darkColorScheme(primary = TeaLight, secondary = Tea, background = Color(0xFF111111), surface = Color(0xFF1A1A1A))
private val Light = lightColorScheme(primary = Tea, secondary = TeaLight)

@Composable
fun CayResimTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
