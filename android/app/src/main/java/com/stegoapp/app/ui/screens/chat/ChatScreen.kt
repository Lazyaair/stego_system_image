package com.stegoapp.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stegoapp.app.data.local.entity.MessageEntity
import com.stegoapp.app.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactId: String,
    contactName: String,
    chatViewModel: ChatViewModel,
    currentUserId: String,
    currentUsername: String,
    onBack: () -> Unit
) {
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            chatViewModel.sendTextMessage(
                                contactId, inputText.trim(), currentUserId, currentUsername
                            )
                            inputText = ""
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }
    }
}

@Composable
fun MessageBubble(message: MessageEntity) {
    val isSent = message.direction == "sent"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isSent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (message.revoked) {
                    Text(
                        "Message revoked",
                        color = if (isSent) Color.White.copy(alpha = 0.7f)
                                else Color.Gray,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    Text(
                        message.content,
                        color = if (isSent) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSent) {
                    val statusText = when (message.status) {
                        "sending" -> "..."
                        "sent" -> "\u2713"
                        "delivered" -> "\u2713\u2713"
                        "read" -> "\u2713\u2713"
                        "failed" -> "\u2717"
                        else -> ""
                    }
                    Text(
                        statusText,
                        fontSize = 10.sp,
                        color = if (message.status == "read") Color(0xFF4FC3F7)
                                else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
