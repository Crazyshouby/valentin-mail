package com.valentinlarge.courrier

import androidx.compose.material.icons.filled.Menu
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Une enveloppe : le liseré fait le tour, la carte blanche est posée dessus.
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .airmail()
                .padding(9.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Sheet, RoundedCornerShape(2.dp))
                    .padding(horizontal = 22.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Courrier", style = MaterialTheme.typography.displaySmall)
                Text("Entre le mot de passe pour ouvrir ta boîte.", color = Muted)

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("password"),
                )

                Row(
                    Modifier.fillMaxWidth().clickable { visible = !visible },
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

                Button(
                    onClick = submit,
                    enabled = password.isNotEmpty() && !vm.loginBusy && !refused,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (vm.loginBusy) "Ouverture…" else "Ouvrir la boîte")
                }

                if (!editServer) {
                    TextButton(onClick = { editServer = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Changer de serveur")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- boîte

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    var filing by remember { mutableStateOf(false) }
    val selecting = vm.selected.isNotEmpty()
    val isDrafts = vm.folder == "drafts"

    AskForNotifications(vm)

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper)) {
                if (selecting) {
                    SelectionBar(vm, onDeleteForever = { confirmDelete = true }, onFile = { filing = true })
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { folders = true }, modifier = Modifier.testTag("bouton-dossiers")) {
                                Icon(Icons.Filled.Menu, contentDescription = "Dossiers")
                            }
                        },
                        title = {
                            Column {
                                Text("Courrier", style = MaterialTheme.typography.titleLarge)
                                if (vm.address.isNotBlank()) {
                                    Text(
                                        vm.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!isDrafts) {
                                IconButton(onClick = {
                                    searching = !searching
                                    if (!searching && vm.query.isNotEmpty()) vm.search("")
                                }) {
                                    Icon(Icons.Filled.Search, contentDescription = "Chercher dans ce dossier")
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Autres actions")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Actualiser") }, onClick = {
                                        menu = false
                                        vm.refresh()
                                    })
                                    if (vm.folder == "trash" && vm.messages.isNotEmpty()) {
                                        DropdownMenuItem(text = { Text("Vider la corbeille", color = Red) }, onClick = {
                                            menu = false
                                            confirmEmpty = true
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Pièces jointes") }, onClick = {
                                        menu = false
                                        vm.openGallery()
                                    })
                                    DropdownMenuItem(text = { Text("Réglages") }, onClick = {
                                        menu = false
                                        vm.openSettings()
                                    })
                                    DropdownMenuItem(text = { Text("Se déconnecter") }, onClick = {
                                        menu = false
                                        vm.logout()
                                    })
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper, scrolledContainerColor = Paper),
                    )
                }
                AirmailStripe()
                if (isCrossView(vm.folder)) FolderBanner(vm, onFolders = { folders = true }) else FolderTabs(vm)
                if (searching && !isDrafts && !selecting) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = { vm.search(it) },
                        placeholder = { Text("Chercher dans les messages") },
                        singleLine = true,
                        trailingIcon = {
                            if (vm.query.isNotEmpty()) {
                                IconButton(onClick = { vm.search("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Effacer la recherche")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("recherche"),
                    )
                }
            }
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = { vm.compose() },
                    // Ce bouton ne transmet pas son texte aux lecteurs d'écran : l'icône porte donc son nom.
                    icon = { Icon(Icons.Filled.Create, contentDescription = "Écrire un message") },
                    text = { Text("Écrire") },
                    containerColor = Blue,
                    contentColor = OnBlue,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (vm.offline && !isDrafts) {
                    item(key = "hors-connexion") {
                        Text(
                            "Hors connexion : voici les messages enregistrés sur l'appareil.",
                            modifier = Modifier.fillMaxWidth().background(BlueWash).padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (isDrafts) {
                    items(vm.drafts, key = { it.id }) { draft -> DraftRow(draft, onClick = { vm.openDraft(draft) }) }
                } else {
                    items(vm.rows, key = { it.item.id }) { row -> SwipeRow(vm, row, selecting) }
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

/** La barre du haut pendant une sélection : ce qu'on peut faire des lignes cochées. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: MainViewModel, onDeleteForever: () -> Unit, onFile: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val count = vm.selected.size
    TopAppBar(
        title = { Text(if (count == 1) "1 sélectionné" else "$count sélectionnés", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = { vm.clearSelection() }) {
                Icon(Icons.Filled.Close, contentDescription = "Quitter la sélection")
            }
        },
        actions = {
            when (vm.folder) {
                "inbox" -> TextButton(onClick = {
                    vm.moveRows(vm.selectedRows(), "archive", counted(count, "Message archivé.", "messages archivés."))
                }) { Text("Archiver") }
                "trash" -> TextButton(onClick = { vm.restoreRows(vm.selectedRows()) }) { Text("Restaurer") }
            }
            IconButton(onClick = {
                if (vm.folder == "trash") {
                    onDeleteForever()
                } else {
                    vm.moveRows(vm.selectedRows(), "trash", counted(count, "Message mis à la corbeille.", "messages mis à la corbeille."))
                }
            }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (vm.folder == "trash") "Supprimer définitivement" else "Mettre à la corbeille",
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Autres actions")
                }
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
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BlueWash),
    )
}

@Composable
private fun FolderTabs(vm: MainViewModel) {
    val selected = FOLDERS.indexOfFirst { it.id == vm.folder }.coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selected,
        containerColor = Paper,
        contentColor = Ink,
        edgePadding = 4.dp,
        divider = { HorizontalDivider(color = Line) },
    ) {
        FOLDERS.forEachIndexed { index, folder ->
            Tab(
                selected = index == selected,
                onClick = { vm.selectFolder(folder.id) },
                selectedContentColor = Ink,
                unselectedContentColor = Muted,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.label, fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Medium)
                        if (folder.id == "inbox" && vm.unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(containerColor = Blue, contentColor = OnBlue) { Text(vm.unread.toString()) }
                        }
                    }
                },
            )
        }
    }
}

/** À la place des onglets quand la liste montre un dossier personnel ou une boîte d'origine : son nom, et de quoi revenir à la réception. */
@Composable
private fun FolderBanner(vm: MainViewModel, onFolders: () -> Unit) {
    val address = boxOf(vm.folder)
    val label = vm.currentLabel
    val name = address ?: label?.name ?: "…"
    val unread = if (address != null) vm.currentBox?.unread ?: 0 else label?.unread ?: 0
    Column {
        Row(
            Modifier.fillMaxWidth().background(BlueWash).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onFolders).testTag("bandeau-dossier").padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (address != null) "Boîte" else "Dossier", style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.width(8.dp))
                Text(
                    name,
                    modifier = Modifier.weight(1f, fill = false),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = Blue, contentColor = OnBlue) { Text(unread.toString()) }
                }
            }
            IconButton(onClick = { vm.selectFolder("inbox") }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = if (address != null) "Fermer la boîte et revenir à la réception" else "Fermer le dossier et revenir à la réception",
                )
            }
        }
        HorizontalDivider(color = Line)
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
            val direction = state.dismissDirection
            val archive = direction == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        when (direction) {
                            SwipeToDismissBoxValue.StartToEnd -> Blue
                            SwipeToDismissBoxValue.EndToStart -> Red
                            SwipeToDismissBoxValue.Settled -> Color.Transparent
                        }
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = if (archive) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Text(
                        if (!archive) "Corbeille" else if (folder == "trash") "Restaurer" else "Archiver",
                        color = OnBlue,
                        fontWeight = FontWeight.SemiBold,
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
    val who = if (item.outgoing) "À : ${item.toAddr}" else item.fromName.ifBlank { item.fromAddr }.ifBlank { "Expéditeur inconnu" }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) BlueWash else Sheet)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .height(IntrinsicSize.Min)
        ) {
            // Non lu : filet bleu à gauche, expéditeur et objet en gras.
            Box(Modifier.width(4.dp).fillMaxHeight().background(if (unread || selected) Blue else Color.Transparent))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 16.dp, top = 11.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            who,
                            modifier = Modifier.weight(1f, fill = false),
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
                                    .border(1.dp, Line, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(shortDate(item.date), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.subject.ifBlank { "(sans objet)" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unread) Ink else Muted,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Les dossiers où le message est rangé : deux au plus, le reste en nombre.
                    tags.take(2).forEach { LabelChip(it, Modifier.padding(start = 6.dp).widthIn(max = 110.dp)) }
                    if (tags.size > 2) {
                        Text("+${tags.size - 2}", modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun DraftRow(draft: Draft, onClick: () -> Unit) {
    val preview = draft.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith(">") }.orEmpty()
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Sheet)
                .clickable(onClick = onClick)
                .padding(start = 18.dp, end = 16.dp, top = 11.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (draft.to.isBlank()) "Sans destinataire" else "À : ${draft.to}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Text(shortDate(draft.updated), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(
                draft.subject.ifBlank { "(sans objet)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotBlank()) {
                Text(preview, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun ListFoot(vm: MainViewModel) {
    val isDrafts = vm.folder == "drafts"
    val empty = if (isDrafts) vm.drafts.isEmpty() else vm.messages.isEmpty()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
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
