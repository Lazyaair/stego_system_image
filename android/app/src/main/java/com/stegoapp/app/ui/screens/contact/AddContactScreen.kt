package com.stegoapp.app.ui.screens.contact

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stegoapp.app.ui.viewmodel.ContactViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    contactViewModel: ContactViewModel,
    onBack: () -> Unit,
    onContactAdded: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val error by contactViewModel.error.collectAsState()
    var loading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Contact") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Invite Code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    loading = true
                    contactViewModel.addContactByCode(code.trim()) { contact ->
                        loading = false
                        onContactAdded(contact.userId)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = code.isNotBlank() && !loading
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else Text("Add Contact")
            }
        }
    }
}
