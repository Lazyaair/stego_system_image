package com.stegoapp.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stegoapp.app.ui.screens.EmbedScreen
import com.stegoapp.app.ui.screens.ExtractScreen

sealed class Screen(val route: String) {
    object Embed : Screen("embed")
    object Extract : Screen("extract")
}

@Composable
fun NavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = Screen.Embed.route
        ) {
            composable(Screen.Embed.route) {
                EmbedScreen()
            }
            composable(Screen.Extract.route) {
                ExtractScreen()
            }
        }
    }
}
