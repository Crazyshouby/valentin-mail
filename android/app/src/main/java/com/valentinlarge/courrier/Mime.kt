package com.valentinlarge.courrier

import android.util.Base64
import java.io.File
import java.nio.charset.Charset
import java.util.Properties
import javax.mail.Address
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimePart
import javax.mail.internet.MimeUtility

data class Person(val name: String, val address: String) {
    val label: String get() = if (name.isNotBlank() && name != address) "$name <$address>" else address
    val short: String get() = name.ifBlank { address }
}

/** Une pièce jointe extraite d'un message reçu, écrite dans le cache de l'appli. */
data class LocalFile(val name: String, val type: String, val file: File) {
    val size: Long get() = file.length()
}

data class ParsedMail(
    val subject: String,
    val from: List<Person>,
    val to: List<Person>,
    val cc: List<Person>,
    val replyTo: List<Person>,
    val date: Long?,
    val html: String?,
    val text: String?,
    val attachments: List<LocalFile>,
)

/** Décode un message brut (.eml) : en-têtes, corps texte ou HTML, images intégrées, pièces jointes. */
object Mime {

    init {
        // Les mails réels sont souvent approximatifs : on lit ce qu'on peut plutôt que d'échouer.
        System.setProperty("mail.mime.address.strict", "false")
        System.setProperty("mail.mime.decodetext.strict", "false")
        System.setProperty("mail.mime.base64.ignoreerrors", "true")
        System.setProperty("mail.mime.uudecode.ignoreerrors", "true")
        System.setProperty("mail.mime.ignoreunknownencoding", "true")
        System.setProperty("mail.mime.multipart.allowempty", "true")
    }

    private class Embedded(val cid: String, val type: String, val file: File, val name: String)

    private class Collector(val dir: File) {
        var html: String? = null
        var text: String? = null
        val files = ArrayList<LocalFile>()
        val embedded = ArrayList<Embedded>()
        private var counter = 0

        fun save(part: Part, name: String): File {
            dir.mkdirs()
            val file = File(dir, "${counter++}-${safeName(name)}")
            part.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        }
    }

    fun parse(raw: File, attachmentDir: File): ParsedMail {
        attachmentDir.deleteRecursively()
        val session = Session.getInstance(Properties())
        val message = raw.inputStream().buffered().use { MimeMessage(session, it) }
        val out = Collector(attachmentDir)
        try {
            walk(message, out, 0)
        } catch (e: Exception) {
            // Structure illisible : on montre au moins le texte brut du corps.
            if (out.html == null && out.text == null) out.text = runCatching { readText(message) }.getOrNull()
        }

        // Les images réellement utilisées dans le HTML y sont intégrées ; les autres deviennent des pièces jointes.
        var html = out.html
        for (image in out.embedded) {
            val used = html != null && html.contains("cid:${image.cid}", ignoreCase = true)
            if (used && image.file.length() <= 3_000_000) {
                val data = "data:${image.type};base64," + Base64.encodeToString(image.file.readBytes(), Base64.NO_WRAP)
                html = html!!.replace("cid:${image.cid}", data, ignoreCase = true)
                image.file.delete()
            } else {
                out.files.add(LocalFile(image.name, image.type, image.file))
            }
        }

        return ParsedMail(
            subject = runCatching { message.subject }.getOrNull().orEmpty(),
            from = people(runCatching { message.from }.getOrNull(), message.getHeader("From", ", ")),
            to = people(runCatching { message.getRecipients(Message.RecipientType.TO) }.getOrNull(), message.getHeader("To", ", ")),
            cc = people(runCatching { message.getRecipients(Message.RecipientType.CC) }.getOrNull(), message.getHeader("Cc", ", ")),
            replyTo = if (message.getHeader("Reply-To") == null) emptyList() else people(runCatching { message.replyTo }.getOrNull(), null),
            date = runCatching { message.sentDate?.time }.getOrNull(),
            html = html,
            text = out.text,
            attachments = out.files,
        )
    }

    private fun walk(part: Part, out: Collector, depth: Int) {
        if (depth > 12) return
        val type = baseType(part)

        if (type.startsWith("multipart/")) {
            val multipart = MimeMultipart(part.dataHandler.dataSource)
            for (i in 0 until multipart.count) {
                try {
                    walk(multipart.getBodyPart(i), out, depth + 1)
                } catch (e: Exception) {
                    // Une partie abîmée ne doit pas faire perdre les autres.
                }
            }
            return
        }

        val disposition = runCatching { part.disposition }.getOrNull()
        val name = fileName(part)
        val attached = Part.ATTACHMENT.equals(disposition, ignoreCase = true)
        val cid = runCatching { (part as? MimePart)?.contentID }.getOrNull()?.trim()?.removePrefix("<")?.removeSuffix(">")

        when {
            !attached && name == null && type == "text/html" && out.html == null -> out.html = readText(part)
            !attached && name == null && type == "text/plain" && out.text == null -> out.text = readText(part)
            !attached && !cid.isNullOrBlank() && type.startsWith("image/") -> {
                val label = name ?: "image.${type.substringAfter('/')}"
                out.embedded.add(Embedded(cid, type, out.save(part, label), label))
            }
            else -> {
                val label = name ?: when {
                    type == "message/rfc822" -> "message.eml"
                    type == "text/plain" -> "texte.txt"
                    type == "text/html" -> "page.html"
                    else -> "piece-jointe"
                }
                out.files.add(LocalFile(label, type, out.save(part, label)))
            }
        }
    }

    private fun baseType(part: Part): String =
        try {
            ContentType(part.contentType).baseType.lowercase()
        } catch (e: Exception) {
            part.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty().ifBlank { "text/plain" }
        }

    private fun readText(part: Part): String {
        val charsetName = try {
            ContentType(part.contentType).getParameter("charset")
        } catch (e: Exception) {
            null
        }
        val bytes = part.inputStream.use { it.readBytes() }
        val charset = try {
            if (charsetName.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(MimeUtility.javaCharset(charsetName))
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    private fun fileName(part: Part): String? {
        val raw = runCatching { part.fileName }.getOrNull() ?: return null
        val decoded = runCatching { MimeUtility.decodeText(raw) }.getOrDefault(raw)
        return decoded.trim().ifBlank { null }
    }

    private fun people(addresses: Array<out Address>?, rawHeader: String?): List<Person> {
        val parsed = addresses.orEmpty().mapNotNull { address ->
            when (address) {
                is InternetAddress -> {
                    val name = address.personal.orEmpty()
                    val mail = address.address.orEmpty()
                    if (name.isBlank() && mail.isBlank()) null else Person(name, mail)
                }
                else -> Person("", address.toString())
            }
        }
        if (parsed.isNotEmpty() || rawHeader.isNullOrBlank()) return parsed
        // En-tête trop abîmé pour être analysé : on l'affiche tel quel.
        return listOf(Person("", runCatching { MimeUtility.decodeText(rawHeader) }.getOrDefault(rawHeader)))
    }

    fun safeName(name: String): String =
        name.replace(Regex("[\\u0000-\\u001f\\u007f\\u202a-\\u202e\\u2066-\\u2069]"), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .takeLast(120)
            .ifBlank { "piece-jointe" }

    /** Texte brut d'un corps HTML, pour citer un message dans une réponse. */
    fun htmlToText(html: String): String {
        var text = html
            .replace(Regex("(?is)<(script|style|head|title)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|table|ul|ol)>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), "")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&rsquo;", "’")
            .replace("&eacute;", "é")
            .replace("&egrave;", "è")
            .replace("&agrave;", "à")
        return text
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
