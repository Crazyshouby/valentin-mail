package com.valentinlarge.courrier

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity

// Une activité « à fragments » : c'est ce que demande la fenêtre d'empreinte du téléphone.
class MainActivity : FragmentActivity() {

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // L'appli suit le thème clair ou sombre du téléphone.
        Palette.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            openFromNotification(intent)
            // Ouverte par « Partager » ou par un lien d'adresse : l'appli n'est là que pour ce message.
            receiveShare(intent, launched = true)
        }
        setContent {
            CourrierTheme {
                App(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
        receiveShare(intent, launched = false)
    }

    override fun onStart() {
        super.onStart()
        viewModel.cameBack()
    }

    override fun onStop() {
        viewModel.wentAway()
        super.onStop()
    }

    /** Un texte ou des fichiers partagés depuis une autre appli, ou un lien d'adresse touché ailleurs. */
    private fun receiveShare(intent: Intent?, launched: Boolean) {
        // Rouverte depuis la liste des applis récentes, l'appli ne rejoue pas un partage déjà traité.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val shared = Share.from(this, intent) ?: return
        viewModel.receive(shared, exit = launched)
    }

    private fun openFromNotification(intent: Intent?) {
        val id = intent?.getStringExtra(Sync.EXTRA_MESSAGE) ?: return
        intent.removeExtra(Sync.EXTRA_MESSAGE)
        viewModel.openFromNotification(id)
    }
}

@Composable
fun App(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val notice = vm.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            // Un geste rapide (glisser, action groupée) peut être annulé depuis le message qui le confirme.
            val undo = vm.undoFor == notice
            val result = snackbar.showSnackbar(
                message = notice,
                actionLabel = if (undo) "Annuler" else null,
                duration = if (undo) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoLast()
            vm.notice = null
        }
    }

    // Ouverte seulement pour écrire un message, l'appli se referme une fois celui-ci parti ou rangé.
    val farewell = vm.farewell
    val context = LocalContext.current
    LaunchedEffect(farewell) {
        if (farewell != null) {
            if (farewell.isNotBlank()) Toast.makeText(context.applicationContext, farewell, Toast.LENGTH_SHORT).show()
            (context as? MainActivity)?.finish()
        }
    }

    // Verrouillée, l'appli ne montre rien d'autre ; le bouton retour la quitte.
    if (vm.locked) {
        LockScreen(vm)
        return
    }

    val screen = vm.screen
    val selecting = screen == Screen.Mailbox && vm.selected.isNotEmpty()
    // Depuis un dossier personnel ou une boîte d'origine, le bouton retour ramène à la réception avant de quitter l'appli.
    val inFolder = screen == Screen.Mailbox && isCrossView(vm.folder)
    BackHandler(
        enabled = screen is Screen.Reader || screen == Screen.Compose || screen == Screen.Settings ||
            screen == Screen.Gallery || selecting || inFolder,
    ) {
        when {
            selecting -> vm.clearSelection()
            inFolder -> vm.selectFolder("inbox")
            else -> vm.back()
        }
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        when (screen) {
            Screen.Login -> LoginScreen(vm)
            Screen.Mailbox -> MailboxScreen(vm, snackbar)
            is Screen.Reader -> ReaderScreen(vm, snackbar)
            Screen.Compose -> ComposeScreen(vm, snackbar)
            Screen.Settings -> SettingsScreen(vm, snackbar)
            Screen.Gallery -> GalleryScreen(vm, snackbar)
        }
    }
}
