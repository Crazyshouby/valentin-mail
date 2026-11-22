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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Les adresses du domaine et les règles de tri : tout ce que le serveur fait d'un message à son arrivée. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val page = vm.aliasPage
    val domain = page?.domain?.ifBlank { null } ?: vm.address.substringAfter('@')

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper)) {
                TopAppBar(
                    title = { Text("Adresses et règles", style = MaterialTheme.typography.titleLarge) },
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
            Column(Modifier.fillMaxWidth().background(Color.White)) {
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
            Column(Modifier.fillMaxWidth().background(Color.White)) {
                vm.rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Si ${labelOf(RULE_FIELDS, rule.field)} contient « ${rule.value} » : ${labelOf(RULE_ACTIONS, rule.action)}",
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
                    Button(
                        onClick = {
                            vm.addRule(field, value, action)
                            value = ""
                        },
                        enabled = value.isNotBlank() && !vm.settingsBusy,
                    ) { Text("Ajouter la règle") }
                }
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
            Spacer(Modifier.height(24.dp))
        }
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
private fun Choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) { Text(labelOf(options, selected)) }
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
