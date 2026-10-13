package com.tessera.otp.data.icon

import android.content.Context
import kotlinx.serialization.json.Json
import java.net.URLDecoder

/**
 * 图标仓库：启动时加载 `assets/icon-index.json`（由 tools/gen_icon_index.ps1 从 assets/icons 生成），
 * 运行时把 issuer 归一化成键查表，命中即可拿到 WebP 路径；未命中由 UI 显示字母头像。
 */
class IconRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private var entriesByKey: Map<String, IconEntry> = emptyMap()
    private var loaded = false
    private var error: String? = null

    /** 索引加载失败原因（assets 缺失/JSON 损坏时非空，UI 可提示但不应崩溃）。 */
    val loadError: String? get() = error

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val raw = context.assets.open(INDEX_ASSET).bufferedReader().use { it.readText() }
            entriesByKey = json.decodeFromString(IconIndex.serializer(), raw).icons
        } catch (t: Throwable) {
            error = t.message ?: t::class.java.simpleName
        }
    }

    /** 全部图标（用于设置页的图标选择器）。 */
    fun allEntries(): List<IconEntry> {
        ensureLoaded()
        return entriesByKey.values.sortedBy { it.displayName.lowercase() }
    }

    /** 按 issuer 精确或归一化匹配。 */
    fun resolve(issuer: String?, explicitKey: String? = null): IconEntry? {
        ensureLoaded()
        explicitKey?.let { key ->
            entriesByKey[IconNormalizer.normalizeKey(key)]?.let { return it }
        }
        if (issuer.isNullOrBlank()) return null
        val key = IconNormalizer.normalizeIssuer(issuer)
        if (key.isEmpty()) return null
        entriesByKey[key]?.let { return it }

        // 宽松回退：issuer 常带后缀（"Acme Corp" vs "Acme"），图标名也可能被截断
        val candidates = entriesByKey.keys.filter { it.isNotEmpty() && (key.startsWith(it) || it.startsWith(key)) }
        if (candidates.isEmpty()) return null
        val best = candidates.minByOrNull { kotlin.math.abs(it.length - key.length) } ?: return null
        // 过短的键容易误命中（例如 "app"），要求至少 4 个字符
        return if (best.length >= 4) entriesByKey[best] else null
    }

    /** 要显示的图标资源路径：优先主题变体，缺失时回退默认变体。 */
    fun assetPath(issuer: String?, explicitKey: String?, preferLight: Boolean): String? {
        val entry = resolve(issuer, explicitKey) ?: return null
        val file = when {
            preferLight && entry.light != null -> entry.light
            !preferLight && entry.dark != null -> entry.dark
            else -> entry.file
        }
        return ICON_ASSET_DIR + "/" + decodeFileName(file)
    }

    /**
     * 生成索引时对文件名做了 URL 编码（文件名含空格、&、ñ、はてな 等字符），
     * 这里解码回 assets 里的真实文件名。
     */
    private fun decodeFileName(file: String): String = runCatching {
        URLDecoder.decode(file, Charsets.UTF_8.name())
    }.getOrDefault(file)

    companion object {
        const val INDEX_ASSET = "icon-index.json"
        const val ICON_ASSET_DIR = "icons"
    }
}
