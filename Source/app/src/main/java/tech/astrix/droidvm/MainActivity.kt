package tech.astrix.droidvm

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import tech.astrix.droidvm.ui.UpdateDialog
import tech.astrix.droidvm.update.UpdateResult
import tech.astrix.droidvm.update.UpdateChecker
import tech.astrix.droidvm.update.ReleaseInfo
import tech.astrix.droidvm.i18n.tr
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import tech.astrix.droidvm.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tech.astrix.droidvm.ui.AboutScreen
import tech.astrix.droidvm.ui.ImagesScreen
import tech.astrix.droidvm.ui.SettingsScreen
import tech.astrix.droidvm.ui.VmDetailScreen
import tech.astrix.droidvm.ui.VmEditorScreen
import tech.astrix.droidvm.ui.VmListScreen
import tech.astrix.droidvm.ui.theme.DroidVmTheme

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val settings by DroidVmApp.repo.settings.collectAsStateWithLifecycle()
            DroidVmTheme(settings.theme, settings.dynamicColor) { AppRoot() }
        }
    }
}

private var launchChecked = false

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("vms", "Machines", Icons.Outlined.Computer),
    Tab("images", "Images", Icons.Outlined.Album),
    Tab("settings", "Settings", Icons.Outlined.Settings),
    Tab("about", "About", Icons.Outlined.Info),
)

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showNav = tabs.any { it.route == route }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    var update by remember { mutableStateOf<ReleaseInfo?>(null) }
    LaunchedEffect(Unit) {
        if (!launchChecked) {
            launchChecked = true
            (UpdateChecker.check() as? UpdateResult.Found)?.release?.takeIf { it.isNewer && !it.prerelease }?.let { update = it }
        }
    }
    update?.let { UpdateDialog(it) { update = null } }

    fun go(r: String) = nav.navigate(r) { popUpTo("vms") { saveState = true }; launchSingleTop = true; restoreState = true }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            if (showNav) NavigationRail {
                tabs.forEach { t ->
                    NavigationRailItem(selected = route == t.route, onClick = { go(t.route) },
                        icon = { Icon(t.icon, null) }, label = { Text(tr(t.label)) })
                }
            }
            NavGraph(nav, Modifier.weight(1f))
        }
    } else {
        Scaffold(bottomBar = {
            if (showNav) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = { go(t.route) },
                        icon = { Icon(t.icon, null) }, label = { Text(tr(t.label)) })
                }
            }
        }) { pad -> NavGraph(nav, Modifier.padding(bottom = pad.calculateBottomPadding())) }
    }
}

@Composable
private fun NavGraph(nav: NavHostController, modifier: Modifier) {
    NavHost(
        nav, startDestination = "vms", modifier = modifier,
        enterTransition = { fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(320), initialOffset = { it / 8 }) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(320), targetOffset = { it / 8 }) },
    ) {
        composable("vms") { VmListScreen(onOpen = { nav.navigate("vm/$it") }, onCreate = { nav.navigate("edit/new") }) }
        composable("images") { ImagesScreen() }
        composable("settings") { SettingsScreen() }
        composable("about") { AboutScreen() }
        composable("vm/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            VmDetailScreen(e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("edit/${e.arguments?.getString("id")}") })
        }
        composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            VmEditorScreen(e.arguments?.getString("id") ?: "new", onDone = { id ->
                nav.popBackStack()
                if (id != null && e.arguments?.getString("id") == "new") nav.navigate("vm/$id")
            })
        }
    }
}
