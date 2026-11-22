package com.valentinlarge.courrier

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Une rangée de la grille : trois images au plus, côte à côte. */
private class TileRow(val items: List<Attachment>) {
    val key: String get() = "tuiles-" + items.first().key
}

/** Ce qu'on fait d'une pièce touchée : son menu est-il ouvert, est-elle en cours de téléchargement. */
private class PieceState {
    var menu by mutableStateOf(false)
    var busy by mutableStateOf(false)
}

/**
 * La page des pièces jointes : toutes celles de la boîte, des plus récentes aux plus anciennes,
 * sans ouvrir les messages un à un. Les images se rangent en grille, les autres fichiers en lignes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPage(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var toSave by remember { mutableStateOf<LocalFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val file = toSave
        toSave = null
        if (target != null && file != null) scope.launch { vm.notice = saveCopy(context, file, target) }
    }
    val onOpen: (LocalFile) -> Unit = { file -> openFile(context, file)?.let { vm.notice = it } }
    val onSave: (LocalFile) -> Unit = { file ->
        toSave = file
        saver.launch(file.name)
    }

    // Les images qui se suivent forment des rangées de trois ; un autre fichier referme la grille en cours.
    val gallery = vm.gallery
    val blocks = remember(gallery) {
        val out = ArrayList<Any>(gallery.size)
        val run = ArrayList<Attachment>()
        for (attachment in gallery) {
            if (attachment.kind == "image") {
                run.add(attachment)
            } else {
                run.chunked(3).forEach { out.add(TileRow(it)) }
                run.clear()
                out.add(attachment)
            }
        }
        run.chunked(3).forEach { out.add(TileRow(it)) }
        out
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).height(48.dp), contentAlignment = Alignment.CenterStart) {
            Text("Pièces jointes", style = MaterialTheme.typography.headlineMedium, maxLines = 1)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ATTACHMENT_KINDS.forEach { (id, label) ->
                Chip(
                    label,
                    selected = vm.galleryKind == id,
                    onClick = { vm.filterGallery(id) },
                    modifier = Modifier.testTag("famille-" + id.ifBlank { "tout" }),
                )
            }
        }
        SearchPill(
            vm.galleryQuery,
            { vm.searchGallery(it) },
            hint = "Nom du fichier, expéditeur, objet",
            tag = "recherche-pieces",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )

        PullToRefreshBox(isRefreshing = vm.galleryLoading, onRefresh = { vm.reloadGallery() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                val paused = vm.galleryPaused
                if (paused != null || vm.galleryPending > 0) {
                    item(key = "inventaire") {
                        Notice(
                            paused ?: (
                                (if (vm.galleryPending == 1) "Inventaire en cours : il reste 1 message à examiner."
                                else "Inventaire en cours : il reste ${vm.galleryPending} messages à examiner.") +
                                    " La liste se complète toute seule tant que cette page reste ouverte."
                                ),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("inventaire"),
                        )
                    }
                }
                items(
                    blocks,
                    key = { block -> if (block is TileRow) block.key else (block as Attachment).key },
                    contentType = { block -> block is TileRow },
                ) { block ->
                    if (block is TileRow) {
                        Row(
                            Modifier.animateItem().fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            block.items.forEach { attachment -> Tile(vm, attachment, onOpen, onSave, Modifier.weight(1f)) }
                            repeat(3 - block.items.size) { Spacer(Modifier.weight(1f)) }
                        }
                    } else {
                        FileRow(vm, block as Attachment, onOpen, onSave, Modifier.animateItem())
                    }
                }
                item(key = "pied") { GalleryFoot(vm) }
            }
        }
    }
}

/** Le menu d'une pièce : l'ouvrir, l'enregistrer, ou aller au message qui la porte. */
@Composable
private fun PieceMenu(
    vm: MainViewModel,
    attachment: Attachment,
    state: PieceState,
    onOpen: (LocalFile) -> Unit,
    onSave: (LocalFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val act = { then: (LocalFile) -> Unit ->
        state.menu = false
        if (!state.busy) {
            state.busy = true
            scope.launch {
                val file = vm.fetchAttachment(attachment)
                state.busy = false
                if (file != null) then(file)
            }
        }
    }
    DropdownMenu(expanded = state.menu, onDismissRequest = { state.menu = false }) {
        DropdownMenuItem(text = { Text("Ouvrir") }, onClick = { act(onOpen) })
        DropdownMenuItem(text = { Text("Enregistrer sur l'appareil") }, onClick = { act(onSave) })
        DropdownMenuItem(text = { Text("Voir le message") }, onClick = {
            state.menu = false
            vm.openFromGallery(attachment)
        })
    }
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

/** Une image de la grille : son aperçu dès qu'il est prêt, son nom en bas. Elle arrive en grossissant un peu. */
@Composable
private fun Tile(
    vm: MainViewModel,
    attachment: Attachment,
    onOpen: (LocalFile) -> Unit,
    onSave: (LocalFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { PieceState() }
    val pop = remember { Animatable(0.88f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
    Box(
        modifier.graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
            alpha = ((pop.value - 0.88f) / 0.1f).coerceIn(0f, 1f)
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Tint)
                .clickable { state.menu = true }
                .testTag("piece-${attachment.name}"),
        ) {
            Thumb(vm, attachment, Modifier.fillMaxSize())
            Text(
                if (state.busy) "Téléchargement…" else attachment.name,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Sheet.copy(alpha = 0.92f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PieceMenu(vm, attachment, state, onOpen, onSave)
    }
}

/** Un fichier qui n'est pas une image : son type dans un carré, son nom, d'où il vient. */
@Composable
private fun FileRow(
    vm: MainViewModel,
    attachment: Attachment,
    onOpen: (LocalFile) -> Unit,
    onSave: (LocalFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { PieceState() }
    val who = if (attachment.outgoing) {
        "À : ${attachment.toAddr}"
    } else {
        "De : " + attachment.fromName.ifBlank { attachment.fromAddr }.ifBlank { "expéditeur inconnu" }
    }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { state.menu = true }
                .testTag("piece-${attachment.name}")
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(vm, attachment, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(BlueWash))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        attachment.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(shortDate(attachment.date), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        who,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.busy) "Téléchargement…" else fileSize(attachment.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
        PieceMenu(vm, attachment, state, onOpen, onSave)
    }
}

/** L'aperçu d'une pièce : la vignette d'une image dès qu'elle est prête, sinon le type du fichier. */
@Composable
private fun Thumb(vm: MainViewModel, attachment: Attachment, modifier: Modifier = Modifier) {
    val bitmap by produceState(vm.cachedThumb(attachment), attachment.key) {
        if (value == null) value = vm.thumbnail(attachment)
    }
    val ready = bitmap
    Box(modifier.testTag("vignette-${attachment.name}"), contentAlignment = Alignment.Center) {
        if (ready != null) {
            Image(
                bitmap = remember(ready) { ready.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                badgeOf(attachment),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                fontWeight = FontWeight.Bold,
                color = Blue,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GalleryFoot(vm: MainViewModel) {
    val filtered = vm.galleryKind.isNotBlank() || vm.galleryQuery.isNotBlank()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = if (vm.gallery.isEmpty()) 56.dp else 20.dp),
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
