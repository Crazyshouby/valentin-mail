package com.valentinlarge.courrier

import android.os.Build
import com.sun.mail.util.DecodingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.mail.MessagingException
import javax.mail.internet.MimeUtility

/** Le serveur a répondu par une erreur ; [message] est affichable tel quel. */
class ApiException(val status: Int, message: String) : Exception(message)

/** Le serveur n'a pas pu être joint (pas de réseau, délai dépassé…). */
class OfflineException : Exception("Serveur injoignable. Vérifie ta connexion internet.")

data class ListPage(val messages: List<MailItem>, val hasMore: Boolean, val unread: Int)

/** Dialogue avec le serveur de la boîte mail. */
class Api(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonType: MediaType = "application/json; charset=utf-8".toMediaType()

    /** Le modèle du téléphone, pour qu'on le reconnaisse dans la liste des appareils connectés. */
    private val device: String = run {
        val maker = (Build.MANUFACTURER ?: "").trim()
        val model = (Build.MODEL ?: "").trim()
        val full = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        full.filter { it.code in 32..126 }.trim().replaceFirstChar { it.uppercase() }.take(60)
    }

    private fun base(): HttpUrl =
        prefs.server.toHttpUrlOrNull() ?: throw ApiException(0, "L'adresse du serveur n'est pas valide.")

    private fun request(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = base().newBuilder().apply {
            path.trim('/').split('/').forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val builder = Request.Builder().url(url).header("X-Courrier-App", "android")
        if (device.isNotBlank()) builder.header("X-Courrier-Device", device)
        prefs.token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun execute(request: Request): Response =
        try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw OfflineException()
        }

    private fun failure(response: Response): ApiException {
        val body = try {
            response.body?.string().orEmpty()
        } catch (e: IOException) {
            ""
        }
        val message = try {
            JSONObject(body).optString("error")
        } catch (e: Exception) {
            ""
        }
        return ApiException(response.code, message.ifBlank { "Le serveur a répondu par une erreur (${response.code})." })
    }

    private fun json(request: Request): JSONObject =
        execute(request).use { response ->
            if (!response.isSuccessful) throw failure(response)
            val text = try {
                response.body?.string().orEmpty()
            } catch (e: IOException) {
                throw OfflineException()
            }
            try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException(response.code, "Réponse du serveur illisible.")
            }
        }

    private fun download(request: Request, target: File) {
        execute(request).use { response ->
            if (!response.isSuccessful) throw failure(response)
            val temp = File(target.parentFile, target.name + ".part")
            target.parentFile?.mkdirs()
            try {
                response.body!!.byteStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                temp.delete()
                throw OfflineException()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    /** Un texte du serveur ; une valeur absente ou nulle donne une chaîne vide. */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

    private fun JSONObject.toItem() = MailItem(
        id = getString("id"),
        folder = optString("folder", "inbox"),
        direction = optString("direction", "in"),
        fromAddr = optString("from_addr"),
        fromName = optString("from_name"),
        toAddr = optString("to_addr"),
        subject = optString("subject"),
        date = optLong("date"),
        size = optLong("size"),
        isRead = optInt("is_read") != 0,
        threadId = optString("thread_id").ifBlank { getString("id") },
        threadCount = maxOf(1, optInt("thread_count", 1)),
        labels = optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
        box = text("box"),
        snippet = text("snippet"),
        files = optInt("files"),
    )

    private fun JSONArray?.toFiles(): List<RemoteFile> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val o = getJSONObject(i)
            RemoteFile(o.getString("id"), o.optString("name", "piece-jointe"), o.optString("type", "application/octet-stream"), o.optLong("size"))
        }
    }

    suspend fun login(password: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("password", password).toString().toRequestBody(jsonType)
        val result = json(request("/api/login").post(body).build())
        val token = result.optString("token")
        if (token.isBlank()) throw ApiException(0, "Le serveur n'a pas fourni de jeton : il n'est sans doute pas à jour.")
        prefs.token = token
        loadAccount()
    }

    /** Recharge l'adresse d'expédition et la capacité d'envoi. */
    suspend fun me() = withContext(Dispatchers.IO) { loadAccount() }

    private fun loadAccount() {
        val me = json(request("/api/me").get().build())
        if (!me.optBoolean("authenticated")) throw ApiException(401, "Connexion requise.")
        prefs.from = me.optString("from")
        prefs.address = me.optString("address")
        prefs.canSend = me.optBoolean("canSend")
        prefs.pushServer = me.optBoolean("push")
        prefs.signature = me.optString("signature")
        val list = me.optJSONArray("addresses") ?: JSONArray()
        prefs.addresses = (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
    }

    /** Demande au serveur un signal d'essai ; renvoie vrai et un nombre de téléphones, ou faux et la raison. */
    suspend fun testPush(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val result = json(request("/api/push/test").post("{}".toRequestBody(jsonType)).build())
        if (result.optBoolean("ok")) true to result.optInt("sent").toString() else false to result.optString("error")
    }

    suspend fun saveSignature(text: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("signature", text).toString().toRequestBody(jsonType)
        prefs.signature = json(request("/api/settings").put(body).build()).optString("signature")
    }

    suspend fun registerDevice(token: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("token", token).toString().toRequestBody(jsonType)
        json(request("/api/devices").put(body).build())
        Unit
    }

    suspend fun forgetDevice(token: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("token", token).toString().toRequestBody(jsonType)
        json(request("/api/devices").delete(body).build())
        Unit
    }

    suspend fun aliases(): AliasPage = withContext(Dispatchers.IO) {
        val result = json(request("/api/aliases").get().build())
        val array = result.optJSONArray("aliases") ?: JSONArray()
        AliasPage(
            main = result.optString("main"),
            domain = result.optString("domain"),
            aliases = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Alias(o.getString("address"), o.optString("label"), o.optBoolean("enabled", true), o.optInt("received"))
            },
        )
    }

    suspend fun saveAlias(address: String, label: String? = null, enabled: Boolean? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (label != null) body.put("label", label)
        if (enabled != null) body.put("enabled", enabled)
        json(request("/api/aliases/$address").put(body.toString().toRequestBody(jsonType)).build())
        Unit
    }

    suspend fun deleteAlias(address: String) = withContext(Dispatchers.IO) {
        json(request("/api/aliases/$address").delete().build())
        Unit
    }

    suspend fun rules(): List<Rule> = withContext(Dispatchers.IO) {
        val array = json(request("/api/rules").get().build()).optJSONArray("rules") ?: JSONArray()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Rule(
                o.getString("id"),
                o.optString("field"),
                o.optString("value"),
                o.optString("action"),
                if (o.isNull("target")) null else o.optString("target").ifBlank { null },
            )
        }
    }

    suspend fun saveRule(id: String, field: String, value: String, action: String, target: String? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("field", field).put("value", value).put("action", action)
        if (target != null) body.put("target", target)
        json(request("/api/rules/$id").put(body.toString().toRequestBody(jsonType)).build())
        Unit
    }

    suspend fun deleteRule(id: String) = withContext(Dispatchers.IO) {
        json(request("/api/rules/$id").delete().build())
        Unit
    }

    suspend fun list(folder: String, query: String = "", before: Long = 0, after: Long = 0): ListPage =
        withContext(Dispatchers.IO) {
            val params = LinkedHashMap<String, String>()
            // Un dossier personnel se demande par son identifiant, une boîte d'origine par son adresse,
            // un dossier de la boîte par son nom.
            val label = labelIdOf(folder)
            val box = boxOf(folder)
            when {
                box != null -> params["box"] = box
                label != null -> params["label"] = label
                else -> params["folder"] = folder
            }
            if (query.isNotBlank()) params["q"] = query.trim()
            if (before > 0) params["before"] = before.toString()
            if (after > 0) params["after"] = after.toString()
            val result = json(request("/api/messages", params).get().build())
            val array = result.optJSONArray("messages") ?: JSONArray()
            ListPage(
                messages = (0 until array.length()).map { array.getJSONObject(it).toItem() },
                hasMore = result.optBoolean("hasMore"),
                unread = result.optInt("unread"),
            )
        }

    suspend fun detail(id: String): Pair<MailItem, List<RemoteFile>> = withContext(Dispatchers.IO) {
        val result = json(request("/api/messages/$id").get().build())
        result.getJSONObject("message").toItem() to result.optJSONArray("attachments").toFiles()
    }

    /** Tous les messages d'une conversation, du plus ancien au plus récent. */
    suspend fun thread(id: String): List<MailItem> = withContext(Dispatchers.IO) {
        val array = json(request("/api/threads/$id").get().build()).optJSONArray("messages") ?: JSONArray()
        val items = (0 until array.length()).map { array.getJSONObject(it).toItem() }
        items.map { it.copy(threadCount = maxOf(1, items.size)) }
    }

    /** Les dossiers personnels, avec leurs compteurs. */
    suspend fun labels(): List<Label> = withContext(Dispatchers.IO) {
        val array = json(request("/api/labels").get().build()).optJSONArray("labels") ?: JSONArray()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Label(o.getString("id"), o.optString("name"), o.optInt("total"), o.optInt("unread"))
        }
    }

    /** Crée le dossier s'il n'existe pas, le renomme sinon. */
    suspend fun saveLabel(id: String, name: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name)
        json(request("/api/labels/$id").put(body.toString().toRequestBody(jsonType)).build())
        Unit
    }

    suspend fun deleteLabel(id: String) = withContext(Dispatchers.IO) {
        json(request("/api/labels/$id").delete().build())
        Unit
    }

    /** Les boîtes qui ont reçu du courrier : adresses du domaine et comptes extérieurs. */
    suspend fun boxes(): List<MailBox> = withContext(Dispatchers.IO) {
        val array = json(request("/api/boxes").get().build()).optJSONArray("boxes") ?: JSONArray()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MailBox(o.getString("address"), o.optString("kind", "alias"), o.optInt("total"), o.optInt("unread"))
        }
    }

    /** Les appareils et navigateurs connectés à la boîte, celui-ci en premier. */
    suspend fun sessions(): List<DeviceSession> = withContext(Dispatchers.IO) {
        val array = json(request("/api/sessions").get().build()).optJSONArray("sessions") ?: JSONArray()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            DeviceSession(
                id = o.getString("id"),
                kind = o.optString("kind", "web"),
                name = o.text("name"),
                place = o.text("place"),
                created = o.optLong("created"),
                seen = o.optLong("seen"),
                current = o.optBoolean("current"),
            )
        }
    }

    /** Déconnecte un appareil à distance. */
    suspend fun closeSession(id: String) = withContext(Dispatchers.IO) {
        json(request("/api/sessions/$id").delete().build())
        Unit
    }

    /** Déconnecte tous les appareils sauf celui-ci ; renvoie leur nombre. */
    suspend fun closeOtherSessions(): Int = withContext(Dispatchers.IO) {
        json(request("/api/sessions/others").delete().build()).optInt("closed")
    }

    /** Ferme la session de cet appareil côté serveur : son jeton ne vaudra plus rien. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        json(request("/api/logout").post("{}".toRequestBody(jsonType)).build())
        Unit
    }

    /** Une page de l'inventaire des pièces jointes, des plus récentes aux plus anciennes. */
    suspend fun attachments(kind: String = "", query: String = "", after: String = ""): AttachmentPage =
        withContext(Dispatchers.IO) {
            val params = LinkedHashMap<String, String>()
            if (kind.isNotBlank()) params["kind"] = kind
            if (query.isNotBlank()) params["q"] = query.trim()
            if (after.isNotBlank()) params["after"] = after
            val result = json(request("/api/attachments", params).get().build())
            val array = result.optJSONArray("attachments") ?: JSONArray()
            AttachmentPage(
                items = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    Attachment(
                        messageId = o.getString("message_id"),
                        part = o.optInt("part"),
                        name = o.text("name").ifBlank { "piece-jointe" },
                        mime = o.text("mime").ifBlank { "application/octet-stream" },
                        kind = o.optString("kind", "other"),
                        size = o.optLong("size"),
                        date = o.optLong("date"),
                        subject = o.text("subject"),
                        fromName = o.text("from_name"),
                        fromAddr = o.text("from_addr"),
                        toAddr = o.text("to_addr"),
                        outgoing = o.optString("direction") == "out",
                    )
                },
                hasMore = result.optBoolean("hasMore"),
                pending = result.optInt("pending"),
            )
        }

    /** Fait avancer l'inventaire des pièces jointes ; renvoie le nombre de messages qu'il reste à examiner. */
    suspend fun scanAttachments(): Int = withContext(Dispatchers.IO) {
        json(request("/api/attachments/scan").post("{}".toRequestBody(jsonType)).build()).optInt("remaining")
    }

    /**
     * Télécharge une pièce jointe de l'inventaire. Le serveur l'envoie telle qu'elle est écrite dans
     * le message (le plus souvent en base64) et dit comment : c'est ici qu'elle est décodée.
     */
    suspend fun downloadAttachment(messageId: String, part: Int, target: File) = withContext(Dispatchers.IO) {
        execute(request("/api/messages/$messageId/attachments/$part").get().build()).use { response ->
            if (!response.isSuccessful) throw failure(response)
            val encoding = response.header("X-Part-Encoding").orEmpty().trim().lowercase()
            val temp = File(target.parentFile, target.name + ".part")
            target.parentFile?.mkdirs()
            try {
                val raw = response.body!!.byteStream()
                val input = if (encoding.isBlank() || encoding == "identity") raw else MimeUtility.decode(raw, encoding)
                input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
            } catch (e: DecodingException) {
                temp.delete()
                throw ApiException(0, "Cette pièce jointe est abîmée : ouvre plutôt le message qui la porte.")
            } catch (e: MessagingException) {
                temp.delete()
                throw ApiException(0, "Cette pièce jointe est écrite d'une façon que l'appli ne sait pas lire : ouvre plutôt le message qui la porte.")
            } catch (e: IOException) {
                temp.delete()
                throw OfflineException()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    /**
     * Le début d'une pièce jointe ([bytes] octets au plus), tel qu'il est écrit dans le message, et la
     * façon dont il est codé. Sert à lire la miniature qu'une photo porte dans ses premiers octets.
     */
    suspend fun attachmentHead(messageId: String, part: Int, bytes: Int): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val query = mapOf("head" to bytes.toString())
        execute(request("/api/messages/$messageId/attachments/$part", query).get().build()).use { response ->
            if (!response.isSuccessful) throw failure(response)
            val encoding = response.header("X-Part-Encoding").orEmpty().trim().lowercase()
            // On ne lit jamais plus que demandé, même si le serveur en envoyait davantage.
            val buffer = ByteArray(bytes)
            var total = 0
            try {
                response.body!!.byteStream().use { input ->
                    while (total < buffer.size) {
                        val n = input.read(buffer, total, buffer.size - total)
                        if (n < 0) break
                        total += n
                    }
                }
            } catch (e: IOException) {
                throw OfflineException()
            }
            (if (total == buffer.size) buffer else buffer.copyOf(total)) to encoding
        }
    }

    /** Une même action sur plusieurs messages : lu ou non lu, changement de dossier, rangement, suppression. */
    suspend fun batch(
        ids: List<String>,
        isRead: Boolean? = null,
        folder: String? = null,
        delete: Boolean = false,
        addLabels: List<String> = emptyList(),
        removeLabels: List<String> = emptyList(),
        leaveInbox: Boolean = false,
    ) =
        withContext(Dispatchers.IO) {
            for (part in ids.distinct().chunked(100)) {
                val body = JSONObject().put("ids", JSONArray(part))
                if (isRead != null) body.put("is_read", isRead)
                if (folder != null) body.put("folder", folder)
                if (delete) body.put("delete", true)
                if (addLabels.isNotEmpty()) body.put("add_labels", JSONArray(addLabels))
                if (removeLabels.isNotEmpty()) body.put("remove_labels", JSONArray(removeLabels))
                if (leaveInbox) body.put("leave_inbox", true)
                json(request("/api/messages/batch").post(body.toString().toRequestBody(jsonType)).build())
            }
        }

    suspend fun drafts(): List<Draft> = withContext(Dispatchers.IO) {
        val array = json(request("/api/drafts").get().build()).optJSONArray("drafts") ?: JSONArray()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Draft(
                id = o.getString("id"),
                to = o.optString("to"),
                cc = o.optString("cc"),
                subject = o.optString("subject"),
                text = o.optString("text"),
                replyToId = if (o.isNull("reply_to_id")) null else o.optString("reply_to_id").ifBlank { null },
                updated = o.optLong("updated"),
                files = o.optJSONArray("attachments").toFiles(),
            )
        }
    }

    suspend fun saveDraft(
        id: String,
        to: String,
        cc: String,
        subject: String,
        text: String,
        replyToId: String?,
        attachmentIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("to", to)
            .put("cc", cc)
            .put("subject", subject)
            .put("text", text)
            .put("attachment_ids", JSONArray(attachmentIds))
        if (replyToId != null) body.put("reply_to_id", replyToId)
        json(request("/api/drafts/$id").put(body.toString().toRequestBody(jsonType)).build())
        Unit
    }

    suspend fun deleteDraft(id: String) = withContext(Dispatchers.IO) {
        json(request("/api/drafts/$id").delete().build())
        Unit
    }

    suspend fun downloadRaw(id: String, target: File) = withContext(Dispatchers.IO) {
        download(request("/api/messages/$id/raw").get().build(), target)
    }

    suspend fun patch(id: String, isRead: Boolean? = null, folder: String? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (isRead != null) body.put("is_read", isRead)
        if (folder != null) body.put("folder", folder)
        json(request("/api/messages/$id").patch(body.toString().toRequestBody(jsonType)).build())
        Unit
    }

    /** Renvoie vrai si le message a été supprimé pour de bon, faux s'il est parti à la corbeille. */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        json(request("/api/messages/$id").delete().build()).optBoolean("deleted")
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        json(request("/api/trash/empty").post("{}".toRequestBody(jsonType)).build()).optInt("remaining")
    }

    suspend fun upload(id: String, name: String, type: String, length: Long, opener: () -> InputStream) =
        withContext(Dispatchers.IO) {
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = type.toMediaTypeOrNull()
                override fun contentLength(): Long = length
                override fun writeTo(sink: BufferedSink) {
                    opener().use { input -> sink.writeAll(input.source()) }
                }
            }
            json(request("/api/uploads/$id", mapOf("name" to name, "type" to type)).put(body).build())
            Unit
        }

    suspend fun deleteUpload(id: String) = withContext(Dispatchers.IO) {
        json(request("/api/uploads/$id").delete().build())
        Unit
    }

    suspend fun downloadUpload(id: String, target: File) = withContext(Dispatchers.IO) {
        download(request("/api/uploads/$id").get().build(), target)
    }

    /** Renvoie faux si le message est parti mais que sa copie n'a pas pu être rangée dans « Envoyés ». */
    suspend fun send(
        to: String,
        cc: String,
        subject: String,
        text: String,
        replyToId: String?,
        attachmentIds: List<String>,
        draftId: String? = null,
        from: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("to", to)
            .put("cc", cc)
            .put("subject", subject)
            .put("text", text)
        if (replyToId != null) body.put("reply_to_id", replyToId)
        if (attachmentIds.isNotEmpty()) body.put("attachment_ids", JSONArray(attachmentIds))
        if (draftId != null) body.put("draft_id", draftId)
        if (from != null) body.put("from", from)
        json(request("/api/send").post(body.toString().toRequestBody(jsonType)).build()).optBoolean("stored", true)
    }
}
