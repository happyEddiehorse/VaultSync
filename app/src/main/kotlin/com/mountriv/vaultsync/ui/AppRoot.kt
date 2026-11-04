package com.mountriv.vaultsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mountriv.vaultsync.ui.screens.addrepo.AddRepoScreen
import com.mountriv.vaultsync.ui.screens.conflicts.ConflictFileScreen
import com.mountriv.vaultsync.ui.screens.conflicts.ConflictsScreen
import com.mountriv.vaultsync.ui.screens.history.CommitDetailScreen
import com.mountriv.vaultsync.ui.screens.history.HistoryScreen
import com.mountriv.vaultsync.ui.screens.logs.CrashReportScreen
import com.mountriv.vaultsync.ui.screens.logs.LogDetailScreen
import com.mountriv.vaultsync.ui.screens.logs.LogsScreen
import com.mountriv.vaultsync.ui.screens.repodetail.RepoDetailScreen
import com.mountriv.vaultsync.ui.screens.repolist.RepoListScreen
import com.mountriv.vaultsync.ui.screens.reposettings.RepoSettingsScreen
import com.mountriv.vaultsync.ui.screens.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** 类型安全路由（docs/UI.md §1）。 */
object Routes {
    @Serializable
    object RepoList

    @Serializable
    object AddRepo

    @Serializable
    data class RepoDetail(val repoId: String)

    @Serializable
    data class RepoSettings(val repoId: String)

    @Serializable
    data class History(val repoId: String)

    @Serializable
    data class CommitDetail(val repoId: String, val commitId: String)

    @Serializable
    data class Logs(val repoId: String)

    @Serializable
    data class LogDetail(val repoId: String, val fileName: String)

    @Serializable
    data class CrashReport(val fileName: String)

    @Serializable
    data class Conflicts(val repoId: String)

    /** [path] 是仓库内相对路径；navigation 会自动做 URI 编码。 */
    @Serializable
    data class ConflictFile(val repoId: String, val path: String)

    @Serializable
    object Settings
}

/** [onLanguageChanged]：语言设置可能变了（设置页切换语言、从备份恢复），由 Activity 决定是否重建界面（D-030）。 */
@Composable
fun AppRoot(openRepoId: String?, onOpenRepoConsumed: () -> Unit, onLanguageChanged: () -> Unit) {
    val nav: NavHostController = rememberNavController()

    // 从通知进入：打开对应仓库详情
    LaunchedEffect(openRepoId) {
        if (openRepoId != null) {
            nav.navigate(Routes.RepoDetail(openRepoId)) { launchSingleTop = true }
            onOpenRepoConsumed()
        }
    }

    NavHost(navController = nav, startDestination = Routes.RepoList) {
        composable<Routes.RepoList> {
            RepoListScreen(
                onAddRepo = { nav.navigate(Routes.AddRepo) },
                onOpenRepo = { nav.navigate(Routes.RepoDetail(it)) },
                onOpenSettings = { nav.navigate(Routes.Settings) },
                onOpenCrashReport = { nav.navigate(Routes.CrashReport(it)) },
            )
        }
        composable<Routes.AddRepo> {
            AddRepoScreen(
                onClose = { nav.popBackStack() },
                onOpenRepo = { repoId ->
                    nav.navigate(Routes.RepoDetail(repoId)) { popUpTo<Routes.RepoList>() }
                },
                onOpenSettings = { nav.navigate(Routes.Settings) },
            )
        }
        composable<Routes.RepoDetail> { entry ->
            val route = entry.toRoute<Routes.RepoDetail>()
            RepoDetailScreen(
                repoId = route.repoId,
                onBack = { nav.popBackStack() },
                onOpenLogs = { nav.navigate(Routes.Logs(route.repoId)) },
                onOpenHistory = { nav.navigate(Routes.History(route.repoId)) },
                onOpenConflicts = { nav.navigate(Routes.Conflicts(route.repoId)) },
                onOpenRepoSettings = { nav.navigate(Routes.RepoSettings(route.repoId)) },
            )
        }
        composable<Routes.RepoSettings> { entry ->
            val route = entry.toRoute<Routes.RepoSettings>()
            RepoSettingsScreen(
                repoId = route.repoId,
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Routes.Settings) },
                onRemoved = { nav.popBackStack<Routes.RepoList>(inclusive = false) },
            )
        }
        composable<Routes.History> { entry ->
            val route = entry.toRoute<Routes.History>()
            HistoryScreen(
                repoId = route.repoId,
                onBack = { nav.popBackStack() },
                onOpenCommit = { nav.navigate(Routes.CommitDetail(route.repoId, it)) },
            )
        }
        composable<Routes.CommitDetail> { entry ->
            val route = entry.toRoute<Routes.CommitDetail>()
            CommitDetailScreen(repoId = route.repoId, commitId = route.commitId, onBack = { nav.popBackStack() })
        }
        composable<Routes.Conflicts> { entry ->
            val route = entry.toRoute<Routes.Conflicts>()
            ConflictsScreen(
                repoId = route.repoId,
                onBack = { nav.popBackStack() },
                onOpenFile = { nav.navigate(Routes.ConflictFile(route.repoId, it)) },
                onMergeCompleted = { nav.popBackStack<Routes.RepoDetail>(inclusive = false) },
            )
        }
        composable<Routes.ConflictFile> { entry ->
            val route = entry.toRoute<Routes.ConflictFile>()
            ConflictFileScreen(repoId = route.repoId, path = route.path, onBack = { nav.popBackStack() })
        }
        composable<Routes.Logs> { entry ->
            val route = entry.toRoute<Routes.Logs>()
            LogsScreen(
                repoId = route.repoId,
                onBack = { nav.popBackStack() },
                onOpenLog = { nav.navigate(Routes.LogDetail(route.repoId, it)) },
            )
        }
        composable<Routes.LogDetail> { entry ->
            val route = entry.toRoute<Routes.LogDetail>()
            LogDetailScreen(repoId = route.repoId, fileName = route.fileName, onBack = { nav.popBackStack() })
        }
        composable<Routes.CrashReport> { entry ->
            val route = entry.toRoute<Routes.CrashReport>()
            CrashReportScreen(fileName = route.fileName, onBack = { nav.popBackStack() })
        }
        composable<Routes.Settings> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenCrashReport = { nav.navigate(Routes.CrashReport(it)) },
                onLanguageChanged = onLanguageChanged,
            )
        }
    }
}
