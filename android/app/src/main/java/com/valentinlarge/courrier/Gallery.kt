package com.valentinlarge.courrier

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Toutes les pièces jointes de la boîte, des plus récentes aux plus anciennes, sans ouvrir les messages un à un. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toSave by remember { mutableStateOf<LocalFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val file = toSave
        toSave = null
        if (target != null && file != null) scope.launch { vm.notice = saveCopy(context, file, target) }
    }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper)) {
                TopAppBar(
                    title = { Text("Pièces jointes", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour à la boîte")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                )
                AirmailStripe()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ATTACHMENT_KINDS.forEach { (id, label) ->
                        KindChip(
                            label,
                            selected = vm.galleryKind == id,
                            tag = "famille-" + id.ifBlank { "tout" },
                            onClick = { vm.filterGallery(id) },
                        )
                    }
                }
                OutlinedTextField(
                    value = vm.galleryQuery,
                    onValueChange = { vm.searchGallery(it) },
                    placeholder = { Text("Nom du fichier, expéditeur, objet") },
                    singleLine = true,
                    trailingIcon = {
                        if (vm.galleryQuery.isNotEmpty()) {
                            IconButton(onClick = { vm.searchGallery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Effacer la recherche")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp).testTag("recherche-pieces"),
                )
                HorizontalDivider(color = Line)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.galleryLoading,
            onRefresh = { vm.reloadGallery() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                val paused = vm.galleryPaused
                if (paused != null || vm.galleryPending > 0) {
                    item(key = "inventaire") {
                        Text(
                            paused ?: (
                                (if (vm.galleryPending == 1) "Inventaire en cours : il reste 1 message à examiner."
                                else "Inventaire en cours : il reste ${vm.galleryPending} messages à examiner.") +
                                    " La liste se complète toute seule tant que cet écran reste ouvert."
                                ),
                            modifier = Modifier.fillMaxWidth().background(BlueWash).padding(horizontal = 16.dp, vertical = 10.dp).testTag("inventaire"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(vm.gallery, key = { it.key }) { attachment ->
                    AttachmentRow(
                        vm,
                        attachment,
                        onOpen = { file -> openFile(context, file)?.let { vm.notice = it } },
                        onSave = { file ->
                            toSave = file
                            saver.launch(file.name)
                        },
                    )
                }
                item(key = "pied") { GalleryFoot(vm) }
            }
        }
    }
}

/** Un filtre de la galerie : une famille de fichiers. */
@Composable
private fun KindChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        label,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Blue else Color.Transparent)
            .border(1.dp, if (selected) Blue else Line, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        color = if (selected) OnBlue else Ink,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
    )
}

/** Ce qu'on lit dans le carré à gauche d'une pièce : son extension, ou à défaut sa famille. */
private fun badgeOf(attachment: Attachment): String {
    val extension = attachment.name.substringAfterLast('.', "").trim()
    if (extension.length in 1..4 && extension.all { it.isLetterOrDigit() }) return extension.uppercase()
    return when (attachment.kind) {
        "image" -> "IMG"
        "pdf" -> "PDF"
        "doc" -> "DOC"
        "media" -> "AV"
        else -> "…"
    }
}

@Composable
private fun AttachmentRow(
    vm: MainViewModel,
    attachment: Attachment,
    onOpen: (LocalFile) -> Unit,
    onSave: (LocalFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val act = { then: (LocalFile) -> Unit ->
        menu = false
        if (!busy) {
            busy = true
            scope.launch {
                val file = vm.fetchAttachment(attachment)
                busy = false
                if (file != null) then(file)
            }
        }
    }
    val who = if (attachment.outgoing) {
        "À : ${attachment.toAddr}"
    } else {
        "De : " + attachment.fromName.ifBlank { attachment.fromAddr }.ifBlank { "expéditeur inconnu" }
    }

    Column {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Sheet)
                    .clickable { menu = true }
                    .testTag("piece-${attachment.name}")
                    .padding(start = 14.dp, end = 16.dp, top = 11.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).background(BlueWash, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badgeOf(attachment), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Blue, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            attachment.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(shortDate(attachment.date), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Text(who, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            attachment.subject.ifBlank { "(sans objet)" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (busy) "Téléchargement…" else fileSize(attachment.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Ouvrir") }, onClick = { act(onOpen) })
                DropdownMenuItem(text = { Text("Enregistrer sur l'appareil") }, onClick = { act(onSave) })
                DropdownMenuItem(text = { Text("Voir le message") }, onClick = {
                    menu = false
                    vm.openFromGallery(attachment)
                })
            }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun GalleryFoot(vm: MainViewModel) {
    val filtered = vm.galleryKind.isNotBlank() || vm.galleryQuery.isNotBlank()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val error = vm.galleryError
        when {
            error != null -> {
                Text(error, color = Red, textAlign = TextAlign.Center)
                OutlinedButton(onClick = { vm.reloadGallery() }) { Text("Réessayer") }
            }
            vm.gallery.isEmpty() && vm.galleryLoading -> Text("Chargement…", color = Muted)
            vm.gallery.isEmpty() -> Text(
                when {
                    filtered -> "Aucune pièce jointe ne correspond à ce filtre."
                    vm.galleryPending > 0 -> "Aucune pièce jointe relevée pour l'instant."
                    else -> "Aucune pièce jointe dans la boîte."
                },
                color = Muted,
                textAlign = TextAlign.Center,
            )
            vm.galleryMore -> OutlinedButton(onClick = { vm.moreGallery() }, enabled = !vm.galleryLoadingMore) {
                Text(if (vm.galleryLoadingMore) "Chargement…" else "Afficher les pièces plus anciennes")
            }
        }
    }
}
