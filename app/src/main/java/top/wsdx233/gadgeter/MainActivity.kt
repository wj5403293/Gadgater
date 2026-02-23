package top.wsdx233.gadgeter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GadgeterTheme {
                AppNavigation()
            }
        }
    }
}

// Custom theme for WOW effect
private val DarkColors = darkColorScheme(
    primary = Color(0xFFC0A0FF),
    onPrimary = Color(0xFF2C0076),
    primaryContainer = Color(0xFF45199A),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF6DE1DF),
    onSecondary = Color(0xFF003736),
    secondaryContainer = Color(0xFF00504E),
    onSecondaryContainer = Color(0xFF8CFEFC),
    background = Color(0xFF0F0E13),
    onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF131217),
    onSurface = Color(0xFFC8C5CA)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5D31B4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF1D005F),
    secondary = Color(0xFF006A68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF8CFEFC),
    onSecondaryContainer = Color(0xFF00201F),
    background = Color(0xFFFDFBFE),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFF8F9FA),
    onSurface = Color(0xFF1B1B1F)
)

@Composable
fun GadgeterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}