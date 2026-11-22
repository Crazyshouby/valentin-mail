package com.valentinlarge.courrier

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Notifications instantanées : le serveur envoie un simple signal par Firebase, sans rien du message,
 * et l'appli vient alors relever le courrier elle-même.
 */
object Push {

    /** Appelé sur le fil principal quand un signal vient d'être traité, pour rafraîchir l'écran ouvert. */
    @Volatile
    var listener: (() -> Unit)? = null

    /** Vrai si cette version de l'appli a été construite avec les réglages Firebase. */
    fun available(context: Context): Boolean =
        try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (e: Exception) {
            false
        }

    /** Déclare ce téléphone au serveur, pour qu'il soit prévenu à l'arrivée d'un message. */
    fun register(context: Context) {
        val app = context.applicationContext
        if (!available(app)) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> declare(app, token) }
        } catch (e: Exception) {
            // Sans les services Google, on en reste à la relève périodique.
        }
    }

    fun declare(context: Context, token: String) {
        val repo = Repo.get(context)
        if (repo.prefs.token == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.api.registerDevice(token)
                repo.prefs.pushToken = token
            } catch (e: Exception) {
                // Nouvel essai à la prochaine ouverture de l'appli.
            }
        }
    }

    /** À la déconnexion : le serveur ne doit plus prévenir ce téléphone. */
    suspend fun forget(context: Context) {
        val repo = Repo.get(context)
        val token = repo.prefs.pushToken ?: return
        try {
            repo.api.forgetDevice(token)
        } catch (e: Exception) {
            // Le serveur oubliera de lui-même un téléphone qui ne répond plus.
        }
        repo.prefs.pushToken = null
    }
}

class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Push.declare(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Android laisse une vingtaine de secondes : le temps de relever le courrier et d'afficher la notification.
        runBlocking {
            try {
                withTimeout(18_000) { Sync.check(applicationContext) }
            } catch (e: Exception) {
                // La relève périodique rattrapera ce message.
            }
        }
        Handler(Looper.getMainLooper()).post { Push.listener?.invoke() }
    }
}
