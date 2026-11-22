package com.valentinlarge.courrier

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.derivedStateOf
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
    data object Settings : Screen
}

/** Un fichier choisi pour être joint au message en cours de rédaction. */
data class DraftFile(
    val uri: Uri,
    val name: String,
    val type: String,
    val size: Long,
    val uploadId: String? = null,
)

/** Une ligne de la liste : le dernier message d'une conversation, et ceux du dossier qu'elle regroupe. */
data class ThreadRow(val item: MailItem, val ids: List<String>, val count: Int, val unread: Boolean)

private fun groupByThread(items: List<MailItem>): List<ThreadRow> {
    val threads = LinkedHashMap<String, MutableList<MailItem>>()
    for (item in items) threads.getOrPut(item.threadId) { ArrayList() }.add(item)
    return threads.values.map { group ->
        ThreadRow(
            item = group.first(),
            ids = group.map { it.id },
            count = maxOf(group.size, group.maxOf { it.threadCount }),
            unread = group.any { !it.isRead },
        )
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    val repo = Repo.get(application)
    val prefs get() = repo.prefs

    var screen by mutableStateOf<Screen>(if (repo.prefs.token != null) Screen.Mailbox else Screen.Login)
        private set

    /** Message bref affiché en bas de l'écran. */
    var notice by mutableStateOf<String?>(null)

    /** Le message bref qui propose « Annuler », s'il y en a un. */
    var undoFor by mutableStateOf<String?>(null)
        private set
    private var undoAction: (() -> Unit)? = null

    private fun say(text: String, undo: (() -> Unit)? = null) {
        undoAction = undo
        undoFor = if (undo != null) text else null
        notice = text
    }

    fun undoLast() {
        val action = undoAction
        undoAction = null
        undoFor = null
        action?.invoke()
    }

    // ---- compte (tel que le serveur le décrit)
    var address by mutableStateOf(repo.prefs.address)
        private set
    var from by mutableStateOf(repo.prefs.from)
        private set
    var canSend by mutableStateOf(repo.prefs.canSend)
        private set

    /** Les adresses depuis lesquelles on peut écrire, la principale en premier. */
    var addresses by mutableStateOf(repo.prefs.addresses)
        private set

    /** Notifications instantanées : il faut que l'appli et le serveur soient tous deux réglés pour. */
    val pushApp: Boolean = Push.available(application)
    var pushServer by mutableStateOf(repo.prefs.pushServer)
        private set

    private fun showAccount() {
        address = repo.prefs.address
        from = repo.prefs.from
        canSend = repo.prefs.canSend
        addresses = repo.prefs.addresses
        pushServer = repo.prefs.pushServer
        signature = repo.prefs.signature
    }

    /** Texte ajouté au bas des messages qu'on écrit. */
    var signature by mutableStateOf(repo.prefs.signature)
        private set

    fun saveSignature(text: String) = settings {
        repo.api.saveSignature(text)
        notice = "Signature enregistrée."
    }

    /** La signature telle qu'elle se place dans un nouveau message, sous l'endroit où l'on écrit. */
    private fun signatureBlock(): String = if (signature.isBlank()) "" else "\n\n-- \n${signature.trim()}"

    // ---- adresses et règles
    var aliasPage by mutableStateOf<AliasPage?>(null)
        private set
    var rules by mutableStateOf<List<Rule>>(emptyList())
        private set
    var settingsError by mutableStateOf<String?>(null)
        private set
    var settingsBusy by mutableStateOf(false)
        private set

    /** Vrai une fois ce téléphone déclaré au serveur pour les notifications instantanées. */
    val pushRegistered: Boolean get() = repo.prefs.pushToken != null

    var pushTesting by mutableStateOf(false)
        private set

    fun testPush() {
        if (pushTesting) return
        pushTesting = true
        Push.register(app)
        viewModelScope.launch {
            try {
                val (ok, detail) = repo.api.testPush()
                notice = if (ok) "Signal envoyé. Une notification d'essai doit apparaître dans quelques secondes." else "Essai impossible : $detail"
            } catch (e: Exception) {
                notice = handle(e)
            }
            pushTesting = false
        }
    }

    fun openSettings() {
        screen = Screen.Settings
        settings { }
    }

    /** Applique un changement, puis relit les adresses et les règles telles que le serveur les garde. */
    private fun settings(change: suspend () -> Unit) {
        settingsBusy = true
        settingsError = null
        viewModelScope.launch {
            try {
                change()
                aliasPage = repo.api.aliases()
                rules = repo.api.rules()
                repo.api.me()
                showAccount()
            } catch (e: Exception) {
                settingsError = handle(e)
            }
            settingsBusy = false
        }
    }

    fun addAlias(name: String, label: String) {
        val typed = name.trim().lowercase()
        if (typed.isEmpty()) return
        val domain = aliasPage?.domain?.ifBlank { null } ?: address.substringAfter('@')
        settings { repo.api.saveAlias(if ('@' in typed) typed else "$typed@$domain", label = label.trim(), enabled = true) }
    }

    fun setAliasEnabled(alias: Alias, enabled: Boolean) = settings { repo.api.saveAlias(alias.address, enabled = enabled) }

    fun forgetAlias(alias: Alias) = settings { repo.api.deleteAlias(alias.address) }

    fun addRule(field: String, value: String, action: String) {
        if (value.isBlank()) return
        settings { repo.api.saveRule(UUID.randomUUID().toString(), field, value.trim(), action) }
    }

    fun deleteRule(rule: Rule) = settings { repo.api.deleteRule(rule.id) }

    // ---- connexion
    var loginBusy by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    /** Vrai quand le serveur vient de refuser le mot de passe : le renvoyer tel quel userait un essai pour rien. */
    var loginRefused by mutableStateOf(false)
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

    /** La liste telle qu'elle s'affiche : une ligne par conversation. */
    val rows: List<ThreadRow> by derivedStateOf { groupByThread(messages) }

    /** Lignes cochées (repérées par leur message le plus récent). */
    val selected = mutableStateListOf<String>()

    var drafts by mutableStateOf<List<Draft>>(emptyList())
        private set
    private var listJob: Job? = null
    private var searchJob: Job? = null

    // ---- lecture
    var opened by mutableStateOf<OpenedMail?>(null)
        private set
    var readerError by mutableStateOf<String?>(null)
        private set

    /** Les messages de la conversation ouverte, du plus ancien au plus récent (vide s'il n'y en a qu'un). */
    var thread by mutableStateOf<List<MailItem>>(emptyList())
        private set
    private var readerJob: Job? = null
    private var returnFolder: String? = null

    // ---- rédaction
    var draftTo by mutableStateOf("")
    var draftCc by mutableStateOf("")
    var draftSubject by mutableStateOf("")
    var draftText by mutableStateOf("")

    /** Adresse d'expédition choisie ; vide pour l'adresse principale. */
    var draftFrom by mutableStateOf("")
    val draftFiles = mutableStateListOf<DraftFile>()
    var draftReplyTo by mutableStateOf<String?>(null)
        private set

    /** Identifiant du brouillon sur le serveur, s'il y a déjà été enregistré. */
    var draftId by mutableStateOf<String?>(null)
        private set
    private var baseline = ""
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
        Push.register(app)
        // Un signal arrivé pendant que la boîte est à l'écran la met à jour sans attendre.
        Push.listener = { if (screen == Screen.Mailbox && folder == "inbox") refresh() }
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
        loginRefused = false
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
                loginRefused = e is ApiException && e.status == 401
                loginError = when {
                    e is ApiException || e is OfflineException -> e.message
                    else -> "Connexion impossible : ${e.message ?: e.javaClass.simpleName}"
                }
            }
            loginBusy = false
        }
    }

    override fun onCleared() {
        Push.listener = null
        super.onCleared()
    }

    fun logout() {
        viewModelScope.launch {
            Push.forget(app)
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
            // Pendant une recherche, la liste vient du serveur (il cherche aussi dans le texte) :
            // on en rafraîchit seulement l'état, sans la refiltrer sur l'appareil.
            messages = if (query.isBlank()) repo.local(folder, query) else repo.known(messages.map { it.id }, folder)
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
        selected.clear()
        showLocal()
        refresh()
    }

    private fun loadDrafts() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            loading = true
            listError = null
            try {
                drafts = repo.api.drafts()
            } catch (e: OfflineException) {
                listError = "Les brouillons sont gardés sur le serveur : reconnecte-toi à internet pour les voir."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                listError = handle(e)
            }
            loading = false
        }
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
        if (folder == "drafts") {
            loadDrafts()
            return
        }
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
                    messages = if (text.isBlank()) repo.local(target, text) else page.messages
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
                    messages = if (text.isBlank()) repo.local(target, text) else (messages + page.messages).distinctBy { it.id }
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

    // ------------------------------------------- sélection et actions groupées

    fun toggleSelect(row: ThreadRow) {
        if (!selected.remove(row.item.id)) selected.add(row.item.id)
    }

    fun clearSelection() = selected.clear()

    fun selectedRows(): List<ThreadRow> = rows.filter { it.item.id in selected }

    /** Déplace des conversations entières ; la liste se met à jour tout de suite, et le geste peut être annulé. */
    fun moveRows(target: List<ThreadRow>, to: String, done: String) {
        val ids = target.flatMap { it.ids }
        if (ids.isEmpty()) return
        val origin = folder
        selected.clear()
        messages = messages.filter { it.id !in ids }
        viewModelScope.launch {
            try {
                repo.moveAll(ids, to)
                say(done) {
                    viewModelScope.launch {
                        try {
                            repo.moveAll(ids, origin)
                        } catch (e: Exception) {
                            notice = handle(e)
                        }
                        showLocal()
                        refresh()
                    }
                }
            } catch (e: Exception) {
                notice = handle(e)
            }
            showLocal()
        }
    }

    fun markRows(target: List<ThreadRow>, read: Boolean) {
        val ids = target.flatMap { it.ids }
        if (ids.isEmpty()) return
        selected.clear()
        messages = messages.map { if (it.id in ids) it.copy(isRead = read) else it }
        viewModelScope.launch {
            try {
                repo.readAll(ids, read)
            } catch (e: Exception) {
                notice = handle(e)
            }
            showLocal()
        }
    }

    /** Sort des conversations de la corbeille : chaque message retourne d'où il venait. */
    fun restoreRows(target: List<ThreadRow>) {
        val ids = target.flatMap { it.ids }
        if (ids.isEmpty()) return
        val outgoing = messages.filter { it.id in ids && it.outgoing }.map { it.id }
        val incoming = ids - outgoing.toSet()
        selected.clear()
        messages = messages.filter { it.id !in ids }
        viewModelScope.launch {
            try {
                if (incoming.isNotEmpty()) repo.moveAll(incoming, "inbox")
                if (outgoing.isNotEmpty()) repo.moveAll(outgoing, "sent")
                say(if (ids.size == 1) "Message restauré." else "${ids.size} messages restaurés.")
            } catch (e: Exception) {
                notice = handle(e)
            }
            showLocal()
        }
    }

    /** Suppression définitive, depuis la corbeille. */
    fun deleteRows(target: List<ThreadRow>) {
        val ids = target.flatMap { it.ids }
        if (ids.isEmpty()) return
        selected.clear()
        messages = messages.filter { it.id !in ids }
        viewModelScope.launch {
            try {
                repo.deleteAll(ids)
                say(if (ids.size == 1) "Message supprimé définitivement." else "${ids.size} messages supprimés définitivement.")
            } catch (e: Exception) {
                notice = handle(e)
            }
            showLocal()
            refresh()
        }
    }

    // -------------------------------------------------------------- lecture

    /** Ouvre une conversation par son dernier message, et marque lus ceux qu'elle regroupe. */
    fun openRow(row: ThreadRow) {
        open(row.item.id)
        val others = messages.filter { it.id in row.ids && it.id != row.item.id && !it.isRead }.map { it.id }
        if (others.isEmpty()) return
        messages = messages.map { if (it.id in others) it.copy(isRead = true) else it }
        viewModelScope.launch {
            try {
                repo.readAll(others, true)
            } catch (e: Exception) {
                // Sans réseau, l'état « lu » sera corrigé à la prochaine relève.
            }
        }
    }

    /** Passe au message précédent ou suivant de la conversation ouverte. */
    fun openSibling(step: Int) {
        val current = opened?.item?.id ?: return
        val index = thread.indexOfFirst { it.id == current }
        val target = thread.getOrNull(index + step) ?: return
        if (index >= 0) open(target.id)
    }

    fun open(id: String) {
        readerJob?.cancel()
        opened = null
        readerError = null
        if (thread.none { it.id == id }) thread = emptyList()
        screen = Screen.Reader(id)
        readerJob = viewModelScope.launch {
            try {
                val mail = repo.open(id)
                opened = mail
                if (thread.isEmpty() && mail.item.threadCount > 1) {
                    launch {
                        try {
                            val all = repo.thread(mail.item.threadId)
                            if (opened?.item?.id == id && all.size > 1) thread = all
                        } catch (e: Exception) {
                            // La conversation reste lisible message par message.
                        }
                    }
                }
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
                thread = emptyList()
                screen = Screen.Mailbox
                showLocal()
            }
            Screen.Compose -> closeComposer()
            Screen.Settings -> screen = Screen.Mailbox
            else -> Unit
        }
    }

    private fun leaveReader(done: String) {
        notice = done
        opened = null
        thread = emptyList()
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

    private fun hasContent(): Boolean =
        draftTo.isNotBlank() || draftCc.isNotBlank() || draftSubject.isNotBlank() || draftText.isNotBlank() || draftFiles.isNotEmpty()

    /** Photographie du message en cours, pour savoir s'il a changé depuis son ouverture. */
    private fun snapshot(): String =
        listOf(draftFrom, draftTo, draftCc, draftSubject, draftText, draftFiles.joinToString("|") { it.uploadId ?: it.uri.toString() })
            .joinToString("\u0001")

    private fun showComposer(from: Screen = screen) {
        baseline = snapshot()
        composeReturn = if (from == Screen.Compose) Screen.Mailbox else from
        sendError = null
        screen = Screen.Compose
    }

    fun compose() {
        // Un texte resté là faute de réseau pour l'enregistrer est proposé à nouveau.
        val leftover = hasContent()
        if (!leftover) {
            clearDraft()
            draftText = signatureBlock()
        }
        showComposer()
        if (leftover) baseline = ""
    }

    /** Nouveau message pour une adresse touchée dans un mail. */
    fun composeTo(address: String) {
        clearDraft()
        draftTo = address.trim()
        draftText = signatureBlock()
        showComposer()
        baseline = ""
    }

    fun openDraft(draft: Draft) {
        clearDraft()
        draftTo = draft.to
        draftCc = draft.cc
        draftSubject = draft.subject
        draftText = draft.text
        draftReplyTo = draft.replyToId
        draftId = draft.id
        draft.files.forEach { draftFiles.add(DraftFile(Uri.EMPTY, it.name, it.type, it.size, it.id)) }
        showComposer()
    }

    private fun replyTarget(current: OpenedMail): String =
        current.mail.replyTo.firstOrNull()?.address?.ifBlank { null }
            ?: current.mail.from.firstOrNull()?.address?.ifBlank { null }
            ?: current.item.fromAddr

    /** Les autres destinataires du message, hors soi-même et hors la personne à qui l'on répond. */
    private fun othersOf(current: OpenedMail): List<String> {
        val skip = setOf(repo.prefs.address.lowercase(), replyTarget(current).lowercase())
        return (current.mail.to + current.mail.cc)
            .map { it.address.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in skip }
            .distinctBy { it.lowercase() }
    }

    val canReplyAll: Boolean
        get() {
            val current = opened ?: return false
            return !current.item.outgoing && othersOf(current).isNotEmpty()
        }

    fun reply(all: Boolean = false) {
        val current = opened ?: return
        clearDraft()
        val subject = current.mail.subject.ifBlank { current.item.subject }
        draftTo = replyTarget(current)
        if (all) draftCc = othersOf(current).joinToString(", ")
        // On répond depuis l'adresse qui a reçu le message, si c'est l'une des nôtres.
        val received = current.item.toAddr.trim().lowercase()
        if (received != address.lowercase() && received in addresses) draftFrom = received
        draftSubject = if (Regex("^\\s*re\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(subject)) subject else "Re: $subject"
        draftText = signatureBlock() + quote(current)
        draftReplyTo = current.item.id
        showComposer()
    }

    /** Transfère le message ouvert, pièces jointes comprises. */
    fun forward() {
        val current = opened ?: return
        clearDraft()
        val mail = current.mail
        val subject = mail.subject.ifBlank { current.item.subject }
        val source = mail.text?.takeIf { it.isNotBlank() } ?: mail.html?.let { Mime.htmlToText(it) }.orEmpty()
        draftSubject = if (Regex("^\\s*(fwd?|tr)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(subject)) subject else "Fwd: $subject"
        draftText = buildString {
            append(signatureBlock())
            append("\n\n---------- Message transféré ----------\n")
            append("De : ").append(mail.from.joinToString(", ") { it.label }.ifBlank { current.item.fromAddr }).append('\n')
            append("Date : ").append(longDate(mail.date ?: current.item.date)).append('\n')
            append("Objet : ").append(subject).append('\n')
            append("À : ").append(mail.to.joinToString(", ") { it.label }.ifBlank { current.item.toAddr }).append("\n\n")
            append(source.trim()).append('\n')
        }
        mail.attachments.forEach { draftFiles.add(DraftFile(Uri.fromFile(it.file), it.name, it.type, it.size)) }
        showComposer()
        if (current.sentFiles.isNotEmpty()) {
            // Les fichiers d'un message envoyé sont sur le serveur : on les récupère pour les joindre à nouveau.
            viewModelScope.launch {
                for (remote in current.sentFiles) {
                    val file = fetchSentFile(current.item.id, remote) ?: continue
                    draftFiles.add(DraftFile(Uri.fromFile(file.file), file.name, file.type, file.size))
                }
                baseline = snapshot()
            }
        }
    }

    /** Fermeture de la rédaction : ce qui a été écrit est gardé dans les brouillons. */
    private fun closeComposer() {
        if (sending) return
        val target = composeReturn
        if (snapshot() == baseline) {
            clearDraft()
            screen = target
            return
        }
        if (!hasContent()) {
            discardDraft()
            return
        }
        sending = true
        viewModelScope.launch {
            try {
                val ids = uploadPending()
                val id = draftId ?: UUID.randomUUID().toString()
                repo.api.saveDraft(id, draftTo, draftCc, draftSubject, draftText, draftReplyTo, ids)
                clearDraft()
                say("Brouillon enregistré.")
                if (folder == "drafts") loadDrafts()
            } catch (e: Exception) {
                // Rien n'est perdu : le texte reste dans la fenêtre de rédaction.
                val reason = handle(e)
                if (reason != null) notice = "Brouillon non enregistré : il reste ouvert dans « Écrire ». $reason"
            }
            sending = false
            if (screen == Screen.Compose) screen = target
        }
    }

    fun discardDraft() {
        val id = draftId
        val uploaded = draftFiles.mapNotNull { it.uploadId }
        val target = composeReturn
        clearDraft()
        screen = target
        viewModelScope.launch {
            try {
                if (id != null) repo.api.deleteDraft(id) else uploaded.forEach { repo.api.deleteUpload(it) }
                if (id != null) say("Brouillon supprimé.")
                if (folder == "drafts") loadDrafts()
            } catch (e: Exception) {
                notice = handle(e)
            }
        }
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
        draftFrom = ""
        draftFiles.clear()
        draftReplyTo = null
        draftId = null
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
                val ids = uploadPending()
                val stored = repo.api.send(draftTo, draftCc, draftSubject, draftText, draftReplyTo, ids, draftId, draftFrom.ifBlank { null })
                clearDraft()
                notice = if (stored) "Message envoyé." else "Message envoyé, mais sa copie n'a pas pu être rangée dans Envoyés."
                screen = composeReturn
                if (folder == "sent" || folder == "drafts") refresh()
            } catch (e: Exception) {
                sendError = handle(e)
            }
            sending = false
        }
    }

    /** Dépose sur le serveur les fichiers qui n'y sont pas encore ; renvoie les identifiants de tous. */
    private suspend fun uploadPending(): List<String> {
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
        return ids
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
