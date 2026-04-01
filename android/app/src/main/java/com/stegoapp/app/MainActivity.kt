package com.stegoapp.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.data.local.TokenStore
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
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            Toast.makeText(context, "Logged in on another device", Toast.LENGTH_LONG).show()
        }
    }

    val startDestination = if (isAuthenticated) Screen.ChatList.route else Screen.Login.route

    val navItems = listOf(
        NavItem(Screen.ChatList.route, "Messages", Icons.Default.Email),
        NavItem(Screen.Contacts.route, "Contacts", Icons.Default.Person),
        NavItem(Screen.Embed.route, "Stego", Icons.Default.Lock),
        NavItem(Screen.Profile.route, "Profile", Icons.Default.AccountCircle),
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
