package com.valentinlarge.courrier

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Ce qu'une autre appli confie à Courrier pour en faire un message : texte, fichiers, ou une adresse touchée. */
data class Shared(
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val text: String = "",
    val files: List<Uri> = emptyList(),
    /** Nombre de fichiers proposés que l'appli a refusé de joindre. */
    val refused: Int = 0,
)

object Share {

    /** Vrai si cette demande vient d'un « Partager » ou d'un lien d'adresse mail. */
    fun handles(intent: Intent?): Boolean = when (intent?.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> true
        Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> intent?.data?.scheme.equals("mailto", ignoreCase = true)
        else -> false
    }

    /** Lit la demande d'une autre appli ; null si elle ne contient rien dont faire un message. */
    fun from(context: Context, intent: Intent?): Shared? {
        if (intent == null || !handles(intent)) return null
        return when (intent.action) {
            Intent.ACTION_SEND ->
                fromSend(context, intent, listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)))
            Intent.ACTION_SEND_MULTIPLE ->
                fromSend(context, intent, IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
            else -> {
                val link = mailto(intent.data) ?: return null
                // Certaines applis complètent le lien par les mêmes champs qu'un partage.
                link.copy(
                    subject = link.subject.ifBlank { intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty() },
                    text = link.text.ifBlank { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() },
                )
            }
        }
    }

    private fun fromSend(context: Context, intent: Intent, streams: List<Uri>): Shared {
        // Une appli ne peut faire joindre que ce qu'elle expose elle-même : jamais un chemin de fichier
        // brut, et jamais les fichiers que Courrier garde pour lui.
        val own = "${context.packageName}.fichiers"
        val files = streams.filter { it.scheme == "content" && it.authority != own }.distinct()
        return Shared(
            to = addresses(intent.getStringArrayExtra(Intent.EXTRA_EMAIL)),
            cc = addresses(intent.getStringArrayExtra(Intent.EXTRA_CC)),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim(),
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim(),
            files = files,
            refused = streams.size - files.size,
        )
    }

    private fun addresses(list: Array<String>?): String =
        list.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    /** Un lien « mailto: » : l'adresse, et les champs qu'il préremplit (objet, copie, texte). */
    fun mailto(uri: Uri?): Shared? {
        if (uri == null || !uri.scheme.equals("mailto", ignoreCase = true)) return null
        val raw = uri.toString().substringAfter(':')
        val to = ArrayList<String>()
        val cc = ArrayList<String>()
        var subject = ""
        var body = ""
        // Avant le point d'interrogation, les destinataires ; après, des champs « nom=valeur ».
        Uri.decode(raw.substringBefore('?')).trim().takeIf { it.isNotEmpty() }?.let { to.add(it) }
        for (pair in raw.substringAfter('?', "").split('&')) {
            if (pair.isBlank()) continue
            val value = Uri.decode(pair.substringAfter('=', "")).orEmpty()
            when (Uri.decode(pair.substringBefore('=')).orEmpty().trim().lowercase()) {
                "to" -> if (value.isNotBlank()) to.add(value.trim())
                "cc" -> if (value.isNotBlank()) cc.add(value.trim())
                "subject" -> subject = value.trim()
                "body" -> body = value
            }
        }
        return Shared(to = to.joinToString(", "), cc = cc.joinToString(", "), subject = subject, text = body)
    }
}
