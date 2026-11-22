package com.valentinlarge.courrier

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La rédaction : une feuille qui monte par-dessus le reste. À l'envoi, l'avion du bouton s'envole
 * et le liseré « par avion » défile jusqu'à ce que le message soit parti.
 */
@Composable
fun ComposeScreen(vm: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    val sending = vm.sending
    // L'avion part vers le coin de l'écran ; il revient d'un coup, sans faire le trajet à l'envers.
    val flight by animateFloatAsState(
        if (sending) 1f else 0f,
        if (sending) tween(800, easing = CubicBezierEasing(0.5f, 0f, 0.8f, 0.4f)) else snap(),
        label = "envol",
    )

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Sheet)
            .solid()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyphs.Close, "Fermer la rédaction", onClick = { vm.back() }, enabled = !sending)
            Text(
                if (vm.draftReplyTo != null) "Réponse" else "Nouveau message",
                modifier = Modifier.weight(1f).padding(start = 2.dp),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GlyphButton(Glyphs.Trash, "Supprimer le brouillon", onClick = { confirmDiscard = true }, enabled = !sending, tint = Muted)
            Spacer(Modifier.width(4.dp))
            Pill(
                if (sending) "Patiente…" else "Envoyer",
                onClick = { vm.send() },
                glyph = Glyphs.Plane,
                enabled = !sending && vm.canSend,
                height = 46.dp,
                modifier = Modifier.testTag("envoyer"),
                glyphModifier = Modifier.graphicsLayer {
                    translationX = 64.dp.toPx() * flight
                    translationY = -96.dp.toPx() * flight
                    rotationZ = -14f * flight
                    scaleX = 1f - 0.3f * flight
                    scaleY = 1f - 0.3f * flight
                    alpha = (1f - (flight - 0.35f) / 0.65f).coerceIn(0f, 1f)
                },
            )
        }
        AirmailStripe(running = sending)

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (!vm.canSend) {
                Notice(
                    "L'envoi n'est pas encore activé sur le serveur : il manque la clé du service d'envoi.",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
            }
            vm.sendError?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    color = Red,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (vm.from.isNotBlank()) {
                // Avec plusieurs adresses, la ligne « De » se touche pour en choisir une autre.
                var choosing by remember { mutableStateOf(false) }
                val several = vm.addresses.size > 1
                val name = vm.from.substringBefore('<').trim()
                val chosen = vm.draftFrom.ifBlank { vm.address }
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(enabled = several && !sending) { choosing = true }
                            .padding(horizontal = 20.dp)
                            .testTag("expediteur"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldName("De")
                        // Même retrait que le texte saisi dans les lignes suivantes.
                        Text(
                            if (name.isNotBlank()) "$name <$chosen>" else chosen,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (several) Icon(Glyphs.ChevronDown, contentDescription = "Changer d'adresse", tint = Muted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        vm.addresses.forEach { address ->
                            DropdownMenuItem(
                                text = { Text(address, fontWeight = if (address == chosen) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    choosing = false
                                    vm.draftFrom = if (address == vm.address) "" else address
                                },
                            )
                        }
                    }
                }
                Hairline(Modifier.padding(horizontal = 20.dp))
            }
            FieldLine("À", vm.draftTo, { vm.draftTo = it }, "destinataire", KeyboardType.Email)
            FieldLine("Cc", vm.draftCc, { vm.draftCc = it }, "copie", KeyboardType.Email)
            FieldLine("Objet", vm.draftSubject, { vm.draftSubject = it }, "objet", KeyboardType.Text)

            TextField(
                value = vm.draftText,
                onValueChange = { vm.draftText = it },
                placeholder = { Text("Ton message", color = Muted) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                colors = bare(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 200.dp).testTag("message"),
            )

            vm.draftFiles.forEach { file ->
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(shape)
                        .background(Tint)
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Clip, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            file.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                        )
                        Text(fileSize(file.size), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    GlyphButton(Glyphs.Close, "Retirer ${file.name}", onClick = { vm.removeFile(file) }, enabled = !sending, tint = Muted)
                }
            }
            Spacer(Modifier.heightIn(min = 12.dp))
        }

        Hairline()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                "Joindre un fichier",
                onClick = { picker.launch(arrayOf("*/*")) },
                glyph = Glyphs.Clip,
                enabled = !sending,
                filled = false,
                height = 44.dp,
            )
        }
    }

    if (confirmDiscard) {
        DiscardDialog(
            onConfirm = {
                confirmDiscard = false
                vm.discardDraft()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer ce brouillon ?") },
        text = { Text("Ce que tu as écrit ici sera effacé. Pour le garder, ferme plutôt avec la croix : il ira dans les brouillons.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Supprimer", color = Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun bare() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@Composable
private fun FieldName(label: String) {
    Text(label, modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = Muted)
}

/** Une ligne d'en-tête du message : son nom à gauche, la saisie à droite, un filet dessous. */
@Composable
private fun FieldLine(label: String, value: String, onChange: (String) -> Unit, tag: String, keyboard: KeyboardType) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FieldName(label)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
            ),
            colors = bare(),
            modifier = Modifier.weight(1f).testTag(tag),
        )
    }
    Hairline(Modifier.padding(horizontal = 20.dp))
}
