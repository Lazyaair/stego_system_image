package com.stegoapp.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import com.stegoapp.app.ui.viewmodel.ContactViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    contactViewModel: ContactViewModel,
    chatViewModel: ChatViewModel,
    onOpenChat: (String) -> Unit
) {
    val contacts by contactViewModel.contacts.collectAsState()
    val lastMessages by chatViewModel.getLastMessages().collectAsState(initial = emptyList())
    val pendingRequests by chatViewModel.pendingRequests.collectAsState()
    var expandedRequests by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Messages") })

        if (pendingRequests.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                onClick = { expandedRequests = !expandedRequests }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${pendingRequests.size} friend request(s)",
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        if (expandedRequests) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle"
                    )
                }
            }
            AnimatedVisibility(visible = expandedRequests) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    pendingRequests.forEach { request ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(request.username, fontWeight = FontWeight.Medium)
                                    Text(
                                        "wants to be your friend",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row {
                                    IconButton(onClick = {
                                        chatViewModel.acceptPendingRequest(request)
                                    }) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Accept",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = {
                                        chatViewModel.rejectPendingRequest(request)
                                    }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Reject",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (contacts.isEmpty() && pendingRequests.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conversations yet", color = Color.Gray)
            }
        } else {
            LazyColumn {
                items(contacts.filter { it.status == "accepted" }) { contact ->
                    val lastMsg = lastMessages.find { it.contactId == contact.userId }
                    ListItem(
                        headlineContent = {
                            Text(
                                contact.nickname.ifEmpty { contact.username },
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Text(
                                lastMsg?.content ?: "No messages",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.Gray
                            )
                        },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(44.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        (contact.nickname.ifEmpty { contact.username })
                                            .first().uppercase(),
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
