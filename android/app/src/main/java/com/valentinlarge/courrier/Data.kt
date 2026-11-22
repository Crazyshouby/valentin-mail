package com.valentinlarge.courrier

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Un message tel qu'il apparaît dans une liste. */
data class MailItem(
    val id: String,
    val folder: String,
    val direction: String,
    val fromAddr: String,
    val fromName: String,
    val toAddr: String,
    val subject: String,
    val date: Long,
    val size: Long,
    val isRead: Boolean,
    /** Conversation à laquelle le message appartient : l'identifiant de son premier message. */
    val threadId: String = id,
    /** Nombre de messages de cette conversation, tous dossiers confondus sauf la corbeille. */
    val threadCount: Int = 1,
    /** Dossiers personnels où le message est rangé (leurs identifiants). */
    val labels: List<String> = emptyList(),
    /** La boîte qui a reçu le message : une adresse du domaine, ou le compte Gmail d'où il vient. */
    val box: String = "",
    /** Le début du texte du message, pour la liste ; vide tant que le serveur ne l'a pas relevé. */
    val snippet: String = "",
    /** Nombre de pièces jointes relevées par le serveur. */
    val files: Int = 0,
) {
    val outgoing: Boolean get() = direction == "out"

    /** Vrai si le message figure dans cette vue : un dossier de la boîte, un dossier personnel, ou une boîte d'origine. */
    fun isIn(view: String): Boolean {
        boxOf(view)?.let { return !outgoing && folder != "trash" && box == it }
        val label = labelIdOf(view) ?: return folder == view
        return folder != "trash" && label in labels
    }
}

/** Un dossier personnel ; « Parent/Enfant » dans son nom en fait un sous-dossier. */
data class Label(val id: String, val name: String, val total: Int = 0, val unread: Int = 0)

/** Une ligne de l'arbre des dossiers : le dossier, son nom court et son niveau. */
data class LabelNode(val label: Label, val short: String, val depth: Int)

/** Range les dossiers en arbre : un sous-dossier se place sous son parent quand celui-ci existe. */
fun labelTree(labels: List<Label>): List<LabelNode> {
    // La barre oblique passe avant tout autre caractère : un sous-dossier suit toujours son parent.
    val sorted = labels.sortedBy { it.name.lowercase().replace('/', '\u0001') }
    val names = sorted.map { it.name }.toSet()
    return sorted.map { label ->
        val parts = label.name.split('/')
        var depth = 0
        var cut = 0
        for (i in 1 until parts.size) {
            if (parts.take(i).joinToString("/") in names) {
                depth++
                cut = i
            }
        }
        LabelNode(label, parts.drop(cut).joinToString("/"), depth)
    }
}

/**
 * Une ligne de l'arbre telle qu'on l'affiche : [parent] si elle contient des sous-dossiers, [open] si
 * ceux-ci sont dépliés, [visible] tant qu'aucun de ses parents n'est replié. Repliée, elle porte aussi
 * dans [unread] les non-lus de ce qu'elle cache.
 */
data class FolderRow(val node: LabelNode, val parent: Boolean, val open: Boolean, val visible: Boolean, val unread: Int)

/** Prépare l'arbre pour l'affichage ; [opened] : les dossiers dépliés (aucun au départ). */
fun folderRows(tree: List<LabelNode>, opened: Set<String>): List<FolderRow> {
    val rows = ArrayList<FolderRow>(tree.size)
    // Pour chaque niveau au-dessus de la ligne en cours : ce parent est-il déplié ?
    val path = ArrayList<Boolean>()
    for ((index, node) in tree.withIndex()) {
        while (path.size > node.depth) path.removeAt(path.size - 1)
        while (path.size < node.depth) path.add(true)
        val parent = index + 1 < tree.size && tree[index + 1].depth > node.depth
        val open = parent && node.label.id in opened
        var unread = node.label.unread
        if (parent && !open) {
            var next = index + 1
            while (next < tree.size && tree[next].depth > node.depth) {
                unread += tree[next].label.unread
                next++
            }
        }
        rows.add(FolderRow(node, parent, open, visible = path.all { it }, unread = unread))
        path.add(open)
    }
    return rows
}

/** Les dossiers au-dessus de celui-ci dans l'arbre : ceux qu'il faut déplier pour le voir. */
fun ancestorsOf(tree: List<LabelNode>, id: String): Set<String> {
    val index = tree.indexOfFirst { it.label.id == id }
    if (index < 0) return emptySet()
    val found = HashSet<String>()
    var depth = tree[index].depth
    var i = index - 1
    while (i >= 0 && depth > 0) {
        if (tree[i].depth < depth) {
            found.add(tree[i].label.id)
            depth = tree[i].depth
        }
        i--
    }
    return found
}

// La liste affiche soit un dossier de la boîte (« inbox », « archive »…), soit un dossier personnel,
// désigné alors par « label: » suivi de son identifiant.
private const val LABEL_PREFIX = "label:"

fun labelKey(id: String): String = LABEL_PREFIX + id

fun labelIdOf(view: String): String? = if (view.startsWith(LABEL_PREFIX)) view.substring(LABEL_PREFIX.length) else null

// Troisième sorte de vue : le courrier reçu par une boîte, désignée par « box: » suivi de son adresse.
private const val BOX_PREFIX = "box:"

fun boxKey(address: String): String = BOX_PREFIX + address

fun boxOf(view: String): String? = if (view.startsWith(BOX_PREFIX)) view.substring(BOX_PREFIX.length) else null

/** Vrai pour une vue qui traverse les dossiers de la boîte : un dossier personnel, ou une boîte d'origine. */
fun isCrossView(view: String): Boolean = labelIdOf(view) != null || boxOf(view) != null

/** Une boîte d'origine : l'adresse principale, une autre adresse du domaine, ou un compte extérieur (Gmail). */
data class MailBox(val address: String, val kind: String, val total: Int, val unread: Int)

/** Un appareil ou un navigateur connecté à la boîte. */
data class DeviceSession(
    val id: String,
    val kind: String,
    val name: String,
    val place: String,
    val created: Long,
    val seen: Long,
    val current: Boolean,
)

/** Une pièce jointe de l'inventaire : le fichier, et le message qui la porte. */
data class Attachment(
    val messageId: String,
    val part: Int,
    val name: String,
    val mime: String,
    val kind: String,
    val size: Long,
    val date: Long,
    val subject: String,
    val fromName: String,
    val fromAddr: String,
    val toAddr: String,
    val outgoing: Boolean,
) {
    val key: String get() = "$messageId.$part"

    /** Le repère à donner au serveur pour obtenir les pièces qui suivent celle-ci. */
    val cursor: String get() = "$date.$messageId.$part"
}

data class AttachmentPage(val items: List<Attachment>, val hasMore: Boolean, val pending: Int)

/** Les familles de fichiers de la galerie ; la première ne filtre rien. */
val ATTACHMENT_KINDS = listOf(
    "" to "Tout",
    "image" to "Images",
    "pdf" to "PDF",
    "doc" to "Documents",
    "media" to "Audio et vidéo",
    "other" to "Autres",
)

/** Une adresse du domaine : elle reçoit du courrier tant qu'elle n'est pas coupée. */
data class Alias(val address: String, val label: String, val enabled: Boolean, val received: Int)

data class AliasPage(val main: String, val domain: String, val aliases: List<Alias>)

/** Une règle de tri appliquée par le serveur à l'arrivée d'un message. */
data class Rule(val id: String, val field: String, val value: String, val action: String, val target: String? = null)

val RULE_FIELDS = listOf("from" to "l'expéditeur", "to" to "l'adresse de destination", "subject" to "l'objet")
val RULE_ACTIONS = listOf(
    "archive" to "archiver",
    "trash" to "mettre à la corbeille",
    "read" to "marquer lu",
    "block" to "refuser le message",
    "label" to "ranger dans un dossier",
    "file" to "ranger sans passer par la réception",
)

/** Un message commencé et gardé sur le serveur, à reprendre depuis l'appli ou le webmail. */
data class Draft(
    val id: String,
    val to: String,
    val cc: String,
    val subject: String,
    val text: String,
    val replyToId: String?,
    val updated: Long,
    val files: List<RemoteFile>,
)

/** Une pièce jointe d'un message envoyé, conservée sur le serveur. */
data class RemoteFile(val id: String, val name: String, val type: String, val size: Long)

data class Folder(val id: String, val label: String, val empty: String)

val FOLDERS = listOf(
    Folder("inbox", "Réception", "Aucun message pour l'instant. Les mails envoyés à ton adresse arriveront ici."),
    Folder("drafts", "Brouillons", "Aucun brouillon. Un message fermé avant l'envoi est gardé ici."),
    Folder("sent", "Envoyés", "Tu n'as encore rien envoyé depuis cette boîte."),
    Folder("archive", "Archives", "Aucun message archivé."),
    Folder("trash", "Corbeille", "La corbeille est vide."),
)

fun folderOf(id: String): Folder =
    FOLDERS.firstOrNull { it.id == id } ?: when {
        labelIdOf(id) != null -> Folder(id, "Dossier", "Aucun message rangé dans ce dossier.")
        boxOf(id) != null -> Folder(id, "Boîte", "Aucun message reçu par cette boîte.")
        else -> FOLDERS[0]
    }

/** Réglages et session, gardés dans le stockage privé de l'appli. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("courrier", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(value) = sp.edit().putString("server", value.trim().trimEnd('/')).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(value) = sp.edit().putString("token", value).apply()

    /** Expéditeur tel que le serveur l'affiche : « Nom <adresse> ». */
    var from: String
        get() = sp.getString("from", "") ?: ""
        set(value) = sp.edit().putString("from", value).apply()

    var address: String
        get() = sp.getString("address", "") ?: ""
        set(value) = sp.edit().putString("address", value).apply()

    var canSend: Boolean
        get() = sp.getBoolean("can_send", false)
        set(value) = sp.edit().putBoolean("can_send", value).apply()

    /** Texte ajouté au bas des messages qu'on écrit ; gardé sur le serveur, donc commun au webmail. */
    var signature: String
        get() = sp.getString("signature", "") ?: ""
        set(value) = sp.edit().putString("signature", value).apply()

    /** Vrai si le serveur sait envoyer les notifications instantanées. */
    var pushServer: Boolean
        get() = sp.getBoolean("push_server", false)
        set(value) = sp.edit().putBoolean("push_server", value).apply()

    /** Le jeton sous lequel ce téléphone est déclaré au serveur. */
    var pushToken: String?
        get() = sp.getString("push_token", null)
        set(value) = sp.edit().putString("push_token", value).apply()

    /** Les adresses depuis lesquelles on peut écrire, la principale en premier. */
    var addresses: List<String>
        get() = (sp.getString("addresses", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(value) = sp.edit().putString("addresses", value.joinToString("\n")).apply()

    /** Date du message le plus récent déjà vu : sert à ne notifier que les nouveautés. */
    var lastSeen: Long
        get() = sp.getLong("last_seen", 0L)
        set(value) = sp.edit().putLong("last_seen", value).apply()

    /** Les dossiers personnels connus à la dernière relève (identifiant, tabulation, nom), pour les revoir sans réseau. */
    var labelsCache: String
        get() = sp.getString("labels", "") ?: ""
        set(value) = sp.edit().putString("labels", value).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(value) = sp.edit().putBoolean("asked_notifications", value).apply()

    /** Le thème choisi : « auto » suit le téléphone, « light » et « dark » l'imposent. */
    var themeMode: String
        get() = sp.getString("theme", "auto") ?: "auto"
        set(value) = sp.edit().putString("theme", value).apply()

    /** Les dossiers personnels dont les sous-dossiers sont dépliés dans la liste ; aucun au départ. */
    var openFolders: Set<String>
        get() = sp.getStringSet("open_folders", null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet("open_folders", value.toSet()).apply()

    /** Vrai si l'appli demande l'empreinte (ou le code du téléphone) avant de montrer le courrier. */
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock", false)
        set(value) = sp.edit().putBoolean("lock", value).apply()

    fun clearSession() {
        sp.edit().remove("token").remove("from").remove("address").remove("can_send").remove("signature").remove("push_server").remove("push_token").remove("addresses").remove("last_seen").remove("labels").remove("open_folders").apply()
    }
}

/** Copie locale des listes de messages, pour la lecture hors connexion. */
class Db(context: Context) : SQLiteOpenHelper(context, "courrier.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                folder TEXT NOT NULL,
                direction TEXT NOT NULL,
                from_addr TEXT NOT NULL,
                from_name TEXT NOT NULL,
                to_addr TEXT NOT NULL,
                subject TEXT NOT NULL,
                date INTEGER NOT NULL,
                size INTEGER NOT NULL,
                is_read INTEGER NOT NULL,
                thread_id TEXT NOT NULL,
                thread_count INTEGER NOT NULL,
                labels TEXT NOT NULL DEFAULT '',
                box TEXT NOT NULL DEFAULT '',
                snippet TEXT NOT NULL DEFAULT '',
                files INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_messages_folder_date ON messages (folder, date DESC)")
        db.execSQL("CREATE INDEX idx_messages_thread ON messages (thread_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
    }

    private fun Cursor.toItem() = MailItem(
        id = getString(0),
        folder = getString(1),
        direction = getString(2),
        fromAddr = getString(3),
        fromName = getString(4),
        toAddr = getString(5),
        subject = getString(6),
        date = getLong(7),
        size = getLong(8),
        isRead = getInt(9) != 0,
        threadId = getString(10),
        threadCount = getInt(11),
        labels = getString(12).split(',').filter { it.isNotEmpty() },
        box = getString(13),
        snippet = getString(14),
        files = getInt(15),
    )

    private val columns = "id, folder, direction, from_addr, from_name, to_addr, subject, date, size, is_read, thread_id, thread_count, labels, box, snippet, files"

    /** Ce qui désigne une vue dans la base : un dossier de la boîte, un dossier personnel ou une boîte d'origine (hors corbeille). */
    private fun scope(view: String): Pair<String, List<String>> {
        boxOf(view)?.let { return "box = ? AND direction = 'in' AND folder != 'trash'" to listOf(it) }
        val label = labelIdOf(view) ?: return "folder = ?" to listOf(view)
        return "folder != 'trash' AND (',' || labels || ',') LIKE ?" to listOf("%,$label,%")
    }

    fun list(folder: String, query: String = ""): List<MailItem> {
        val (scoped, scopeArgs) = scope(folder)
        val args = scopeArgs.toMutableList()
        var where = scoped
        val q = query.trim()
        if (q.isNotEmpty()) {
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where += " AND (subject LIKE ? ESCAPE '\\' OR from_addr LIKE ? ESCAPE '\\' OR from_name LIKE ? ESCAPE '\\' OR to_addr LIKE ? ESCAPE '\\')"
            repeat(4) { args.add(like) }
        }
        val out = ArrayList<MailItem>()
        readableDatabase.rawQuery(
            "SELECT $columns FROM messages WHERE $where ORDER BY date DESC LIMIT 1000",
            args.toTypedArray()
        ).use { c -> while (c.moveToNext()) out.add(c.toItem()) }
        return out
    }

    fun get(id: String): MailItem? =
        readableDatabase.rawQuery("SELECT $columns FROM messages WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toItem() else null
        }

    fun unreadCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE folder = 'inbox' AND is_read = 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun values(item: MailItem) = ContentValues().apply {
        put("id", item.id)
        put("folder", item.folder)
        put("direction", item.direction)
        put("from_addr", item.fromAddr)
        put("from_name", item.fromName)
        put("to_addr", item.toAddr)
        put("subject", item.subject)
        put("date", item.date)
        put("size", item.size)
        put("is_read", if (item.isRead) 1 else 0)
        put("thread_id", item.threadId)
        put("thread_count", item.threadCount)
        put("labels", item.labels.joinToString(","))
        put("box", item.box)
        put("snippet", item.snippet)
        put("files", item.files)
    }

    /** Les messages d'une conversation connus de l'appareil, du plus ancien au plus récent. */
    fun thread(threadId: String): List<MailItem> {
        val out = ArrayList<MailItem>()
        readableDatabase.rawQuery(
            "SELECT $columns FROM messages WHERE thread_id = ? AND folder != 'trash' ORDER BY date ASC",
            arrayOf(threadId)
        ).use { c -> while (c.moveToNext()) out.add(c.toItem()) }
        return out
    }

    fun upsert(items: List<MailItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) db.insertWithOnConflict("messages", null, values(item), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Aligne la copie locale d'un dossier sur la première page reçue du serveur.
     * Les messages locaux de la même période que le serveur ne renvoie plus
     * (supprimés ou déplacés ailleurs) sont retirés ; renvoie leurs identifiants.
     */
    fun replaceFirstPage(folder: String, items: List<MailItem>, complete: Boolean): List<String> {
        val db = writableDatabase
        val removed = ArrayList<String>()
        db.beginTransaction()
        try {
            val keep = items.map { it.id }.toHashSet()
            val oldest = items.minOfOrNull { it.date } ?: Long.MAX_VALUE
            val (scoped, scopeArgs) = scope(folder)
            val where = if (complete) scoped else "$scoped AND date >= ?"
            val args = (if (complete) scopeArgs else scopeArgs + oldest.toString()).toTypedArray()
            val label = labelIdOf(folder)
            val gone = ArrayList<Pair<String, String>>()
            db.rawQuery("SELECT id, labels FROM messages WHERE $where", args).use { c ->
                while (c.moveToNext()) if (c.getString(0) !in keep) gone.add(c.getString(0) to c.getString(1))
            }
            for ((id, labels) in gone) {
                if (label == null) {
                    db.delete("messages", "id = ?", arrayOf(id))
                    removed.add(id)
                } else {
                    // Sorti d'un dossier personnel, le message existe toujours ailleurs : on ne lui retire que ce rangement.
                    val left = labels.split(',').filter { it.isNotEmpty() && it != label }.joinToString(",")
                    db.update("messages", ContentValues().apply { put("labels", left) }, "id = ?", arrayOf(id))
                }
            }
            for (item in items) db.insertWithOnConflict("messages", null, values(item), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return removed
    }

    fun setRead(id: String, read: Boolean) {
        writableDatabase.update("messages", ContentValues().apply { put("is_read", if (read) 1 else 0) }, "id = ?", arrayOf(id))
    }

    fun setFolder(id: String, folder: String) {
        writableDatabase.update("messages", ContentValues().apply { put("folder", folder) }, "id = ?", arrayOf(id))
    }

    fun setLabels(id: String, labels: List<String>) {
        writableDatabase.update("messages", ContentValues().apply { put("labels", labels.joinToString(",")) }, "id = ?", arrayOf(id))
    }

    fun delete(id: String) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id))
    }

    fun idsIn(folder: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT id FROM messages WHERE folder = ?", arrayOf(folder)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun clear() {
        writableDatabase.delete("messages", null, null)
    }
}
