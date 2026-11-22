package com.valentinlarge.courrier

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La page des réglages : d'abord un sommaire (thème, adresses, règles de tri, signature, notifications,
 * verrou, appareils connectés), puis une page par sujet, qui glisse à sa place.
 */
@Composable
fun SettingsPage(vm: MainViewModel) {
    var section by remember { mutableStateOf<String?>(null) }
    // Depuis un sujet, le bouton retour ramène au sommaire avant de quitter les réglages.
    BackHandler(enabled = section != null) { section = null }

    AnimatedContent(
        targetState = section,
        transitionSpec = {
            // Un sujet arrive par la droite ; le sommaire revient par la gauche.
            val side = if (targetState != null) 1 else -1
            (slideInHorizontally(tween(380, easing = Glide)) { width -> side * width / 6 } + fadeIn(tween(220))) togetherWith fadeOut(tween(120))
        },
        label = "réglages",
    ) { current ->
        if (current == null) {
            SettingsHub(vm, onOpen = { section = it })
        } else {
            SettingsSection(vm, current, onBack = { section = null })
        }
    }
}

private fun howMany(number: Int, one: String, many: String): String = if (number == 1) "1 $one" else "$number $many"

@Composable
private fun SettingsHub(vm: MainViewModel, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                Text("Réglages", style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            }
            Text(vm.address, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        vm.settingsError?.let { Text(it, color = Red, fontWeight = FontWeight.Medium) }

        // ------------------------------------------------------- thème
        Card {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Moon, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text("Thème", modifier = Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // « Automatique » suit le réglage clair ou sombre du téléphone.
                listOf("auto" to "Automatique", "light" to "Clair", "dark" to "Sombre").forEach { (mode, label) ->
                    Chip(label, selected = vm.themeMode == mode, onClick = { vm.chooseTheme(mode) }, modifier = Modifier.testTag("theme-$mode"))
                }
            }
        }

        // ------------------------------------------- courrier : adresses, tri, signature
        Card {
            SettingLine(
                Glyphs.At,
                "Adresses",
                note = vm.aliasPage?.let { howMany(it.aliases.size, "adresse", "adresses") },
                modifier = Modifier.testTag("reglage-adresses"),
                onClick = { onOpen("adresses") },
            )
            Hairline()
            SettingLine(
                Glyphs.Funnel,
                "Règles de tri",
                note = if (vm.rules.isEmpty()) "aucune" else howMany(vm.rules.size, "règle", "règles"),
                modifier = Modifier.testTag("reglage-regles"),
                onClick = { onOpen("regles") },
            )
            Hairline()
            SettingLine(
                Glyphs.Pencil,
                "Signature",
                note = if (vm.signature.isBlank()) "aucune" else null,
                modifier = Modifier.testTag("reglage-signature"),
                onClick = { onOpen("signature") },
            )
            Hairline()
            SettingLine(
                Glyphs.Bell,
                "Notifications",
                note = if (vm.pushApp && vm.pushServer) "instantanées" else "toutes les 15 min",
                modifier = Modifier.testTag("reglage-notifications"),
                onClick = { onOpen("notifications") },
            )
        }

        // ------------------------------------------------ verrou et appareils
        Card {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyphs.Padlock, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text("Verrou par empreinte", modifier = Modifier.weight(1f))
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
                    colors = switchColors(),
                    modifier = Modifier.testTag("verrou"),
                )
            }
            if (vm.lockEnabled) {
                Hairline()
                SettingLine(
                    Glyphs.Fingerprint,
                    "Verrouiller maintenant",
                    chevron = false,
                    modifier = Modifier.testTag("verrouiller-maintenant"),
                    onClick = { vm.lockNow() },
                )
            }
            Hairline()
            SettingLine(
                Glyphs.Phone,
                "Appareils connectés",
                note = if (vm.sessions.isEmpty()) null else vm.sessions.size.toString(),
                modifier = Modifier.testTag("reglage-appareils"),
                onClick = { onOpen("appareils") },
            )
        }
        Text(
            "Le verrou demande ton empreinte, ou à défaut le code du téléphone, à l'ouverture de l'appli et quand tu y reviens " +
                "après plus d'une minute. Les notifications continuent d'afficher l'expéditeur et l'objet.",
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        TextButton(onClick = { vm.logout() }) {
            Text("Se déconnecter", color = Red, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Courrier ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

/** Une ligne du sommaire : un pictogramme, un intitulé, parfois une précision, et le chevron qui dit « la suite ». */
@Composable
private fun SettingLine(
    glyph: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(Glyphs.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** La page d'un sujet : son titre, de quoi revenir au sommaire, et le liseré qui défile pendant que le serveur travaille. */
@Composable
private fun SettingsSection(vm: MainViewModel, section: String, onBack: () -> Unit) {
    val title = when (section) {
        "adresses" -> "Adresses"
        "regles" -> "Règles de tri"
        "signature" -> "Signature"
        "notifications" -> "Notifications"
        else -> "Appareils connectés"
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyphs.Back, "Retour aux réglages", onClick = onBack)
            Text(title, modifier = Modifier.padding(start = 2.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (vm.settingsBusy) AirmailStripe(running = true, height = 4.dp)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.settingsError?.let { Text(it, color = Red, fontWeight = FontWeight.Medium) }
            when (section) {
                "adresses" -> Addresses(vm)
                "regles" -> Rules(vm)
                "signature" -> Signature(vm)
                "notifications" -> Notifications(vm)
                else -> Devices(vm)
            }
        }
    }
}

@Composable
private fun Help(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp), color = Muted)
}

// ---------------------------------------------------------------- adresses

@Composable
private fun Addresses(vm: MainViewModel) {
    val page = vm.aliasPage
    val domain = page?.domain?.ifBlank { null } ?: vm.address.substringAfter('@')
    Help(
        "Chaque adresse en @$domain qui reçoit un message apparaît ici. Coupe celle qui attire le spam : " +
            "elle refusera le courrier, les autres continuent de marcher.",
    )
    Card {
        (page?.aliases ?: emptyList()).forEachIndexed { index, alias ->
            val main = alias.address == page?.main
            if (index > 0) Hairline()
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                if (main) {
                    Spacer(Modifier.width(12.dp))
                } else {
                    Switch(
                        checked = alias.enabled,
                        onCheckedChange = { vm.setAliasEnabled(alias, it) },
                        enabled = !vm.settingsBusy,
                        colors = switchColors(),
                        modifier = Modifier.testTag("actif-${alias.address}"),
                    )
                    GlyphButton(Glyphs.Close, "Retirer ${alias.address} de la liste", onClick = { vm.forgetAlias(alias) }, enabled = !vm.settingsBusy, tint = Muted)
                }
            }
        }
        if (page == null && !vm.settingsBusy && vm.settingsError == null) {
            Text("Chargement…", modifier = Modifier.padding(16.dp), color = Muted)
        }
    }

    var name by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.filterNot(Char::isWhitespace) },
            label = { Text("Nouvelle adresse") },
            suffix = { Text("@$domain", color = Muted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None),
            shape = FieldShape,
            colors = fieldColors(),
            modifier = Modifier.weight(1f).testTag("alias-nom"),
        )
        Pill(
            "Ajouter",
            onClick = {
                vm.addAlias(name, "")
                name = ""
            },
            enabled = name.isNotBlank() && !vm.settingsBusy,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Help(
        "Pour qu'une adresse reçoive du courrier, la règle « Catch-all » doit envoyer tout le domaine vers le Worker, " +
            "dans Cloudflare (Email Routing). Écrire depuis une adresse marche sans cela.",
    )
}

// ------------------------------------------------------------------ règles

private fun labelOf(options: List<Pair<String, String>>, id: String): String = options.firstOrNull { it.first == id }?.second ?: id

@Composable
private fun Rules(vm: MainViewModel) {
    Help("À l'arrivée d'un message, la première règle qui correspond s'applique. Les majuscules ne comptent pas.")
    if (vm.rules.isNotEmpty()) {
        Card {
            vm.rules.forEachIndexed { index, rule ->
                if (index > 0) Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
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
                    GlyphButton(Glyphs.Close, "Supprimer la règle sur ${rule.value}", onClick = { vm.deleteRule(rule) }, enabled = !vm.settingsBusy, tint = Muted)
                }
            }
        }
    }

    var field by rememberSaveable { mutableStateOf("from") }
    var action by rememberSaveable { mutableStateOf("archive") }
    var value by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    val needsFolder = action == "label" || action == "file"
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nouvelle règle", fontWeight = FontWeight.SemiBold)
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
                shape = FieldShape,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("regle-texte"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("alors", modifier = Modifier.width(52.dp), color = Muted)
                Choice(RULE_ACTIONS, action, onPick = { action = it }, tag = "regle-action")
            }
            if (needsFolder) {
                if (vm.labels.isEmpty()) {
                    Text(
                        "Crée d'abord un dossier, en touchant le titre de la page du courrier.",
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
            Pill(
                "Ajouter la règle",
                onClick = {
                    vm.addRule(field, value, action, if (needsFolder) target else null)
                    value = ""
                },
                enabled = value.isNotBlank() && !vm.settingsBusy && (!needsFolder || vm.labels.any { it.id == target }),
            )
        }
    }
}

// --------------------------------------------------------------- signature

@Composable
private fun Signature(vm: MainViewModel) {
    Help("Ajoutée au bas des messages que tu écris, sous l'endroit où tu tapes. Tu peux la retirer d'un message avant l'envoi.")
    var text by rememberSaveable(vm.signature) { mutableStateOf(vm.signature) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Ton nom, ton métier, un numéro…") },
        minLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = FieldShape,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("signature"),
    )
    Pill(
        "Enregistrer la signature",
        onClick = { vm.saveSignature(text) },
        enabled = text.trim() != vm.signature && !vm.settingsBusy,
    )
}

// ----------------------------------------------------------- notifications

@Composable
private fun Notifications(vm: MainViewModel) {
    Help(
        when {
            vm.pushApp && vm.pushServer -> "Instantanées : le serveur prévient ce téléphone dès qu'un message arrive."
            !vm.pushApp -> "Vérifiées toutes les 15 minutes environ. Cette version de l'appli n'a pas reçu les réglages Firebase."
            else -> "Vérifiées toutes les 15 minutes environ. Le serveur n'a pas encore la clé Firebase (secret FCM_SERVICE_ACCOUNT)."
        },
    )
    if (vm.pushApp) {
        Pill(
            if (vm.pushTesting) "Essai en cours…" else "Tester les notifications",
            onClick = { vm.testPush() },
            enabled = !vm.pushTesting,
            filled = false,
        )
    }
}

// ------------------------------------------------------ appareils connectés

@Composable
private fun Devices(vm: MainViewModel) {
    var closingOthers by remember { mutableStateOf(false) }
    Help("Chaque téléphone ou navigateur où la boîte est ouverte. Un appareil déconnecté doit redonner le mot de passe.")
    Card {
        vm.sessions.forEachIndexed { index, session ->
            val name = session.name.ifBlank { if (session.kind == "app") "Appli Courrier" else "Navigateur" }
            if (index > 0) Hairline()
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
        }
        if (vm.sessions.isEmpty() && !vm.settingsBusy && vm.settingsError == null) {
            Text("Chargement…", modifier = Modifier.padding(16.dp), color = Muted)
        }
    }
    Pill(
        "Déconnecter tous les autres appareils",
        onClick = { closingOthers = true },
        enabled = !vm.settingsBusy,
        filled = false,
        modifier = Modifier.testTag("deconnecter-les-autres"),
    )
    Help("Ferme aussi les connexions anciennes qui ne se sont pas encore montrées dans cette liste.")

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
