package com.tessera.otp.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 更新日志里的一个版本。 */
data class ChangelogEntry(
    val version: String,
    val date: String,
    val notes: String
)

/**
 * 更新日志：优先用 APK 内置的 [ASSET]（离线可看、含历史版本），
 * 若内置版本里还没有待更新的版本，再尝试从仓库 main 分支读取最新文件。
 */
class ChangelogRepository(
    private val context: Context,
    private val client: OkHttpClient = defaultClient
) {

    private val bundledEntries: List<ChangelogEntry> by lazy { loadBundled() }

    fun bundled(): List<ChangelogEntry> = bundledEntries

    /** 某个版本的更新说明；内置优先，缺失时回退远端。网络失败返回 null。 */
    suspend fun notesFor(version: String): String? {
        bundledEntries.firstOrNull { it.version == version }
            ?.notes?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return runCatching { remoteEntries() }.getOrNull()
            ?.firstOrNull { it.version == version }
            ?.notes?.takeIf { it.isNotBlank() }
    }

    private fun loadBundled(): List<ChangelogEntry> = runCatching {
        context.assets.open(ASSET).bufferedReader().use { parseChangelog(it.readText()) }
    }.getOrDefault(emptyList())

    private suspend fun remoteEntries(): List<ChangelogEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RAW_URL)
            .header("User-Agent", "Tessera-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            parseChangelog(response.body.string())
        }
    }

    private companion object {
        const val ASSET = "changelog.md"

        /**
         * 内置文件同时是仓库里的源文件：发版时更新它，
         * 旧版本客户端也能读到新版本的更新日志。
         */
        const val RAW_URL =
            "https://raw.githubusercontent.com/akiker233/Tessera/main/app/src/main/assets/changelog.md"

        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 解析更新日志 Markdown。
 *
 * 只认 `## 1.1.2 · 2026-10-18` 这种二级标题（版本号必填，日期可省），
 * 标题到下一个二级标题之间的内容即该版本的说明。
 */
internal fun parseChangelog(text: String): List<ChangelogEntry> {
    val entries = mutableListOf<ChangelogEntry>()
    var version: String? = null
    var date = ""
    val notes = StringBuilder()

    fun flush() {
        val current = version ?: return
        entries += ChangelogEntry(current, date, notes.toString().trim())
        notes.setLength(0)
    }

    text.lineSequence().forEach { raw ->
        val heading = HEADING.find(raw.trim())
        if (heading != null) {
            flush()
            version = heading.groupValues[1]
            date = heading.groupValues[2].trim()
        } else if (version != null) {
            notes.appendLine(raw)
        }
    }
    flush()

    return entries.sortedWith(Comparator { a, b -> compareVersions(b.version, a.version) })
}

private val HEADING = Regex("""^##\s+v?(\d+(?:\.\d+)+)\s*(?:[·•\-—–|]\s*(.*))?$""")
