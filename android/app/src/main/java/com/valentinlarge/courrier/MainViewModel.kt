package com.valentinlarge.courrier

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

sealed interface Screen {
    data object Login : Screen
    data object Mailbox : Screen
    data class Reader(val id: String) : Screen
    data object Compose : Screen
}

/** Un fichier choisi pour être joint au message en cours de rédaction. */
data class DraftFile(
    val uri: Uri,
    val name: String,
    val type: String,
    val size: Long,
    val uploadId: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    val repo = Repo.get(application)
    val prefs get() = repo.prefs

    var screen by mutableStateOf<Screen>(if (repo.prefs.token != null) Screen.Mailbox else Screen.Login)
        private set

    /** Message bref affiché en bas de l'écran. */
    var notice by mutableStateOf<String?>(null)

    // ---- compte (tel que le serveur le décrit)
    var address by mutableStateOf(repo.prefs.address)
        private set
    var from by mutableStateOf(repo.prefs.from)
        private set
    var canSend by mutableStateOf(repo.prefs.canSend)
        private set

    private fun showAccount() {
        address = repo.prefs.address
        from = repo.prefs.from
        canSend = repo.prefs.canSend
    }

    // ---- connexion
    var loginBusy by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    // ---- liste
    var folder by mutableStateOf("inbox")
        private set
    var query by mutableStateOf("")
        private set
    var messages by mutableStateOf<List<MailItem>>(emptyList())
        private set
    var unread by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    var listError by mutableStateOf<String?>(null)
        private set
    private var listJob: Job? = null
    private var searchJob: Job? = null

    // ---- lecture
    var opened by mutableStateOf<OpenedMail?>(null)
        private set
    var readerError by mutableStateOf<String?>(null)
        private set
    private var readerJob: Job? = null
    private var returnFolder: String? = null

    // ---- rédaction
    var draftTo by mutableStateOf("")
    var draftCc by mutableStateOf("")
    var draftSubject by mutableStateOf("")
    var draftText by mutableStateOf("")
    val draftFiles = mutableStateListOf<DraftFile>()
    var draftReplyTo by mutableStateOf<String?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set
    private var composeReturn: Screen = Screen.Mailbox

    init {
        if (repo.prefs.token != null) startMailbox()
    }

    private fun startMailbox() {
        Sync.schedule(app)
        showLocal()
        refresh()
        viewModelScope.launch {
            try {
                repo.api.me()
                showAccount()
            } catch (e: Exception) {
                handle(e)
            }
        }
    }

    /** Traite les erreurs communes ; renvoie un texte à montrer, ou null si l'écran change. */
    private fun handle(error: Exception): String? = when {
        error is ApiException && error.status == 401 -> {
            sessionExpired()
            null
        }
        error is ApiException || error is OfflineException -> error.message
        else -> "Erreur inattendue : ${error.message ?: error.javaClass.simpleName}"
    }

    private fun sessionExpired() {
        viewModelScope.launch {
            repo.logout()
            Sync.cancel(app)
            messages = emptyList()
            opened = null
            loginError = "Ta session a expiré. Reconnecte-toi."
            screen = Screen.Login
        }
    }

    // ------------------------------------------------------------ connexion

    fun login(server: String, password: String) {
        if (loginBusy) return
        loginBusy = true
        loginError = null
        viewModelScope.launch {
            try {
                if (server.isNotBlank()) repo.prefs.server = server
                repo.api.login(password)
                showAccount()
                folder = "inbox"
                query = ""
                screen = Screen.Mailbox
                startMailbox()
            } catch (e: Exception) {
                loginError = when {
                    e is ApiException || e is OfflineException -> e.message
                    else -> "Connexion impossible : ${e.message ?: e.javaClass.simpleName}"
                }
            }
            loginBusy = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            Sync.cancel(app)
            messages = emptyList()
            opened = null
            unread = 0
            loginError = null
            screen = Screen.Login
        }
    }

    // ---------------------------------------------------------------- liste

    private fun showLocal() {
        viewModelScope.launch {
            messages = repo.local(folder, query)
            unread = repo.unread()
        }
    }

    fun selectFolder(id: String) {
        if (id == folder) return
        folder = id
        query = ""
        hasMore = false
        listError = null
        messages = emptyList()
        showLocal()
        refresh()
    }

    fun search(text: String) {
        query = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            messages = repo.local(folder, text)
            delay(350)
            refresh()
        }
    }

    fun refresh() {
        listJob?.cancel()
        val target = folder
        val text = query
        listJob = viewModelScope.launch {
            loading = true
            listError = null
            try {
                val page = repo.refresh(target, text)
                if (target == folder && text == query) {
                    hasMore = page.hasMore
                    unread = page.unread
                    offline = false
                    messages = repo.local(target, text)
                }
                loading = false
                if (target == "inbox" && text.isBlank()) repo.prefetch(page.messages)
            } catch (e: OfflineException) {
                // Sans réseau, on continue avec ce qui est enregistré sur l'appareil.
                offline = true
                hasMore = false
                messages = repo.local(target, text)
                loading = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                listError = handle(e)
                loading = false
            }
        }
    }

    fun loadMore() {
        val last = messages.lastOrNull() ?: return
        if (loadingMore) return
        val target = folder
        val text = query
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = repo.more(target, text, last.date)
                if (target == folder && text == query) {
                    hasMore = page.hasMore
                    messages = repo.local(target, text)
                }
            } catch (e: Exception) {
                notice = handle(e)
            }
            loadingMore = false
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            try {
                val remaining = repo.emptyTrash()
                notice = if (remaining > 0) "Il reste $remaining messages dans la corbeille." else "Corbeille vidée."
            } catch (e: Exception) {
                notice = handle(e)
            }
            refresh()
        }
    }

    // -------------------------------------------------------------- lecture

    fun open(id: String) {
        readerJob?.cancel()
        opened = null
        readerError = null
        screen = Screen.Reader(id)
        readerJob = viewModelScope.launch {
            try {
                val mail = repo.open(id)
                opened = mail
                if (!mail.item.isRead) {
                    messages = messages.map { if (it.id == id) it.copy(isRead = true) else it }
                    if (mail.item.folder == "inbox") unread = maxOf(0, unread - 1)
                    try {
                        repo.setRead(id, true)
                    } catch (e: Exception) {
                        // Sans réseau, l'état « lu » sera corrigé à la prochaine relève.
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                readerError = handle(e)
            }
        }
    }

    /** Ouverture depuis une notification : la liste derrière doit être celle de la réception. */
    fun openFromNotification(id: String) {
        if (repo.prefs.token == null) return
        if (folder != "inbox") selectFolder("inbox")
        open(id)
    }

    fun back() {
        when (screen) {
            is Screen.Reader -> {
                readerJob?.cancel()
                opened = null
                readerError = null
                screen = Screen.Mailbox
                showLocal()
            }
            Screen.Compose -> screen = composeReturn
            else -> Unit
        }
    }

    private fun leaveReader(done: String) {
        notice = done
        opened = null
        screen = Screen.Mailbox
        showLocal()
        refresh()
    }

    fun move(item: MailItem, target: String, done: String) {
        viewModelScope.launch {
            try {
                repo.move(item.id, target)
                leaveReader(done)
            } catch (e: Exception) {
                notice = handle(e)
            }
        }
    }

    fun markUnread(item: MailItem) {
        viewModelScope.launch {
            try {
                repo.setRead(item.id, false)
                leaveReader("Message marqué non lu.")
            } catch (e: Exception) {
                notice = handle(e)
            }
        }
    }

    fun delete(item: MailItem) {
        viewModelScope.launch {
            try {
                val gone = repo.delete(item.id)
                leaveReader(if (gone) "Message supprimé définitivement." else "Message mis à la corbeille.")
            } catch (e: Exception) {
                notice = handle(e)
            }
        }
    }

    /** Prépare un fichier joint (reçu ou envoyé) pour l'ouvrir ou l'enregistrer. */
    suspend fun fetchSentFile(messageId: String, remote: RemoteFile): LocalFile? =
        try {
            repo.sentFile(messageId, remote)
        } catch (e: Exception) {
            notice = handle(e)
            null
        }

    // ------------------------------------------------------------ rédaction

    fun compose() {
        if (draftReplyTo != null) clearDraft()
        composeReturn = screen
        sendError = null
        screen = Screen.Compose
    }

    /** Nouveau message pour une adresse touchée dans un mail. */
    fun composeTo(address: String) {
        clearDraft()
        draftTo = address.trim()
        composeReturn = screen
        screen = Screen.Compose
    }

    fun reply() {
        val current = opened ?: return
        clearDraft()
        val mail = current.mail
        val target = mail.replyTo.firstOrNull()?.address?.ifBlank { null }
            ?: mail.from.firstOrNull()?.address?.ifBlank { null }
            ?: current.item.fromAddr
        val subject = mail.subject.ifBlank { current.item.subject }
        draftTo = target
        draftSubject = if (Regex("^\\s*re\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(subject)) subject else "Re: $subject"
        draftText = quote(current)
        draftReplyTo = current.item.id
        composeReturn = screen
        sendError = null
        screen = Screen.Compose
    }

    private fun quote(current: OpenedMail): String {
        val mail = current.mail
        val source = mail.text?.takeIf { it.isNotBlank() } ?: mail.html?.let { Mime.htmlToText(it) }.orEmpty()
        val who = mail.from.firstOrNull()?.short ?: current.item.fromAddr
        val date = longDate(mail.date ?: current.item.date)
        val quoted = source.trim().lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
        return "\n\nLe $date, $who a écrit :\n$quoted\n"
    }

    private fun clearDraft() {
        draftTo = ""
        draftCc = ""
        draftSubject = ""
        draftText = ""
        draftFiles.clear()
        draftReplyTo = null
        sendError = null
    }

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                if (draftFiles.any { it.uri == uri }) continue
                if (draftFiles.size >= 10) {
                    notice = "Pas plus de 10 pièces jointes par message."
                    break
                }
                val file = withContext(Dispatchers.IO) { describe(uri) }
                if (file.size > 25L * 1024 * 1024) {
                    notice = "« ${file.name} » dépasse 25 Mo et ne peut pas être joint."
                    continue
                }
                draftFiles.add(file)
            }
        }
    }

    fun removeFile(file: DraftFile) {
        draftFiles.remove(file)
        val id = file.uploadId ?: return
        viewModelScope.launch {
            try {
                repo.api.deleteUpload(id)
            } catch (e: Exception) {
                // Le serveur fera le ménage tout seul au bout d'un jour.
            }
        }
    }

    private fun describe(uri: Uri): DraftFile {
        val resolver = app.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "piece-jointe"
        var size = -1L
        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Certaines sources ne décrivent pas leurs fichiers : on mesurera à l'envoi.
        }
        if (size < 0 && uri.scheme == "file") size = uri.path?.let { File(it).length() } ?: -1L
        val type = resolver.getType(uri) ?: "application/octet-stream"
        return DraftFile(uri, name, type, size)
    }

    fun send() {
        if (sending) return
        if (draftTo.isBlank()) {
            sendError = "Indique au moins un destinataire."
            return
        }
        sending = true
        sendError = null
        viewModelScope.launch {
            try {
                val ids = ArrayList<String>()
                for (index in draftFiles.indices) {
                    val file = draftFiles[index]
                    val existing = file.uploadId
                    if (existing != null) {
                        ids.add(existing)
                        continue
                    }
                    val id = UUID.randomUUID().toString()
                    uploadFile(id, file)
                    draftFiles[index] = file.copy(uploadId = id)
                    ids.add(id)
                }
                val stored = repo.api.send(draftTo, draftCc, draftSubject, draftText, draftReplyTo, ids)
                clearDraft()
                notice = if (stored) "Message envoyé." else "Message envoyé, mais sa copie n'a pas pu être rangée dans Envoyés."
                screen = composeReturn
                if (folder == "sent") refresh()
            } catch (e: Exception) {
                sendError = handle(e)
            }
            sending = false
        }
    }

    private suspend fun uploadFile(id: String, file: DraftFile) {
        val resolver = app.contentResolver
        if (file.size >= 0) {
            repo.api.upload(id, file.name, file.type, file.size) {
                resolver.openInputStream(file.uri) ?: throw ApiException(0, "Impossible de lire « ${file.name} ».")
            }
            return
        }
        // Taille inconnue : on passe par une copie temporaire pour pouvoir l'annoncer au serveur.
        val temp = withContext(Dispatchers.IO) {
            File.createTempFile("envoi", null, app.cacheDir).also { target ->
                (resolver.openInputStream(file.uri) ?: throw ApiException(0, "Impossible de lire « ${file.name} »."))
                    .use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        try {
            repo.api.upload(id, file.name, file.type, temp.length()) { temp.inputStream() }
        } finally {
            temp.delete()
        }
    }
}

// ------------------------------------------------------------------- dates

private val zone: ZoneId get() = ZoneId.systemDefault()
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
private val yearFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE)
private val longFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' HH:mm", Locale.FRANCE)

fun shortDate(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    val now = Instant.now().atZone(zone)
    return when {
        date.toLocalDate() == now.toLocalDate() -> timeFormat.format(date)
        date.year == now.year -> dayFormat.format(date)
        else -> yearFormat.format(date)
    }
}

fun longDate(millis: Long): String = longFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

fun fileSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes o"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} Ko"
    else -> String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0))
}
