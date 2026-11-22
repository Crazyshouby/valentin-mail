package com.valentinlarge.courrier

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

/** L'écran montré à la place du courrier tant que le téléphone n'a pas reconnu son propriétaire. */
@Composable
fun LockScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val ask = { Lock.ask(context, "Ouvrir Courrier") { ok -> if (ok) vm.unlock() } }
    // La demande part d'elle-même à l'arrivée sur l'écran ; le bouton sert à la relancer.
    LaunchedEffect(Unit) { ask() }

    Box(
        Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(20.dp).testTag("ecran-verrou"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .airmail()
                .padding(9.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Sheet, RoundedCornerShape(2.dp))
                    .padding(horizontal = 22.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Courrier", style = MaterialTheme.typography.displaySmall)
                Text("La boîte est verrouillée. Utilise ton empreinte, ou le code du téléphone, pour l'ouvrir.", color = Muted)
                Button(onClick = ask, modifier = Modifier.fillMaxWidth().testTag("deverrouiller")) {
                    Text("Déverrouiller")
                }
                TextButton(onClick = { vm.logout() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Se déconnecter")
                }
            }
        }
    }
}
