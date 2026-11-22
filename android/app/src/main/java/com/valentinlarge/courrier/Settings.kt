package com.valentinlarge.courrier

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Les réglages : adresses du domaine, règles de tri, signature, notifications, verrou et appareils connectés. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val page = vm.aliasPage
    val domain = page?.domain?.ifBlank { null } ?: vm.address.substringAfter('@')
    val context = LocalContext.current
    var closingOthers by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper)) {
                TopAppBar(
                    title = { Text("Réglages", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour à la boîte")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                )
                if (vm.settingsBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue, trackColor = BlueWash) else AirmailStripe()
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            vm.settingsError?.let {
                Text(it, modifier = Modifier.fillMaxWidth().padding(16.dp), color = Red, fontWeight = FontWeight.Medium)
            }

            // ---------------------------------------------------- adresses
            SectionTitle(
                "Tes adresses",
                "Chaque adresse en @$domain qui reçoit un message apparaît ici. Coupe celle qui attire le spam : " +
                    "elle refusera le courrier, les autres continuent de marcher.",
            )
            Column(Modifier.fillMaxWidth().background(Sheet)) {
                (page?.aliases ?: emptyList()).forEach { alias ->
                    val main = alias.address == page?.main
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(alias.address, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(
                                if (main) "adresse principale" else null,
                                alias.label.ifBlank { null },
                                when (alias.received) {
                                    0 -> "aucun message reçu"
                                    1 -> "1 message reçu"
                                    else -> "${alias.received} messages reçus"
                                },
                                if (!alias.enabled) "coupée" else null,
                            )
                            Text(details.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = if (alias.enabled) Muted else Red)
                        }
                        if (!main) {
                            Switch(
                                checked = alias.enabled,
                                onCheckedChange = { vm.setAliasEnabled(alias, it) },
                                enabled = !vm.settingsBusy,
                                modifier = Modifier.testTag("actif-${alias.address}"),
                            )
                            IconButton(onClick = { vm.forgetAlias(alias) }, enabled = !vm.settingsBusy) {
                                Icon(Icons.Filled.Close, contentDescription = "Retirer ${alias.address} de la liste")
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                }
                if (page == null && !vm.settingsBusy && vm.settingsError == null) {
                    Text("Chargement…", modifier = Modifier.padding(16.dp), color = Muted)
                }

                var name by rememberSaveable { mutableStateOf("") }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.filterNot(Char::isWhitespace) },
                        label = { Text("Nouvelle adresse") },
                        suffix = { Text("@$domain", color = Muted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.weight(1f).testTag("alias-nom"),
                    )
                    Button(
                        onClick = {
                            vm.addAlias(name, "")
                            name = ""
                        },
                        enabled = name.isNotBlank() && !vm.settingsBusy,
                    ) { Text("Ajouter") }
                }
            }
            Text(
                "Pour qu'une adresse reçoive du courrier, la règle « Catch-all » doit envoyer tout le domaine vers le Worker, " +
                    "dans Cloudflare (Email Routing). Écrire depuis une adresse marche sans cela.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )

            // ------------------------------------------------------ règles
            SectionTitle(
                "Règles de tri",
                "À l'arrivée d'un message, la première règle qui correspond s'applique. Les majuscules ne comptent pas.",
            )
            Column(Modifier.fillMaxWidth().background(Sheet)) {
                vm.rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val where = vm.labelName(rule.target.orEmpty()) ?: "un dossier"
                        val what = when (rule.action) {
                            "label" -> "ranger dans « $where »"
                            "file" -> "ranger dans « $where », sans passer par la réception"
                            else -> labelOf(RULE_ACTIONS, rule.action)
                        }
                        Text(
                            "Si ${labelOf(RULE_FIELDS, rule.field)} contient « ${rule.value} » : $what",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(onClick = { vm.deleteRule(rule) }, enabled = !vm.settingsBusy) {
                            Icon(Icons.Filled.Close, contentDescription = "Supprimer la règle sur ${rule.value}")
                        }
                    }
                    HorizontalDivider(color = Line)
                }

                var field by rememberSaveable { mutableStateOf("from") }
                var action by rememberSaveable { mutableStateOf("archive") }
                var value by rememberSaveable { mutableStateOf("") }
                var target by rememberSaveable { mutableStateOf("") }
                val needsFolder = action == "label" || action == "file"
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Si", modifier = Modifier.width(52.dp), color = Muted)
                        Choice(RULE_FIELDS, field, onPick = { field = it }, tag = "regle-champ")
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("contient") },
                        placeholder = { Text("un nom, une adresse, un mot") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("regle-texte"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("alors", modifier = Modifier.width(52.dp), color = Muted)
                        Choice(RULE_ACTIONS, action, onPick = { action = it }, tag = "regle-action")
                    }
                    if (needsFolder) {
                        if (vm.labels.isEmpty()) {
                            Text(
                                "Crée d'abord un dossier, depuis le menu en haut à gauche de la boîte.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("dans", modifier = Modifier.width(52.dp), color = Muted)
                                Choice(
                                    vm.labels.map { it.id to it.name },
                                    target,
                                    onPick = { target = it },
                                    tag = "regle-dossier",
                                    placeholder = "choisir un dossier",
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            vm.addRule(field, value, action, if (needsFolder) target else null)
                            value = ""
                        },
                        enabled = value.isNotBlank() && !vm.settingsBusy && (!needsFolder || vm.labels.any { it.id == target }),
                    ) { Text("Ajouter la règle") }
                }
            }

            // --------------------------------------------------- signature
            SectionTitle("Signature", "Ajoutée au bas des messages que tu écris, sous l'endroit où tu tapes. Tu peux la retirer d'un message avant l'envoi.")
            Column(Modifier.fillMaxWidth().background(Sheet).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var text by rememberSaveable(vm.signature) { mutableStateOf(vm.signature) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Ton nom, ton métier, un numéro…") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("signature"),
                )
                Button(
                    onClick = { vm.saveSignature(text) },
                    enabled = text.trim() != vm.signature && !vm.settingsBusy,
                ) { Text("Enregistrer la signature") }
            }

            // ----------------------------------------------- notifications
            SectionTitle(
                "Notifications",
                when {
                    vm.pushApp && vm.pushServer -> "Instantanées : le serveur prévient ce téléphone dès qu'un message arrive."
                    !vm.pushApp -> "Vérifiées toutes les 15 minutes environ. Cette version de l'appli n'a pas reçu les réglages Firebase."
                    else -> "Vérifiées toutes les 15 minutes environ. Le serveur n'a pas encore la clé Firebase (secret FCM_SERVICE_ACCOUNT)."
                },
            )
            if (vm.pushApp) {
                OutlinedButton(
                    onClick = { vm.testPush() },
                    enabled = !vm.pushTesting,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(if (vm.pushTesting) "Essai en cours…" else "Tester les notifications") }
            }

            // ------------------------------------------------ verrouillage
            SectionTitle(
                "Verrouillage",
                "L'appli demande ton empreinte, ou à défaut le code du téléphone, avant de montrer le courrier : à l'ouverture, " +
                    "et quand tu y reviens après plus d'une minute. Les notifications continuent d'afficher l'expéditeur et l'objet.",
            )
            Row(
                Modifier.fillMaxWidth().background(Sheet).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Verrouiller l'appli par empreinte", modifier = Modifier.weight(1f))
                Switch(
                    checked = vm.lockEnabled,
                    onCheckedChange = { wanted ->
                        // Le téléphone vérifie l'identité avant tout changement, dans un sens comme dans l'autre.
                        val state = Lock.state(context)
                        if (state == Lock.State.Ready) {
                            Lock.ask(context, if (wanted) "Activer le verrou de Courrier" else "Désactiver le verrou de Courrier") { ok ->
                                if (ok) vm.setLock(wanted)
                            }
                        } else if (!wanted) {
                            vm.setLock(false)
                        } else {
                            vm.notice = if (state == Lock.State.NothingSet) {
                                "Enregistre d'abord une empreinte ou un code de déverrouillage dans les réglages du téléphone."
                            } else {
                                "Ce téléphone ne sait pas vérifier une empreinte ou un code pour une appli."
                            }
                        }
                    },
                    modifier = Modifier.testTag("verrou"),
                )
            }

            // ------------------------------------------ appareils connectés
            SectionTitle(
                "Appareils connectés",
                "Chaque téléphone ou navigateur où la boîte est ouverte. Un appareil déconnecté doit redonner le mot de passe.",
            )
            Column(Modifier.fillMaxWidth().background(Sheet)) {
                vm.sessions.forEach { session ->
                    val name = session.name.ifBlank { if (session.kind == "app") "Appli Courrier" else "Navigateur" }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(
                                if (session.current) "cet appareil" else null,
                                if (session.kind == "app") "appli" else "navigateur",
                                session.place.ifBlank { null },
                                if (session.current) null else "dernière activité : ${shortDate(session.seen)}",
                            )
                            Text(details.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        if (!session.current) {
                            TextButton(
                                onClick = { vm.closeSession(session) },
                                enabled = !vm.settingsBusy,
                                modifier = Modifier.testTag("deconnecter-$name"),
                            ) { Text("Déconnecter", color = Red) }
                        }
                    }
                    HorizontalDivider(color = Line)
                }
                if (vm.sessions.isEmpty() && !vm.settingsBusy && vm.settingsError == null) {
                    Text("Chargement…", modifier = Modifier.padding(16.dp), color = Muted)
                }
                OutlinedButton(
                    onClick = { closingOthers = true },
                    enabled = !vm.settingsBusy,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("deconnecter-les-autres"),
                ) { Text("Déconnecter tous les autres appareils") }
                Text(
                    "Ferme aussi les connexions anciennes qui ne se sont pas encore montrées dans cette liste.",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (closingOthers) {
        AlertDialog(
            onDismissRequest = { closingOthers = false },
            title = { Text("Déconnecter les autres appareils ?") },
            text = { Text("Seul ce téléphone restera connecté. Les autres, webmail compris, devront redonner le mot de passe.") },
            confirmButton = {
                TextButton(onClick = {
                    closingOthers = false
                    vm.closeOtherSessions()
                }) { Text("Déconnecter", color = Red) }
            },
            dismissButton = { TextButton(onClick = { closingOthers = false }) { Text("Annuler") } },
        )
    }
}

private fun labelOf(options: List<Pair<String, String>>, id: String): String = options.firstOrNull { it.first == id }?.second ?: id

@Composable
private fun SectionTitle(title: String, help: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(help, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** Un choix parmi quelques possibilités : le bouton montre la valeur, un menu propose les autres. */
@Composable
private fun Choice(
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    tag: String,
    placeholder: String = "",
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Text(labelOf(options, selected).ifBlank { placeholder })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (id == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onPick(id)
                    },
                )
            }
        }
    }
}
