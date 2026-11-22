package com.valentinlarge.courrier

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
    private fun deliver(from: String, subject: String, vararg headers: String, to: String = "valentin@valentinlarge.com") {
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
            .url("${vm.prefs.server}/cdn-cgi/local/email?from=essai@example.net&to=valentin@valentinlarge.com")
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

        // ---- déconnexion
        menu("Se déconnecter")
        waitText("Ouvrir la boîte")
    }
}
