package com.valentinlarge.courrier

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Le verrou de l'appli : avant de montrer le courrier, le téléphone vérifie que c'est bien son
 * propriétaire — par l'empreinte ou le visage, sinon par le code de déverrouillage du téléphone.
 * L'appli ne voit jamais l'empreinte ni le code : elle reçoit seulement « oui » ou « non ».
 */
object Lock {

    enum class State { Ready, NothingSet, Unsupported }

    /** Au-delà de cette absence, l'appli se verrouille à nouveau. */
    @Volatile
    var graceMs: Long = 60_000

    /** Pendant les essais automatiques : tient lieu de la demande faite par le téléphone. */
    @Volatile
    var stand: ((done: (Boolean) -> Unit) -> Unit)? = null

    private const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun state(context: Context): State {
        if (stand != null) return State.Ready
        // Avant Android 9, la demande ne sait pas se rabattre sur le code du téléphone.
        if (Build.VERSION.SDK_INT < 28) return State.Unsupported
        return when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> State.Ready
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> State.NothingSet
            else -> State.Unsupported
        }
    }

    /** Demande au téléphone de vérifier son propriétaire ; [done] reçoit vrai s'il l'a reconnu. */
    fun ask(context: Context, title: String, done: (Boolean) -> Unit) {
        stand?.let {
            it(done)
            return
        }
        val activity = activityOf(context)
        if (activity == null) {
            done(false)
            return
        }
        try {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)

                    // Demande refermée, trop d'essais, plus de verrou sur le téléphone… Une empreinte
                    // simplement mal lue ne passe pas par ici : le téléphone propose de réessayer.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Empreinte, visage ou code du téléphone")
                .setAllowedAuthenticators(ALLOWED)
                .build()
            prompt.authenticate(info)
        } catch (e: Exception) {
            done(false)
        }
    }

    private fun activityOf(context: Context): FragmentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return current
            current = current.baseContext
        }
        return null
    }
}

/**
 * L'écran montré à la place du courrier tant que le téléphone n'a pas reconnu son propriétaire.
 * Pas de cible dessinée pour le doigt : le capteur n'est pas au même endroit d'un téléphone à l'autre,
 * c'est la fenêtre du téléphone qui montre où poser l'empreinte.
 */
@Composable
fun LockScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    val ask = {
        checking = true
        // Reconnu, l'écran se lève en gardant son liseré en mouvement ; refusé, il attend un nouvel essai.
        Lock.ask(context, "Ouvrir Courrier") { ok -> if (ok) vm.unlock() else checking = false }
    }
    // La demande part d'elle-même à l'arrivée sur l'écran ; le bouton sert à la relancer.
    // Après « Verrouiller maintenant », rien ne part : on vient de fermer, pas de rouvrir.
    LaunchedEffect(Unit) { if (!vm.lockQuiet) ask() }

    Column(
        Modifier.fillMaxSize().background(Paper).solid().statusBarsPadding().navigationBarsPadding().testTag("ecran-verrou"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AirmailStripe(running = checking, height = 8.dp)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Courrier", style = MaterialTheme.typography.displaySmall)
            if (vm.address.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(vm.address, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(26.dp))
            Pill(
                if (checking) "Vérification…" else "Déverrouiller",
                onClick = ask,
                glyph = Glyphs.Padlock,
                height = 52.dp,
                modifier = Modifier.widthIn(min = 196.dp).shadow(10.dp, CircleShape).testTag("deverrouiller"),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "La boîte est verrouillée. Le téléphone vérifie ton empreinte ou son code.",
                modifier = Modifier.widthIn(max = 260.dp),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(onClick = { vm.logout() }, modifier = Modifier.padding(bottom = 16.dp)) {
            Text("Se déconnecter", color = Muted)
        }
    }
}
