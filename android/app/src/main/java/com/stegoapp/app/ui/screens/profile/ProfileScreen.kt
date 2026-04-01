package com.stegoapp.app.ui.screens.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onLogout: () -> Unit
) {
    val username by authViewModel.username.collectAsState()
    var inviteCode by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        try {
            val res = ApiClient.inviteApi.getMyCode()
            inviteCode = res.code
        } catch (_: Exception) {}
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Profile") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(60.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            username?.firstOrNull()?.uppercase() ?: "?",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(username ?: "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("My Invite Code", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text("Share this code so friends can add you.", color = Color.Gray, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        inviteCode.ifEmpty { "Loading..." },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(inviteCode))
                }) {
                    Text("Copy")
                }
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            try {
                                val res = ApiClient.inviteApi.resetCode()
                                inviteCode = res.code
                            } catch (_: Exception) {}
                            loading = false
                        }
                    },
                    enabled = !loading
                ) {
                    Text("Reset")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { authViewModel.logout(onLogout) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }
        }
    }
}
