package com.valentinlarge.courrier

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

// Mêmes teintes que le webmail : papier bleuté, encre marine, et le rouge et le bleu du courrier par avion.
val Paper = Color(0xFFF1F4F9)
val Ink = Color(0xFF17223B)
val Muted = Color(0xFF5A6880)
val Line = Color(0xFFD8DFEA)
val Blue = Color(0xFF1D57B8)
val BlueWash = Color(0xFFE4ECFA)
val Red = Color(0xFFCF2F3C)

private val colors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueWash,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Color.White,
    secondaryContainer = BlueWash,
    onSecondaryContainer = Ink,
    tertiary = Red,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = Muted,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Paper,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
    outline = Muted,
    outlineVariant = Line,
    error = Red,
    onError = Color.White,
)

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
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** Le liseré « par avion » : rayures obliques rouge, blanc, bleu, blanc. */
fun Modifier.airmail(): Modifier = drawBehind {
    val band = 12.dp.toPx()
    val height = size.height
    val shades = listOf(Red, Color.White, Blue, Color.White)
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
