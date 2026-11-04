package com.mountriv.vaultsync.data

import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.store.JsonFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * 一个 JSON 配置文件对应的内存状态（docs/ANDROID_PLATFORM.md §6）。
 * 启动时读一次；读失败不覆盖原文件（[JsonFileStore] 已把它改名为 `.bak-*`），把错误暴露在 [loadError] 供首页显示。
 * 所有写入在调用线程同步完成（文件很小），并更新 [state]。
 */
open class ConfigFileState<T>(
    file: File,
    serializer: KSerializer<T>,
    version: Int,
    private val default: T,
    private val logger: SyncLogger,
) {
    private val store = JsonFileStore(file, serializer, version)

    private val _state = MutableStateFlow(default)
    val state: StateFlow<T> = _state.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)

    /** 启动时读取失败的说明（含备份文件名），null 表示正常。 */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    val fileName: String = file.name

    fun load() {
        when (val r = store.read()) {
            is JsonFileStore.ReadResult.Ok -> {
                _state.value = r.value
                _loadError.value = null
            }
            JsonFileStore.ReadResult.Missing -> {
                _state.value = default
                _loadError.value = null
            }
            is JsonFileStore.ReadResult.Corrupt -> {
                logger.error("config '${store.file.name}' is corrupt; kept as ${r.backup?.name}", r.error)
                _state.value = default
                _loadError.value = "${store.file.name}: ${r.error.message ?: r.error.javaClass.simpleName}" +
                    (r.backup?.let { " (saved as ${it.name})" } ?: "")
            }
        }
    }

    @Synchronized
    fun update(transform: (T) -> T) {
        val next = transform(_state.value)
        store.write(next)
        _state.value = next
    }
}

/** 仓库列表。 */
class RepoStore(dir: File, logger: SyncLogger) : ConfigFileState<List<com.mountriv.vaultsync.gitcore.model.RepoSpec>>(
    File(dir, "repos.json"),
    kotlinx.serialization.builtins.ListSerializer(com.mountriv.vaultsync.gitcore.model.RepoSpec.serializer()),
    VERSION,
    emptyList(),
    logger,
) {
    fun get(id: String) = state.value.firstOrNull { it.id == id }

    fun add(spec: com.mountriv.vaultsync.gitcore.model.RepoSpec) = update { it.filter { s -> s.id != spec.id } + spec }

    fun put(spec: com.mountriv.vaultsync.gitcore.model.RepoSpec) = update { list ->
        if (list.any { it.id == spec.id }) list.map { if (it.id == spec.id) spec else it } else list + spec
    }

    fun remove(id: String) = update { it.filter { s -> s.id != id } }

    companion object {
        const val VERSION = 1
    }
}

/** 全局设置。 */
class SettingsStore(dir: File, logger: SyncLogger) : ConfigFileState<AppSettings>(
    File(dir, "settings.json"),
    AppSettings.serializer(),
    VERSION,
    AppSettings(),
    logger,
) {
    companion object {
        const val VERSION = 1
    }
}

/** 上一次导出配置的记录（D-039），不进备份。 */
class BackupStatusStore(dir: File, logger: SyncLogger) : ConfigFileState<BackupStatus>(
    File(dir, "backup-status.json"),
    BackupStatus.serializer(),
    VERSION,
    BackupStatus(),
    logger,
) {
    companion object {
        const val VERSION = 1
    }
}

/** 每个仓库最近一次同步结果。 */
class StatusStore(dir: File, logger: SyncLogger) : ConfigFileState<Map<String, RepoStatus>>(
    File(dir, "status.json"),
    kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), RepoStatus.serializer()),
    VERSION,
    emptyMap(),
    logger,
) {
    fun put(status: RepoStatus) = update { it + (status.repoId to status) }

    fun remove(repoId: String) = update { it - repoId }

    companion object {
        const val VERSION = 1
    }
}
