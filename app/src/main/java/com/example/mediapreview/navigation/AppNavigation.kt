package com.example.mediapreview.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.mediapreview.ui.gallery.GalleryScreen
import com.example.mediapreview.ui.gallery.GalleryViewModel
import com.example.mediapreview.ui.music.MusicViewModel
import com.example.mediapreview.ui.settings.SettingsScreen
import com.example.mediapreview.ui.settings.SettingsViewModel
import com.example.mediapreview.ui.viewer.MediaPagerScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    val activity = LocalContext.current as ComponentActivity
    val sharedViewModel: GalleryViewModel = viewModel(viewModelStoreOwner = activity)
    val musicViewModel: MusicViewModel = viewModel(viewModelStoreOwner = activity)
    val settingsViewModel: SettingsViewModel = viewModel(viewModelStoreOwner = activity)

    NavHost(
        navController = navController,
        startDestination = "gallery",
        enterTransition  = { slideInHorizontally { it } + fadeIn() },
        exitTransition   = { slideOutHorizontally { -it / 3 } + fadeOut(targetAlpha = 0.6f) },
        popEnterTransition  = { slideInHorizontally { -it / 3 } + fadeIn(initialAlpha = 0.6f) },
        popExitTransition   = { slideOutHorizontally { it } + fadeOut() }
    ) {

        composable("gallery") {
            GalleryScreen(
                viewModel = sharedViewModel,
                musicViewModel = musicViewModel,
                onOpenSettings = { navController.navigate("settings") },
                onItemClick = { item ->
                    val mediaItems = sharedViewModel.getCurrentMediaItems()
                    sharedViewModel.setViewerItems(mediaItems)
                    val index = mediaItems.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                    navController.navigate("pager/$index")
                }
            )
        }

        composable(
            route = "pager/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStack ->
            val index = backStack.arguments?.getInt("index") ?: 0
            MediaPagerScreen(
                initialIndex = index,
                viewModel = sharedViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
