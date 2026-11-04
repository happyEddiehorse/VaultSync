package com.mountriv.vaultsync.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.repo.RepoHistory
import com.mountriv.vaultsync.ui.ChipTone
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.StatusChip
import com.mountriv.vaultsync.ui.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 50

/** 离列表末尾还剩这么多条时自动加载下一页。 */
private const val PREFETCH = 10

/** 提交列表放在 ViewModel 里：从提交详情返回时不重新加载，列表滚动位置得以保留。 */
class HistoryViewModel(repoId: String) : ViewModel() {
    val spec = AppGraph.repos.get(repoId)
    var commits by mutableStateOf<List<RepoHistory.CommitInfo>>(emptyList())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        loadMore()
    }

    fun loadMore() {
        val s = spec ?: return
        if (loading) return
        loading = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.history(s, commits.size, PAGE_SIZE) } }
            r.onSuccess { page -> commits = commits + page.commits; hasMore = page.hasMore; error = null }
                .onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }
}

/** 提交历史（docs/UI.md §4.2）：当前分支最近的提交，滑到接近底部时自动加载下一页；标出还没推送到远端的提交与合并提交。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(repoId: String, onBack: () -> Unit, onOpenCommit: (String) -> Unit) {
    val vm: HistoryViewModel = viewModel(key = "history:$repoId") { HistoryViewModel(repoId) }
    val spec = vm.spec
    val commits = vm.commits
    val hasMore = vm.hasMore
    val loading = vm.loading
    val error = vm.error
    fun loadMore() = vm.loadMore()
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1 - PREFETCH
        }
    }
    // 加载失败后不自动重试（免得反复请求），改由底部的"重试"
    LaunchedEffect(nearEnd, hasMore, loading, error) {
        if (nearEnd && hasMore && !loading && error == null) loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.history_title))
                        spec?.let { Text("${it.name} · ${it.branch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { ErrorCard(stringResource(R.string.history_load_failed), it) }
                    if (!loading && commits.isEmpty() && error == null) {
                        Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (commits.isNotEmpty()) {
                        Text(stringResource(R.string.history_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(commits, key = { it.id }) { c ->
                CommitRow(c, onClick = { onOpenCommit(c.id) })
                HorizontalDivider()
            }
            item {
                // 底部一行固定高度：还有更早的提交时一直显示转圈（加载很快，用户滑到底时看得出后面还有），加载完直接接上；
                // 到头写"没有更早的提交了"。首次加载时列表是空的，用整行的进度条
                Box(Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    when {
                        commits.isEmpty() -> if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error != null && !loading -> TextButton(onClick = ::loadMore) { Text(stringResource(R.string.action_retry)) }
                        loading || hasMore -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else -> Text(
                            stringResource(R.string.history_end),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(c: RepoHistory.CommitInfo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.summary.ifBlank { stringResource(R.string.history_no_message) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatTime(LocalContext.current, c.time)} · ${c.authorName} · ${c.shortId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            !c.pushed -> StatusChip(stringResource(R.string.history_not_pushed), ChipTone.WARN)
            c.isMerge -> StatusChip(stringResource(R.string.history_merge), ChipTone.NEUTRAL)
        }
    }
}
