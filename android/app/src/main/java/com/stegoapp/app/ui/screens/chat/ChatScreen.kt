package com.stegoapp.app.ui.screens.chat

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stegoapp.app.data.local.entity.MessageEntity
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val stegoMode by chatViewModel.stegoMode.collectAsState()
    val inviteCodesLoaded by chatViewModel.inviteCodesLoaded.collectAsState()
    val maxCapacity by chatViewModel.maxCapacity.collectAsState()
    val stegoLoading by chatViewModel.stegoLoading.collectAsState()
    val scope = rememberCoroutineScope()

    // Load invite codes on mount
    LaunchedEffect(contactId) {
        chatViewModel.loadInviteCodes(contactId)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val inputByteLength = remember(inputText) {
        inputText.toByteArray(Charsets.UTF_8).size
    }
    val overCapacity = stegoMode && maxCapacity > 0 && inputByteLength > maxCapacity
    val canSend = inputText.isNotBlank() && !stegoLoading && !(stegoMode && overCapacity)

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
            Column {
                // Capacity indicator
                if (stegoMode && maxCapacity > 0) {
                    Text(
                        text = "秘密消息: $inputByteLength/$maxCapacity 字节",
                        fontSize = 12.sp,
                        color = if (overCapacity) Color.Red else Color.Gray,
                        modifier = Modifier.padding(start = 56.dp, top = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stego mode toggle
                    IconButton(
                        onClick = { chatViewModel.toggleStegoMode() },
                        enabled = inviteCodesLoaded
                    ) {
                        Text(
                            text = if (stegoMode) "\uD83D\uDD12" else "\uD83D\uDCAC",
                            fontSize = 20.sp
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(if (stegoMode) "输入秘密消息..." else "输入消息...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    if (stegoLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(8.dp).size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (stegoMode) {
                                    chatViewModel.sendStegoMessage(contactId, inputText.trim(), currentUserId, currentUsername)
                                } else {
                                    chatViewModel.sendTextMessage(contactId, inputText.trim(), currentUserId, currentUsername)
                                }
                                inputText = ""
                            },
                            enabled = canSend
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
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
                MessageBubble(message = msg, chatViewModel = chatViewModel)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageEntity, chatViewModel: ChatViewModel) {
    val isSent = message.direction == "sent"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }

    val stegoBitmap = remember(message.stegoImage) {
        message.stegoImage?.let {
            try {
                val base64 = if (it.startsWith("data:")) it.substringAfter(",") else it
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isSent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(10.dp).widthIn(max = 260.dp)) {
                if (message.revoked) {
                    Text(
                        "消息已撤回",
                        color = if (isSent) Color.White.copy(alpha = 0.7f) else Color.Gray,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    // Stego image
                    if (message.contentType == "stego" && stegoBitmap != null) {
                        Box {
                            Image(
                                bitmap = stegoBitmap.asImageBitmap(),
                                contentDescription = "隐写图像",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showMenu = true }
                                    )
                            )
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("提取秘密消息") },
                                    onClick = {
                                        showMenu = false
                                        if (!extracting && message.stegoImage != null) {
                                            extracting = true
                                            scope.launch {
                                                val isOut = message.direction == "sent"
                                                extractedText = chatViewModel.extractMessage(message.stegoImage, isOut)
                                                extracting = false
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("保存图像") },
                                    onClick = {
                                        showMenu = false
                                        message.stegoImage?.let { img ->
                                            scope.launch {
                                                saveImageToGallery(context, img, message.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // Extraction result
                        if (extracting) {
                            Text("提取中...", fontSize = 12.sp, color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp))
                        } else if (extractedText != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(extractedText!!, fontSize = 13.sp,
                                    modifier = Modifier.padding(6.dp))
                            }
                        }
                    }

                    // Text content (only if non-empty)
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            color = if (isSent) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp
                        )
                    }

                    // Status
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimestamp(message.createdAt),
                            fontSize = 11.sp,
                            color = if (isSent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                    else Color.Gray
                        )
                        if (isSent) {
                            val icon = when (message.status) {
                                "sending" -> "⏳"; "sent" -> "✓"; "delivered" -> "✓✓"
                                "read" -> "👁"; "failed" -> "❌"; else -> ""
                            }
                            Text(icon, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(ts: String): String {
    return try {
        val epoch = ts.toLongOrNull() ?: return ts
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(epoch * 1000))
    } catch (_: Exception) { ts }
}

private suspend fun saveImageToGallery(context: android.content.Context, base64: String, msgId: String) {
    withContext(Dispatchers.IO) {
        try {
            val clean = if (base64.startsWith("data:")) base64.substringAfter(",") else base64
            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "stego_$msgId.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StegoApp")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "图像已保存", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
