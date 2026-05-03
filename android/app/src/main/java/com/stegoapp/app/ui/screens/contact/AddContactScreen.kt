package com.stegoapp.app.ui.screens.contact

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.LabelImportant
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.api.InviteLookupResponse
import com.stegoapp.app.ui.viewmodel.ContactViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REMARK_MAX = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    contactViewModel: ContactViewModel,
    onBack: () -> Unit,
    onContactAdded: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<InviteLookupResponse?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val vmError by contactViewModel.error.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(code, remark) {
        localError = null
        if (vmError != null) contactViewModel.clearError()
    }

    val displayError = localError ?: vmError

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "添加联系人",
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
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "输入对方的邀请码,向 TA 发起好友请求",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    preview = null
                },
                label = { Text("邀请码") },
                placeholder = { Text("例如 A3F9K7XM") },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (code.isBlank()) {
                        localError = "请输入邀请码"
                        return@Button
                    }
                    scope.launch {
                        searching = true
                        localError = null
                        preview = null
                        try {
                            val res = withContext(Dispatchers.IO) {
                                ApiClient.inviteApi.lookupCode(code.trim())
                            }
                            preview = res
                        } catch (e: Exception) {
                            localError = e.message ?: "未找到该邀请码"
                        } finally {
                            searching = false
                        }
                    }
                },
                enabled = !searching && code.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("搜索中...")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("搜索", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (displayError != null && preview == null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = displayError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            preview?.let {
                Spacer(modifier = Modifier.height(24.dp))
                PreviewCard(info = it)
                Spacer(modifier = Modifier.height(24.dp))
                RemarkSection(remark = remark, onRemarkChange = { remark = it.take(REMARK_MAX) })
                Spacer(modifier = Modifier.height(24.dp))

                if (displayError != null) {
                    Text(
                        text = displayError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        sending = true
                        contactViewModel.addContactByCode(code.trim()) { contact ->
                            sending = false
                            onContactAdded(contact.userId)
                        }
                    },
                    enabled = !sending,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("发送中...")
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("发送好友请求", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "对方同意后即可开始加密聊天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PreviewCard(info: InviteLookupResponse) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = info.username.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.username,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.LabelImportant,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ID ${info.user_id}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemarkSection(remark: String, onRemarkChange: (String) -> Unit) {
    Column {
        Text(
            text = "附言(可选)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = remark,
            onValueChange = onRemarkChange,
            placeholder = { Text("让对方知道你是谁,例如:我是 Alice,在 Pulsar 论坛认识") },
            minLines = 3,
            maxLines = 5,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${remark.length} / $REMARK_MAX",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}
