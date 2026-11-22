package com.valentinlarge.courrier

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Parcours complet de l'appli contre un vrai serveur lancé sur la machine de test :
 * connexion, lecture, réponse avec pièce jointe, notification, lecture hors connexion, rangement.
 */
@RunWith(AndroidJUnit4::class)
class E2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val vm get() = rule.activity.viewModel

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1500) // le temps que le contenu du message soit dessiné
        val screen = instrumentation.uiAutomation.takeScreenshot() ?: return
        val bitmap = screen.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(text: String, timeout: Long = 30_000) {
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGone(text: String, timeout: Long = 30_000) {
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun menu(entry: String) {
        rule.onNodeWithContentDescription("Autres actions").performClick()
        waitText(entry)
        rule.onNodeWithText(entry).performClick()
    }

    /** Dépose un mail dans la boîte d'essai, comme s'il venait d'arriver. */
    private fun deliver(
        from: String,
        subject: String,
        vararg headers: String,
        to: String = "valentin@valentinlarge.com",
        rcpt: String = "valentin@valentinlarge.com",
    ) {
        // Un objet accentué voyage encodé, comme dans un vrai mail.
        val title = if (subject.all { it.code < 128 }) subject
        else "=?UTF-8?B?" + Base64.encodeToString(subject.toByteArray(), Base64.NO_WRAP) + "?="
        val raw = (
            listOf("From: $from", "To: $to", "Subject: $title", "Message-ID: <essai-${System.nanoTime()}@example.net>") +
                headers +
                listOf("MIME-Version: 1.0", "Content-Type: text/plain; charset=utf-8", "", "Bonjour Valentin, voici la suite.", "")
            ).joinToString("\r\n")
        // Le serveur d'essai n'accepte un dépôt de mail que s'il se croit appelé en local.
        val request = Request.Builder()
            .url("${vm.prefs.server}/cdn-cgi/local/email?from=essai@example.net&to=$rcpt")
            .header("Host", "127.0.0.1:8787")
            .post(raw.toByteArray().toRequestBody())
            .build()
        OkHttpClient().newCall(request).execute().use { assertEquals(200, it.code) }
    }

    /** Laisse passer le temps d'affichage du message de confirmation, pour que le suivant soit bien un nouveau. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(12_000)
        rule.waitForIdle()
    }

    /** Ouvre la boîte depuis un second appareil ; renvoie le jeton qu'il reçoit. */
    private fun loginElsewhere(password: String, device: String): String {
        val body = JSONObject().put("password", password).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${vm.prefs.server}/api/login")
            .header("X-Courrier-App", "android")
            .header("X-Courrier-Device", device)
            .post(body)
            .build()
        return OkHttpClient().newCall(request).execute().use {
            assertEquals(200, it.code)
            JSONObject(it.body!!.string()).getString("token")
        }
    }

    /** Vrai si le serveur accepte encore ce jeton. */
    private fun accepted(token: String): Boolean {
        val request = Request.Builder()
            .url("${vm.prefs.server}/api/messages")
            .header("X-Courrier-App", "android")
            .header("Authorization", "Bearer $token")
            .build()
        return OkHttpClient().newCall(request).execute().use { it.code == 200 }
    }

    /** Un fichier offert par une autre source que l'appli (ici, les téléchargements du téléphone), comme lors d'un vrai partage. */
    private fun mediaFile(name: String, content: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Le téléphone n'a pas accepté le fichier d'essai.")
        resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
        return uri
    }

    private fun closeComposer() {
        rule.onNodeWithContentDescription("Fermer la rédaction").performClick()
    }

    private fun backToList() {
        rule.onNodeWithContentDescription("Retour à la liste").performClick()
        waitText("Réception")
    }

    @Test
    fun parcoursComplet() {
        val password = InstrumentationRegistry.getArguments().getString("motdepasse")
            ?: error("Le mot de passe de test n'a pas été fourni.")
        try {
            run(password)
        } catch (e: Throwable) {
            runCatching { shot("99-echec") }
            throw e
        } finally {
            Lock.stand = null
            Lock.graceMs = 60_000
        }
    }

    private fun run(password: String) {
        // ---- connexion : un mauvais mot de passe est refusé, le bon ouvre la boîte
        waitText("Ouvrir la boîte")
        shot("01-connexion")
        rule.onNodeWithTag("password").performTextInput("ce-n-est-pas-le-bon")
        rule.onNodeWithText("Ouvrir la boîte").performClick()
        waitText("Mot de passe incorrect")
        // Le même mot de passe ne peut pas être renvoyé tel quel : cela userait les essais pour rien.
        rule.onNodeWithText("Ouvrir la boîte").assertIsNotEnabled()
        rule.onNodeWithTag("password").performTextReplacement(password)
        rule.onNodeWithText("Ouvrir la boîte").performClick()

        // ---- liste de réception
        waitText("Votre commande est partie")
        waitText("Devis pour")
        waitText("valentin@valentinlarge.com")
        shot("02-reception")

        // ---- message HTML : pièce jointe, images distantes masquées, rien d'exécuté
        rule.onNodeWithText("Votre commande est partie").performClick()
        waitText("facture.txt")
        val commande = vm.opened
        assertNotNull(commande)
        assertEquals("commandes@atelier-papier.example", commande!!.mail.replyTo.first().address)
        assertTrue("l'image intégrée doit être dans la page", commande.mail.html!!.contains("data:image/"))
        rule.onNodeWithText("Afficher").assertExists()
        shot("03-message-html")
        rule.onNodeWithText("facture.txt", substring = true).performClick()
        waitText("Enregistrer sur l'appareil")
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        waitGone("Enregistrer sur l'appareil")
        rule.onNodeWithText("Afficher les détails").performClick()
        waitText("Répondre à")
        shot("04-details")
        backToList()

        // ---- message texte, puis réponse avec une pièce jointe
        rule.onNodeWithText("Devis pour", substring = true).performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        assertTrue(vm.opened!!.mail.text!!.contains("Bonjour Valentin"))
        shot("05-message-texte")
        rule.onNodeWithText("Répondre").performClick()
        waitText("Réponse")
        rule.onNodeWithTag("destinataire").assertTextContains("eloise@example.org")
        rule.onNodeWithTag("objet").assertTextContains("Re: Devis pour", substring = true)
        rule.onNodeWithTag("message").performTextInput("Bonjour Éloïse, c'est parfait pour moi. Je te renvoie le fichier.")

        val piece = File(context.cacheDir, "essai-piece.txt")
        piece.writeText("Contenu de la pièce jointe d'essai.\n".repeat(40))
        rule.runOnUiThread { vm.addFiles(listOf(Uri.fromFile(piece))) }
        waitText("essai-piece.txt")
        shell("input keyevent 111") // referme le clavier
        shot("06-reponse")

        rule.onNodeWithTag("envoyer").performClick()
        waitText("Message envoyé", 60_000)
        assertTrue("retour au message après l'envoi", vm.screen is Screen.Reader)
        settle()
        backToList()

        // ---- la copie est rangée dans Envoyés, avec sa pièce jointe
        rule.onNodeWithText("Envoyés").performClick()
        waitText("Re: Devis pour")
        rule.onNodeWithText("Re: Devis pour", substring = true).performClick()
        waitText("essai-piece.txt")
        assertEquals(piece.length(), vm.opened!!.sentFiles.single().size)
        shot("07-envoye")
        backToList()
        rule.onNodeWithText("Réception").performClick()
        waitText("Votre commande est partie")
        // La relève de la liste doit être finie, sinon elle verrait le nouveau mail avant la relève de fond.
        rule.waitUntil(30_000) { !vm.loading }

        // ---- un nouveau mail arrive : la relève en arrière-plan le signale
        val stamp = System.currentTimeMillis()
        val raw = listOf(
            "From: Camille Martin <camille@example.net>",
            "To: valentin@valentinlarge.com",
            "Subject: Rendez-vous jeudi",
            "Message-ID: <essai-$stamp@example.net>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=utf-8",
            "",
            "On se voit jeudi à 14 h ? Détails ici : https://example.net/agenda.",
            "",
        ).joinToString("\r\n")
        // Le serveur d'essai n'accepte un dépôt de mail que s'il se croit appelé en local.
        val inject = Request.Builder()
            .url("${vm.prefs.server}/cdn-cgi/local/email?from=camille@example.net&to=valentin@valentinlarge.com")
            .header("Host", "127.0.0.1:8787")
            .post(raw.toByteArray().toRequestBody())
            .build()
        OkHttpClient().newCall(inject).execute().use { assertEquals(200, it.code) }

        val fresh = runBlocking { Sync.check(context) }
        assertTrue("au plus un nouveau message à signaler", fresh <= 1)
        val manager = context.getSystemService(NotificationManager::class.java)
        rule.waitUntil(15_000) {
            manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Camille Martin" }
        }
        runCatching {
            shell("cmd statusbar expand-notifications")
            Thread.sleep(2500)
            shot("08-notification")
            shell("cmd statusbar collapse")
            Thread.sleep(1500)
        }

        menu("Actualiser")
        waitText("Rendez-vous jeudi")
        shot("09-nouveau-message")

        // ---- hors connexion : la liste et les messages déjà reçus restent lisibles
        val cached = vm.messages.first { it.subject.startsWith("Votre commande") }.id
        rule.waitUntil(30_000) { vm.repo.isCached(cached) }
        val server = vm.prefs.server
        vm.prefs.server = "http://10.0.2.2:9"
        try {
            menu("Actualiser")
            waitText("Hors connexion")
            shot("10-hors-connexion")
            rule.onNodeWithText("Votre commande est partie").performClick()
            waitText("facture.txt")
            backToList()
        } finally {
            vm.prefs.server = server
        }
        menu("Actualiser")
        waitGone("Hors connexion")

        // ---- conversation : la réponse reçue rejoint le message d'origine et notre réponse
        deliver(
            "Eloise Dupre <eloise@example.org>",
            "Re: Devis pour l'affiche",
            "In-Reply-To: <devis-001@example.org>",
            "References: <devis-001@example.org>",
        )
        menu("Actualiser")
        rule.waitUntil(30_000) { vm.rows.any { it.count == 3 } }
        shot("12-liste-conversation")
        rule.onNodeWithText("Re: Devis pour l'affiche").performClick()
        waitText("Message 3 sur 3")
        rule.waitUntil(30_000) { vm.opened != null }
        shot("13-conversation")
        rule.onNodeWithContentDescription("Message précédent de la conversation").performClick()
        waitText("Message 2 sur 3")
        rule.waitUntil(30_000) { vm.opened != null }
        assertTrue("le message du milieu est notre réponse", vm.opened!!.item.outgoing)
        backToList()

        // ---- répondre à tous : les autres destinataires passent en copie, pas soi-même
        deliver(
            "Paul Morel <paul@example.net>",
            "Réunion atelier",
            "Cc: Marc <marc@example.com>",
            to = "valentin@valentinlarge.com, Lea <lea@example.org>",
        )
        menu("Actualiser")
        waitText("Réunion atelier")
        rule.onNodeWithText("Réunion atelier").performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        menu("Répondre à tous")
        waitText("Réponse")
        rule.onNodeWithTag("destinataire").assertTextContains("paul@example.net")
        assertEquals("lea@example.org, marc@example.com", vm.draftCc)
        closeComposer()
        rule.waitUntil(15_000) { vm.screen is Screen.Reader }
        assertTrue("une réponse refermée sans rien écrire ne laisse pas de brouillon", vm.draftText.isEmpty())
        backToList()

        // ---- transférer : la pièce jointe d'origine suit
        rule.onNodeWithText("Votre commande est partie").performClick()
        waitText("facture.txt")
        menu("Transférer")
        waitText("Nouveau message")
        rule.onNodeWithTag("objet").assertTextContains("Fwd: Votre commande est partie")
        waitText("facture.txt")
        rule.onNodeWithTag("destinataire").performTextInput("ami@example.com")
        shell("input keyevent 111")
        shot("14-transfert")
        rule.onNodeWithTag("envoyer").performClick()
        rule.waitUntil(60_000) { vm.screen is Screen.Reader && !vm.sending }
        waitText("Message envoyé")
        settle()
        backToList()

        // ---- brouillon : un message refermé est gardé, puis repris et envoyé
        rule.onNodeWithContentDescription("Écrire un message").performClick()
        waitText("Nouveau message")
        rule.onNodeWithTag("destinataire").performTextInput("plus-tard@example.com")
        rule.onNodeWithTag("objet").performTextInput("Idée à creuser")
        rule.onNodeWithTag("message").performTextInput("À finir demain.")
        closeComposer()
        waitText("Brouillon enregistré")
        settle()
        rule.onNodeWithText("Brouillons").performClick()
        waitText("Idée à creuser")
        shell("input keyevent 111")
        shot("15-brouillons")
        rule.onNodeWithText("Idée à creuser").performClick()
        waitText("Nouveau message")
        rule.onNodeWithTag("destinataire").assertTextContains("plus-tard@example.com")
        rule.onNodeWithTag("message").assertTextContains("À finir demain.")
        rule.onNodeWithTag("envoyer").performClick()
        rule.waitUntil(60_000) { vm.screen == Screen.Mailbox && !vm.sending }
        waitText("Message envoyé")
        settle()
        waitText("Aucun brouillon")
        rule.onNodeWithText("Réception").performClick()
        waitText("Réunion atelier")

        // ---- glisser vers la gauche : corbeille, avec de quoi annuler
        rule.onNodeWithText("Réunion atelier").performTouchInput { swipeLeft() }
        waitText("Message mis à la corbeille")
        rule.waitUntil(15_000) { vm.messages.none { it.subject == "Réunion atelier" } }
        rule.onNodeWithText("Annuler").performClick()
        rule.waitUntil(30_000) { vm.messages.any { it.subject == "Réunion atelier" } }
        waitText("Réunion atelier")

        // ---- sélection multiple : deux conversations archivées d'un coup
        rule.onNodeWithText("Rendez-vous jeudi").performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        shot("11-lien-dans-texte")
        backToList()
        rule.onNodeWithText("Réunion atelier").performTouchInput { longClick() }
        waitText("1 sélectionné")
        rule.onNodeWithText("Rendez-vous jeudi").performClick()
        waitText("2 sélectionnés")
        shot("16-selection")
        rule.onNodeWithText("Archiver").performClick()
        waitText("2 messages archivés")

        // ---- rangement : archive, corbeille, suppression définitive
        rule.onNodeWithText("Archives").performClick()
        waitText("Rendez-vous jeudi")
        waitText("Réunion atelier")
        rule.onNodeWithText("Rendez-vous jeudi").performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        menu("Mettre à la corbeille")
        waitText("Message mis à la corbeille")
        rule.onNodeWithText("Corbeille").performClick()
        waitText("Rendez-vous jeudi")
        rule.onNodeWithText("Rendez-vous jeudi").performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        menu("Supprimer définitivement")
        waitText("Il sera effacé définitivement")
        // Le menu se referme : il ne doit rester que le bouton de la confirmation.
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Supprimer définitivement").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText("Supprimer définitivement").performClick()
        waitText("La corbeille est vide")

        // ---- adresses et règles : une nouvelle adresse, une règle qui archive à l'arrivée
        // Le message de confirmation précédent s'affiche en bas de l'écran, là où se trouvent les boutons à venir.
        settle()
        rule.onNodeWithText("Réception").performClick()
        waitText("Votre commande est partie")

        // ---- recherche : un mot qui ne figure que dans le texte d'un message
        rule.onNodeWithContentDescription("Chercher dans ce dossier").performClick()
        rule.onNodeWithTag("recherche").performTextInput("festival")
        rule.waitUntil(30_000) {
            vm.messages.isNotEmpty() && vm.messages.any { it.subject.startsWith("Devis pour") } &&
                vm.messages.none { it.subject.startsWith("Votre commande") }
        }
        shell("input keyevent 111")
        shot("16b-recherche-dans-le-texte")
        rule.onNodeWithContentDescription("Chercher dans ce dossier").performClick()
        rule.waitUntil(30_000) { vm.query.isEmpty() && vm.messages.any { it.subject.startsWith("Votre commande") } }

        // ---- dossiers personnels : en créer deux, dont un sous-dossier
        rule.onNodeWithTag("bouton-dossiers").performClick()
        waitText("Nouveau dossier")
        waitText("Aucun dossier pour l'instant")
        rule.onNodeWithTag("nouveau-dossier").performClick()
        waitText("Nom du dossier")
        rule.onNodeWithTag("nom-dossier").performTextInput("Clients")
        rule.onNodeWithText("Créer").performClick()
        rule.waitUntil(30_000) { vm.labels.any { it.name == "Clients" } && !vm.labelBusy }
        rule.onNodeWithTag("nouveau-dossier").performClick()
        waitText("Nom du dossier")
        rule.onNodeWithTag("nom-dossier").performTextInput("Clients/Affiches")
        rule.onNodeWithText("Créer").performClick()
        rule.waitUntil(30_000) { vm.labels.size == 2 && !vm.labelBusy }
        waitText("Affiches")
        shot("16c-dossiers")
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        waitGone("Nouveau dossier")

        // ---- ranger un message depuis la lecture : il quitte la réception et gagne son dossier
        rule.onNodeWithText("Votre commande est partie").performClick()
        waitText("facture.txt")
        menu("Ranger dans…")
        rule.onNodeWithTag("ranger-Clients").performClick()
        rule.waitUntil(30_000) { vm.opened?.item?.let { it.labels.size == 1 && it.folder == "archive" } == true }
        waitText("Rangé dans « Clients »")
        shot("16d-message-range")
        settle()
        backToList()
        rule.waitUntil(30_000) { vm.messages.none { it.subject.startsWith("Votre commande") } }

        // ---- ranger une conversation depuis la liste, puis annuler
        rule.onNodeWithText("Re: Devis pour l'affiche").performTouchInput { longClick() }
        waitText("1 sélectionné")
        menu("Ranger dans…")
        rule.onNodeWithTag("ranger-Clients/Affiches").performClick()
        waitText("rangés dans « Clients/Affiches »")
        rule.waitUntil(30_000) { vm.messages.none { it.subject.contains("Devis") } }
        rule.onNodeWithText("Annuler").performClick()
        rule.waitUntil(30_000) { vm.messages.any { it.subject == "Re: Devis pour l'affiche" && it.labels.isEmpty() } }
        waitText("Re: Devis pour l'affiche")
        settle()

        // ---- ouvrir le dossier : le message rangé s'y trouve, un bandeau dit où l'on est
        rule.onNodeWithTag("bouton-dossiers").performClick()
        waitText("Nouveau dossier")
        rule.onNodeWithTag("dossier-Clients").performClick()
        rule.waitUntil(30_000) { labelIdOf(vm.folder) != null && vm.messages.any { it.subject.startsWith("Votre commande") } }
        rule.onNodeWithTag("bandeau-dossier").assertTextContains("Clients")
        shot("16e-dans-un-dossier")
        rule.onNodeWithContentDescription("Fermer le dossier et revenir à la réception").performClick()
        rule.waitUntil(30_000) { vm.folder == "inbox" && vm.messages.any { it.subject == "Re: Devis pour l'affiche" } }
        waitText("Réception")

        // ---- un lien d'adresse touché ailleurs : Courrier ouvre un message prérempli
        val link = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:lea@example.org?cc=marc@example.com&subject=Devis%20%C3%A9t%C3%A9&body=Bonjour%20L%C3%A9a%2C"))
            .setPackage(context.packageName)
        rule.activity.startActivity(link)
        rule.waitUntil(30_000) { vm.screen == Screen.Compose && vm.draftTo == "lea@example.org" }
        waitText("Nouveau message")
        assertEquals("marc@example.com", vm.draftCc)
        assertEquals("Devis été", vm.draftSubject)
        assertTrue("le texte du lien précède la signature", vm.draftText.startsWith("Bonjour Léa,"))
        shell("input keyevent 111")
        shot("16f-lien-adresse")
        rule.runOnUiThread { vm.discardDraft() }
        rule.waitUntil(15_000) { vm.screen == Screen.Mailbox }

        // ---- « Partager » depuis une autre appli : le texte et le fichier arrivent dans un nouveau message
        val sneaky = Share.from(
            context,
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(File(context.filesDir, "captures"))),
        )
        assertTrue("un chemin de fichier brut n'est jamais joint", sneaky != null && sneaky.files.isEmpty() && sneaky.refused == 1)
        val offered = runCatching { mediaFile("partage-essai.txt", "Fichier partagé depuis une autre appli.\n") }.getOrNull()
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setPackage(context.packageName)
            .putExtra(Intent.EXTRA_SUBJECT, "Article à lire")
            .putExtra(Intent.EXTRA_TEXT, "https://example.net/article")
        if (offered != null) share.putExtra(Intent.EXTRA_STREAM, offered).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        rule.activity.startActivity(share)
        rule.waitUntil(30_000) { vm.screen == Screen.Compose && vm.draftSubject == "Article à lire" }
        assertTrue("le texte partagé est dans le message", vm.draftText.startsWith("https://example.net/article"))
        if (offered != null) {
            rule.waitUntil(30_000) { vm.draftFiles.size == 1 }
            waitText("partage-essai")
        }
        rule.onNodeWithTag("destinataire").performTextInput("ami@example.com")
        shell("input keyevent 111")
        shot("16g-partage")
        rule.onNodeWithTag("envoyer").performClick()
        rule.waitUntil(60_000) { vm.screen == Screen.Mailbox && !vm.sending }
        waitText("Message envoyé")
        settle()

        // ---- un second appareil ouvre la boîte : il apparaîtra dans les réglages
        val elsewhere = loginElsewhere(password, "Tablette d'essai")
        assertTrue("le second appareil est connecté", accepted(elsewhere))

        menu("Réglages")
        waitText("Tes adresses")
        waitText("adresse principale")
        rule.onNodeWithTag("alias-nom").performTextInput("contact")
        rule.onNodeWithText("Ajouter").performClick()
        waitText("contact@valentinlarge.com")
        // Les boutons restent éteints tant que le serveur n'a pas fini de répondre.
        rule.waitUntil(30_000) { !vm.settingsBusy }
        rule.onNodeWithTag("regle-texte").performScrollTo().performTextInput("infolettre")
        rule.onNodeWithText("Ajouter la règle").performScrollTo().performClick()
        waitText("contient « infolettre »")
        rule.waitUntil(30_000) { !vm.settingsBusy }
        // Une règle qui range dans un dossier en laissant le message en réception.
        rule.onNodeWithTag("regle-action").performScrollTo().performClick()
        waitText("ranger dans un dossier")
        rule.onNodeWithText("ranger dans un dossier").performClick()
        rule.onNodeWithTag("regle-dossier").performScrollTo().performClick()
        waitText("Clients/Affiches")
        rule.onNodeWithText("Clients").performClick()
        rule.onNodeWithTag("regle-texte").performScrollTo().performTextInput("client@example.com")
        rule.onNodeWithText("Ajouter la règle").performScrollTo().performClick()
        waitText("ranger dans « Clients »")
        rule.waitUntil(30_000) { !vm.settingsBusy }
        shot("17-adresses-et-regles")
        rule.onNodeWithTag("signature").performScrollTo().performTextInput("Valentin, essai")
        rule.onNodeWithText("Enregistrer la signature").performScrollTo().performClick()
        rule.waitUntil(30_000) { vm.signature == "Valentin, essai" && !vm.settingsBusy }
        settle()

        // ---- appareils connectés : ce téléphone et la tablette ; on déconnecte la tablette d'ici
        rule.waitUntil(30_000) { vm.sessions.size == 2 && vm.sessions[0].current }
        rule.onNodeWithTag("deconnecter-Tablette d'essai").performScrollTo()
        waitText("cet appareil")
        shot("17b-appareils")
        rule.onNodeWithTag("deconnecter-Tablette d'essai").performClick()
        rule.waitUntil(30_000) { vm.sessions.size == 1 && !vm.settingsBusy }
        assertTrue("la tablette déconnectée n'entre plus", !accepted(elsewhere))
        settle()
        rule.onNodeWithTag("deconnecter-les-autres").performScrollTo().performClick()
        waitText("Seul ce téléphone restera connecté")
        rule.onNodeWithText("Déconnecter").performClick()
        waitText("Aucun autre appareil")
        rule.waitUntil(30_000) { !vm.settingsBusy }
        settle()

        // ---- verrou : le téléphone vérifie l'identité avant de l'activer (ici, un téléphone d'essai qui dit oui ou non)
        val recognised = AtomicBoolean(true)
        Lock.stand = { done -> done(recognised.get()) }
        rule.onNodeWithTag("verrou").performScrollTo().performClick()
        rule.waitUntil(15_000) { vm.lockEnabled }
        shot("17c-verrou-active")
        settle()
        rule.onNodeWithContentDescription("Retour à la boîte").performClick()
        waitText("Réception")

        // ---- de retour après une absence, l'appli ne montre plus rien tant que le téléphone ne reconnaît pas son propriétaire
        recognised.set(false)
        Lock.graceMs = 0
        rule.runOnUiThread {
            vm.wentAway()
            vm.cameBack()
        }
        rule.waitUntil(15_000) { vm.locked }
        waitText("La boîte est verrouillée")
        assertTrue("verrouillée, l'appli ne montre aucun message", rule.onAllNodesWithText("Réception").fetchSemanticsNodes().isEmpty())
        shot("17d-verrou")
        rule.onNodeWithTag("deverrouiller").performClick()
        rule.waitForIdle()
        assertTrue("un refus du téléphone laisse l'appli verrouillée", vm.locked)
        recognised.set(true)
        rule.onNodeWithTag("deverrouiller").performClick()
        rule.waitUntil(15_000) { !vm.locked }
        waitText("Réception")
        Lock.graceMs = 60_000
        rule.runOnUiThread { vm.setLock(false) }
        settle()

        deliver("Infolettre du lundi <news@infolettre.example>", "Numero 7")
        deliver("Client <client@example.com>", "Demande de devis", to = "contact@valentinlarge.com", rcpt = "contact@valentinlarge.com")
        // Un message transféré par Gmail dit, dans ses en-têtes, de quelle boîte Gmail il vient.
        deliver(
            "Ancien contact <ancien@example.org>",
            "Suivi par Gmail",
            "X-Forwarded-To: valentin@valentinlarge.com",
            "X-Forwarded-For: boite.essai@gmail.example valentin@valentinlarge.com",
        )
        menu("Actualiser")
        waitText("Demande de devis")
        assertTrue("le message visé par la règle n'est pas en réception", vm.messages.none { it.subject == "Numero 7" })
        rule.waitUntil(30_000) { vm.messages.any { it.subject == "Demande de devis" && it.labels.size == 1 } }
        rule.onNodeWithText("Archives").performClick()
        waitText("Numero 7")
        rule.onNodeWithText("Réception").performClick()
        waitText("Demande de devis")

        // ---- tri par boîte : l'adresse principale, une autre adresse du domaine, et le compte Gmail d'origine
        rule.onNodeWithTag("bouton-dossiers").performClick()
        waitText("Nouveau dossier")
        rule.waitUntil(30_000) { vm.boxes.size == 3 }
        rule.onNodeWithTag("boite-boite.essai@gmail.example").performScrollTo()
        shot("17e-boites")
        rule.onNodeWithTag("boite-contact@valentinlarge.com").performScrollTo().performClick()
        rule.waitUntil(30_000) { boxOf(vm.folder) == "contact@valentinlarge.com" && vm.messages.map { it.subject } == listOf("Demande de devis") }
        rule.onNodeWithTag("bandeau-dossier").assertTextContains("contact@valentinlarge.com")
        shot("17f-dans-une-boite")
        rule.onNodeWithTag("bandeau-dossier").performClick()
        waitText("Nouveau dossier")
        rule.onNodeWithTag("boite-boite.essai@gmail.example").performScrollTo().performClick()
        rule.waitUntil(30_000) { boxOf(vm.folder) == "boite.essai@gmail.example" && vm.messages.map { it.subject } == listOf("Suivi par Gmail") }
        rule.onNodeWithContentDescription("Fermer la boîte et revenir à la réception").performClick()
        rule.waitUntil(30_000) { vm.folder == "inbox" && vm.messages.any { it.subject == "Demande de devis" } }
        waitText("Réception")

        // ---- galerie des pièces jointes : reçues et envoyées, sans ouvrir les messages
        menu("Pièces jointes")
        rule.waitUntil(30_000) { vm.screen == Screen.Gallery && !vm.galleryLoading && vm.gallery.any { it.name == "essai-piece.txt" } }
        val facture = vm.gallery.first { it.name == "facture.txt" && !it.outgoing }
        assertTrue("les pièces envoyées y sont aussi", vm.gallery.any { it.name == "essai-piece.txt" && it.outgoing })
        waitText("facture.txt")
        shot("17g-pieces-jointes")
        // Une pièce se télécharge seule, et l'appli la décode elle-même.
        assertEquals("Facture 778 : 42,00 EUR", runBlocking { vm.fetchAttachment(facture) }!!.file.readText())
        val sentPiece = vm.gallery.first { it.name == "essai-piece.txt" }
        assertEquals(piece.length(), runBlocking { vm.fetchAttachment(sentPiece) }!!.file.length())
        rule.onNodeWithTag("famille-pdf").performClick()
        rule.waitUntil(30_000) { vm.galleryKind == "pdf" && !vm.galleryLoading && vm.gallery.isEmpty() }
        waitText("Aucune pièce jointe ne correspond")
        rule.onNodeWithTag("famille-tout").performClick()
        rule.waitUntil(30_000) { !vm.galleryLoading && vm.gallery.isNotEmpty() }
        rule.onNodeWithTag("recherche-pieces").performTextInput("facture")
        rule.waitUntil(30_000) { vm.gallery.isNotEmpty() && vm.gallery.all { it.name == "facture.txt" } }
        shell("input keyevent 111")
        rule.onAllNodesWithTag("piece-facture.txt")[0].performClick()
        waitText("Voir le message")
        shot("17h-piece-choisie")
        rule.onNodeWithText("Voir le message").performClick()
        rule.waitUntil(30_000) { vm.screen is Screen.Reader && vm.opened != null }
        rule.onNodeWithContentDescription("Retour à la liste").performClick()
        rule.waitUntil(15_000) { vm.screen == Screen.Gallery }
        rule.onNodeWithContentDescription("Retour à la boîte").performClick()
        waitText("Réception")

        // ---- on répond depuis l'adresse qui a reçu le message
        rule.onNodeWithText("Demande de devis").performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        rule.onNodeWithText("Répondre").performClick()
        waitText("Réponse")
        assertEquals("contact@valentinlarge.com", vm.draftFrom)
        assertTrue("la signature se place avant le message cité", vm.draftText.startsWith("\n\n-- \nValentin, essai\n\nLe "))
        waitText("Valentin <contact@valentinlarge.com>")
        rule.onNodeWithTag("message").performTextInput("Bien reçu, je prépare le devis.")
        shell("input keyevent 111")
        shot("18-reponse-depuis-une-autre-adresse")
        rule.onNodeWithTag("envoyer").performClick()
        rule.waitUntil(60_000) { vm.screen is Screen.Reader && !vm.sending }
        settle()
        backToList()

        // ---- thème sombre : l'appli suit le réglage du téléphone
        shell("cmd uimode night yes")
        Thread.sleep(4000)
        waitText("Réception")
        shot("19-theme-sombre")
        rule.onNodeWithText("Demande de devis").performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        shot("20-theme-sombre-message")
        backToList()
        shell("cmd uimode night no")
        Thread.sleep(4000)
        waitText("Réception")

        // ---- déconnexion : le serveur ferme aussi la session, le jeton du téléphone ne vaut plus rien
        val mine = vm.prefs.token!!
        menu("Se déconnecter")
        waitText("Ouvrir la boîte")
        assertTrue("le jeton de l'appli est refusé après la déconnexion", !accepted(mine))
    }
}
