package com.valentinlarge.courrier

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Une pièce jointe affichée sous l'en-tête : déjà sur l'appareil, ou à télécharger à la demande. */
private class Piece(val name: String, val size: Long, val local: LocalFile?, val remote: RemoteFile?)

/**
 * La lecture d'un message. L'écran glisse par-dessus la boîte : en haut, de quoi revenir, archiver
 * ou jeter ; en bas, de quoi répondre.
 */
@Composable
fun ReaderScreen(vm: MainViewModel, id: String) {
    // Pendant que l'écran glisse hors de vue, le message qu'il montrait reste affiché tel quel.
    val opened = vm.opened?.takeIf { it.item.id == id }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var filing by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<Uri?>(null) }
    var toSave by remember { mutableStateOf<LocalFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val file = toSave
        toSave = null
        if (target != null && file != null) scope.launch { vm.notice = saveCopy(context, file, target) }
    }
    val item = opened?.item

    Column(Modifier.fillMaxSize().background(Paper).solid()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyphs.Back, "Retour à la liste", onClick = { vm.back() })
            Spacer(Modifier.weight(1f))
            if (item != null) {
                if (item.folder == "inbox") {
                    GlyphButton(Glyphs.Archive, "Archiver", onClick = { vm.move(item, "archive", "Message archivé.") })
                }
                if (item.folder == "trash") {
                    GlyphButton(Glyphs.Trash, "Supprimer définitivement", onClick = { confirmDelete = true }, tint = Red)
                } else {
                    GlyphButton(Glyphs.Trash, "Mettre à la corbeille", onClick = { vm.delete(item) })
                }
                if (item.folder != "trash") {
                    Box {
                        GlyphButton(Glyphs.More, "Autres actions", onClick = { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Ranger dans…") }, onClick = {
                                menu = false
                                filing = true
                            })
                            if (item.folder == "archive") {
                                DropdownMenuItem(text = { Text("Remettre en réception") }, onClick = {
                                    menu = false
                                    vm.move(item, "inbox", "Message remis en réception.")
                                })
                            }
                            if (!item.outgoing) {
                                DropdownMenuItem(text = { Text("Marquer non lu") }, onClick = {
                                    menu = false
                                    vm.markUnread(item)
                                })
                            }
                        }
                    }
                }
            }
        }
        ThreadStrip(vm, id)

        Column(Modifier.fillMaxWidth().weight(1f)) {
            if (opened == null) {
                Waiting(vm.readerError, onRetry = { vm.open(id) })
            } else {
                Message(
                    vm = vm,
                    opened = opened,
                    onLink = { target ->
                        if (target.scheme.equals("mailto", ignoreCase = true)) {
                            vm.composeLink(target)
                        } else {
                            link = target
                        }
                    },
                    onSave = { file ->
                        toSave = file
                        saver.launch(file.name)
                    },
                )
            }
        }

        // La barre du bas : répondre d'abord, le reste en boutons ronds.
        if (item != null) {
            Hairline()
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    item.folder == "trash" -> Pill(
                        "Restaurer",
                        onClick = { vm.move(item, if (item.outgoing) "sent" else "inbox", "Message restauré.") },
                        height = 52.dp,
                        modifier = Modifier.weight(1f),
                    )
                    item.outgoing -> Pill("Transférer", onClick = { vm.forward() }, glyph = Glyphs.Forward, height = 52.dp, modifier = Modifier.weight(1f))
                    else -> {
                        Pill("Répondre", onClick = { vm.reply() }, glyph = Glyphs.Reply, height = 52.dp, modifier = Modifier.weight(1f))
                        if (vm.canReplyAll) RoundButton(Glyphs.ReplyAll, "Répondre à tous", onClick = { vm.reply(all = true) })
                        RoundButton(Glyphs.Forward, "Transférer", onClick = { vm.forward() })
                    }
                }
            }
        }
    }

    link?.let { target ->
        AlertDialog(
            onDismissRequest = { link = null },
            title = { Text("Ouvrir ce lien ?") },
            text = { Text(target.toString(), maxLines = 8, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    link = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, target))
                    } catch (e: ActivityNotFoundException) {
                        vm.notice = "Aucune appli installée ne peut ouvrir ce lien."
                    }
                }) { Text("Ouvrir") }
            },
            dismissButton = { TextButton(onClick = { link = null }) { Text("Annuler") } },
        )
    }

    if (filing) {
        opened?.item?.let { filed ->
            FilePicker(
                vm,
                current = filed.labels,
                onDismiss = { filing = false },
                onPick = { label ->
                    filing = false
                    vm.toggleLabel(label)
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ce message ?") },
            text = { Text("Il sera effacé définitivement, sans retour possible.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    opened?.item?.let { vm.delete(it) }
                }) { Text("Supprimer définitivement", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ColumnScope.Message(
    vm: MainViewModel,
    opened: OpenedMail,
    onLink: (Uri) -> Unit,
    onSave: (LocalFile) -> Unit,
) {
    val context = LocalContext.current
    val id = opened.item.id
    var remote by rememberSaveable(id) { mutableStateOf(false) }
    // Le texte brut prend les couleurs du thème ; un message HTML reste sur fond clair.
    val darkPage = Palette.dark && opened.mail.html.isNullOrBlank()
    val page = remember(opened, darkPage) { pageFor(opened.mail, darkPage) }
    val hasRemote = remember(opened) { opened.mail.html?.let { REMOTE.containsMatchIn(it) } == true }
    val pieces = remember(opened) {
        opened.mail.attachments.map { Piece(it.name, it.size, it, null) } +
            opened.sentFiles.map { Piece(it.name, it.size, null, it) }
    }

    Header(opened, vm.labelNames(opened.item.labels))

    if (pieces.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pieces.forEach { piece ->
                PieceCard(
                    piece,
                    fetch = { piece.local ?: piece.remote?.let { vm.fetchSentFile(id, it) } },
                    onOpen = { file -> openFile(context, file)?.let { vm.notice = it } },
                    onSave = onSave,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    if (hasRemote && !remote) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BlueWash)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Les images distantes sont masquées : elles signalent à l'expéditeur que tu as ouvert le message.",
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { remote = true }) { Text("Afficher") }
        }
    }

    // Le message lui-même, posé comme une feuille sur le fond de l'écran.
    Spacer(Modifier.height(6.dp))
    MailBody(
        page,
        remote,
        darkPage,
        onLink = onLink,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .testTag("corps"),
    )
}

/** Dans une conversation : où l'on en est, et de quoi passer d'un message à l'autre. */
@Composable
private fun ThreadStrip(vm: MainViewModel, current: String) {
    val thread = vm.thread
    val index = thread.indexOfFirst { it.id == current }
    if (thread.size < 2 || index < 0) return
    var list by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(CircleShape).background(BlueWash),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(Glyphs.ChevronLeft, "Message précédent de la conversation", onClick = { vm.openSibling(-1) }, enabled = index > 0)
        Box(Modifier.weight(1f)) {
            Text(
                "Message ${index + 1} sur ${thread.size} de la conversation",
                modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable { list = true }.padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DropdownMenu(expanded = list, onDismissRequest = { list = false }) {
                thread.forEach { item ->
                    val who = if (item.outgoing) "Moi" else item.fromName.ifBlank { item.fromAddr }
                    DropdownMenuItem(
                        text = {
                            Text(
                                "$who, ${shortDate(item.date)}",
                                fontWeight = if (item.id == current) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            list = false
                            if (item.id != current) vm.open(item.id)
                        },
                    )
                }
            }
        }
        GlyphButton(Glyphs.ChevronRight, "Message suivant de la conversation", onClick = { vm.openSibling(1) }, enabled = index < thread.lastIndex)
    }
}

@Composable
private fun Waiting(error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (error != null) {
            Text(error, color = Red, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onRetry) { Text("Réessayer") }
        } else {
            CircularProgressIndicator(color = Blue)
            Text("Ouverture du message…", color = Muted)
        }
    }
}

@Composable
private fun Header(opened: OpenedMail, folders: List<String>) {
    val item = opened.item
    val mail = opened.mail
    var details by rememberSaveable(item.id) { mutableStateOf(false) }

    val sender = mail.from.firstOrNull()
    val senderName = sender?.short?.ifBlank { null } ?: item.fromName.ifBlank { item.fromAddr }.ifBlank { "Expéditeur inconnu" }
    val recipients = mail.to.ifEmpty { listOf(Person("", item.toAddr)) }
    val date = mail.date ?: item.date

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp)) {
        Text(
            mail.subject.ifBlank { item.subject }.ifBlank { "(sans objet)" },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = if (details) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(senderName, seed = sender?.address?.ifBlank { null } ?: item.fromAddr, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(senderName, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(10.dp))
                    Text(shortDate(date), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (!details) {
                    Text(
                        "à " + recipients.joinToString(", ") { it.short },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LabelChips(folders, Modifier.padding(top = 10.dp))
        if (details) {
            Spacer(Modifier.height(10.dp))
            Meta("De", mail.from.joinToString(", ") { it.label }.ifBlank { item.fromAddr })
            Meta("À", recipients.joinToString(", ") { it.label })
            if (mail.cc.isNotEmpty()) Meta("Cc", mail.cc.joinToString(", ") { it.label })
            if (mail.replyTo.isNotEmpty()) Meta("Répondre à", mail.replyTo.joinToString(", ") { it.label })
            Meta("Date", longDate(date))
        }
        Text(
            if (details) "Masquer les détails" else "Afficher les détails",
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { details = !details }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Blue,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = Muted)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp))
    }
}

/** Ce qu'on lit dans le carré d'une pièce : son extension. */
private fun badgeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").trim()
    return if (extension.length in 1..4 && extension.all { it.isLetterOrDigit() }) extension.uppercase() else "…"
}

@Composable
private fun PieceCard(
    piece: Piece,
    fetch: suspend () -> LocalFile?,
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
                val file = fetch()
                busy = false
                if (file != null) then(file)
            }
        }
    }
    val shape = RoundedCornerShape(16.dp)

    Box {
        Row(
            Modifier
                .widthIn(max = 260.dp)
                .clip(shape)
                .background(Sheet)
                .border(1.dp, Line, shape)
                .clickable { menu = true }
                .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BlueWash), contentAlignment = Alignment.Center) {
                Text(badgeOf(piece.name), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), fontWeight = FontWeight.Bold, color = Blue, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    piece.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                )
                Text(if (busy) "Téléchargement…" else fileSize(piece.size), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Ouvrir") }, onClick = { act(onOpen) })
            DropdownMenuItem(text = { Text("Enregistrer sur l'appareil") }, onClick = { act(onSave) })
        }
    }
}

// ------------------------------------------------------- corps du message

private val REMOTE = Regex("""(?i)(src|background)\s*=\s*["']?\s*https?:|url\(\s*["']?\s*https?:""")

// Aucun script, cadre ni formulaire ne s'exécute dans un message ; le reste n'est que mise en forme par défaut.
private fun pageHead(dark: Boolean): String {
    val ink = if (dark) "#eceef1" else "#14171c"
    val paper = if (dark) "#171a1f" else "#fff"
    val link = if (dark) "#9daaf2" else "#2443d8"
    val line = if (dark) "#272c34" else "#e2e4df"
    val muted = if (dark) "#9ba4b0" else "#5b6470"
    return "<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'; object-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{margin:0;padding:18px 20px 40px;font-family:sans-serif;font-size:16px;line-height:1.5;color:$ink;background:$paper;word-wrap:break-word}" +
        "img{max-width:100%;height:auto}a{color:$link}" +
        "blockquote{margin:8px 0 8px 2px;padding-left:12px;border-left:3px solid $line;color:$muted}" +
        "pre.texte{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;margin:0}</style>"
}

private val DOCTYPE = Regex("(?is)^\\s*<!doctype[^>]*>")
private val LINK = Regex("""https?://[^\s<>"]+""")

private fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** La page montrée pour un message : son HTML tel quel, ou son texte brut avec des liens cliquables. */
internal fun pageFor(mail: ParsedMail, dark: Boolean = false): String {
    mail.html?.takeIf { it.isNotBlank() }?.let { html ->
        // Un message HTML est conçu pour un fond clair : il le garde, même quand l'appli est en sombre.
        // Les réglages se glissent après la déclaration de type, pour ne pas changer le rendu prévu par l'expéditeur.
        val head = pageHead(false)
        val doctype = DOCTYPE.find(html) ?: return head + html
        return html.substring(0, doctype.range.last + 1) + head + html.substring(doctype.range.last + 1)
    }
    val head = pageHead(dark)
    val text = mail.text?.takeIf { it.isNotBlank() }
        ?: return head + "<p>Ce message n'a pas de contenu affichable.</p>"
    val body = LINK.replace(escape(text.trimEnd())) { match ->
        // La ponctuation qui suit une adresse n'en fait pas partie.
        val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
        "<a href=\"$url\">$url</a>" + match.value.substring(url.length)
    }
    return "$head<pre class=\"texte\">$body</pre>"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MailBody(page: String, remote: Boolean, dark: Boolean, onLink: (Uri) -> Unit, modifier: Modifier) {
    AndroidView(
        // La découpe (aux coins arrondis de la feuille) retient aussi le fond que la vue web
        // peint en attendant sa page : sans elle, il déborde et recouvre l'en-tête du message.
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().setAcceptCookie(false)
                webViewClient = object : WebViewClient() {
                    // Rien ne se charge à la place du message : un lien ne s'ouvre que si tu le touches.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme?.lowercase()
                        if (request.hasGesture() && (scheme == "http" || scheme == "https" || scheme == "mailto")) {
                            onLink(request.url)
                        }
                        return true
                    }
                }
            }
        },
        onRelease = { it.destroy() },
        update = { view ->
            val key = page to remote
            if (view.tag != key) {
                view.tag = key
                view.setBackgroundColor(if (dark) 0xFF171A1F.toInt() else android.graphics.Color.WHITE)
                view.settings.blockNetworkLoads = !remote
                view.settings.blockNetworkImage = !remote
                view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
    )
}

// ---------------------------------------------------------- pièces jointes

private fun viewType(file: LocalFile): String {
    val declared = file.type.substringBefore(';').trim().lowercase()
    if (declared.isNotBlank() && declared != "application/octet-stream") return declared
    val extension = file.name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}

/** Confie le fichier à une autre appli. Renvoie un message à afficher si aucune ne convient. */
fun openFile(context: Context, file: LocalFile): String? {
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fichiers", file.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, viewType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "Aucune appli installée ne sait ouvrir « ${file.name} ». Tu peux l'enregistrer sur l'appareil."
    } catch (e: IllegalArgumentException) {
        "Impossible de préparer « ${file.name} »."
    }
}

suspend fun saveCopy(context: Context, file: LocalFile, target: Uri): String = withContext(Dispatchers.IO) {
    try {
        val output = context.contentResolver.openOutputStream(target)
            ?: return@withContext "Impossible d'écrire à cet emplacement."
        output.use { out -> file.file.inputStream().use { it.copyTo(out) } }
        "« ${file.name} » enregistré."
    } catch (e: Exception) {
        "L'enregistrement de « ${file.name} » a échoué."
    }
}
