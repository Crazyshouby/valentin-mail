package com.valentinlarge.courrier

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
        Thread.sleep(900) // le temps que le contenu du message soit dessiné
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
        Espresso.pressBack()
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
        Espresso.closeSoftKeyboard()
        shot("06-reponse")

        rule.onNodeWithTag("envoyer").performClick()
        waitText("Message envoyé", 60_000)
        assertTrue("retour au message après l'envoi", vm.screen is Screen.Reader)
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
        val inject = URL("${vm.prefs.server}/cdn-cgi/local/email?from=camille@example.net&to=valentin@valentinlarge.com")
            .openConnection() as HttpURLConnection
        inject.requestMethod = "POST"
        inject.doOutput = true
        inject.outputStream.use { it.write(raw.toByteArray()) }
        assertEquals(200, inject.responseCode)
        inject.disconnect()

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

        // ---- rangement : archive, corbeille, suppression définitive
        rule.onNodeWithText("Rendez-vous jeudi").performClick()
        rule.waitUntil(30_000) { vm.opened != null }
        shot("11-lien-dans-texte")
        menu("Archiver")
        waitText("Message archivé")
        rule.onNodeWithText("Archives").performClick()
        waitText("Rendez-vous jeudi")
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
