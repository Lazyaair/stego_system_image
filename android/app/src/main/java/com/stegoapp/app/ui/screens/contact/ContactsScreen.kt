package com.stegoapp.app.ui.screens.contact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stegoapp.app.ui.viewmodel.ContactViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    contactViewModel: ContactViewModel,
    onOpenChat: (String) -> Unit,
    onAddContact: () -> Unit
) {
    val contacts by contactViewModel.contacts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    IconButton(onClick = onAddContact) {
                        Icon(Icons.Default.Add, "Add contact")
                    }
                }
            )
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No contacts yet", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(contacts) { contact ->
                    ListItem(
                        headlineContent = {
                            Text(contact.nickname.ifEmpty { contact.username }, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = { Text("@${contact.username}", color = Color.Gray) },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        contact.username.first().uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpenChat(contact.userId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
