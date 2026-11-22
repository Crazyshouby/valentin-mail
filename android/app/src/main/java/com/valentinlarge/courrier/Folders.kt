package com.valentinlarge.courrier

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Le panneau des dossiers personnels : en ouvrir un, en créer, en renommer, en supprimer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersSheet(vm: MainViewModel, onClose: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Label?>(null) }
    var deleting by remember { mutableStateOf<Label?>(null) }
    val current = labelIdOf(vm.folder)
    val currentBox = boxOf(vm.folder)

    LaunchedEffect(Unit) {
        vm.loadLabels()
        vm.loadBoxes()
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper,
        contentColor = Ink,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Text("Dossiers", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            Text(
                "Un même message peut être rangé dans plusieurs dossiers.",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            vm.labelError?.let {
                Text(
                    it,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    color = Red,
                    fontWeight = FontWeight.Medium,
                )
            }
            HorizontalDivider(color = Line)

            val tree = labelTree(vm.labels)
            if (tree.isEmpty()) {
                Text("Aucun dossier pour l'instant.", modifier = Modifier.padding(20.dp), color = Muted)
            }
            tree.forEach { node ->
                FolderLine(
                    node,
                    selected = node.label.id == current,
                    onOpen = {
                        vm.selectFolder(labelKey(node.label.id))
                        onClose()
                    },
                    onRename = { renaming = node.label },
                    onDelete = { deleting = node.label },
                )
            }

            TextButton(
                onClick = { creating = true },
                enabled = !vm.labelBusy,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).testTag("nouveau-dossier"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nouveau dossier")
            }

            // Avec une seule boîte, il n'y a rien à trier.
            if (vm.boxes.size > 1) {
                HorizontalDivider(color = Line)
                Text(
                    "Boîtes",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Le courrier reçu par chaque adresse, où qu'il soit rangé.",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                vm.boxes.forEach { box ->
                    BoxLine(
                        box,
                        selected = box.address == currentBox,
                        onOpen = {
                            vm.selectFolder(boxKey(box.address))
                            onClose()
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "Nouveau dossier",
            initial = "",
            action = "Créer",
            onDismiss = { creating = false },
            onConfirm = {
                creating = false
                vm.createLabel(it)
            },
        )
    }

    renaming?.let { label ->
        NameDialog(
            title = "Renommer le dossier",
            initial = label.name,
            action = "Renommer",
            onDismiss = { renaming = null },
            onConfirm = {
                renaming = null
                vm.renameLabel(label, it)
            },
        )
    }

    deleting?.let { label ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${label.name} » ?") },
            text = { Text("Le dossier disparaît, pas ses messages : ils restent dans la boîte.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    vm.deleteLabel(label)
                }) { Text("Supprimer", color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun FolderLine(node: LabelNode, selected: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val label = node.label
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) BlueWash else Color.Transparent)
            .clickable(onClick = onOpen)
            .testTag("dossier-${label.name}")
            .padding(start = (20 + node.depth * 18).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            node.short,
            modifier = Modifier.weight(1f),
            fontWeight = if (selected || label.unread > 0) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (label.unread > 0) {
            Badge(containerColor = Blue, contentColor = OnBlue) { Text(label.unread.toString()) }
            Spacer(Modifier.width(10.dp))
        }
        Text(label.total.toString(), style = MaterialTheme.typography.bodySmall, color = Muted)
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions sur le dossier ${label.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Renommer") }, onClick = {
                    menu = false
                    onRename()
                })
                DropdownMenuItem(text = { Text("Supprimer le dossier", color = Red) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

/** Une boîte d'origine : son adresse, ce qu'elle est, ses messages non lus et son total. */
@Composable
private fun BoxLine(box: MailBox, selected: Boolean, onOpen: () -> Unit) {
    val what = when (box.kind) {
        "main" -> "adresse principale"
        "outside" -> if (box.address.endsWith("@gmail.com") || box.address.endsWith("@googlemail.com")) "compte Gmail" else "compte extérieur"
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) BlueWash else Color.Transparent)
            .clickable(onClick = onOpen)
            .testTag("boite-${box.address}")
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                box.address,
                fontWeight = if (selected || box.unread > 0) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (what != null) Text(what, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (box.unread > 0) {
            Spacer(Modifier.width(10.dp))
            Badge(containerColor = Blue, contentColor = OnBlue) { Text(box.unread.toString()) }
        }
        Spacer(Modifier.width(10.dp))
        Text(box.total.toString(), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", "") },
                    label = { Text("Nom du dossier") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("nom-dossier"),
                )
                Text(
                    "Une barre oblique crée un sous-dossier : « Finances/Énergie ».",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank() && name.trim() != initial) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * Le choix d'un dossier où ranger. [current] : les dossiers où le message se trouve déjà
 * (vide quand on range plusieurs messages à la fois).
 */
@Composable
fun FilePicker(vm: MainViewModel, current: List<String>, onDismiss: () -> Unit, onPick: (Label) -> Unit) {
    LaunchedEffect(Unit) { vm.loadLabels() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ranger dans…") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val tree = labelTree(vm.labels)
                if (tree.isEmpty()) {
                    Text("Aucun dossier pour l'instant. Crées-en un depuis le menu en haut à gauche de la boîte.", color = Muted)
                }
                tree.forEach { node ->
                    val filed = node.label.id in current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(node.label) }
                            .testTag("ranger-${node.label.name}")
                            .padding(start = (node.depth * 18).dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            node.short,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (filed) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (filed) {
                            Icon(Icons.Filled.Check, contentDescription = "Déjà rangé ici : toucher pour l'en sortir", tint = Blue)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Le nom d'un dossier, en petite étiquette. */
@Composable
fun LabelChip(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        modifier = modifier.border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Les dossiers d'un message, sur autant de lignes qu'il faut. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelChips(names: List<String>, modifier: Modifier = Modifier) {
    if (names.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        names.forEach { LabelChip(it) }
    }
}
