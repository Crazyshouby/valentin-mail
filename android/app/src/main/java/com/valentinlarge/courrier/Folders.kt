package com.valentinlarge.courrier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val SYSTEM_GLYPHS = mapOf(
    "inbox" to Glyphs.Mail,
    "drafts" to Glyphs.Pencil,
    "sent" to Glyphs.Plane,
    "archive" to Glyphs.Archive,
    "trash" to Glyphs.Trash,
)

/**
 * Le panneau qui monte quand on touche le titre de la page : les dossiers de la boîte, les dossiers
 * personnels (en ouvrir un, en créer, en renommer, en supprimer) et les boîtes d'origine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersSheet(vm: MainViewModel, onClose: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Label?>(null) }
    var deleting by remember { mutableStateOf<Label?>(null) }
    val current = labelIdOf(vm.folder)
    val currentBox = boxOf(vm.folder)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Le panneau redescend avant de disparaître, au lieu de s'effacer d'un coup.
    val pick: (String) -> Unit = { view ->
        vm.selectFolder(view)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onClose() }
    }

    LaunchedEffect(Unit) {
        vm.loadLabels()
        vm.loadBoxes()
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Sheet,
        contentColor = Ink,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Line))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FOLDERS.forEach { folder ->
                SheetLine(
                    glyph = SYSTEM_GLYPHS[folder.id] ?: Glyphs.Folder,
                    title = folder.label,
                    selected = vm.folder == folder.id,
                    unread = if (folder.id == "inbox") vm.unread else 0,
                    onClick = { pick(folder.id) },
                    modifier = Modifier.testTag("systeme-${folder.id}"),
                )
            }

            SheetHeading("Dossiers", "Un même message peut être rangé dans plusieurs dossiers.")
            vm.labelError?.let {
                Text(it, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), color = Red, fontWeight = FontWeight.Medium)
            }
            val tree = labelTree(vm.labels)
            if (tree.isEmpty()) {
                Text("Aucun dossier pour l'instant.", modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), color = Muted)
            }
            tree.forEach { node ->
                FolderLine(
                    node,
                    selected = node.label.id == current,
                    onOpen = { pick(labelKey(node.label.id)) },
                    onRename = { renaming = node.label },
                    onDelete = { deleting = node.label },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !vm.labelBusy) { creating = true }
                    .testTag("nouveau-dossier")
                    .heightIn(min = 50.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyphs.Plus, contentDescription = null, tint = Blue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text("Nouveau dossier", color = Blue, fontWeight = FontWeight.SemiBold)
            }

            // Avec une seule boîte, il n'y a rien à trier.
            if (vm.boxes.size > 1) {
                SheetHeading("Boîtes", "Le courrier reçu par chaque adresse, où qu'il soit rangé.")
                vm.boxes.forEach { box ->
                    val what = when (box.kind) {
                        "main" -> "adresse principale"
                        "outside" -> if (box.address.endsWith("@gmail.com") || box.address.endsWith("@googlemail.com")) "compte Gmail" else "compte extérieur"
                        else -> null
                    }
                    SheetLine(
                        glyph = Glyphs.At,
                        title = box.address,
                        note = what,
                        selected = box.address == currentBox,
                        unread = box.unread,
                        total = box.total,
                        onClick = { pick(boxKey(box.address)) },
                        modifier = Modifier.testTag("boite-${box.address}"),
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

/** Le titre d'une partie du panneau, et ce qu'il faut en savoir. */
@Composable
private fun SheetHeading(title: String, help: String) {
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Muted)
        Text(help, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** Le compteur à droite d'une ligne : les non lus en pastille, puis le total. */
@Composable
private fun Counts(unread: Int, total: Int?) {
    if (unread > 0) {
        Text(
            unread.toString(),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Blue).padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Bold,
            color = OnBlue,
        )
    }
    if (total != null) {
        Spacer(Modifier.width(10.dp))
        Text(total.toString(), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** Une ligne du panneau : un pictogramme, un nom, ses compteurs. Celle qu'on regarde est teintée. */
@Composable
private fun SheetLine(
    glyph: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    unread: Int = 0,
    total: Int? = null,
    indent: Dp = 0.dp,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) BlueWash else Color.Transparent)
            .clickable(onClick = onClick)
            .then(modifier)
            .heightIn(min = 50.dp)
            .padding(start = 12.dp + indent, end = if (trailing != null) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, contentDescription = null, tint = if (selected) Blue else Ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                title,
                color = if (selected) Blue else Ink,
                fontWeight = if (selected || unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.width(10.dp))
        Counts(unread, total)
        trailing?.invoke()
    }
}

@Composable
private fun FolderLine(node: LabelNode, selected: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val label = node.label
    SheetLine(
        glyph = Glyphs.Folder,
        title = node.short,
        selected = selected,
        unread = label.unread,
        total = label.total,
        indent = (node.depth * 18).dp,
        onClick = onOpen,
        modifier = Modifier.testTag("dossier-${label.name}"),
        trailing = {
            Box {
                GlyphButton(Glyphs.More, "Actions sur le dossier ${label.name}", onClick = { menu = true }, tint = Muted)
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
        },
    )
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
                    shape = FieldShape,
                    colors = fieldColors(),
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
                    Text("Aucun dossier pour l'instant. Crées-en un en touchant le titre de la page du courrier.", color = Muted)
                }
                tree.forEach { node ->
                    val filed = node.label.id in current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(node.label) }
                            .testTag("ranger-${node.label.name}")
                            .padding(start = (node.depth * 18).dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Glyphs.Folder, contentDescription = null, tint = if (filed) Blue else Muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            node.short,
                            modifier = Modifier.weight(1f),
                            color = if (filed) Blue else Ink,
                            fontWeight = if (filed) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (filed) {
                            Icon(Glyphs.Check, contentDescription = "Déjà rangé ici : toucher pour l'en sortir", tint = Blue)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Le nom d'un dossier, en petite étiquette teintée. */
@Composable
fun LabelChip(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(BlueWash).padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        fontWeight = FontWeight.SemiBold,
        color = Blue,
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
