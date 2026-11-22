package com.valentinlarge.courrier

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.LruCache
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    data object Gallery : Screen
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

    // ---- verrou
    var lockEnabled by mutableStateOf(repo.prefs.lockEnabled)
        private set

    /** Vrai tant que le téléphone n'a pas reconnu son propriétaire : l'écran de verrou cache tout le reste. */
    var locked by mutableStateOf(false)
        private set
    private var leftAt = 0L

    /** Faut-il verrouiller ? Seulement si c'est demandé, qu'une boîte est ouverte, et que le téléphone sait vérifier. */
    private fun mustLock(): Boolean {
        if (!repo.prefs.lockEnabled || repo.prefs.token == null) return false
        if (Lock.state(app) != Lock.State.Ready) {
            // Le téléphone n'a plus ni empreinte ni code : le verrou n'aurait plus de clé. Pour retirer
            // ceux-ci, il a fallu les connaître ; on rouvre donc l'appli plutôt que de l'enfermer.
            repo.prefs.lockEnabled = false
            lockEnabled = false
            return false
        }
        return true
    }

    /** Vrai quand le verrou vient d'être mis à la main : l'écran attend alors qu'on demande à rouvrir. */
    var lockQuiet by mutableStateOf(false)
        private set

    fun unlock() {
        locked = false
        lockQuiet = false
    }

    /** Active ou coupe le verrou ; l'écran des réglages a déjà fait vérifier l'identité par le téléphone. */
    fun setLock(enabled: Boolean) {
        repo.prefs.lockEnabled = enabled
        lockEnabled = enabled
        notice = if (enabled) "Verrou activé : l'appli demandera l'empreinte à l'ouverture." else "Verrou désactivé."
    }

    /** L'appli quitte l'écran. */
    fun wentAway() {
        leftAt = SystemClock.elapsedRealtime()
    }

    /** L'appli revient à l'écran : elle se verrouille si elle l'a quitté depuis trop longtemps. */
    fun cameBack() {
        val since = leftAt
        leftAt = 0L
        if (repo.prefs.token != null) Push.listener = pushListener
        if (!locked && since != 0L && SystemClock.elapsedRealtime() - since >= Lock.graceMs && mustLock()) {
            lockQuiet = false
            locked = true
        }
    }

    /** Verrouille tout de suite, à la demande. */
    fun lockNow() {
        if (mustLock()) {
            lockQuiet = true
            locked = true
        }
    }

    // ---- thème
    var themeMode by mutableStateOf(repo.prefs.themeMode)
        private set

    /** « auto » suit le téléphone ; « light » et « dark » imposent un thème. */
    fun chooseTheme(mode: String) {
        repo.prefs.themeMode = mode
        themeMode = mode
        Palette.mode = mode
    }

    // ---- pages
    /** La page de fond : celle qu'on voit, ou celle qui attend sous un message ouvert ou une rédaction. */
    val tab: Screen
        get() = when (val now = screen) {
            is Screen.Reader -> readerReturn
            Screen.Compose -> when (val under = composeReturn) {
                is Screen.Reader -> readerReturn
                Screen.Gallery, Screen.Settings -> under
                else -> Screen.Mailbox
            }
            Screen.Gallery, Screen.Settings -> now
            else -> Screen.Mailbox
        }

    /** L'identifiant du message ouvert, y compris quand on lui répond par-dessus. */
    val readerId: String?
        get() = (screen as? Screen.Reader)?.id
            ?: if (screen == Screen.Compose) (composeReturn as? Screen.Reader)?.id else null

    /** Revient à la page du courrier depuis une autre page. */
    fun goMailbox() {
        if (screen == Screen.Gallery || screen == Screen.Settings) back()
    }

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

    // ---- dossiers personnels
    var labels by mutableStateOf(repo.cachedLabels())
        private set
    var labelBusy by mutableStateOf(false)
        private set
    var labelError by mutableStateOf<String?>(null)
        private set

    /** Le dossier personnel que la liste affiche, s'il y en a un. */
    val currentLabel: Label? get() = labelIdOf(folder)?.let { id -> labels.firstOrNull { it.id == id } }

    fun labelName(id: String): String? = labels.firstOrNull { it.id == id }?.name

    /** Les noms des dossiers où un message est rangé. */
    fun labelNames(ids: List<String>): List<String> = ids.mapNotNull { labelName(it) }

    /** Pour une ligne de la liste : le nom court de ses dossiers, sauf celui qu'on est en train de regarder. */
    fun tagsOf(item: MailItem): List<String> {
        if (item.labels.isEmpty()) return emptyList()
        val shown = labelIdOf(folder)
        return item.labels.filter { it != shown }.mapNotNull { labelName(it)?.substringAfterLast('/') }
    }

    fun loadLabels() {
        viewModelScope.launch {
            try {
                labels = repo.labels()
            } catch (e: Exception) {
                handle(e)
            }
        }
    }

    /** Les dossiers dont on a déplié les sous-dossiers ; au départ, tous sont repliés. */
    var openFolders by mutableStateOf(repo.prefs.openFolders)
        private set

    /** Déplie ou replie les sous-dossiers d'un dossier ; le choix est gardé d'une fois sur l'autre. */
    fun toggleFolder(id: String) {
        openFolders = if (id in openFolders) openFolders - id else openFolders + id
        repo.prefs.openFolders = openFolders
    }

    /** Déplie ce qu'il faut pour qu'un dossier soit visible dans la liste. */
    private fun revealFolder(id: String) {
        val missing = ancestorsOf(labelTree(labels), id) - openFolders
        if (missing.isEmpty()) return
        openFolders = openFolders + missing
        repo.prefs.openFolders = openFolders
    }

    /** Avant de montrer la liste des dossiers : celui qu'on regarde doit s'y voir, même s'il est un sous-dossier. */
    fun revealCurrentFolder() {
        labelIdOf(folder)?.let { revealFolder(it) }
    }

    /** Applique un changement à un dossier, puis relit la liste telle que le serveur la garde. */
    private fun labelChange(after: () -> Unit = {}, change: suspend () -> Unit) {
        if (labelBusy) return
        labelBusy = true
        labelError = null
        viewModelScope.launch {
            try {
                change()
                labels = repo.labels()
                after()
            } catch (e: Exception) {
                labelError = handle(e)
            }
            labelBusy = false
        }
    }

    fun createLabel(name: String) {
        if (name.isBlank()) return
        val id = UUID.randomUUID().toString()
        // Un sous-dossier tout juste créé se montre : son parent se déplie.
        labelChange(after = { revealFolder(id) }) { repo.api.saveLabel(id, name.trim()) }
    }

    fun renameLabel(label: Label, name: String) {
        if (name.isBlank() || name.trim() == label.name) return
        labelChange { repo.api.saveLabel(label.id, name.trim()) }
    }

    fun deleteLabel(label: Label) = labelChange {
        repo.api.deleteLabel(label.id)
        if (labelIdOf(folder) == label.id) selectFolder("inbox")
    }

    // ---- boîtes d'origine
    var boxes by mutableStateOf<List<MailBox>>(emptyList())
        private set

    /** La boîte d'origine que la liste affiche, s'il y en a une. */
    val currentBox: MailBox? get() = boxOf(folder)?.let { address -> boxes.firstOrNull { it.address == address } }

    fun loadBoxes() {
        viewModelScope.launch {
            try {
                boxes = repo.api.boxes()
            } catch (e: OfflineException) {
                // Sans réseau, on garde les boîtes déjà connues.
            } catch (e: Exception) {
                handle(e)
            }
        }
    }

    // ---- appareils connectés
    var sessions by mutableStateOf<List<DeviceSession>>(emptyList())
        private set

    fun closeSession(session: DeviceSession) = settings {
        repo.api.closeSession(session.id)
        notice = "« ${session.name} » est déconnecté."
    }

    fun closeOtherSessions() = settings {
        val closed = repo.api.closeOtherSessions()
        notice = when (closed) {
            0 -> "Aucun autre appareil n'était connecté."
            1 -> "1 appareil déconnecté."
            else -> "$closed appareils déconnectés."
        }
    }

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
        loadLabels()
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
                sessions = repo.api.sessions()
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

    fun addRule(field: String, value: String, action: String, target: String? = null) {
        if (value.isBlank()) return
        settings { repo.api.saveRule(UUID.randomUUID().toString(), field, value.trim(), action, target) }
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

    /** L'écran où revenir en quittant la lecture : la liste, ou la galerie des pièces jointes. */
    private var readerReturn: Screen = Screen.Mailbox

    // ---- messages confiés par une autre appli
    /** Vrai quand l'appli n'a été ouverte que pour écrire un message : elle se referme une fois celui-ci parti ou rangé. */
    private var exitAfterCompose = false

    /** Le mot de la fin (parfois vide) : dès qu'il existe, l'écran se referme et rend la main à l'appli d'où l'on vient. */
    var farewell by mutableStateOf<String?>(null)
        private set

    /** Ce qu'une autre appli a confié alors que la boîte n'était pas ouverte : repris après la connexion. */
    private var pendingShare: Shared? = null

    // ---- galerie des pièces jointes
    var gallery by mutableStateOf<List<Attachment>>(emptyList())
        private set
    var galleryKind by mutableStateOf("")
        private set
    var galleryQuery by mutableStateOf("")
        private set
    var galleryLoading by mutableStateOf(false)
        private set
    var galleryLoadingMore by mutableStateOf(false)
        private set
    var galleryMore by mutableStateOf(false)
        private set

    /** Nombre de messages dont les pièces jointes n'ont pas encore été relevées par le serveur. */
    var galleryPending by mutableStateOf(0)
        private set
    var galleryError by mutableStateOf<String?>(null)
        private set

    /** Pourquoi l'inventaire s'est interrompu, quand ce n'est pas fini (quota du jour atteint). */
    var galleryPaused by mutableStateOf<String?>(null)
        private set
    private var galleryJob: Job? = null
    private var scanJob: Job? = null

    // Les vignettes déjà affichées, gardées en mémoire (16 Mo au plus) ; au-delà, elles se relisent sur l'appareil.
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Les images pour lesquelles il n'y a pas de vignette à faire pour l'instant (réseau compté, format illisible). */
    private val thumbless = HashSet<String>()

    /** Pas plus de trois vignettes en fabrication à la fois. */
    private val thumbGate = Semaphore(3)

    // Un signal arrivé pendant que la boîte est à l'écran la met à jour sans attendre.
    private val pushListener: () -> Unit = { if (screen == Screen.Mailbox && folder == "inbox") refresh() }

    init {
        locked = mustLock()
        if (repo.prefs.token != null) startMailbox()
    }

    private fun startMailbox() {
        Sync.schedule(app)
        Push.register(app)
        Push.listener = pushListener
        showLocal()
        refresh()
        loadLabels()
        loadBoxes()
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
            locked = false
            loginError = "Ta session a expiré, ou cet appareil a été déconnecté. Reconnecte-toi."
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
                // Un message confié par une autre appli attendait que la boîte soit ouverte.
                pendingShare?.let {
                    pendingShare = null
                    applyShare(it)
                }
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
        if (Push.listener === pushListener) Push.listener = null
        super.onCleared()
    }

    fun logout() {
        viewModelScope.launch {
            Push.forget(app)
            // Le serveur ferme aussi la session : le jeton gardé par ce téléphone ne vaut plus rien.
            repo.logout(remote = true)
            Sync.cancel(app)
            galleryJob?.cancel()
            scanJob?.cancel()
            messages = emptyList()
            labels = emptyList()
            openFolders = emptySet()
            boxes = emptyList()
            sessions = emptyList()
            gallery = emptyList()
            thumbs.evictAll()
            thumbless.clear()
            opened = null
            unread = 0
            locked = false
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
        // Les compteurs d'un dossier personnel ou d'une boîte bougent avec leur contenu.
        if (labelIdOf(target) != null) loadLabels()
        if (boxOf(target) != null) loadBoxes()
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
        // D'où vient chaque message : dans un dossier personnel, ils ne viennent pas tous du même endroit.
        val origins = messages.filter { it.id in ids }.groupBy({ it.folder }, { it.id })
        selected.clear()
        messages = messages.filter { it.id !in ids }
        viewModelScope.launch {
            try {
                repo.moveAll(ids, to)
                say(done) {
                    viewModelScope.launch {
                        try {
                            for ((from, part) in origins) repo.moveAll(part, from)
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

    /** Range des conversations dans un dossier personnel ; ce qui était en réception la quitte. Le geste peut être annulé. */
    fun fileRows(target: List<ThreadRow>, label: Label) {
        val ids = target.flatMap { it.ids }
        if (ids.isEmpty()) return
        val fromInbox = messages.filter { it.id in ids && it.folder == "inbox" }.map { it.id }
        val already = messages.filter { it.id in ids && label.id in it.labels }.map { it.id }.toSet()
        selected.clear()
        if (folder == "inbox") messages = messages.filter { it.id !in ids }
        viewModelScope.launch {
            try {
                repo.file(ids, label.id)
                say(if (ids.size == 1) "Rangé dans « ${label.name} »." else "${ids.size} messages rangés dans « ${label.name} ».") {
                    viewModelScope.launch {
                        try {
                            repo.unfile(ids.filter { it !in already }, label.id)
                            if (fromInbox.isNotEmpty()) repo.moveAll(fromInbox, "inbox")
                        } catch (e: Exception) {
                            notice = handle(e)
                        }
                        showLocal()
                        refresh()
                        loadLabels()
                    }
                }
            } catch (e: Exception) {
                notice = handle(e)
            }
            showLocal()
            loadLabels()
        }
    }

    /** Sort des conversations du dossier personnel affiché ; elles restent où elles sont par ailleurs. */
    fun unfileRows(target: List<ThreadRow>) {
        val label = currentLabel ?: return
        val ids = target.flatMap { it.ids }
        if (ids.isEmpty()) return
        selected.clear()
        messages = messages.filter { it.id !in ids }
        viewModelScope.launch {
            try {
                repo.unfile(ids, label.id)
                say(if (ids.size == 1) "Sorti de « ${label.name} »." else "${ids.size} messages sortis de « ${label.name} ».") {
                    viewModelScope.launch {
                        try {
                            repo.file(ids, label.id, leaveInbox = false)
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
            loadLabels()
        }
    }

    /** Dans la lecture : range le message ouvert dans un dossier, ou l'en sort s'il y est déjà. */
    fun toggleLabel(label: Label) {
        val current = opened ?: return
        val item = current.item
        val filed = label.id in item.labels
        viewModelScope.launch {
            try {
                if (filed) repo.unfile(listOf(item.id), label.id) else repo.file(listOf(item.id), label.id)
                val fresh = if (filed) {
                    item.copy(labels = item.labels - label.id)
                } else {
                    item.copy(labels = item.labels + label.id, folder = if (item.folder == "inbox") "archive" else item.folder)
                }
                if (opened?.item?.id == item.id) opened = current.copy(item = fresh)
                notice = if (filed) "Sorti de « ${label.name} »." else "Rangé dans « ${label.name} »."
                loadLabels()
            } catch (e: Exception) {
                notice = handle(e)
            }
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
        readerReturn = Screen.Mailbox
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
        readerReturn = Screen.Mailbox
        open(id)
    }

    fun back() {
        when (screen) {
            is Screen.Reader -> {
                // Le message reste en mémoire le temps que son écran glisse hors de vue ; le prochain le remplacera.
                readerJob?.cancel()
                readerError = null
                closeReader()
                showLocal()
            }
            Screen.Compose -> closeComposer()
            Screen.Settings -> screen = Screen.Mailbox
            Screen.Gallery -> {
                galleryJob?.cancel()
                scanJob?.cancel()
                galleryLoading = false
                screen = Screen.Mailbox
            }
            else -> Unit
        }
    }

    /** Quitte la lecture pour l'écran d'où le message a été ouvert. */
    private fun closeReader() {
        val target = readerReturn
        readerReturn = Screen.Mailbox
        screen = target
        if (target == Screen.Gallery) {
            // Le message a pu être rangé ou jeté entre-temps : la galerie se remet à jour.
            if (gallery.size <= GALLERY_PAGE) loadGallery(quiet = true)
            startScan()
        }
    }

    private fun leaveReader(done: String) {
        notice = done
        closeReader()
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

    // -------------------------------------------- galerie des pièces jointes

    fun openGallery() {
        screen = Screen.Gallery
        galleryPaused = null
        // Le réseau a pu changer depuis la dernière visite : les grandes images ont une nouvelle chance.
        thumbless.clear()
        loadGallery()
        startScan()
    }

    fun filterGallery(kind: String) {
        if (kind == galleryKind) return
        galleryKind = kind
        gallery = emptyList()
        loadGallery()
    }

    fun searchGallery(text: String) {
        galleryQuery = text
        loadGallery(wait = 350)
    }

    fun reloadGallery() {
        galleryPaused = null
        loadGallery()
        startScan()
    }

    private fun loadGallery(wait: Long = 0, quiet: Boolean = false) {
        galleryJob?.cancel()
        val kind = galleryKind
        val text = galleryQuery
        galleryJob = viewModelScope.launch {
            if (wait > 0) delay(wait)
            if (!quiet) galleryLoading = true
            galleryError = null
            try {
                val page = repo.api.attachments(kind, text)
                gallery = page.items
                galleryMore = page.hasMore
                galleryPending = page.pending
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                galleryError = handle(e)
            }
            galleryLoading = false
        }
    }

    fun moreGallery() {
        val last = gallery.lastOrNull() ?: return
        if (galleryLoadingMore) return
        val kind = galleryKind
        val text = galleryQuery
        galleryLoadingMore = true
        viewModelScope.launch {
            try {
                val page = repo.api.attachments(kind, text, after = last.cursor)
                if (kind == galleryKind && text == galleryQuery) {
                    gallery = (gallery + page.items).distinctBy { it.key }
                    galleryMore = page.hasMore
                    galleryPending = page.pending
                }
            } catch (e: Exception) {
                notice = handle(e)
            }
            galleryLoadingMore = false
        }
    }

    /**
     * Tant que la galerie est à l'écran, fait avancer l'inventaire du serveur : il relève les pièces
     * jointes des messages qu'il n'a pas encore examinés (ceux d'avant la galerie, ou importés).
     */
    private fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            var passes = 0
            var failures = 0
            var stumbles = 0
            while (screen == Screen.Gallery) {
                val remaining = try {
                    repo.api.scanAttachments()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.status == 401) {
                        handle(e)
                        break
                    }
                    if (e.status == 429) {
                        galleryPaused = "L'inventaire a atteint la limite du jour ; il reprendra demain."
                        break
                    }
                    -1
                } catch (e: Exception) {
                    -1
                }
                if (remaining < 0) {
                    // Un très gros message peut faire trébucher le serveur : il le met de côté et passe
                    // aux suivants à l'essai d'après. On insiste donc un peu avant de s'arrêter.
                    failures++
                    stumbles++
                    if (failures >= 8 || stumbles >= 150) {
                        galleryPaused = "L'inventaire s'est interrompu. Tire la liste vers le bas pour le relancer."
                        break
                    }
                    delay(1500)
                    continue
                }
                failures = 0
                passes++
                val done = remaining <= 0
                // La liste se complète au fil de l'inventaire, tant qu'on n'a pas commencé à la faire défiler loin.
                if ((done && galleryPending > 0) || (!done && passes % 4 == 0)) {
                    if (gallery.size <= GALLERY_PAGE) loadGallery(quiet = true)
                }
                galleryPending = remaining
                if (done) break
                delay(250)
            }
        }
    }

    /** La vignette d'une image, si elle est déjà en mémoire. */
    fun cachedThumb(attachment: Attachment): Bitmap? = thumbs.get(attachment.key)

    /** Vrai si le réseau du moment est compté (données mobiles) : on n'y télécharge pas de grande image pour une vignette. */
    private fun metered(): Boolean =
        app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /** La vignette d'une image de la galerie : relue sur l'appareil, ou fabriquée ; null s'il n'y en a pas pour l'instant. */
    suspend fun thumbnail(attachment: Attachment): Bitmap? {
        thumbs.get(attachment.key)?.let { return it }
        if (attachment.kind != "image" || attachment.key in thumbless) return null
        val file = repo.cachedThumbnail(attachment) ?: try {
            thumbGate.withPermit { repo.thumbnail(attachment, wifi = !metered()) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Réseau coupé, serveur occupé : on réessaiera quand la ligne reviendra à l'écran.
            return null
        } catch (e: OutOfMemoryError) {
            null
        }
        val bitmap = if (file == null) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }
        if (bitmap == null) {
            file?.delete()
            thumbless.add(attachment.key)
            return null
        }
        thumbs.put(attachment.key, bitmap)
        return bitmap
    }

    /** Prépare une pièce de la galerie pour l'ouvrir ou l'enregistrer. */
    suspend fun fetchAttachment(attachment: Attachment): LocalFile? =
        try {
            repo.attachmentFile(attachment)
        } catch (e: Exception) {
            notice = handle(e)
            null
        }

    /** Ouvre le message qui porte une pièce de la galerie ; le retour ramène à la galerie. */
    fun openFromGallery(attachment: Attachment) {
        readerReturn = Screen.Gallery
        open(attachment.messageId)
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

    /** Nouveau message pour une adresse touchée dans un mail ; le lien peut aussi proposer un objet ou un texte. */
    fun composeLink(link: Uri) {
        val shared = Share.mailto(link) ?: return
        clearDraft()
        draftTo = shared.to
        draftCc = shared.cc
        draftSubject = shared.subject
        draftText = shared.text + signatureBlock()
        showComposer()
        baseline = ""
    }

    /**
     * Une autre appli confie de quoi écrire un message : un texte ou des fichiers partagés, ou une
     * adresse touchée ailleurs. [exit] : l'appli n'a été ouverte que pour cela.
     */
    fun receive(shared: Shared, exit: Boolean) {
        if (exit) exitAfterCompose = true
        if (repo.prefs.token == null) {
            pendingShare = shared
            return
        }
        applyShare(shared)
    }

    private fun applyShare(shared: Shared) {
        // Un message déjà commencé n'est pas remplacé : ce qui arrive s'y ajoute.
        val untouched = !hasContent() || snapshot() == baseline
        if (untouched) {
            clearDraft()
            draftText = signatureBlock()
        }
        draftTo = joinAddresses(draftTo, shared.to)
        draftCc = joinAddresses(draftCc, shared.cc)
        if (draftSubject.isBlank()) draftSubject = shared.subject
        if (shared.text.isNotBlank()) {
            draftText = if (untouched) shared.text + draftText else draftText.trimEnd() + "\n\n" + shared.text
        }
        if (screen != Screen.Compose) showComposer()
        // Ce qui vient d'arriver compte comme écrit : refermer la rédaction le garde en brouillon.
        baseline = ""
        if (shared.files.isNotEmpty()) addFiles(shared.files)
        if (shared.refused > 0) {
            notice = if (shared.refused == 1) "Un fichier proposé n'a pas pu être joint." else "${shared.refused} fichiers proposés n'ont pas pu être joints."
        }
    }

    private fun joinAddresses(current: String, added: String): String = when {
        added.isBlank() -> current
        current.isBlank() -> added
        else -> current.trim().trimEnd(',') + ", " + added
    }

    /** Quitte la rédaction : retour à l'écran d'avant, ou à l'appli d'où le message a été lancé. */
    private fun leaveComposer(target: Screen, word: String = "") {
        if (exitAfterCompose) farewell = word else screen = target
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
            leaveComposer(target)
            return
        }
        if (!hasContent()) {
            discardDraft()
            return
        }
        sending = true
        viewModelScope.launch {
            var saved = false
            try {
                val ids = uploadPending()
                val id = draftId ?: UUID.randomUUID().toString()
                repo.api.saveDraft(id, draftTo, draftCc, draftSubject, draftText, draftReplyTo, ids)
                clearDraft()
                saved = true
                if (!exitAfterCompose) say("Brouillon enregistré.")
                if (folder == "drafts") loadDrafts()
            } catch (e: Exception) {
                // Rien n'est perdu : le texte reste dans la fenêtre de rédaction.
                val reason = handle(e)
                if (reason != null) {
                    notice = if (exitAfterCompose) "Brouillon non enregistré. $reason" else "Brouillon non enregistré : il reste ouvert dans « Écrire ». $reason"
                }
            }
            sending = false
            if (screen == Screen.Compose) {
                // Ouverte seulement pour ce message, l'appli ne se referme que s'il est en lieu sûr.
                if (!exitAfterCompose) screen = target else if (saved) farewell = "Brouillon enregistré."
            }
        }
    }

    fun discardDraft() {
        val id = draftId
        val uploaded = draftFiles.mapNotNull { it.uploadId }
        val target = composeReturn
        clearDraft()
        if (id == null && uploaded.isEmpty()) {
            leaveComposer(target)
            return
        }
        if (!exitAfterCompose) screen = target
        viewModelScope.launch {
            try {
                if (id != null) repo.api.deleteDraft(id) else uploaded.forEach { repo.api.deleteUpload(it) }
                if (id != null && !exitAfterCompose) say("Brouillon supprimé.")
                if (folder == "drafts") loadDrafts()
            } catch (e: Exception) {
                notice = handle(e)
            }
            // Le ménage sur le serveur est fini : l'appli peut rendre la main.
            if (exitAfterCompose) farewell = ""
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
                val word = if (stored) "Message envoyé." else "Message envoyé, mais sa copie n'a pas pu être rangée dans Envoyés."
                if (!exitAfterCompose) notice = word
                leaveComposer(composeReturn, word)
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

/** Taille d'une page de la galerie, telle que le serveur la découpe. */
private const val GALLERY_PAGE = 50

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

private val monthFormat = DateTimeFormatter.ofPattern("MMMM", Locale.FRANCE)
private val monthYearFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)

/** Le groupe où ranger un message dans la liste : le jour pour les plus récents, le mois ensuite. */
fun dayBucket(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(zone)
    val day = date.toLocalDate()
    val today = Instant.now().atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> "Aujourd'hui"
        day == today.minusDays(1) -> "Hier"
        day.isAfter(today.minusDays(7)) -> "Cette semaine"
        day.year == today.year -> monthFormat.format(date).replaceFirstChar { it.uppercase() }
        else -> monthYearFormat.format(date).replaceFirstChar { it.uppercase() }
    }
}

fun fileSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes o"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} Ko"
    else -> String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0))
}
