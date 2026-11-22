package com.valentinlarge.courrier

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// --------------------------------------------------------------- connexion

@Composable
fun LoginScreen(vm: MainViewModel) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(vm.prefs.server) }
    var editServer by rememberSaveable { mutableStateOf(false) }
    var lastTried by rememberSaveable { mutableStateOf("") }
    // Renvoyer un mot de passe que le serveur vient de refuser ne ferait qu'user un essai.
    val refused = vm.loginRefused && password == lastTried
    val submit = {
        if (password.isNotEmpty() && !vm.loginBusy && !refused) {
            lastTried = password
            vm.login(server, password)
        }
    }

    Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding()) {
        // Le liseré « par avion » ouvre l'écran ; il défile pendant que le serveur vérifie le mot de passe.
        AirmailStripe(running = vm.loginBusy, height = 8.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Courrier", style = MaterialTheme.typography.displaySmall)
                Text("Entre le mot de passe pour ouvrir ta boîte.", color = Muted)
                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = FieldShape,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("password"),
                )

                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { visible = !visible },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = visible, onCheckedChange = { visible = it })
                    Text("Afficher le mot de passe", color = Muted)
                }

                if (editServer) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Adresse du serveur") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = FieldShape,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                vm.loginError?.let { Text(it, color = Red, fontWeight = FontWeight.Medium) }
                if (refused) {
                    Text(
                        "Corrige le mot de passe avant de réessayer : au bout de huit refus, le serveur bloque un quart d'heure.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Pill(
                    if (vm.loginBusy) "Ouverture…" else "Ouvrir la boîte",
                    onClick = submit,
                    enabled = password.isNotEmpty() && !vm.loginBusy && !refused,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!editServer) {
                    TextButton(onClick = { editServer = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Changer de serveur", color = Muted)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- boîte

/** L'en-tête d'un groupe de messages : le jour, ou le mois pour les plus anciens. */
private class DayHead(val label: String, val key: String)

/** La page du courrier : le dossier ouvert, sa recherche, et ses messages regroupés par jour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxPage(vm: MainViewModel, cascade: Boolean) {
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    var filing by remember { mutableStateOf(false) }
    val selecting = vm.selected.isNotEmpty()
    val isDrafts = vm.folder == "drafts"

    AskForNotifications(vm)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)) {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "en-tête",
            ) { on ->
                if (on) {
                    SelectionBar(vm, onDeleteForever = { confirmDelete = true }, onFile = { filing = true })
                } else {
                    FolderTitle(vm, onFolders = {
                        vm.revealCurrentFolder()
                        folders = true
                    })
                }
            }
            if (!isDrafts) {
                Spacer(Modifier.height(8.dp))
                SearchPill(vm.query, { vm.search(it) }, hint = "Rechercher dans ce dossier", tag = "recherche")
            }
        }

        // Les messages et, entre eux, le jour qui les rassemble. Une recherche ne suit pas l'ordre des dates : pas d'en-têtes.
        val rows = vm.rows
        val plain = vm.query.isNotBlank()
        val entries = remember(rows, plain) {
            val out = ArrayList<Any>(rows.size + 8)
            var last: String? = null
            for (row in rows) {
                if (!plain) {
                    val day = dayBucket(row.item.date)
                    if (day != last) {
                        out.add(DayHead(day, "jour-$day-${row.item.id}"))
                        last = day
                    }
                }
                out.add(row)
            }
            out
        }

        PullToRefreshBox(isRefreshing = vm.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                if (vm.offline && !isDrafts) {
                    item(key = "hors-connexion") {
                        Notice(
                            "Hors connexion : voici les messages enregistrés sur l'appareil.",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (vm.folder == "trash" && vm.messages.isNotEmpty() && !selecting) {
                    item(key = "vider") {
                        TextButton(onClick = { confirmEmpty = true }, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text("Vider la corbeille", color = Red)
                        }
                    }
                }
                if (isDrafts) {
                    items(vm.drafts, key = { it.id }) { draft ->
                        DraftRow(draft, onClick = { vm.openDraft(draft) }, modifier = Modifier.animateItem())
                    }
                } else {
                    itemsIndexed(
                        entries,
                        key = { _, entry -> if (entry is ThreadRow) entry.item.id else (entry as DayHead).key },
                        contentType = { _, entry -> entry is ThreadRow },
                    ) { index, entry ->
                        Arriving(index, cascade, Modifier.animateItem()) {
                            if (entry is ThreadRow) {
                                SwipeRow(vm, entry, selecting)
                            } else {
                                Text(
                                    (entry as DayHead).label,
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Muted,
                                )
                            }
                        }
                    }
                }
                item(key = "pied") { ListFoot(vm) }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Vider la corbeille ?") },
            text = { Text("Tous les messages de la corbeille seront supprimés définitivement.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.emptyTrash()
                }) { Text("Vider la corbeille", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Annuler") } },
        )
    }

    if (folders) FoldersSheet(vm, onClose = { folders = false })

    if (filing) {
        FilePicker(
            vm,
            current = emptyList(),
            onDismiss = { filing = false },
            onPick = { label ->
                filing = false
                vm.fileRows(vm.selectedRows(), label)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer la sélection ?") },
            text = { Text("Ces messages seront effacés définitivement, sans retour possible.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteRows(vm.selectedRows())
                }) { Text("Supprimer définitivement", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

private fun counted(count: Int, one: String, many: String): String = if (count == 1) one else "$count $many"

/** Le grand titre de la page : le dossier ou la boîte qu'on regarde. Le toucher ouvre la liste des autres. */
@Composable
private fun FolderTitle(vm: MainViewModel, onFolders: () -> Unit) {
    val address = boxOf(vm.folder)
    val inLabel = labelIdOf(vm.folder) != null
    val label = vm.currentLabel
    val name = when {
        address != null -> address
        inLabel -> label?.name ?: "…"
        else -> folderOf(vm.folder).label
    }
    val unread = when {
        address != null -> vm.currentBox?.unread ?: 0
        inLabel -> label?.unread ?: 0
        vm.folder == "inbox" -> vm.unread
        else -> 0
    }
    val total = if (address != null) vm.currentBox?.total else if (inLabel) label?.total else null
    val subtitle = when {
        unread == 1 -> "1 non lu"
        unread > 1 -> "$unread non lus"
        total != null -> if (total == 1) "1 message" else "$total messages"
        else -> vm.address
    }
    Column(Modifier.fillMaxWidth().height(64.dp), verticalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Changer de dossier", role = Role.Button, onClick = onFolders)
                .testTag("bouton-dossiers")
                .heightIn(min = 40.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f, fill = false),
                style = if (name.length > 16) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Icon(Glyphs.ChevronDown, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** À la place du titre pendant une sélection : ce qu'on peut faire des lignes cochées. */
@Composable
private fun SelectionBar(vm: MainViewModel, onDeleteForever: () -> Unit, onFile: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // Le temps que la barre s'efface, la sélection est déjà vide : on n'affiche pas « 0 ».
    val count = vm.selected.size.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(64.dp).offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyphs.Close, "Quitter la sélection", onClick = { vm.clearSelection() })
        Text(
            if (count == 1) "1 sélectionné" else "$count sélectionnés",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (vm.folder) {
            "inbox" -> TextButton(onClick = {
                vm.moveRows(vm.selectedRows(), "archive", counted(count, "Message archivé.", "messages archivés."))
            }) { Text("Archiver") }
            "trash" -> TextButton(onClick = { vm.restoreRows(vm.selectedRows()) }) { Text("Restaurer") }
        }
        GlyphButton(
            Glyphs.Trash,
            if (vm.folder == "trash") "Supprimer définitivement" else "Mettre à la corbeille",
            onClick = {
                if (vm.folder == "trash") {
                    onDeleteForever()
                } else {
                    vm.moveRows(vm.selectedRows(), "trash", counted(count, "Message mis à la corbeille.", "messages mis à la corbeille."))
                }
            },
        )
        Box {
            GlyphButton(Glyphs.More, "Autres actions", onClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (vm.folder != "trash") {
                    DropdownMenuItem(text = { Text("Ranger dans…") }, onClick = {
                        menu = false
                        onFile()
                    })
                }
                if (labelIdOf(vm.folder) != null) {
                    DropdownMenuItem(text = { Text("Sortir de ce dossier") }, onClick = {
                        menu = false
                        vm.unfileRows(vm.selectedRows())
                    })
                }
                DropdownMenuItem(text = { Text("Marquer lu") }, onClick = {
                    menu = false
                    vm.markRows(vm.selectedRows(), true)
                })
                DropdownMenuItem(text = { Text("Marquer non lu") }, onClick = {
                    menu = false
                    vm.markRows(vm.selectedRows(), false)
                })
            }
        }
    }
}

/** Une ligne qu'on peut glisser : vers la droite pour archiver, vers la gauche pour la corbeille. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(vm: MainViewModel, row: ThreadRow, selecting: Boolean) {
    val folder = vm.folder
    val toEnd: (() -> Unit)? = when (folder) {
        "inbox" -> {
            { vm.moveRows(listOf(row), "archive", counted(row.ids.size, "Message archivé.", "messages archivés.")) }
        }
        "trash" -> {
            { vm.restoreRows(listOf(row)) }
        }
        else -> null
    }
    val toStart: (() -> Unit)? = if (folder == "trash") {
        null
    } else {
        { vm.moveRows(listOf(row), "trash", counted(row.ids.size, "Message mis à la corbeille.", "messages mis à la corbeille.")) }
    }
    val onEnd by rememberUpdatedState(toEnd)
    val onStart by rememberUpdatedState(toStart)

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onEnd?.invoke()
                    onEnd != null
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onStart?.invoke()
                    onStart != null
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    // Une ligne revenue après « Annuler » doit réapparaître à sa place, pas glissée hors de l'écran.
    LaunchedEffect(row.item.id) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) state.snapTo(SwipeToDismissBoxValue.Settled)
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = toEnd != null && !selecting,
        enableDismissFromEndToStart = toStart != null && !selecting,
        backgroundContent = {
            // Sous la ligne qui glisse : la destination, dite par son pictogramme.
            val direction = state.dismissDirection
            val towardsEnd = direction == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        when (direction) {
                            SwipeToDismissBoxValue.StartToEnd -> BlueWash
                            SwipeToDismissBoxValue.EndToStart -> RedWash
                            SwipeToDismissBoxValue.Settled -> Color.Transparent
                        }
                    )
                    .padding(horizontal = 26.dp),
                contentAlignment = if (towardsEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        if (!towardsEnd) Glyphs.Trash else if (folder == "trash") Glyphs.Mail else Glyphs.Archive,
                        contentDescription = null,
                        tint = if (towardsEnd) Blue else Red,
                    )
                }
            }
        },
    ) {
        MessageRow(
            row,
            tags = vm.tagsOf(row.item),
            selected = row.item.id in vm.selected,
            onClick = { if (selecting) vm.toggleSelect(row) else vm.openRow(row) },
            onLongClick = { vm.toggleSelect(row) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(row: ThreadRow, tags: List<String>, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val item = row.item
    val unread = row.unread
    val name = if (item.outgoing) item.toAddr else item.fromName.ifBlank { item.fromAddr }
    val who = if (item.outgoing) "À : ${item.toAddr}" else name.ifBlank { "Expéditeur inconnu" }
    val back by animateColorAsState(if (selected) BlueWash else Paper, label = "fond")
    // Non lu : une pastille bleue dans la marge, l'expéditeur et l'objet en gras.
    val dot by animateFloatAsState(if (unread && !selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "pastille")

    Box(Modifier.fillMaxWidth().background(back).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .padding(start = 4.5.dp, top = 22.5.dp)
                .size(7.dp)
                .graphicsLayer {
                    scaleX = dot
                    scaleY = dot
                    alpha = dot.coerceIn(0f, 1f)
                }
                .background(Blue, CircleShape),
        )
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            if (selected) {
                Box(Modifier.size(36.dp).background(Blue, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.Check, contentDescription = "Sélectionné", tint = OnBlue, modifier = Modifier.size(20.dp))
                }
            } else {
                Avatar(name, seed = if (item.outgoing) item.toAddr else item.fromAddr)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            who,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.count > 1) {
                            // Plusieurs messages dans la conversation : leur nombre, à côté du nom.
                            Text(
                                row.count.toString(),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                    }
                    if (item.files > 0) {
                        Icon(
                            Glyphs.Clip,
                            contentDescription = "Pièce jointe",
                            tint = Muted,
                            modifier = Modifier.padding(start = 8.dp).size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(shortDate(item.date), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.subject.ifBlank { "(sans objet)" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Les dossiers où le message est rangé : deux au plus, le reste en nombre.
                    tags.take(2).forEach { LabelChip(it, Modifier.padding(start = 6.dp).widthIn(max = 110.dp)) }
                    if (tags.size > 2) {
                        Text("+${tags.size - 2}", modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                if (item.snippet.isNotBlank()) {
                    Text(
                        item.snippet,
                        modifier = Modifier.padding(top = 1.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftRow(draft: Draft, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val preview = draft.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith(">") }.orEmpty()
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(36.dp).background(Tint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.Pencil, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (draft.to.isBlank()) "Sans destinataire" else "À : ${draft.to}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(shortDate(draft.updated), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(
                draft.subject.ifBlank { "(sans objet)" },
                modifier = Modifier.padding(top = 1.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ListFoot(vm: MainViewModel) {
    val isDrafts = vm.folder == "drafts"
    val empty = if (isDrafts) vm.drafts.isEmpty() else vm.messages.isEmpty()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = if (empty) 56.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val error = vm.listError
        when {
            error != null -> {
                Text(error, color = Red, textAlign = TextAlign.Center)
                OutlinedButton(onClick = { vm.refresh() }) { Text("Réessayer") }
            }
            empty && vm.loading -> Text("Chargement…", color = Muted)
            empty -> Text(
                if (vm.query.isNotBlank() && !isDrafts) "Aucun message ne correspond à « ${vm.query} » dans ce dossier." else folderOf(vm.folder).empty,
                color = Muted,
                textAlign = TextAlign.Center,
            )
            vm.hasMore && !isDrafts -> OutlinedButton(onClick = { vm.loadMore() }, enabled = !vm.loadingMore) {
                Text(if (vm.loadingMore) "Chargement…" else "Afficher les messages plus anciens")
            }
        }
    }
}

/** Demande une seule fois le droit d'afficher les notifications de nouveaux messages. */
@Composable
private fun AskForNotifications(vm: MainViewModel) {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !vm.prefs.askedNotifications) {
            vm.prefs.askedNotifications = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
