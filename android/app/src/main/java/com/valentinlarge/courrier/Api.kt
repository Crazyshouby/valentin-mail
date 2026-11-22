package com.valentinlarge.courrier

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

    private fun base(): HttpUrl =
        prefs.server.toHttpUrlOrNull() ?: throw ApiException(0, "L'adresse du serveur n'est pas valide.")

    private fun request(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = base().newBuilder().apply {
            path.trim('/').split('/').forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val builder = Request.Builder().url(url).header("X-Courrier-App", "android")
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
        val list = me.optJSONArray("addresses") ?: JSONArray()
        prefs.addresses = (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
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
            Rule(o.getString("id"), o.optString("field"), o.optString("value"), o.optString("action"))
        }
    }

    suspend fun saveRule(id: String, field: String, value: String, action: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("field", field).put("value", value).put("action", action)
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
            params["folder"] = folder
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

    /** Une même action sur plusieurs messages : lu ou non lu, changement de dossier, suppression. */
    suspend fun batch(ids: List<String>, isRead: Boolean? = null, folder: String? = null, delete: Boolean = false) =
        withContext(Dispatchers.IO) {
            for (part in ids.distinct().chunked(100)) {
                val body = JSONObject().put("ids", JSONArray(part))
                if (isRead != null) body.put("is_read", isRead)
                if (folder != null) body.put("folder", folder)
                if (delete) body.put("delete", true)
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
