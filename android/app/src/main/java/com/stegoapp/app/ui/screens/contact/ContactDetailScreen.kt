package com.stegoapp.app.ui.screens.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stegoapp.app.data.local.entity.ContactEntity
import com.stegoapp.app.ui.viewmodel.ContactViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    userId: String,
    contactViewModel: ContactViewModel,
    onBack: () -> Unit,
    onOpenChat: (userId: String) -> Unit,
) {
    val contacts by contactViewModel.contacts.collectAsState()
    val contact = contacts.find { it.userId == userId }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val displayName = contact?.nickname?.ifEmpty { contact.username } ?: contact?.username ?: "联系人"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (contact != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "删除联系人",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (contact == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "联系人不存在或已删除",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            HeaderBlock(displayName = displayName, username = contact.username)
            Spacer(modifier = Modifier.height(24.dp))
            InfoCard(contact = contact)
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(32.dp))
            ChatCTA(onOpenChat = { onOpenChat(contact.userId) })
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDeleteDialog && contact != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除联系人?") },
            text = { Text("将从通讯录移除 $displayName,聊天记录也会一并删除。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    contactViewModel.removeContact(contact.userId)
                    onBack()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HeaderBlock(displayName: String, username: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(112.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "@$username",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoCard(contact: ContactEntity) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            InfoRow(
                icon = Icons.Outlined.AlternateEmail,
                label = "用户 ID",
                value = contact.userId,
            )
            InfoRow(
                icon = Icons.Outlined.Schedule,
                label = "添加时间",
                value = formatAddedAt(contact.addedAt),
            )
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ChatCTA(onOpenChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Button(
            onClick = onOpenChat,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("发起聊天", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatAddedAt(raw: String): String {
    val millis = raw.toLongOrNull() ?: return raw.ifEmpty { "未记录" }
    return runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        fmt.format(Date(millis))
    }.getOrDefault(raw)
}

