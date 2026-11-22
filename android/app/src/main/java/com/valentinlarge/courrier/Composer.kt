package com.valentinlarge.courrier

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }

    Scaffold(
        containerColor = Color.White,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper)) {
                TopAppBar(
                    title = { Text(if (vm.draftReplyTo != null) "Réponse" else "Nouveau message", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, enabled = !vm.sending) {
                            Icon(Icons.Filled.Close, contentDescription = "Fermer la rédaction")
                        }
                    },
                    actions = {
                        Button(
                            onClick = { vm.send() },
                            enabled = !vm.sending && vm.canSend,
                            modifier = Modifier.padding(end = 8.dp).testTag("envoyer"),
                        ) {
                            Text(if (vm.sending) "Envoi…" else "Envoyer")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                )
                if (vm.sending) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue, trackColor = BlueWash) else AirmailStripe()
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
        ) {
            if (!vm.canSend) {
                Text(
                    "L'envoi n'est pas encore activé sur le serveur : il manque la clé du service d'envoi.",
                    modifier = Modifier.fillMaxWidth().background(BlueWash).padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            vm.sendError?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Red,
                    fontWeight = FontWeight.Medium,
                )
                HorizontalDivider(color = Line)
            }

            if (vm.from.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("De", modifier = Modifier.width(56.dp), color = Muted)
                    Text(vm.from, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = Line)
            }
            FieldLine("À", vm.draftTo, { vm.draftTo = it }, "destinataire", KeyboardType.Email)
            FieldLine("Cc", vm.draftCc, { vm.draftCc = it }, "copie", KeyboardType.Email)
            FieldLine("Objet", vm.draftSubject, { vm.draftSubject = it }, "objet", KeyboardType.Text)

            TextField(
                value = vm.draftText,
                onValueChange = { vm.draftText = it },
                placeholder = { Text("Ton message", color = Muted) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = bare(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag("message"),
            )

            if (vm.draftFiles.isNotEmpty()) HorizontalDivider(color = Line)
            vm.draftFiles.forEach { file ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fileSize(file.size), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    IconButton(onClick = { vm.removeFile(file) }, enabled = !vm.sending) {
                        Icon(Icons.Filled.Close, contentDescription = "Retirer ${file.name}")
                    }
                }
                HorizontalDivider(color = Line)
            }

            TextButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !vm.sending,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Joindre un fichier")
            }
        }
    }
}

@Composable
private fun bare() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

/** Une ligne d'en-tête du message : son nom à gauche, la saisie à droite, un filet dessous. */
@Composable
private fun FieldLine(label: String, value: String, onChange: (String) -> Unit, tag: String, keyboard: KeyboardType) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(56.dp), color = Muted)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                imeAction = ImeAction.Next,
            ),
            colors = bare(),
            modifier = Modifier.weight(1f).testTag(tag),
        )
    }
    HorizontalDivider(color = Line)
}
