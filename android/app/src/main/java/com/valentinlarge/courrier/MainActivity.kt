package com.valentinlarge.courrier

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

// Une activité « à fragments » : c'est ce que demande la fenêtre d'empreinte du téléphone.
class MainActivity : FragmentActivity() {

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Le thème : celui choisi dans les réglages, sinon celui du téléphone.
        Palette.system = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Palette.mode = Repo.get(this).prefs.themeMode
        Palette.settle()
        applyBars(Palette.dark)
        super.onCreate(savedInstanceState)
        // Le fond de la fenêtre, visible un instant avant le premier affichage, prend déjà le bon thème.
        window.setBackgroundDrawable(ColorDrawable(if (Palette.dark) 0xFF0D0F12.toInt() else 0xFFF3F4F1.toInt()))
        if (savedInstanceState == null) {
            openFromNotification(intent)
            // Ouverte par « Partager » ou par un lien d'adresse : l'appli n'est là que pour ce message.
            receiveShare(intent, launched = true)
        }
        setContent {
            // Les icônes de la barre du téléphone suivent le thème de l'appli, même quand il est imposé.
            val dark = Palette.dark
            LaunchedEffect(dark) { applyBars(dark) }
            CourrierTheme {
                App(viewModel)
            }
        }
    }

    private fun applyBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
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

/**
 * L'appli entière, en couches : la boîte (ses trois pages) au fond, le message ouvert qui glisse
 * par-dessus, la rédaction qui monte comme une feuille, et le verrou qui cache tout.
 */
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

    val locked = vm.locked
    val screen = vm.screen
    val selecting = screen == Screen.Mailbox && vm.selected.isNotEmpty()
    // Depuis un autre dossier, le bouton retour ramène à la réception avant de quitter l'appli.
    val inFolder = screen == Screen.Mailbox && vm.folder != "inbox"
    BackHandler(
        enabled = !locked && (screen is Screen.Reader || screen == Screen.Compose || screen == Screen.Settings ||
            screen == Screen.Gallery || selecting || inFolder),
    ) {
        when {
            selecting -> vm.clearSelection()
            inFolder -> vm.selectFolder("inbox")
            else -> vm.back()
        }
    }

    // À l'ouverture de la boîte (au lancement, ou quand le verrou se lève), les messages arrivent en cascade.
    var cascade by remember { mutableStateOf(true) }
    LaunchedEffect(locked, screen == Screen.Login) {
        if (locked || screen == Screen.Login) {
            // Rien de la boîte n'est à l'écran : la prochaine ouverture aura sa cascade.
            cascade = true
        } else {
            Animatable(0f).animateTo(1f, tween(1200))
            cascade = false
        }
    }

    Box(Modifier.fillMaxSize().background(Backdrop)) {
        // Verrouillée, l'appli ne compose rien de la boîte : ni à l'écran, ni pour un lecteur d'écran.
        if (!locked) {
            if (screen == Screen.Login) {
                LoginScreen(vm)
            } else {
                val composing = screen == Screen.Compose
                val readerId = vm.readerId
                // Le dernier message ouvert reste affiché le temps que son écran glisse hors de vue.
                var shownId by remember { mutableStateOf(readerId) }
                if (readerId != null && readerId != shownId) shownId = readerId
                val depth by animateFloatAsState(if (readerId != null) 1f else 0f, tween(460, easing = Glide), label = "recul")

                // La boîte : elle recule d'un cran quand un message s'ouvre par-dessus.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = 1f - 0.07f * depth
                            scaleX = scale
                            scaleY = scale
                            translationX = -14.dp.toPx() * depth
                            alpha = 1f - 0.14f * depth
                            if (depth > 0f) {
                                shape = RoundedCornerShape(30.dp)
                                clip = true
                            }
                        }
                        .background(Paper)
                        .then(if (readerId != null || composing) Modifier.clearAndSetSemantics { } else Modifier),
                ) {
                    Shell(vm, cascade)
                }

                AnimatedVisibility(
                    visible = readerId != null,
                    enter = slideInHorizontally(tween(460, easing = Glide)) { it },
                    exit = slideOutHorizontally(tween(360, easing = Glide)) { it },
                ) {
                    Box(Modifier.fillMaxSize().then(if (composing) Modifier.clearAndSetSemantics { } else Modifier)) {
                        shownId?.let { ReaderScreen(vm, it) }
                    }
                }

                // La rédaction : un voile sur ce qu'il y a derrière, puis la feuille qui monte.
                AnimatedVisibility(visible = composing, enter = fadeIn(tween(320)), exit = fadeOut(tween(320))) {
                    Box(Modifier.fillMaxSize().background(Backdrop.copy(alpha = 0.45f)).solid())
                }
                AnimatedVisibility(
                    visible = composing,
                    enter = slideInVertically(tween(480, easing = Glide)) { it },
                    exit = slideOutVertically(tween(380, easing = Glide)) { it },
                ) {
                    ComposeScreen(vm)
                }
            }

            Toasts(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = if (screen == Screen.Login) 24.dp else 92.dp),
            )
        }

        // Le verrou se lève comme un rideau quand le téléphone a reconnu son propriétaire.
        AnimatedVisibility(
            visible = locked,
            enter = EnterTransition.None,
            exit = slideOutVertically(tween(700, easing = CubicBezierEasing(0.7f, 0f, 0.2f, 1f))) { -it } +
                fadeOut(tween(350, delayMillis = 350)),
        ) {
            LockScreen(vm)
        }
    }
}
