package com.valentinlarge.courrier

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
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

class MainActivity : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFromNotification(intent)
        setContent {
            CourrierTheme {
                App(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
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

    val screen = vm.screen
    val selecting = screen == Screen.Mailbox && vm.selected.isNotEmpty()
    BackHandler(enabled = screen is Screen.Reader || screen == Screen.Compose || selecting) {
        if (selecting) vm.clearSelection() else vm.back()
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        when (screen) {
            Screen.Login -> LoginScreen(vm)
            Screen.Mailbox -> MailboxScreen(vm, snackbar)
            is Screen.Reader -> ReaderScreen(vm, snackbar)
            Screen.Compose -> ComposeScreen(vm, snackbar)
        }
    }
}
