package dev.mtzinstaller.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра Sync: чистый чёрный, белый акцент, волосяные границы. */
object Sync {
    val Black = Color(0xFF000000)
    val Card = Color(0xFF0C0C0D)
    val CardHigh = Color(0xFF141416)
    val Elevated = Color(0xFF1C1C1F)
    val Hairline = Color(0xFF26262A)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xFF8E8E96)
    val White = Color(0xFFFFFFFF)
    val Danger = Color(0xFFFF5F56)
    val Ok = Color(0xFF32D74B)
}

private val SyncColors = darkColorScheme(
    primary = Sync.White,
    onPrimary = Sync.Black,
    primaryContainer = Sync.CardHigh,
    onPrimaryContainer = Sync.Text,
    secondary = Sync.Text,
    onSecondary = Sync.Black,
    secondaryContainer = Sync.Card,
    onSecondaryContainer = Sync.Text,
    tertiary = Sync.TextDim,
    tertiaryContainer = Sync.Card,
    onTertiaryContainer = Sync.Text,
    background = Sync.Black,
    onBackground = Sync.Text,
    surface = Sync.Black,
    onSurface = Sync.Text,
    surfaceVariant = Sync.Elevated,
    onSurfaceVariant = Sync.TextDim,
    surfaceContainer = Sync.Card,
    surfaceContainerHigh = Sync.CardHigh,
    surfaceContainerHighest = Sync.Elevated,
    outline = Sync.Hairline,
    outlineVariant = Sync.Hairline,
    error = Sync.Danger,
    onError = Sync.Black,
)

/** Плотная сетка с узкими трекингами — ближе всего к Inter из того, что есть в системе. */
private val SyncTypography: Typography = Typography().let { base ->
    fun TextStyle.tight(weight: FontWeight? = null, tracking: Float = -0.2f) = copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight ?: fontWeight,
        letterSpacing = tracking.sp,
    )
    base.copy(
        headlineSmall = base.headlineSmall.tight(FontWeight.Bold, -0.8f),
        headlineMedium = base.headlineMedium.tight(FontWeight.Bold, -1f),
        titleLarge = base.titleLarge.tight(FontWeight.SemiBold, -0.5f),
        titleMedium = base.titleMedium.tight(FontWeight.SemiBold, -0.3f),
        titleSmall = base.titleSmall.tight(FontWeight.Medium),
        bodyLarge = base.bodyLarge.tight(),
        bodyMedium = base.bodyMedium.tight(),
        bodySmall = base.bodySmall.tight(tracking = 0f),
        labelMedium = base.labelMedium.tight(FontWeight.Medium, 0f),
        labelSmall = base.labelSmall.tight(FontWeight.Medium, 0.2f),
    )
}

@Composable
fun MtzTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SyncColors, typography = SyncTypography, content = content)
}
