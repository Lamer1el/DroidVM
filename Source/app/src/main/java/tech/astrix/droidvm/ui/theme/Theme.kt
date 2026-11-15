package tech.astrix.droidvm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Green (primary) + salad / lime (tertiary) palette derived from the DroidVM icon
private val Light = lightColorScheme(
    primary = Color(0xFF2F6B20), onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F397), onPrimaryContainer = Color(0xFF052100),
    secondary = Color(0xFF53634A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E8C8), onSecondaryContainer = Color(0xFF111F0B),
    tertiary = Color(0xFF4C6700), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEE6E), onTertiaryContainer = Color(0xFF151F00),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF0), onBackground = Color(0xFF181D15),
    surface = Color(0xFFF7FBF0), onSurface = Color(0xFF181D15),
    surfaceVariant = Color(0xFFDEE5D5), onSurfaceVariant = Color(0xFF42493D),
    outline = Color(0xFF72796C), outlineVariant = Color(0xFFC2C9BA),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F5EA),
    surfaceContainer = Color(0xFFEBF0E4), surfaceContainerHigh = Color(0xFFE5EADE),
    surfaceContainerHighest = Color(0xFFE0E4D9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9BD67D), onPrimary = Color(0xFF0E3900),
    primaryContainer = Color(0xFF175209), onPrimaryContainer = Color(0xFFB6F397),
    secondary = Color(0xFFBACCAE), onSecondary = Color(0xFF26351F),
    secondaryContainer = Color(0xFF3C4B34), onSecondaryContainer = Color(0xFFD6E8C8),
    tertiary = Color(0xFFB1D255), onTertiary = Color(0xFF263500),
    tertiaryContainer = Color(0xFF394E00), onTertiaryContainer = Color(0xFFCDEE6E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10140E), onBackground = Color(0xFFDFE4D7),
    surface = Color(0xFF10140E), onSurface = Color(0xFFDFE4D7),
    surfaceVariant = Color(0xFF42493D), onSurfaceVariant = Color(0xFFC2C9BA),
    outline = Color(0xFF8C9385), outlineVariant = Color(0xFF42493D),
    surfaceContainerLowest = Color(0xFF0B0F09), surfaceContainerLow = Color(0xFF181D15),
    surfaceContainer = Color(0xFF1C2119), surfaceContainerHigh = Color(0xFF262B23),
    surfaceContainerHighest = Color(0xFF31362D),
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()
val ExpressiveTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DroidVmTheme(theme: Int = 0, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        typography = ExpressiveTypography,
        content = content,
    )
}
