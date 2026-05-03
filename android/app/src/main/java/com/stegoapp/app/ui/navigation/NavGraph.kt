package com.stegoapp.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stegoapp.app.ui.screens.auth.AuthScreen
import com.stegoapp.app.ui.screens.stego.StegoToolScreen
import com.stegoapp.app.ui.screens.chat.ChatListScreen
import com.stegoapp.app.ui.screens.chat.ChatScreen
import com.stegoapp.app.ui.screens.contact.AddContactScreen
import com.stegoapp.app.ui.screens.contact.ContactDetailScreen
import com.stegoapp.app.ui.screens.contact.ContactsScreen
import com.stegoapp.app.ui.screens.contact.RequestsScreen
import com.stegoapp.app.ui.screens.profile.ProfileScreen
import com.stegoapp.app.ui.viewmodel.AuthViewModel
import com.stegoapp.app.ui.viewmodel.ChatViewModel
import com.stegoapp.app.ui.viewmodel.ContactViewModel

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object ChatList : Screen("chats")
    object Chat : Screen("chat/{contactId}/{contactName}") {
        fun createRoute(contactId: String, contactName: String) =
            "chat/$contactId/${Uri.encode(contactName)}"
    }
    object Contacts : Screen("contacts")
    object AddContact : Screen("contacts/add")
    object Profile : Screen("profile")
    object StegoTool : Screen("stego")
    object Requests : Screen("requests")
    object ContactDetail : Screen("contact/{userId}") {
        fun createRoute(userId: String) = "contact/$userId"
    }
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
        composable(Screen.Auth.route) {
            AuthScreen(
                authViewModel = authViewModel,
                onAuthSuccess = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                },
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
                chatViewModel = chatViewModel,
                onAddContact = { navController.navigate(Screen.AddContact.route) },
                onOpenRequests = { navController.navigate(Screen.Requests.route) },
                onOpenDetail = { userId ->
                    navController.navigate(Screen.ContactDetail.createRoute(userId))
                },
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
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.StegoTool.route) {
            StegoToolScreen()
        }
        composable(Screen.Requests.route) {
            RequestsScreen(
                chatViewModel = chatViewModel,
                contactViewModel = contactViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.ContactDetail.route) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: return@composable
            ContactDetailScreen(
                userId = userId,
                contactViewModel = contactViewModel,
                onBack = { navController.popBackStack() },
                onOpenChat = { openUserId ->
                    val name = contactViewModel.contacts.value
                        .find { it.userId == openUserId }?.let {
                            it.nickname.ifEmpty { it.username }
                        } ?: "Chat"
                    navController.navigate(Screen.Chat.createRoute(openUserId, name)) {
                        popUpTo(Screen.ContactDetail.route) { inclusive = true }
                    }
                },
            )
        }
    }
}
