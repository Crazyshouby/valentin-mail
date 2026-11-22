package com.valentinlarge.courrier

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Un message ouvert : ses métadonnées, son contenu décodé et, s'il a été envoyé d'ici, ses fichiers joints. */
data class OpenedMail(val item: MailItem, val mail: ParsedMail, val sentFiles: List<RemoteFile>)

/**
 * Relie le serveur et la copie locale : les listes passent par la base de l'appareil,
 * les messages bruts sont gardés sur disque pour pouvoir être relus sans réseau.
 */
class Repo private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: Repo? = null

        /** Une seule instance pour toute l'appli : écran et relève en arrière-plan partagent la même base. */
        fun get(context: Context): Repo =
            instance ?: synchronized(this) { instance ?: Repo(context).also { instance = it } }
    }

    private val app = context.applicationContext
    val prefs = Prefs(app)
    val api = Api(prefs)
    val db = Db(app)

    private val rawDir = File(app.filesDir, "messages")
    private val pieceDir = File(app.cacheDir, "pieces")
    private val thumbDir = File(app.cacheDir, "vignettes")

    private fun rawFile(id: String) = File(rawDir, "$id.eml")

    fun isCached(id: String): Boolean = rawFile(id).exists()

    suspend fun local(folder: String, query: String): List<MailItem> = withContext(Dispatchers.IO) { db.list(folder, query) }

    suspend fun unread(): Int = withContext(Dispatchers.IO) { db.unreadCount() }

    /** L'état actuel de messages déjà affichés (résultats d'une recherche), dans le même ordre. */
    suspend fun known(ids: List<String>, folder: String): List<MailItem> =
        withContext(Dispatchers.IO) { ids.mapNotNull { db.get(it) }.filter { it.isIn(folder) } }

    /** Les dossiers personnels connus à la dernière relève, sans leurs compteurs. */
    fun cachedLabels(): List<Label> =
        prefs.labelsCache.split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 1) null else Label(line.substring(0, tab), line.substring(tab + 1))
        }

    /** Les dossiers personnels ; sans réseau, ceux de la dernière relève. */
    suspend fun labels(): List<Label> =
        try {
            val fresh = api.labels()
            prefs.labelsCache = fresh.joinToString("\n") { "${it.id}\t${it.name}" }
            fresh
        } catch (e: OfflineException) {
            cachedLabels()
        }

    /** Range des messages dans un dossier personnel ; [leaveInbox] en sort ceux qui étaient en réception. */
    suspend fun file(ids: List<String>, label: String, leaveInbox: Boolean = true) {
        if (ids.isEmpty()) return
        api.batch(ids, addLabels = listOf(label), leaveInbox = leaveInbox)
        withContext(Dispatchers.IO) {
            for (id in ids) {
                val item = db.get(id) ?: continue
                db.setLabels(id, (item.labels + label).distinct())
                if (leaveInbox && item.folder == "inbox") db.setFolder(id, "archive")
            }
        }
    }

    /** Sort des messages d'un dossier personnel ; ils restent où ils sont par ailleurs. */
    suspend fun unfile(ids: List<String>, label: String) {
        if (ids.isEmpty()) return
        api.batch(ids, removeLabels = listOf(label))
        withContext(Dispatchers.IO) {
            for (id in ids) {
                val item = db.get(id) ?: continue
                db.setLabels(id, item.labels - label)
            }
        }
    }

    /** Première page d'un dossier : met la copie locale à jour, et prépare la lecture hors connexion. */
    suspend fun refresh(folder: String, query: String): ListPage {
        val page = api.list(folder, query)
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                val removed = db.replaceFirstPage(folder, page.messages, complete = !page.hasMore)
                // Un message disparu d'un dossier peut simplement avoir été déplacé : on ne jette
                // son contenu que s'il n'existe plus nulle part.
                removed.filter { db.get(it) == null }.forEach { forget(it) }
            } else {
                db.upsert(page.messages)
            }
            if (folder == "inbox" && query.isBlank()) {
                val newest = page.messages.maxOfOrNull { it.date } ?: 0L
                if (newest > prefs.lastSeen) prefs.lastSeen = newest
            }
        }
        return page
    }

    suspend fun more(folder: String, query: String, before: Long): ListPage {
        val page = api.list(folder, query, before = before)
        withContext(Dispatchers.IO) { db.upsert(page.messages) }
        return page
    }

    /** Télécharge à l'avance les messages récents et légers, pour qu'ils soient lisibles sans réseau. */
    suspend fun prefetch(items: List<MailItem>, limit: Int = 30) {
        for (item in items.take(limit)) {
            if (item.size > 3_000_000 || isCached(item.id)) continue
            try {
                api.downloadRaw(item.id, rawFile(item.id))
            } catch (e: Exception) {
                return
            }
        }
    }

    suspend fun open(id: String): OpenedMail {
        var item = withContext(Dispatchers.IO) { db.get(id) }
        var sentFiles = emptyList<RemoteFile>()
        val file = rawFile(id)

        if (item == null || !file.exists() || item.outgoing) {
            try {
                if (item == null || item.outgoing) {
                    val detail = api.detail(id)
                    // Le détail ne dit pas la taille de la conversation : on garde celle qu'on connaît.
                    val fresh = detail.first.copy(threadCount = item?.threadCount ?: 1)
                    item = fresh
                    sentFiles = detail.second
                    withContext(Dispatchers.IO) { db.upsert(listOf(fresh)) }
                }
                if (!file.exists()) api.downloadRaw(id, file)
            } catch (e: OfflineException) {
                if (item == null || !file.exists()) {
                    throw ApiException(0, "Ce message n'a pas été enregistré sur l'appareil. Reconnecte-toi à internet pour l'ouvrir.")
                }
            }
        }

        val current = item ?: throw ApiException(404, "Message introuvable.")
        val parsed = withContext(Dispatchers.IO) { Mime.parse(file, File(pieceDir, id)) }
        return OpenedMail(current, parsed, sentFiles)
    }

    suspend fun setRead(id: String, read: Boolean) {
        withContext(Dispatchers.IO) { db.setRead(id, read) }
        api.patch(id, isRead = read)
    }

    suspend fun move(id: String, folder: String) {
        api.patch(id, folder = folder)
        withContext(Dispatchers.IO) { db.setFolder(id, folder) }
    }

    /** La conversation complète ; sans réseau, ce que l'appareil en connaît. */
    suspend fun thread(threadId: String): List<MailItem> =
        try {
            val items = api.thread(threadId)
            withContext(Dispatchers.IO) { db.upsert(items) }
            items
        } catch (e: OfflineException) {
            withContext(Dispatchers.IO) { db.thread(threadId) }
        }

    suspend fun moveAll(ids: List<String>, folder: String) {
        api.batch(ids, folder = folder)
        withContext(Dispatchers.IO) { ids.forEach { db.setFolder(it, folder) } }
    }

    suspend fun readAll(ids: List<String>, read: Boolean) {
        withContext(Dispatchers.IO) { ids.forEach { db.setRead(it, read) } }
        api.batch(ids, isRead = read)
    }

    /** Suppression définitive de messages déjà à la corbeille. */
    suspend fun deleteAll(ids: List<String>) {
        api.batch(ids, delete = true)
        withContext(Dispatchers.IO) {
            ids.forEach {
                db.delete(it)
                forget(it)
            }
        }
    }

    /** Renvoie vrai si le message a été supprimé pour de bon. */
    suspend fun delete(id: String): Boolean {
        val gone = api.delete(id)
        withContext(Dispatchers.IO) {
            if (gone) {
                db.delete(id)
                forget(id)
            } else {
                db.setFolder(id, "trash")
            }
        }
        return gone
    }

    suspend fun emptyTrash(): Int {
        val remaining = api.emptyTrash()
        withContext(Dispatchers.IO) {
            if (remaining == 0) {
                db.idsIn("trash").forEach {
                    db.delete(it)
                    forget(it)
                }
            }
        }
        return remaining
    }

    /** Fichier joint d'un message envoyé : téléchargé à la demande dans le cache. */
    suspend fun sentFile(messageId: String, remote: RemoteFile): LocalFile {
        val target = File(File(pieceDir, "$messageId-envoi"), "${remote.id.take(8)}-${Mime.safeName(remote.name)}")
        if (!target.exists() || target.length() != remote.size) api.downloadUpload(remote.id, target)
        return LocalFile(remote.name, remote.type, target)
    }

    private fun galleryFile(attachment: Attachment) =
        File(File(pieceDir, "${attachment.messageId}-galerie"), "${attachment.part}-${Mime.safeName(attachment.name)}")

    /** Pièce jointe choisie dans la galerie : téléchargée seule, sans le reste du message. */
    suspend fun attachmentFile(attachment: Attachment): LocalFile {
        val target = galleryFile(attachment)
        if (!target.exists() || target.length() == 0L) api.downloadAttachment(attachment.messageId, attachment.part, target)
        return LocalFile(attachment.name, attachment.mime, target)
    }

    private fun thumbFile(attachment: Attachment) = File(thumbDir, "${attachment.messageId}-${attachment.part}.jpg")

    /** La vignette déjà fabriquée pour cette image, s'il y en a une. */
    fun cachedThumbnail(attachment: Attachment): File? = thumbFile(attachment).takeIf { it.length() > 0 }

    /**
     * Fabrique la vignette d'une image de la galerie en téléchargeant le moins possible (voir [Thumbs]).
     * [wifi] : le réseau n'est pas compté, on peut y prendre une grande image entière.
     * Renvoie null quand il n'y a pas de vignette à faire pour l'instant.
     */
    suspend fun thumbnail(attachment: Attachment, wifi: Boolean): File? {
        val target = thumbFile(attachment)
        if (target.length() > 0) return target
        if (attachment.size <= 0 || attachment.size > Thumbs.BIG_BYTES) return null
        val small = attachment.size <= Thumbs.smallBytes
        var bitmap: Bitmap? = null

        // Une photo : sa miniature tient dans le début du fichier.
        if (!small && Thumbs.isPhoto(attachment)) {
            val (raw, encoding) = api.attachmentHead(attachment.messageId, attachment.part, Thumbs.HEAD_BYTES)
            bitmap = withContext(Dispatchers.IO) { Thumbs.decodeHead(raw, encoding)?.let { Thumbs.exifThumbnail(it) } }
        }

        // Sinon l'image entière : toujours si elle est petite, seulement en Wi-Fi si elle est grande.
        if (bitmap == null && (small || wifi)) {
            val known = galleryFile(attachment)
            val source = if (known.length() > 0) known else File(thumbDir, "${attachment.messageId}-${attachment.part}.source")
            if (source != known) api.downloadAttachment(attachment.messageId, attachment.part, source)
            bitmap = withContext(Dispatchers.IO) {
                try {
                    Thumbs.fromFile(source)
                } finally {
                    if (source != known) source.delete()
                }
            }
        }

        val made = bitmap ?: return null
        withContext(Dispatchers.IO) { Thumbs.save(made, target) }
        return target
    }

    private fun forget(id: String) {
        rawFile(id).delete()
        File(pieceDir, id).deleteRecursively()
        File(pieceDir, "$id-envoi").deleteRecursively()
        File(pieceDir, "$id-galerie").deleteRecursively()
        thumbDir.listFiles { file -> file.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    /** Efface tout ce que l'appareil garde de la boîte ; [remote] ferme aussi la session sur le serveur. */
    suspend fun logout(remote: Boolean = false) {
        if (remote) {
            try {
                api.logout()
            } catch (e: Exception) {
                // Sans réseau, la session s'éteindra d'elle-même ; elle reste fermable depuis un autre appareil.
            }
        }
        withContext(Dispatchers.IO) {
            prefs.clearSession()
            db.clear()
            rawDir.deleteRecursively()
            pieceDir.deleteRecursively()
            thumbDir.deleteRecursively()
        }
    }
}
