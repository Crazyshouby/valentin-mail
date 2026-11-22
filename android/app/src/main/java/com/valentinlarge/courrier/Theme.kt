package com.valentinlarge.courrier

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Clair ou sombre : réglé par l'activité d'après le thème du téléphone, avant tout affichage. */
object Palette {
    var dark by mutableStateOf(false)
}

// Mêmes teintes que le webmail : papier bleuté, encre marine, et le rouge et le bleu du courrier par avion.
// En sombre, le papier devient nuit et l'encre claire ; le bleu s'éclaircit pour rester lisible.
val Paper: Color get() = if (Palette.dark) Color(0xFF0F141D) else Color(0xFFF1F4F9)
val Sheet: Color get() = if (Palette.dark) Color(0xFF19202D) else Color.White
val Ink: Color get() = if (Palette.dark) Color(0xFFE6EAF2) else Color(0xFF17223B)
val Muted: Color get() = if (Palette.dark) Color(0xFF9BA7BD) else Color(0xFF5A6880)
val Line: Color get() = if (Palette.dark) Color(0xFF2B3546) else Color(0xFFD8DFEA)
val Blue: Color get() = if (Palette.dark) Color(0xFF7BA7F7) else Color(0xFF1D57B8)
val BlueWash: Color get() = if (Palette.dark) Color(0xFF1D2B45) else Color(0xFFE4ECFA)
val Red: Color get() = if (Palette.dark) Color(0xFFF27C86) else Color(0xFFCF2F3C)

/** Ce qui s'écrit sur un fond bleu ou rouge. */
val OnBlue: Color get() = if (Palette.dark) Color(0xFF0B1220) else Color.White

// Le liseré garde ses couleurs d'origine dans les deux thèmes.
private val StripeRed = Color(0xFFCF2F3C)
private val StripeBlue = Color(0xFF1D57B8)

private fun scheme(): ColorScheme {
    val base = if (Palette.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Blue,
        onPrimary = OnBlue,
        primaryContainer = BlueWash,
        onPrimaryContainer = Ink,
        secondary = Ink,
        onSecondary = Paper,
        secondaryContainer = BlueWash,
        onSecondaryContainer = Ink,
        tertiary = Red,
        onTertiary = OnBlue,
        background = Paper,
        onBackground = Ink,
        surface = Sheet,
        onSurface = Ink,
        surfaceVariant = Paper,
        onSurfaceVariant = Muted,
        surfaceContainer = Sheet,
        surfaceContainerHigh = Sheet,
        surfaceContainerHighest = Paper,
        surfaceContainerLow = Sheet,
        surfaceContainerLowest = Sheet,
        outline = Muted,
        outlineVariant = Line,
        error = Red,
        onError = OnBlue,
    )
}

private val typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 30.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    )
}

@Composable
fun CourrierTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(), typography = typography, content = content)
}

/** Le liseré « par avion » : rayures obliques rouge, blanc, bleu, blanc. */
fun Modifier.airmail(): Modifier = drawBehind {
    val band = 12.dp.toPx()
    val height = size.height
    val shades = listOf(StripeRed, Color.White, StripeBlue, Color.White)
    clipRect {
        var x = -height
        var index = 0
        while (x < size.width + height) {
            val path = Path().apply {
                moveTo(x, height)
                lineTo(x + band, height)
                lineTo(x + band + height, 0f)
                lineTo(x + height, 0f)
                close()
            }
            drawPath(path, shades[index % shades.size])
            x += band
            index++
        }
    }
}

@Composable
fun AirmailStripe(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).airmail())
}
