package com.stegoapp.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.ui.navigation.NavGraph
import com.stegoapp.app.ui.navigation.Screen
import com.stegoapp.app.ui.theme.StegoAppTheme
import com.stegoapp.app.ui.viewmodel.AuthViewModel
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import com.stegoapp.app.ui.viewmodel.ContactViewModel
import kotlinx.coroutines.flow.firstOrNull

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()
    private val contactViewModel: ContactViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ApiClient.init(this)

        setContent {
            StegoAppTheme {
                MainApp(authViewModel, chatViewModel, contactViewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        chatViewModel.disconnectWebSocket()
    }
}

data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun MainApp(
    authViewModel: AuthViewModel,
    chatViewModel: ChatViewModel,
    contactViewModel: ContactViewModel
) {
    val navController = rememberNavController()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()
    val userId by authViewModel.userId.collectAsState()
    val username by authViewModel.username.collectAsState()

    val context = LocalContext.current

    // Verify the stored token against the server on startup. If the token is
    // expired/revoked the server returns 401; clear it and redirect to Auth.
    LaunchedEffect(Unit) {
        authViewModel.verifySession {
            Toast.makeText(context, "登录已过期,请重新登录", Toast.LENGTH_LONG).show()
            navController.navigate(Screen.Auth.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Connect WebSocket when authenticated
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            val token = com.stegoapp.app.data.local.TokenStore(
                navController.context
            ).token.firstOrNull()
            if (token != null) {
                chatViewModel.connectWebSocket(token)
            }
        } else {
            chatViewModel.disconnectWebSocket()
        }
    }

    // Handle being kicked by another device
    LaunchedEffect(Unit) {
        chatViewModel.kicked.collect {
            authViewModel.onKicked {
                navController.navigate(Screen.Auth.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            Toast.makeText(context, "账号已在其他设备登录", Toast.LENGTH_LONG).show()
        }
    }

    val startDestination = if (isAuthenticated) Screen.ChatList.route else Screen.Auth.route

    val navItems = listOf(
        NavItem(Screen.ChatList.route, "消息", Icons.AutoMirrored.Filled.Chat),
        NavItem(Screen.Contacts.route, "联系人", Icons.Filled.Contacts),
        NavItem(Screen.StegoTool.route, "隐写工具", Icons.Filled.EnhancedEncryption),
        NavItem(Screen.Profile.route, "我的", Icons.Filled.Person),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = isAuthenticated && currentRoute in navItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(Screen.ChatList.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavGraph(
                navController = navController,
                startDestination = startDestination,
                authViewModel = authViewModel,
                chatViewModel = chatViewModel,
                contactViewModel = contactViewModel,
                currentUserId = userId ?: "",
                currentUsername = username ?: ""
            )
        }
    }
}
