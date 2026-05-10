package com.stegoapp.app.ui.screens.chat

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
    onBack: () -> Unit,
    onNavigateProfile: () -> Unit = {},
    onNavigateContactDetail: (String) -> Unit = {},
) {
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val stegoMode by chatViewModel.stegoMode.collectAsState()
    val inviteCodesLoaded by chatViewModel.inviteCodesLoaded.collectAsState()
    val maxCapacity by chatViewModel.maxCapacity.collectAsState()
    val stegoLoading by chatViewModel.stegoLoading.collectAsState()
    val myCode by chatViewModel.myInviteCode.collectAsState()
    val peerCode by chatViewModel.peerInviteCode.collectAsState()
    val selfConfigured by chatViewModel.selfPhraseConfigured.collectAsState()
    val peerConfigured by chatViewModel.peerPhraseConfigured.collectAsState()
    val e2eeReady by chatViewModel.isE2EEConfigured.collectAsState()

    var keyVisible by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(contactId) {
        chatViewModel.setActiveContact(contactId)
        chatViewModel.loadInviteCodes(contactId)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { chatViewModel.setActiveContact(null) }
    }

    LaunchedEffect(Unit) {
        chatViewModel.snackbar.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val inputByteLength = remember(inputText) { inputText.toByteArray(Charsets.UTF_8).size }
    val overCapacity = stegoMode && maxCapacity > 0 && inputByteLength > maxCapacity
    val canSend = inputText.isNotBlank() && !stegoLoading && !(stegoMode && overCapacity) && e2eeReady

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = contactName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                stegoMode = stegoMode,
                stegoEnabled = inviteCodesLoaded && e2eeReady,
                stegoLoading = stegoLoading,
                canSend = canSend,
                inputEnabled = e2eeReady,
                inputByteLength = inputByteLength,
                maxCapacity = maxCapacity,
                overCapacity = overCapacity,
                myCode = myCode,
                peerCode = peerCode,
                keyVisible = keyVisible,
                onToggleKeyVisible = { keyVisible = !keyVisible },
                onToggleStegoMode = { chatViewModel.toggleStegoMode() },
                onSend = {
                    if (stegoMode) {
                        chatViewModel.sendStegoMessage(
                            contactId,
                            inputText.trim(),
                            currentUserId,
                            currentUsername,
                        )
                    } else {
                        chatViewModel.sendTextMessage(
                            contactId,
                            inputText.trim(),
                            currentUserId,
                            currentUsername,
                        )
                    }
                    inputText = ""
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            E2EEBanner(
                selfConfigured = selfConfigured,
                peerConfigured = peerConfigured,
                onConfigureSelf = onNavigateProfile,
                onConfigurePeer = { onNavigateContactDetail(contactId) },
            )
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有消息,说点什么吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(message = msg, chatViewModel = chatViewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun E2EEBanner(
    selfConfigured: Boolean,
    peerConfigured: Boolean,
    onConfigureSelf: () -> Unit,
    onConfigurePeer: () -> Unit,
) {
    when {
        !selfConfigured -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "请先在「我的」页面配置助记词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onConfigureSelf) { Text("去设置") }
                }
            }
        }
        !peerConfigured -> {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "请为该联系人配置对方的助记词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onConfigurePeer) { Text("去配置") }
                }
            }
        }
        else -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "端到端加密已启用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    stegoMode: Boolean,
    stegoEnabled: Boolean,
    stegoLoading: Boolean,
    canSend: Boolean,
    inputEnabled: Boolean,
    inputByteLength: Int,
    maxCapacity: Int,
    overCapacity: Boolean,
    myCode: String,
    peerCode: String,
    keyVisible: Boolean,
    onToggleKeyVisible: () -> Unit,
    onToggleStegoMode: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (stegoMode) {
                StegoInfoBanner(
                    inputByteLength = inputByteLength,
                    maxCapacity = maxCapacity,
                    overCapacity = overCapacity,
                    myCode = myCode,
                    peerCode = peerCode,
                    keyVisible = keyVisible,
                    onToggleKeyVisible = onToggleKeyVisible,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleStegoMode,
                    enabled = stegoEnabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (stegoMode) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        containerColor = if (stegoMode) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (stegoMode) Icons.Filled.Lock else Icons.Outlined.VisibilityOff,
                        contentDescription = if (stegoMode) "关闭隐写模式" else "开启隐写模式",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    enabled = inputEnabled,
                    placeholder = {
                        Text(
                            if (!inputEnabled) "未配置加密,无法发送"
                            else if (stegoMode) "输入秘密消息..."
                            else "输入消息...",
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (stegoLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(44.dp)
                            .padding(10.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StegoInfoBanner(
    inputByteLength: Int,
    maxCapacity: Int,
    overCapacity: Boolean,
    myCode: String,
    peerCode: String,
    keyVisible: Boolean,
    onToggleKeyVisible: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "隐写模式已开启",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (maxCapacity > 0) {
                    Text(
                        text = "$inputByteLength / $maxCapacity B",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overCapacity) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "密钥",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(6.dp))
                val fullKey = remember(myCode, peerCode) {
                    if (myCode.isEmpty() || myCode.length != peerCode.length) "" else {
                        val a = myCode.toByteArray(Charsets.UTF_8)
                        val b = peerCode.toByteArray(Charsets.UTF_8)
                        com.stegoapp.app.crypto.CryptoUtils.xorBytes(a, b)
                            .joinToString("") { "%02x".format(it) }
                    }
                }
                val keyText = if (keyVisible) fullKey else "••••••••••••••••"
                Text(
                    text = keyText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickableSimple(onToggleKeyVisible),
                )
                Icon(
                    imageVector = if (keyVisible) Icons.Filled.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (keyVisible) "隐藏密钥" else "显示密钥",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSimple(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageEntity, chatViewModel: ChatViewModel) {
    val isSent = message.direction == "sent"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    val stegoImageData = message.stegoImage.takeIf { message.contentType == "stego" }

    val stegoBitmap = remember(message.stegoImage) {
        message.stegoImage?.let { raw ->
            runCatching {
                val base64 = if (raw.startsWith("data:")) raw.substringAfter(",") else raw
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    val bubbleColor = when {
        isSent -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        isSent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = bubbleShape(isSent),
            color = bubbleColor,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 280.dp),
            ) {
                if (message.revoked) {
                    Text(
                        text = "消息已撤回",
                        color = textColor.copy(alpha = 0.7f),
                        fontStyle = FontStyle.Italic,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    if (stegoImageData != null && stegoBitmap != null) {
                        Box {
                            Image(
                                bitmap = stegoBitmap.asImageBitmap(),
                                contentDescription = "隐写图像",
                                modifier = Modifier
                                    .size(256.dp)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showMenu = true },
                                    ),
                            )
                            // Badge 右下角
                            StegoBadge(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp),
                            )
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("提取秘密消息") },
                                    onClick = {
                                        showMenu = false
                                        if (!extracting) {
                                            extracting = true
                                            scope.launch {
                                                val isOut = message.direction == "sent"
                                                extractedText = chatViewModel.extractMessage(stegoImageData, isOut)
                                                extracting = false
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("保存图像") },
                                    onClick = {
                                        showMenu = false
                                        scope.launch { saveImageToGallery(context, stegoImageData, message.id) }
                                    },
                                )
                            }
                        }
                        if (extracting) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "提取中...",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.7f),
                            )
                        } else extractedText?.let { ext ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            ) {
                                Text(
                                    text = ext,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    if (message.contentType != "stego" && message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatTimestamp(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.65f),
                        )
                        if (isSent) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusIndicator(status = message.status, tint = textColor.copy(alpha = 0.75f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StegoBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "隐写",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusIndicator(status: String, tint: androidx.compose.ui.graphics.Color) {
    val (icon: ImageVector?, label) = when (status) {
        "sending" -> Icons.Filled.Schedule to "发送中"
        "sent" -> Icons.Filled.Check to "已发送"
        "delivered" -> Icons.Filled.DoneAll to "已送达"
        "read" -> Icons.Filled.DoneAll to "已读"
        "failed" -> Icons.Filled.Error to "失败"
        else -> null to ""
    }
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
    }
}

private fun bubbleShape(isSent: Boolean): androidx.compose.foundation.shape.CornerBasedShape =
    if (isSent) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

private fun formatTimestamp(ts: String): String = runCatching {
    val epoch = ts.toLongOrNull() ?: return ts
    val ms = if (epoch > 10_000_000_000L) epoch else epoch * 1000
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    sdf.format(java.util.Date(ms))
}.getOrDefault(ts)

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
                Toast.makeText(context, "图像已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// =========================================================================
//  @Preview 区 —— 用于 Android Studio Design 面板快速展示
// =========================================================================

@androidx.compose.ui.tooling.preview.Preview(
    name = "ChatScreen 完整对话",
    showBackground = true,
    widthDp = 400,
    heightDp = 820,
)
@Composable
private fun Preview_ChatScreen_FullConversation() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = {
                        Text(
                            text = "张三",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                ChatInputBar(
                    inputText = "",
                    onInputChange = {},
                    stegoMode = false,
                    stegoEnabled = true,
                    stegoLoading = false,
                    canSend = false,
                    inputEnabled = true,
                    inputByteLength = 0,
                    maxCapacity = 520,
                    overCapacity = false,
                    myCode = "AB12CD34",
                    peerCode = "EF56GH78",
                    keyVisible = false,
                    onToggleKeyVisible = {},
                    onToggleStegoMode = {},
                    onSend = {},
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                E2EEBanner(
                    selfConfigured = true,
                    peerConfigured = true,
                    onConfigureSelf = {},
                    onConfigurePeer = {},
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(sampleMessages()) { msg ->
                        PreviewMessageBubble(msg)
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Banner — 自己未配置", showBackground = true, widthDp = 400)
@Composable
private fun Preview_Banner_SelfMissing() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        E2EEBanner(selfConfigured = false, peerConfigured = false, onConfigureSelf = {}, onConfigurePeer = {})
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Banner — 对方未配置", showBackground = true, widthDp = 400)
@Composable
private fun Preview_Banner_PeerMissing() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        E2EEBanner(selfConfigured = true, peerConfigured = false, onConfigureSelf = {}, onConfigurePeer = {})
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Banner — 已启用", showBackground = true, widthDp = 400)
@Composable
private fun Preview_Banner_Ready() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        E2EEBanner(selfConfigured = true, peerConfigured = true, onConfigureSelf = {}, onConfigurePeer = {})
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "输入栏 — 普通模式", showBackground = true, widthDp = 400)
@Composable
private fun Preview_InputBar_Normal() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        ChatInputBar(
            inputText = "你好",
            onInputChange = {},
            stegoMode = false,
            stegoEnabled = true,
            stegoLoading = false,
            canSend = true,
            inputEnabled = true,
            inputByteLength = 6,
            maxCapacity = 520,
            overCapacity = false,
            myCode = "AB12CD34",
            peerCode = "EF56GH78",
            keyVisible = false,
            onToggleKeyVisible = {},
            onToggleStegoMode = {},
            onSend = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "输入栏 — 隐写模式", showBackground = true, widthDp = 400)
@Composable
private fun Preview_InputBar_StegoMode() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        ChatInputBar(
            inputText = "这是一条秘密消息",
            onInputChange = {},
            stegoMode = true,
            stegoEnabled = true,
            stegoLoading = false,
            canSend = true,
            inputEnabled = true,
            inputByteLength = 24,
            maxCapacity = 520,
            overCapacity = false,
            myCode = "AB12CD34",
            peerCode = "EF56GH78",
            keyVisible = true,
            onToggleKeyVisible = {},
            onToggleStegoMode = {},
            onSend = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "输入栏 — 未配置加密", showBackground = true, widthDp = 400)
@Composable
private fun Preview_InputBar_Disabled() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        ChatInputBar(
            inputText = "",
            onInputChange = {},
            stegoMode = false,
            stegoEnabled = false,
            stegoLoading = false,
            canSend = false,
            inputEnabled = false,
            inputByteLength = 0,
            maxCapacity = 0,
            overCapacity = false,
            myCode = "",
            peerCode = "",
            keyVisible = false,
            onToggleKeyVisible = {},
            onToggleStegoMode = {},
            onSend = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "气泡 — 已发送文本", showBackground = true, widthDp = 400)
@Composable
private fun Preview_Bubble_SentText() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        PreviewMessageBubble(
            MessageEntity(
                id = "1",
                contactId = "c",
                direction = "sent",
                content = "你好,这是我发的一条普通消息",
                contentType = "text",
                status = "read",
                createdAt = (System.currentTimeMillis() / 1000).toString(),
            ),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "气泡 — 已接收文本", showBackground = true, widthDp = 400)
@Composable
private fun Preview_Bubble_ReceivedText() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        PreviewMessageBubble(
            MessageEntity(
                id = "2",
                contactId = "c",
                direction = "received",
                content = "收到!晚点联系",
                contentType = "text",
                status = "delivered",
                createdAt = (System.currentTimeMillis() / 1000).toString(),
            ),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "气泡 — 隐写(占位)", showBackground = true, widthDp = 400)
@Composable
private fun Preview_Bubble_Stego() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        PreviewMessageBubble(
            MessageEntity(
                id = "3",
                contactId = "c",
                direction = "received",
                content = "",
                contentType = "stego",
                stegoImage = null, // Preview 无法解码真实图片,走占位分支
                status = "delivered",
                createdAt = (System.currentTimeMillis() / 1000).toString(),
            ),
            extractedText = "这是解密出的秘密",
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "气泡 — 已撤回", showBackground = true, widthDp = 400)
@Composable
private fun Preview_Bubble_Revoked() {
    com.stegoapp.app.ui.theme.StegoAppTheme {
        PreviewMessageBubble(
            MessageEntity(
                id = "4",
                contactId = "c",
                direction = "sent",
                content = "原内容",
                contentType = "text",
                status = "read",
                revoked = true,
                createdAt = (System.currentTimeMillis() / 1000).toString(),
            ),
        )
    }
}

/**
 * 仅供 @Preview 使用的纯 UI 气泡,与 MessageBubble 视觉保持一致但不依赖 ChatViewModel。
 * 如果需要模拟"已提取"的展示,传入 [extractedText]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewMessageBubble(
    message: MessageEntity,
    extractedText: String? = null,
) {
    val isSent = message.direction == "sent"
    val bubbleColor = if (isSent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isSent) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
    ) {
        Surface(shape = bubbleShape(isSent), color = bubbleColor) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 280.dp),
            ) {
                if (message.revoked) {
                    Text(
                        text = "消息已撤回",
                        color = textColor.copy(alpha = 0.7f),
                        fontStyle = FontStyle.Italic,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    if (message.contentType == "stego") {
                        Box(
                            modifier = Modifier.size(256.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "[隐写图像占位 256×256]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            StegoBadge(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp),
                            )
                        }
                        extractedText?.let { ext ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            ) {
                                Text(
                                    text = ext,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    if (message.contentType != "stego" && message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatTimestamp(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.65f),
                        )
                        if (isSent) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusIndicator(status = message.status, tint = textColor.copy(alpha = 0.75f))
                        }
                    }
                }
            }
        }
    }
}

private fun sampleMessages(): List<MessageEntity> {
    val now = System.currentTimeMillis() / 1000
    return listOf(
        MessageEntity("m1", "c", "received", "嗨!在忙吗?", "text", null, "delivered", 0, false, false, (now - 1800).toString()),
        MessageEntity("m2", "c", "sent", "刚开完会,什么事?", "text", null, "read", 0, false, false, (now - 1700).toString()),
        MessageEntity("m3", "c", "received", "想跟你同步下今天的测试进展 👀", "text", null, "delivered", 0, false, false, (now - 1500).toString()),
        MessageEntity("m4", "c", "sent", "好,我刚把端到端加密推到 main,你那边拉下来试试", "text", null, "read", 0, false, false, (now - 1400).toString()),
        MessageEntity("m5", "c", "received", "", "stego", null, "delivered", 0, false, false, (now - 900).toString()),
        MessageEntity("m6", "c", "sent", "收到,看了下流程都对", "text", null, "delivered", 0, false, false, (now - 300).toString()),
        MessageEntity("m7", "c", "sent", "原内容被撤回", "text", null, "read", 0, false, true, (now - 120).toString()),
        MessageEntity("m8", "c", "sent", "发送中...", "text", null, "sending", 0, false, false, now.toString()),
    )
}

