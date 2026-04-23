package com.stegoapp.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stegoapp.app.ui.screens.EmbedScreen
import com.stegoapp.app.ui.screens.ExtractScreen
import com.stegoapp.app.ui.screens.auth.LoginScreen
import com.stegoapp.app.ui.screens.auth.RegisterScreen
import com.stegoapp.app.ui.screens.chat.ChatListScreen
import com.stegoapp.app.ui.screens.chat.ChatScreen
import com.stegoapp.app.ui.screens.contact.AddContactScreen
import com.stegoapp.app.ui.screens.contact.ContactsScreen
import com.stegoapp.app.ui.screens.profile.ProfileScreen
import com.stegoapp.app.ui.viewmodel.AuthViewModel
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import com.stegoapp.app.ui.viewmodel.ContactViewModel

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Register : Screen("register")
    object ChatList : Screen("chats")
    object Chat : Screen("chat/{contactId}/{contactName}") {
        fun createRoute(contactId: String, contactName: String) =
            "chat/$contactId/${Uri.encode(contactName)}"
    }
    object Contacts : Screen("contacts")
    object AddContact : Screen("contacts/add")
    object Profile : Screen("profile")
    object Embed : Screen("embed")
    object Extract : Screen("extract")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    authViewModel: AuthViewModel,
    chatViewModel: ChatViewModel,
    contactViewModel: ContactViewModel,
    currentUserId: String,
    currentUsername: String
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Login.route) {
            LoginScreen(
                authViewModel = authViewModel,
                onLoginSuccess = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                authViewModel = authViewModel,
                onRegisterSuccess = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Register.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }
        composable(Screen.ChatList.route) {
            ChatListScreen(
                contactViewModel = contactViewModel,
                chatViewModel = chatViewModel,
                onOpenChat = { userId ->
                    val name = contactViewModel.contacts.value
                        .find { it.userId == userId }?.let {
                            it.nickname.ifEmpty { it.username }
                        } ?: "Chat"
                    navController.navigate(Screen.Chat.createRoute(userId, name))
                }
            )
        }
        composable(Screen.Chat.route) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
            val contactName = backStackEntry.arguments?.getString("contactName") ?: "Chat"
            ChatScreen(
                contactId = contactId,
                contactName = contactName,
                chatViewModel = chatViewModel,
                currentUserId = currentUserId,
                currentUsername = currentUsername,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Contacts.route) {
            ContactsScreen(
                contactViewModel = contactViewModel,
                onOpenChat = { userId ->
                    val name = contactViewModel.contacts.value
                        .find { it.userId == userId }?.let {
                            it.nickname.ifEmpty { it.username }
                        } ?: "Chat"
                    navController.navigate(Screen.Chat.createRoute(userId, name))
                },
                onAddContact = { navController.navigate(Screen.AddContact.route) }
            )
        }
        composable(Screen.AddContact.route) {
            AddContactScreen(
                contactViewModel = contactViewModel,
                onBack = { navController.popBackStack() },
                onContactAdded = { userId ->
                    // Notify the other user via WebSocket
                    chatViewModel.sendFirstContact(userId, currentUserId, currentUsername)
                    navController.popBackStack()
                    val name = contactViewModel.contacts.value
                        .find { it.userId == userId }?.username ?: "Chat"
                    navController.navigate(Screen.Chat.createRoute(userId, name))
                }
            )
        }
        composable(Screen.Profile.route) {
            ProfileScreen(
                authViewModel = authViewModel,
                onLogout = {
                    chatViewModel.disconnectWebSocket()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Embed.route) {
            EmbedScreen(onNavigateToExtract = {
                navController.navigate(Screen.Extract.route) {
                    popUpTo(Screen.Embed.route) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
        composable(Screen.Extract.route) {
            ExtractScreen(onNavigateToEmbed = {
                navController.navigate(Screen.Embed.route) {
                    popUpTo(Screen.Extract.route) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
    }
}
