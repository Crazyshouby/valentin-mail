@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.valentinlarge.courrier

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Clair ou sombre : d'après le choix fait dans les réglages, sinon d'après le thème du téléphone. */
object Palette {
    /** « auto » suit le téléphone ; « light » et « dark » imposent un thème. */
    var mode by mutableStateOf("auto")

    /** Le thème du téléphone, relevé par l'activité avant tout affichage. */
    var system by mutableStateOf(false)

    val dark: Boolean get() = if (mode == "auto") system else mode == "dark"

    /** 0 en clair, 1 en sombre : les couleurs passent de l'un à l'autre en fondu. */
    var shade by mutableFloatStateOf(0f)

    /** Pose le thème d'un coup, sans fondu (au lancement). */
    fun settle() {
        shade = if (dark) 1f else 0f
    }
}

private fun tone(light: Long, dark: Long): Color = when (Palette.shade) {
    0f -> Color(light)
    1f -> Color(dark)
    else -> lerp(Color(light), Color(dark), Palette.shade)
}

// Papier clair et encre presque noire ; un seul accent, le bleu du courrier par avion.
// En sombre, le papier devient nuit et le bleu s'éclaircit pour rester lisible.
val Paper: Color get() = tone(0xFFF3F4F1, 0xFF0D0F12)
val Sheet: Color get() = tone(0xFFFFFFFF, 0xFF171A1F)
val Ink: Color get() = tone(0xFF14171C, 0xFFECEEF1)
val Muted: Color get() = tone(0xFF5B6470, 0xFF9BA4B0)
val Line: Color get() = tone(0xFFE2E4DF, 0xFF272C34)
val Blue: Color get() = tone(0xFF2443D8, 0xFF9DAAF2)
val BlueWash: Color get() = tone(0xFFE9ECFB, 0xFF242834)
val Tint: Color get() = tone(0xFFEDEEF0, 0xFF26292E)
val Red: Color get() = tone(0xFFC8323F, 0xFFF08A93)
val RedWash: Color get() = tone(0xFFF9E6E8, 0xFF3A2428)

/** Ce qui s'écrit sur un fond bleu ou rouge. */
val OnBlue: Color get() = tone(0xFFFFFFFF, 0xFF0D0F12)

/** Le fond qu'on aperçoit derrière la boîte quand elle recule pour laisser place à un message. */
val Backdrop = Color(0xFF0D0F12)

// Le liseré « par avion » garde ses couleurs d'origine dans les deux thèmes.
private val StripeRed = Color(0xFFC8323F)
private val StripeBlue = Color(0xFF2443D8)

// Deux polices libres, embarquées dans l'appli : Bricolage Grotesque pour les titres, Instrument Sans pour le texte.
private fun display(weight: Int) = Font(
    R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 36f)),
)

private fun body(weight: Int) = Font(
    R.font.instrument_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Display = FontFamily(display(600), display(700), display(800))
val Body = FontFamily(body(400), body(500), body(600), body(700))

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
        surfaceVariant = Tint,
        onSurfaceVariant = Muted,
        surfaceContainer = Sheet,
        surfaceContainerHigh = Sheet,
        surfaceContainerHighest = Tint,
        surfaceContainerLow = Sheet,
        surfaceContainerLowest = Sheet,
        surfaceTint = Color.Transparent,
        inverseSurface = Ink,
        inverseOnSurface = Paper,
        inversePrimary = Paper,
        outline = Muted,
        outlineVariant = Line,
        error = Red,
        onError = OnBlue,
        scrim = Color(0xFF0D0F12),
    )
}

private val typography = Typography().let { base ->
    base.copy(
        displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.035).em),
        headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.025).em),
        headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.02).em),
        titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
        titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = base.titleSmall.copy(fontFamily = Body),
        bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = Body, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontFamily = Body),
        labelSmall = base.labelSmall.copy(fontFamily = Body),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CourrierTheme(content: @Composable () -> Unit) {
    // Un changement de thème se fait en fondu : toutes les couleurs glissent ensemble.
    val target = if (Palette.dark) 1f else 0f
    LaunchedEffect(target) {
        if (Palette.shade != target) animate(Palette.shade, target, animationSpec = tween(350)) { value, _ -> Palette.shade = value }
    }
    MaterialTheme(colorScheme = scheme(), typography = typography, shapes = shapes, content = content)
}

/** Le liseré « par avion » : rayures obliques rouges et bleues ; [shift] (de 0 à 1) le fait défiler d'un motif. */
fun Modifier.airmail(shift: Float = 0f): Modifier = drawBehind {
    val band = 11.dp.toPx()
    val height = size.height
    val shades = listOf(StripeRed, null, StripeBlue, null)
    clipRect {
        var x = -height - shades.size * band * (1f + shift)
        var index = 0
        while (x < size.width + height) {
            val shade = shades[index % shades.size]
            if (shade != null) {
                val path = Path().apply {
                    moveTo(x, height)
                    lineTo(x + band, height)
                    lineTo(x + band + height, 0f)
                    lineTo(x + height, 0f)
                    close()
                }
                drawPath(path, shade)
            }
            x += band
            index++
        }
    }
}

/** Le liseré en bandeau ; [running] le fait défiler, le temps d'un envoi ou d'une vérification. */
@Composable
fun AirmailStripe(modifier: Modifier = Modifier, running: Boolean = false, height: Dp = 6.dp) {
    val shift = if (running) {
        val transition = rememberInfiniteTransition(label = "liseré")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(550, easing = LinearEasing)), label = "défilement").value
    } else {
        0f
    }
    Box(modifier.fillMaxWidth().height(height).airmail(shift))
}
