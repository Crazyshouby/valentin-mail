package com.valentinlarge.courrier

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * La boîte : trois pages (le courrier, les pièces jointes, les réglages), la barre flottante
 * qui passe de l'une à l'autre, et le bouton « Écrire ».
 */
@Composable
fun Shell(vm: MainViewModel, cascade: Boolean) {
    val tab = vm.tab
    BoxWithConstraints(Modifier.fillMaxSize().background(Paper)) {
        // Sur un écran étroit, la barre se resserre pour laisser sa place au bouton « Écrire ».
        val slot = if (maxWidth < 372.dp) 54.dp else 64.dp

        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(400, easing = Glide)) { it / 28 }) togetherWith fadeOut(tween(140))
            },
            label = "page",
        ) { page ->
            when (page) {
                Screen.Gallery -> GalleryPage(vm)
                Screen.Settings -> SettingsPage(vm)
                else -> MailboxPage(vm, cascade)
            }
        }

        BottomBar(
            current = tab,
            slot = slot,
            onPick = { target ->
                if (target != tab) {
                    // On repasse par le courrier : la page quittée arrête ce qu'elle avait en cours.
                    vm.goMailbox()
                    when (target) {
                        Screen.Gallery -> vm.openGallery()
                        Screen.Settings -> vm.openSettings()
                        else -> Unit
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, bottom = 12.dp),
        )

        AnimatedVisibility(
            visible = tab == Screen.Mailbox && vm.selected.isEmpty(),
            enter = scaleIn(spring(dampingRatio = 0.62f, stiffness = 380f), initialScale = 0.6f) + fadeIn(tween(180)),
            exit = scaleOut(tween(180), targetScale = 0.6f) + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 12.dp),
        ) {
            Pill(
                "Écrire",
                onClick = { vm.compose() },
                glyph = Glyphs.Pencil,
                height = 60.dp,
                modifier = Modifier.shadow(10.dp, CircleShape).semantics { contentDescription = "Écrire un message" },
            )
        }
    }
}

private class Tab(val screen: Screen, val glyph: ImageVector, val label: String)

private val TABS = listOf(
    Tab(Screen.Mailbox, Glyphs.Mail, "Courrier"),
    Tab(Screen.Gallery, Glyphs.Clip, "Pièces jointes"),
    Tab(Screen.Settings, Glyphs.Sliders, "Réglages"),
)

/** La barre du bas : trois pages, et une pastille qui glisse sous celle qu'on regarde. */
@Composable
private fun BottomBar(current: Screen, slot: Dp, onPick: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val index = TABS.indexOfFirst { it.screen == current }.coerceAtLeast(0)
    val shift by animateDpAsState(slot * index, spring(dampingRatio = 0.68f, stiffness = 320f), label = "pastille")
    Box(
        modifier
            .shadow(10.dp, CircleShape)
            .background(Sheet, CircleShape)
            .border(1.dp, Line, CircleShape)
            .solid()
            .padding(6.dp),
    ) {
        Box(Modifier.offset(x = shift).size(slot, 46.dp).clip(CircleShape).background(BlueWash))
        Row {
            TABS.forEachIndexed { position, tab ->
                val selected = position == index
                val tint by animateColorAsState(if (selected) Blue else Muted, tween(250), label = "teinte")
                Box(
                    Modifier
                        .width(slot)
                        .height(46.dp)
                        .clip(CircleShape)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onPick(tab.screen) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tab.glyph, contentDescription = tab.label, tint = tint)
                }
            }
        }
    }
}
