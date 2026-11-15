package com.mastifdogo.devotion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mastifdogo.devotion.MainViewModel

/** "What's your name?": type a name (creates or finds the profile) or pick an existing profile. */
@Composable
fun ProfileDialog(vm: MainViewModel) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { vm.dismissProfilePrompt() },
        title = { Text("What's your name?") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Your highlights and notes are kept under your name on the server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !vm.profileBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                val error = vm.profileError
                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val choices = vm.profileChoices
                if (choices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Or pick an existing profile:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (choice in choices) {
                            TextButton(
                                onClick = { vm.selectProfile(choice) },
                                enabled = !vm.profileBusy,
                            ) {
                                Text("Continue as ${choice.name}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.submitProfileName(name) },
                enabled = !vm.profileBusy && name.isNotBlank(),
            ) {
                Text(if (vm.profileBusy) "Saving…" else "Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissProfilePrompt() }) { Text("Not now") }
        },
    )
}
