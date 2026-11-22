package com.valentinlarge.courrier

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val submit = { if (password.isNotEmpty() && !vm.loginBusy) vm.login(server, password) }

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
                    .background(Color.White, RoundedCornerShape(2.dp))
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

                Button(
                    onClick = submit,
                    enabled = password.isNotEmpty() && !vm.loginBusy,
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

    AskForNotifications(vm)

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper)) {
                TopAppBar(
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
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching && vm.query.isNotEmpty()) vm.search("")
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "Chercher dans ce dossier")
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
                                DropdownMenuItem(text = { Text("Se déconnecter") }, onClick = {
                                    menu = false
                                    vm.logout()
                                })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper, scrolledContainerColor = Paper),
                )
                AirmailStripe()
                FolderTabs(vm)
                if (searching) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = { vm.search(it) },
                        placeholder = { Text("Chercher un expéditeur ou un objet") },
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
            ExtendedFloatingActionButton(
                onClick = { vm.compose() },
                icon = { Icon(Icons.Filled.Create, contentDescription = null) },
                text = { Text("Écrire") },
                containerColor = Blue,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (vm.offline) {
                    item(key = "hors-connexion") {
                        Text(
                            "Hors connexion : voici les messages enregistrés sur l'appareil.",
                            modifier = Modifier.fillMaxWidth().background(BlueWash).padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(vm.messages, key = { it.id }) { item ->
                    MessageRow(item, onClick = { vm.open(item.id) })
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
                            Badge(containerColor = Blue, contentColor = Color.White) { Text(vm.unread.toString()) }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun MessageRow(item: MailItem, onClick: () -> Unit) {
    val unread = !item.isRead
    val who = if (item.outgoing) "À : ${item.toAddr}" else item.fromName.ifBlank { item.fromAddr }.ifBlank { "Expéditeur inconnu" }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .clickable(onClick = onClick)
                .height(IntrinsicSize.Min)
        ) {
            // Non lu : filet bleu à gauche, expéditeur et objet en gras.
            Box(Modifier.width(4.dp).fillMaxHeight().background(if (unread) Blue else Color.Transparent))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 16.dp, top = 11.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        who,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(shortDate(item.date), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Text(
                    item.subject.ifBlank { "(sans objet)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) Ink else Muted,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun ListFoot(vm: MainViewModel) {
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
            vm.messages.isEmpty() && vm.loading -> Text("Chargement des messages…", color = Muted)
            vm.messages.isEmpty() -> Text(
                if (vm.query.isNotBlank()) "Aucun message ne correspond à « ${vm.query} » dans ce dossier." else folderOf(vm.folder).empty,
                color = Muted,
                textAlign = TextAlign.Center,
            )
            vm.hasMore -> OutlinedButton(onClick = { vm.loadMore() }, enabled = !vm.loadingMore) {
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
