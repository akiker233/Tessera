package com.tessera.otp.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 导入来源。 */
enum class ImportSource(val label: String, val extensions: List<String>) {
    TESSERA("Tessera 备份", listOf("tessera", "json")),
    TWO_FAS("2FAS", listOf("2fas", "json")),
    STRATUM("Stratum", listOf("json")),
    AEGIS("Aegis", listOf("json")),
    ANDOTP("andOTP", listOf("json")),
    RAIVO("Raivo OTP", listOf("json")),
    LASTPASS("LastPass", listOf("csv", "json")),
    GOOGLE_AUTHENTICATOR("Google Authenticator", listOf("txt", "png", "jpg"))
}

/** 解析出的条目（密钥仍是 Base32 文本，落库前再解码加密）。 */
data class ParsedAccount(
    val issuer: String,
    val accountName: String,
    val secretBase32: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val isSteam: Boolean = false,
    val source: ImportSource,
    /** 该条目所属分类名（仅 Tessera 备份携带；落库时按名字匹配或新建分类）。 */
    val categoryName: String? = null
)

/** 解析出的分类（仅 Tessera 备份携带）。 */
data class ParsedCategory(
    val name: String,
    val colorArgb: Int = 0xFF3B5BDB.toInt(),
    val sortOrder: Int = 0
)

/** 单个条目的解析失败原因，用于导入后的汇总报告。 */
data class ImportIssue(val label: String, val reason: String)

data class ImportResult(
    val accounts: List<ParsedAccount>,
    val issues: List<ImportIssue> = emptyList(),
    val detectedSource: ImportSource? = null,
    val encrypted: Boolean = false,
    /** 备份里携带的分类（仅 Tessera 备份有）。 */
    val categories: List<ParsedCategory> = emptyList()
)

/**
 * 导入器统一接口。
 *
 * 所有实现必须是纯函数（不触网、不写盘），便于在 JVM 单元测试里直接验证；
 * 加密备份（2FAS/Aegis/Raivo 支持口令）在解密层单独处理，解密失败通过 [ImportResult.encrypted] 上报。
 */
interface Importer {
    val source: ImportSource

    /** 通过文件名与内容特征判断是否为该来源。 */
    fun canHandle(fileName: String, content: String): Boolean

    /** 解析；遇到个别坏条目只记录 issue，不中断整批。 */
    fun parse(content: String): ImportResult
}

/** 解析公用工具。 */
object ImportJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    fun JsonElement?.int(): Int? {
        val raw = (this as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.trim().toIntOrNull()
    }

    fun JsonObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key]?.str()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    fun JsonObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            val value = this[key]?.int()
            if (value != null) return value
        }
        return null
    }

    fun parseObject(content: String): JsonObject? = runCatching {
        json.parseToJsonElement(content).jsonObject
    }.getOrNull()

    fun parseArray(content: String): JsonArray? = runCatching {
        json.parseToJsonElement(content).jsonArray
    }.getOrNull()

    fun JsonObject.array(key: String): JsonArray? = runCatching {
        this[key]?.jsonArray
    }.getOrNull()

    fun JsonObject.obj(key: String): JsonObject? = runCatching {
        this[key]?.jsonObject
    }.getOrNull()

    /** 把 algorithm 文本统一成 SHA1/SHA256/SHA512。 */
    fun normalizeAlgorithm(raw: String?): String = when (raw?.uppercase()?.replace("-", "")?.trim()) {
        "SHA256", "SHA2", "2" -> "SHA256"
        "SHA512", "SHA5", "3" -> "SHA512"
        else -> "SHA1"
    }
}
