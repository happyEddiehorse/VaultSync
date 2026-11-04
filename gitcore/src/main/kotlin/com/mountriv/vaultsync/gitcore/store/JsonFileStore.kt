package com.mountriv.vaultsync.gitcore.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 版本化、原子写入的 JSON 配置文件（配置持久性规则见 D-019、docs/ANDROID_PLATFORM.md §6）。
 *
 * 文件内容是信封 `{"version": N, "data": {...}}`：
 * - 写入：先写 `<file>.tmp` 再 rename，避免写一半掉电留下残缺文件；
 * - 读取：解析失败**不覆盖原文件**，把它改名为 `<file>.bak-<时间>` 并返回 [ReadResult.Corrupt]，由 UI 明确报错；
 * - 版本：`version` 小于 [currentVersion] 时按序调用 [migrate]；大于当前版本（被更新的应用写过）照常尝试解析（`ignoreUnknownKeys`）。
 */
class JsonFileStore<T>(
    val file: File,
    private val serializer: KSerializer<T>,
    private val currentVersion: Int,
    /** 把 [fromVersion] 版本的 `data` 升到 `fromVersion + 1`。默认原样返回。 */
    private val migrate: (fromVersion: Int, data: JsonElement) -> JsonElement = { _, data -> data },
) {
    sealed class ReadResult<out T> {
        data class Ok<T>(val value: T, val version: Int) : ReadResult<T>()

        /** 文件不存在。 */
        object Missing : ReadResult<Nothing>()

        /** 文件损坏；原文件已改名为 [backup]（改名失败时为 null）。 */
        data class Corrupt(val backup: File?, val error: Throwable) : ReadResult<Nothing>()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun read(): ReadResult<T> {
        if (!file.isFile) return ReadResult.Missing
        return try {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val version = root[KEY_VERSION]?.jsonPrimitive?.intOrNull
                ?: throw IllegalStateException("missing '$KEY_VERSION' in ${file.name}")
            var data = root[KEY_DATA] ?: throw IllegalStateException("missing '$KEY_DATA' in ${file.name}")
            var v = version
            while (v < currentVersion) {
                data = migrate(v, data)
                v++
            }
            ReadResult.Ok(json.decodeFromJsonElement(serializer, data), version)
        } catch (e: Exception) {
            ReadResult.Corrupt(quarantine(), e)
        }
    }

    /** 原子写入；抛 IOException 时原文件保持不变。 */
    fun write(value: T) {
        file.parentFile?.mkdirs()
        val envelope: JsonObject = buildJsonObject {
            put(KEY_VERSION, JsonPrimitive(currentVersion))
            put(KEY_DATA, json.encodeToJsonElement(serializer, value))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), envelope), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Windows / 某些文件系统上 rename 不覆盖已存在文件：先删旧文件再 rename（窗口极短，且 tmp 已完整）
            if (file.exists() && !file.delete()) throw java.io.IOException("cannot replace ${file.absolutePath}")
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot rename ${tmp.name} to ${file.name}")
        }
    }

    private fun quarantine(): File? {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val backup = File(file.parentFile, "${file.name}.bak-$stamp")
        return if (file.renameTo(backup)) backup else null
    }

    companion object {
        const val KEY_VERSION = "version"
        const val KEY_DATA = "data"
    }
}
