package com.valentinlarge.courrier

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Le mouvement de la maison : part vite, se pose en douceur. */
val Glide = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** La forme des champs de saisie encadrés. */
val FieldShape = RoundedCornerShape(16.dp)

/** Arrête les touchers : rien ne passe à ce qui se trouve dessous. */
fun Modifier.solid(): Modifier = pointerInput(Unit) { }

/** Une action désignée par son pictogramme, dans une cible de 48. */
@Composable
fun GlyphButton(
    glyph: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ink,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(glyph, contentDescription = label, tint = if (enabled) tint else Muted.copy(alpha = 0.45f))
    }
}

/** Les initiales d'un nom : deux lettres au plus. */
fun initials(name: String): String {
    val words = name.trim().split(Regex("[\\s._@<>\"'()-]+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
    val letters = when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1)
        else -> words[0].take(1) + words[1].take(1)
    }
    return letters.uppercase()
}

/** La pastille d'un correspondant : ses initiales, dans une des trois teintes de la maison, toujours la même pour lui. */
@Composable
fun Avatar(name: String, seed: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val tone = (seed.lowercase().hashCode() and 0x7fffffff) % 3
    val back = when (tone) {
        0 -> BlueWash
        1 -> RedWash
        else -> Tint
    }
    val front = when (tone) {
        0 -> Blue
        1 -> Red
        else -> Ink
    }
    Box(
        modifier.size(size).clip(CircleShape).background(back).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = front, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.8f).sp, maxLines = 1)
    }
}

/** Le bouton des actions principales : une pilule, qui s'enfonce un peu sous le doigt. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: ImageVector? = null,
    enabled: Boolean = true,
    filled: Boolean = true,
    height: Dp = 48.dp,
    glyphModifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "appui")
    val front = if (filled) OnBlue else Ink
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .height(height)
            .background(if (filled) Blue else Sheet, CircleShape)
            .then(if (filled) Modifier else Modifier.border(1.dp, Line, CircleShape))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = if (glyph != null) 16.dp else 22.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            Icon(glyph, contentDescription = null, tint = front, modifier = glyphModifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = front, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Un bouton rond de 52, pour une action secondaire à côté d'une pilule. */
@Composable
fun RoundButton(glyph: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Sheet)
            .border(1.dp, Line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = label, tint = Ink, modifier = Modifier.size(22.dp))
    }
}

/** Les messages brefs, en bas de l'écran : une pilule d'encre, avec « Annuler » quand le geste peut se défaire. */
@Composable
fun Toasts(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val shape = RoundedCornerShape(26.dp)
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .shadow(10.dp, shape)
                .background(Ink, shape)
                .heightIn(min = 52.dp)
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                data.visuals.message,
                modifier = Modifier.weight(1f, fill = false).padding(top = 10.dp, bottom = 10.dp, end = 10.dp),
                color = Paper,
                style = MaterialTheme.typography.bodyMedium,
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) { Text(label, color = Paper, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Le champ de recherche : une pilule toujours visible, dont le bord s'allume quand on y écrit. */
@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, hint: String, tag: String, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val edge by animateColorAsState(if (focused) Blue else Line, label = "bord")
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.replace("\n", "")) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
        cursorBrush = SolidColor(Blue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        interactionSource = source,
        modifier = modifier.fillMaxWidth().testTag(tag),
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(44.dp)
                    .background(Sheet, CircleShape)
                    .border(1.dp, edge, CircleShape)
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyphs.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(Glyphs.Close, contentDescription = "Effacer la recherche", tint = Muted, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Spacer(Modifier.width(14.dp))
                }
            }
        },
    )
}

/** Un choix parmi quelques-uns, en pastille : pleine d'encre quand il est retenu. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val back by animateColorAsState(if (selected) Ink else Color.Transparent, label = "fond")
    val front by animateColorAsState(if (selected) Paper else Ink, label = "texte")
    val edge by animateColorAsState(if (selected) Ink else Line, label = "bord")
    Text(
        label,
        modifier = modifier
            .clip(CircleShape)
            .background(back)
            .border(1.dp, edge, CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        color = front,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
        maxLines = 1,
    )
}

/** Une fiche : le fond blanc arrondi sur lequel se rangent des lignes de réglages. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Sheet).border(1.dp, Line, shape), content = content)
}

/** Un filet entre deux lignes d'une fiche. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Une information à connaître avant de lire la suite : un encart discret, teinté de bleu. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BlueWash).padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
        color = Ink,
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Sheet,
    unfocusedContainerColor = Sheet,
    disabledContainerColor = Sheet,
    focusedBorderColor = Blue,
    unfocusedBorderColor = Line,
)

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = OnBlue,
    checkedTrackColor = Blue,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Line,
    uncheckedBorderColor = Color.Transparent,
)

/**
 * Fait entrer un élément en douceur : il monte de quelques points en s'éclaircissant.
 * [rank] décale son tour, pour une arrivée en cascade ; [active] faux, il est là d'emblée.
 */
@Composable
fun Arriving(rank: Int, active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (active && rank < 14) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, tween(520, delayMillis = 40 + rank * 45, easing = Glide))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 16.dp.toPx()
        },
    ) {
        content()
    }
}
