package com.aashishgodambe.arcana.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aashishgodambe.arcana.core.data.repository.CollectibleRepository
import com.aashishgodambe.arcana.feature.benchmark.BenchmarkScreen
import com.aashishgodambe.arcana.feature.capture.CameraScreen
import com.aashishgodambe.arcana.feature.capture.CaptureReviewScreen
import com.aashishgodambe.arcana.feature.collection.CategoryScreen
import com.aashishgodambe.arcana.feature.detail.DetailScreen
import com.aashishgodambe.arcana.feature.settings.SettingsScreen
import com.aashishgodambe.arcana.feature.onboarding.ImportScreen
import com.aashishgodambe.arcana.feature.onboarding.OnboardingWelcomeScreen
import com.aashishgodambe.arcana.feature.portfolio.PortfolioScreen
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val ROUTER = "router"
    const val WELCOME = "welcome"
    const val IMPORT = "import/{uri}"
    const val PORTFOLIO = "portfolio"
    const val CATEGORY = "category/{list}"
    const val DETAIL = "detail/{localId}"
    const val SETTINGS = "settings"
    const val BENCHMARK = "benchmark"
    const val CAPTURE_CAMERA = "capture/camera"
    const val CAPTURE_REVIEW = "capture/review"
    fun import(uri: String) = "import/${Uri.encode(uri)}"
    fun category(list: String) = "category/${Uri.encode(list)}"
    fun detail(localId: Long) = "detail/$localId"
}

@Composable
fun ArcanaNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.ROUTER) {
        composable(Routes.ROUTER) {
            RouterScreen(
                onHasItems = { nav.navigate(Routes.PORTFOLIO) { popUpTo(Routes.ROUTER) { inclusive = true } } },
                onEmpty = { nav.navigate(Routes.WELCOME) { popUpTo(Routes.ROUTER) { inclusive = true } } },
            )
        }
        composable(Routes.WELCOME) {
            OnboardingWelcomeScreen(
                onImportPicked = { uri -> nav.navigate(Routes.import(uri.toString())) },
                onStartFresh = { nav.navigate(Routes.PORTFOLIO) { popUpTo(Routes.WELCOME) { inclusive = true } } },
            )
        }
        composable(Routes.IMPORT, arguments = listOf(navArgument("uri") { type = NavType.StringType })) {
            ImportScreen(
                onComplete = { nav.navigate(Routes.PORTFOLIO) { popUpTo(Routes.WELCOME) { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PORTFOLIO) {
            PortfolioScreen(
                onGroupClick = { name -> nav.navigate(Routes.category(name)) },
                onItemClick = { id -> nav.navigate(Routes.detail(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenCapture = { nav.navigate(Routes.CAPTURE_CAMERA) },
            )
        }
        composable(Routes.CATEGORY, arguments = listOf(navArgument("list") { type = NavType.StringType })) {
            CategoryScreen(
                onItemClick = { id -> nav.navigate(Routes.detail(id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("localId") { type = NavType.LongType })) {
            DetailScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenBenchmark = { nav.navigate(Routes.BENCHMARK) },
            )
        }
        composable(Routes.BENCHMARK) {
            BenchmarkScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CAPTURE_CAMERA) {
            CameraScreen(
                onClose = { nav.popBackStack() },
                // Drop the camera off the back stack so Review returns straight to Portfolio.
                onCaptured = {
                    nav.navigate(Routes.CAPTURE_REVIEW) {
                        popUpTo(Routes.CAPTURE_CAMERA) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CAPTURE_REVIEW) {
            CaptureReviewScreen(
                onClose = { nav.popBackStack() },
                // Rescan (e.g. via barcode) replaces Review with a fresh camera.
                onRescan = {
                    nav.navigate(Routes.CAPTURE_CAMERA) {
                        popUpTo(Routes.CAPTURE_REVIEW) { inclusive = true }
                    }
                },
                // After save, land on the new item's Detail (capture flow off the back stack).
                onSaved = { localId ->
                    nav.navigate(Routes.detail(localId)) {
                        popUpTo(Routes.CAPTURE_REVIEW) { inclusive = true }
                    }
                },
            )
        }
    }
}

@Composable
private fun RouterScreen(
    onHasItems: () -> Unit,
    onEmpty: () -> Unit,
    vm: RouterViewModel = hiltViewModel(),
) {
    val hasItems by vm.hasItems.collectAsStateWithLifecycle()
    LaunchedEffect(hasItems) {
        when (hasItems) {
            true -> onHasItems()
            false -> onEmpty()
            null -> Unit
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = ArcanaTheme.colors.iris)
    }
}

@HiltViewModel
class RouterViewModel @Inject constructor(repository: CollectibleRepository) : ViewModel() {
    val hasItems: StateFlow<Boolean?> = repository.observeCount()
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
