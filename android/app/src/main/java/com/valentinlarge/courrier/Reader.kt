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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Une pièce jointe affichée sous l'en-tête : déjà sur l'appareil, ou à télécharger à la demande. */
private class Piece(val name: String, val size: Long, val local: LocalFile?, val remote: RemoteFile?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val id = (vm.screen as? Screen.Reader)?.id ?: return
    val opened = vm.opened
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<Uri?>(null) }
    var toSave by remember { mutableStateOf<LocalFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val file = toSave
        toSave = null
        if (target != null && file != null) scope.launch { vm.notice = saveCopy(context, file, target) }
    }

    Scaffold(
        containerColor = Color.White,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper)) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour à la liste")
                        }
                    },
                    actions = {
                        val item = opened?.item
                        if (item != null) {
                            if (item.folder == "trash") {
                                TextButton(onClick = {
                                    vm.move(item, if (item.outgoing) "sent" else "inbox", "Message restauré.")
                                }) { Text("Restaurer") }
                            } else if (!item.outgoing) {
                                TextButton(onClick = { vm.reply() }) { Text("Répondre") }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Autres actions")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (item.folder == "inbox") {
                                        DropdownMenuItem(text = { Text("Archiver") }, onClick = {
                                            menu = false
                                            vm.move(item, "archive", "Message archivé.")
                                        })
                                    }
                                    if (item.folder == "archive") {
                                        DropdownMenuItem(text = { Text("Remettre en réception") }, onClick = {
                                            menu = false
                                            vm.move(item, "inbox", "Message remis en réception.")
                                        })
                                    }
                                    if (!item.outgoing && item.folder != "trash") {
                                        DropdownMenuItem(text = { Text("Marquer non lu") }, onClick = {
                                            menu = false
                                            vm.markUnread(item)
                                        })
                                    }
                                    if (item.folder == "trash") {
                                        DropdownMenuItem(text = { Text("Supprimer définitivement", color = Red) }, onClick = {
                                            menu = false
                                            confirmDelete = true
                                        })
                                    } else {
                                        DropdownMenuItem(text = { Text("Mettre à la corbeille") }, onClick = {
                                            menu = false
                                            vm.delete(item)
                                        })
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                )
                AirmailStripe()
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (opened == null) {
                Waiting(vm.readerError, onRetry = { vm.open(id) })
            } else {
                Message(
                    vm = vm,
                    opened = opened,
                    onLink = { target ->
                        if (target.scheme.equals("mailto", ignoreCase = true)) {
                            vm.composeTo(target.schemeSpecificPart.orEmpty().substringBefore('?'))
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
    val page = remember(opened) { pageFor(opened.mail) }
    val hasRemote = remember(opened) { opened.mail.html?.let { REMOTE.containsMatchIn(it) } == true }
    val pieces = remember(opened) {
        opened.mail.attachments.map { Piece(it.name, it.size, it, null) } +
            opened.sentFiles.map { Piece(it.name, it.size, null, it) }
    }

    Header(opened)

    if (pieces.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pieces.forEach { piece ->
                PieceChip(
                    piece,
                    fetch = { piece.local ?: piece.remote?.let { vm.fetchSentFile(id, it) } },
                    onOpen = { file -> openFile(context, file)?.let { vm.notice = it } },
                    onSave = onSave,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (hasRemote && !remote) {
        Row(
            Modifier.fillMaxWidth().background(BlueWash).padding(start = 16.dp, end = 4.dp),
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

    HorizontalDivider(color = Line)
    MailBody(page, remote, onLink = onLink, modifier = Modifier.fillMaxWidth().weight(1f).testTag("corps"))
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
private fun Header(opened: OpenedMail) {
    val item = opened.item
    val mail = opened.mail
    var details by rememberSaveable(item.id) { mutableStateOf(false) }

    val sender = mail.from.firstOrNull()
    val senderName = sender?.short?.ifBlank { null } ?: item.fromName.ifBlank { item.fromAddr }.ifBlank { "Expéditeur inconnu" }
    val recipients = mail.to.ifEmpty { listOf(Person("", item.toAddr)) }
    val date = mail.date ?: item.date

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Text(
            mail.subject.ifBlank { item.subject }.ifBlank { "(sans objet)" },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = if (details) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(senderName, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Text(shortDate(date), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (details) {
            Spacer(Modifier.height(6.dp))
            Meta("De", mail.from.joinToString(", ") { it.label }.ifBlank { item.fromAddr })
            Meta("À", recipients.joinToString(", ") { it.label })
            if (mail.cc.isNotEmpty()) Meta("Cc", mail.cc.joinToString(", ") { it.label })
            if (mail.replyTo.isNotEmpty()) Meta("Répondre à", mail.replyTo.joinToString(", ") { it.label })
            Meta("Date", longDate(date))
        } else {
            Text(
                "à " + recipients.joinToString(", ") { it.short },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (details) "Masquer les détails" else "Afficher les détails",
            modifier = Modifier.clickable { details = !details }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Blue,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PieceChip(
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

    Box {
        Column(
            Modifier
                .widthIn(max = 240.dp)
                .border(1.dp, Line, RoundedCornerShape(8.dp))
                .clickable { menu = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(piece.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(if (busy) "Téléchargement…" else fileSize(piece.size), style = MaterialTheme.typography.bodySmall, color = Muted)
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
private const val PAGE_HEAD =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'; object-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{margin:0;padding:14px 16px 40px;font-family:sans-serif;font-size:16px;line-height:1.5;color:#17223b;background:#fff;word-wrap:break-word}" +
        "img{max-width:100%;height:auto}a{color:#1d57b8}" +
        "blockquote{margin:8px 0 8px 2px;padding-left:12px;border-left:3px solid #d8dfea;color:#5a6880}" +
        "pre.texte{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;margin:0}</style>"

private val DOCTYPE = Regex("(?is)^\\s*<!doctype[^>]*>")
private val LINK = Regex("""https?://[^\s<>"]+""")

private fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** La page montrée pour un message : son HTML tel quel, ou son texte brut avec des liens cliquables. */
internal fun pageFor(mail: ParsedMail): String {
    mail.html?.takeIf { it.isNotBlank() }?.let { html ->
        // Les réglages se glissent après la déclaration de type, pour ne pas changer le rendu prévu par l'expéditeur.
        val doctype = DOCTYPE.find(html) ?: return PAGE_HEAD + html
        return html.substring(0, doctype.range.last + 1) + PAGE_HEAD + html.substring(doctype.range.last + 1)
    }
    val text = mail.text?.takeIf { it.isNotBlank() }
        ?: return PAGE_HEAD + "<p style=\"color:#5a6880\">Ce message n'a pas de contenu affichable.</p>"
    val body = LINK.replace(escape(text.trimEnd())) { match ->
        // La ponctuation qui suit une adresse n'en fait pas partie.
        val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
        "<a href=\"$url\">$url</a>" + match.value.substring(url.length)
    }
    return "$PAGE_HEAD<pre class=\"texte\">$body</pre>"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MailBody(page: String, remote: Boolean, onLink: (Uri) -> Unit, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.WHITE)
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
