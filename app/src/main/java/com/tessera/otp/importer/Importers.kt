package com.tessera.otp.importer

import com.tessera.otp.domain.Base32
import com.tessera.otp.domain.GoogleMigrationParser
import com.tessera.otp.domain.OtpAuthUriParser
import com.tessera.otp.importer.ImportJson.array
import com.tessera.otp.importer.ImportJson.firstInt
import com.tessera.otp.importer.ImportJson.firstString
import com.tessera.otp.importer.ImportJson.normalizeAlgorithm
import com.tessera.otp.importer.ImportJson.obj
import com.tessera.otp.importer.ImportJson.parseArray
import com.tessera.otp.importer.ImportJson.parseObject
import com.tessera.otp.sync.SyncCodec
import com.tessera.otp.sync.TESSERA_FORMAT_MARKER
import kotlinx.serialization.json.jsonObject

/**
 * Tessera 专属备份（本应用自己的导出格式）。
 *
 * 明文版是带 `app:"tessera"` 标记的 JSON（见 [com.tessera.otp.sync.VaultSnapshot]）；
 * 加密版是同一份 JSON 经用户密码派生的 AES-256-GCM 信封（同样带标记 + ct/salt/nonce）。
 * 加密版需要密码，由 [parseEncrypted] 处理。
 */
class TesseraImporter : Importer {
    override val source = ImportSource.TESSERA

    override fun canHandle(fileName: String, content: String): Boolean {
        val root = ImportJson.parseObject(content) ?: return false
        if (root.firstString("app") == TESSERA_FORMAT_MARKER) return true
        return root.containsKey("ct") && root.containsKey("salt") && root.containsKey("nonce")
    }

    override fun parse(content: String): ImportResult = parseEncrypted(content, password = null)

    /** @param password 加密备份的密码；明文备份传 null 即可。 */
    fun parseEncrypted(content: String, password: String?): ImportResult {
        val root = ImportJson.parseObject(content)
            ?: return ImportResult(emptyList(), listOf(ImportIssue("Tessera", "不是合法 JSON")), source)
        val encrypted = root.containsKey("ct")
        val snapshot = if (encrypted) {
            if (password.isNullOrEmpty()) {
                return ImportResult(emptyList(), listOf(ImportIssue("Tessera", "备份已加密，请输入密码")), source, encrypted = true)
            }
            runCatching { SyncCodec.decode(content, password) }.getOrElse {
                return ImportResult(emptyList(), listOf(ImportIssue("Tessera", "密码错误或文件已损坏")), source, encrypted = true)
            }
        } else {
            runCatching { SyncCodec.decodePlain(content) }.getOrElse {
                return ImportResult(emptyList(), listOf(ImportIssue("Tessera", "不是有效的 Tessera 备份")), source)
            }
        }

        val categoryById = snapshot.categories.associateBy { it.id }
        val categories = snapshot.categories
            .filter { !it.isDeleted && it.name.isNotBlank() }
            .map { ParsedCategory(name = it.name, colorArgb = it.colorArgb, sortOrder = it.sortOrder) }

        val issues = mutableListOf<ImportIssue>()
        val accounts = mutableListOf<ParsedAccount>()
        snapshot.entries.filter { it.deletedAt == null }.forEachIndexed { index, entry ->
            if (entry.secretBase32.isBlank()) {
                issues += ImportIssue(
                    entry.issuer.ifBlank { entry.accountName.ifBlank { "#" + index } },
                    "缺少密钥"
                )
                return@forEachIndexed
            }
            accounts += ParsedAccount(
                issuer = entry.issuer,
                accountName = entry.accountName,
                secretBase32 = entry.secretBase32,
                algorithm = ImportJson.normalizeAlgorithm(entry.algorithm),
                digits = clampDigits(entry.digits),
                period = clampPeriod(entry.period),
                isSteam = entry.isSteam,
                source = source,
                categoryName = entry.categoryId?.let { id ->
                    categoryById[id]?.takeIf { !it.isDeleted }?.name?.takeIf { it.isNotBlank() }
                }
            )
        }
        return ImportResult(accounts, issues, source, encrypted = false, categories = categories)
    }
}

/**
 * 2FAS（Android/iOS）JSON 导出。
 *
 * 结构：{"schemaVersion":1,"services":[{"name":..,"secret":..,"otp":{"label":..,"account":..,"issuer":..,"digits":..,"period":..,"algorithm":..,"counter":..}}, ...]}
 * 加密备份（{"servicesEncrypted": "..."}）无法离线解析，按 [ImportResult.encrypted] 上报，由上层提示需要口令。
 */
class TwoFasImporter : Importer {
    override val source = ImportSource.TWO_FAS

    override fun canHandle(fileName: String, content: String): Boolean {
        if (!content.contains("services", ignoreCase = true)) return false
        val root = ImportJson.parseObject(content) ?: return false
        return root.containsKey("services") || root.containsKey("servicesEncrypted") ||
            fileName.endsWith(".2fas", ignoreCase = true)
    }

    override fun parse(content: String): ImportResult {
        val root = ImportJson.parseObject(content)
            ?: return ImportResult(emptyList(), listOf(ImportIssue("2FAS", "不是合法 JSON")), source)
        if (root.containsKey("servicesEncrypted")) {
            return ImportResult(emptyList(), listOf(ImportIssue("2FAS", "备份已加密，需要口令（暂不支持离线解密）")), source, encrypted = true)
        }
        val services = root.array("services")
            ?: return ImportResult(emptyList(), listOf(ImportIssue("2FAS", "缺少 services 数组")), source)

        val accounts = mutableListOf<ParsedAccount>()
        val issues = mutableListOf<ImportIssue>()
        services.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            if (obj == null) {
                issues += ImportIssue("#" + index, "条目不是对象")
                return@forEachIndexed
            }
            val secret = obj.firstString("secret")
            if (secret.isNullOrBlank()) {
                issues += ImportIssue(obj.firstString("name") ?: ("#" + index), "缺少 secret")
                return@forEachIndexed
            }
            val otp = obj.obj("otp")
            val issuer = obj.firstString("name", "issuer") ?: otp?.firstString("issuer") ?: ""
            val account = otp?.firstString("account", "label") ?: obj.firstString("account") ?: ""
            accounts += ParsedAccount(
                issuer = issuer,
                accountName = account,
                secretBase32 = secret.trim(),
                algorithm = ImportJson.normalizeAlgorithm(otp?.firstString("algorithm")),
                digits = clampDigits(otp?.firstInt("digits")),
                period = clampPeriod(otp?.firstInt("period")),
                source = source
            )
        }
        return ImportResult(accounts, issues, source)
    }
}

/** Stratum（已停止维护）JSON 导出：{"authenticators":[{...}]}。字段名有多套变体，容错处理。 */
class StratumImporter : Importer {
    override val source = ImportSource.STRATUM

    override fun canHandle(fileName: String, content: String): Boolean {
        val root = ImportJson.parseObject(content) ?: return false
        return root.containsKey("authenticators") || (root.containsKey("data") && content.contains("secret", true))
    }

    override fun parse(content: String): ImportResult {
        val root = ImportJson.parseObject(content)
            ?: return ImportResult(emptyList(), listOf(ImportIssue("Stratum", "不是合法 JSON")), source)
        val list = root.array("authenticators") ?: root.array("data")
            ?: return ImportResult(emptyList(), listOf(ImportIssue("Stratum", "缺少 authenticators 数组")), source)

        val accounts = mutableListOf<ParsedAccount>()
        val issues = mutableListOf<ImportIssue>()
        list.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            if (obj == null) {
                issues += ImportIssue("#" + index, "条目不是对象")
                return@forEachIndexed
            }
            val secret = obj.firstString("secret")
            if (secret.isNullOrBlank()) {
                issues += ImportIssue(obj.firstString("label", "name") ?: ("#" + index), "缺少 secret")
                return@forEachIndexed
            }
            val label = obj.firstString("label", "name", "account") ?: ""
            accounts += ParsedAccount(
                issuer = obj.firstString("issuer", "name") ?: splitIssuer(label),
                accountName = splitAccount(label),
                secretBase32 = secret.trim(),
                algorithm = ImportJson.normalizeAlgorithm(obj.firstString("algorithm", "type")),
                digits = clampDigits(obj.firstInt("digits")),
                period = clampPeriod(obj.firstInt("period", "totpPeriod", "timer")),
                source = source
            )
        }
        return ImportResult(accounts, issues, source)
    }
}

/**
 * Aegis 导出：{"db":{"version":2,"entries":[{"type":"totp","issuer":..,"name":..,"info":{"secret":..,"digits":..,"period":..,"algo":..}}]}}。
 * 加密导出带 header.slots，需要口令解密后才能看到 db。
 */
class AegisImporter : Importer {
    override val source = ImportSource.AEGIS

    override fun canHandle(fileName: String, content: String): Boolean {
        val root = ImportJson.parseObject(content) ?: return false
        val db = root.obj("db")
        return (root.containsKey("db") && db != null && db.containsKey("entries")) || root.containsKey("header")
    }

    override fun parse(content: String): ImportResult {
        val root = ImportJson.parseObject(content)
            ?: return ImportResult(emptyList(), listOf(ImportIssue("Aegis", "不是合法 JSON")), source)
        val db = root.obj("db")
            ?: return ImportResult(emptyList(), listOf(ImportIssue("Aegis", "备份已加密或结构异常，需要口令")), source, encrypted = true)
        val entries = db.array("entries")
            ?: return ImportResult(emptyList(), listOf(ImportIssue("Aegis", "缺少 db.entries")), source)

        val accounts = mutableListOf<ParsedAccount>()
        val issues = mutableListOf<ImportIssue>()
        entries.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            if (obj == null) {
                issues += ImportIssue("#" + index, "条目不是对象")
                return@forEachIndexed
            }
            val type = obj.firstString("type")?.lowercase()
            val info = obj.obj("info")
            val secret = info?.firstString("secret")
            val label = obj.firstString("issuer", "name") ?: ""
            if (secret.isNullOrBlank() || type == "steam" || type.isNullOrEmpty()) {
                issues += ImportIssue(label.ifBlank { "#" + index }, "缺少 secret 或类型不支持")
                return@forEachIndexed
            }
            accounts += ParsedAccount(
                issuer = obj.firstString("issuer") ?: "",
                accountName = obj.firstString("name") ?: "",
                secretBase32 = secret.trim(),
                algorithm = ImportJson.normalizeAlgorithm(info.firstString("algo", "algorithm")),
                digits = clampDigits(info.firstInt("digits")),
                period = clampPeriod(info.firstInt("period")),
                source = source
            )
        }
        return ImportResult(accounts, issues, source)
    }
}

/** andOTP 导出：顶级数组 [{secret,issuer,label,digits,type,period,algorithm}]。 */
class AndOtpImporter : Importer {
    override val source = ImportSource.ANDOTP

    override fun canHandle(fileName: String, content: String): Boolean {
        val array = ImportJson.parseArray(content) ?: return false
        return array.isNotEmpty() && array.first().let { element ->
            runCatching { val o = element.jsonObject; o.containsKey("secret") && (o.containsKey("label") || o.containsKey("issuer")) }.getOrDefault(false)
        }
    }

    override fun parse(content: String): ImportResult {
        val array = ImportJson.parseArray(content)
            ?: return ImportResult(emptyList(), listOf(ImportIssue("andOTP", "不是合法 JSON 数组")), source)
        val accounts = mutableListOf<ParsedAccount>()
        val issues = mutableListOf<ImportIssue>()
        array.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            if (obj == null) {
                issues += ImportIssue("#" + index, "条目不是对象")
                return@forEachIndexed
            }
            // andOTP 的 type 字段是 hotp/totp 或 "TOTP"/"HOTP"
            val type = obj.firstString("type")?.lowercase()
            val secret = obj.firstString("secret")
            if (secret.isNullOrBlank()) {
                issues += ImportIssue(obj.firstString("label") ?: ("#" + index), "缺少 secret")
                return@forEachIndexed
            }
            if (type != null && type != "totp" && type != "hotp") {
                issues += ImportIssue(obj.firstString("label") ?: ("#" + index), "不支持的 type：" + type)
                return@forEachIndexed
            }
            accounts += ParsedAccount(
                issuer = obj.firstString("issuer") ?: splitIssuer(obj.firstString("label") ?: ""),
                accountName = splitAccount(obj.firstString("label") ?: ""),
                secretBase32 = secret.trim(),
                algorithm = ImportJson.normalizeAlgorithm(obj.firstString("algorithm")),
                digits = clampDigits(obj.firstInt("digits")),
                period = clampPeriod(obj.firstInt("period")),
                source = source
            )
        }
        return ImportResult(accounts, issues, source)
    }
}

/**
 * Raivo OTP 导出：数组或 {"items":[...]}，字段名 secret/issuer/account/algorithm/digits/timer/kind。
 * 注意 Raivo 的 timer 就是 period。
 */
class RaivoImporter : Importer {
    override val source = ImportSource.RAIVO

    override fun canHandle(fileName: String, content: String): Boolean {
        val root = ImportJson.parseObject(content)
        val array = if (root == null) ImportJson.parseArray(content) else root.array("items") ?: root.array("otp")
        if (array == null || array.isEmpty()) return false
        val first = runCatching { array.first().jsonObject }.getOrNull() ?: return false
        return first.containsKey("secret") && (first.containsKey("timer") || first.containsKey("kind") || first.containsKey("account"))
    }

    override fun parse(content: String): ImportResult {
        val root = ImportJson.parseObject(content)
        val array = if (root == null) ImportJson.parseArray(content) else root.array("items") ?: root.array("otp")
        if (array == null) {
            return ImportResult(emptyList(), listOf(ImportIssue("Raivo", "不是合法 JSON（数组或 items 包裹）")), source)
        }
        val accounts = mutableListOf<ParsedAccount>()
        val issues = mutableListOf<ImportIssue>()
        array.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            if (obj == null) {
                issues += ImportIssue("#" + index, "条目不是对象")
                return@forEachIndexed
            }
            val secret = obj.firstString("secret")
            if (secret.isNullOrBlank()) {
                issues += ImportIssue(obj.firstString("issuer") ?: ("#" + index), "缺少 secret")
                return@forEachIndexed
            }
            accounts += ParsedAccount(
                issuer = obj.firstString("issuer") ?: "",
                accountName = obj.firstString("account", "title") ?: "",
                secretBase32 = secret.trim(),
                algorithm = ImportJson.normalizeAlgorithm(obj.firstString("algorithm")),
                digits = clampDigits(obj.firstInt("digits")),
                period = clampPeriod(obj.firstInt("timer", "period")),
                source = source
            )
        }
        return ImportResult(accounts, issues, source)
    }
}

/**
 * LastPass CSV 导出（含 totp 列）。
 *
 * LastPass 的 totp 列可能是 otpauth:// URI，也可能是裸 Base32。
 * 手动拆行以支持带引号、含逗号的字段。
 */
class LastPassImporter : Importer {
    override val source = ImportSource.LASTPASS

    override fun canHandle(fileName: String, content: String): Boolean {
        val firstLine = content.lineSequence().firstOrNull()?.lowercase() ?: return false
        return firstLine.contains("totp") && (firstLine.contains("url") || firstLine.contains("username") || firstLine.contains("name"))
    }

    override fun parse(content: String): ImportResult {
        val lines = content.split("\r?\n".toRegex()).filter { it.isNotBlank() }
        if (lines.isEmpty()) return ImportResult(emptyList(), listOf(ImportIssue("LastPass", "文件为空")), source)

        val header = parseCsvLine(lines.first()).map { it.trim().lowercase() }
        val totpIndex = header.indexOf("totp")
        val nameIndex = header.indexOf("name")
        val urlIndex = header.indexOf("url")
        val usernameIndex = header.indexOf("username")
        if (totpIndex < 0) {
            return ImportResult(emptyList(), listOf(ImportIssue("LastPass", "CSV 缺少 totp 列")), source)
        }

        val accounts = mutableListOf<ParsedAccount>()
        val issues = mutableListOf<ImportIssue>()
        lines.drop(1).forEachIndexed { index, line ->
            val cells = parseCsvLine(line)
            val raw = cells.getOrNull(totpIndex)?.trim().orEmpty()
            if (raw.isEmpty()) return@forEachIndexed
            val name = cells.getOrNull(nameIndex)?.trim().orEmpty()
            val url = cells.getOrNull(urlIndex)?.trim().orEmpty()
            val username = cells.getOrNull(usernameIndex)?.trim().orEmpty()

            val fromUri = if (raw.startsWith("otpauth://", ignoreCase = true)) OtpAuthUriParser.parse(raw) else null
            val secret = fromUri?.secretBase32 ?: raw
            if (runCatching { Base32.decode(secret) }.isFailure) {
                issues += ImportIssue(name.ifBlank { "#" + index }, "totp 列既不是 otpauth URI 也不是合法 Base32")
                return@forEachIndexed
            }
            accounts += ParsedAccount(
                issuer = fromUri?.issuer ?: name.ifBlank { hostOf(url) },
                accountName = fromUri?.accountName ?: username,
                secretBase32 = secret,
                algorithm = fromUri?.algorithm?.name ?: "SHA1",
                digits = fromUri?.digits ?: 6,
                period = fromUri?.period ?: 30,
                source = source
            )
        }
        return ImportResult(accounts, issues, source)
    }

    /** 极简 CSV 行拆分：支持双引号包裹与 '' 转义。 */
    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { cells += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        cells += sb.toString()
        return cells
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    }.getOrDefault(url)
}

/** Google Authenticator 迁移二维码导出的文本（otpauth-migration://offline?data=...）。 */
class GoogleAuthenticatorImporter : Importer {
    override val source = ImportSource.GOOGLE_AUTHENTICATOR

    override fun canHandle(fileName: String, content: String): Boolean =
        content.contains("otpauth-migration://", ignoreCase = true)

    override fun parse(content: String): ImportResult {
        val marker = content.indexOf("otpauth-migration://", ignoreCase = true)
        if (marker < 0) return ImportResult(emptyList(), listOf(ImportIssue("Google Authenticator", "未找到迁移链接")), source)
        val uri = content.substring(marker).trim().split(Regex("\\s+")).first()
        val entries = runCatching { GoogleMigrationParser.parse(uri) }.getOrElse { error ->
            return ImportResult(emptyList(), listOf(ImportIssue("Google Authenticator", error.message ?: "解析失败")), source)
        }
        return ImportResult(
            entries.map { entry ->
                ParsedAccount(
                    issuer = entry.issuer,
                    accountName = entry.accountName,
                    secretBase32 = entry.secretBase32,
                    algorithm = entry.algorithm.name,
                    digits = entry.digits,
                    period = 30,
                    source = source
                )
            },
            emptyList(),
            source
        )
    }
}

/** 单条 otpauth:// 文本（扫码/相册识别结果也会走这里）。 */
class OtpAuthTextImporter : Importer {
    override val source = ImportSource.GOOGLE_AUTHENTICATOR

    override fun canHandle(fileName: String, content: String): Boolean =
        content.trim().startsWith("otpauth://", ignoreCase = true)

    override fun parse(content: String): ImportResult {
        val parsed = OtpAuthUriParser.parse(content.trim())
            ?: return ImportResult(emptyList(), listOf(ImportIssue("otpauth", "URI 无法解析")), source)
        return ImportResult(
            listOf(
                ParsedAccount(
                    issuer = parsed.issuer ?: "",
                    accountName = parsed.accountName,
                    secretBase32 = parsed.secretBase32,
                    algorithm = parsed.algorithm.name,
                    digits = parsed.digits,
                    period = parsed.period,
                    isSteam = parsed.isSteam,
                    source = source
                )
            ),
            emptyList(),
            source
        )
    }
}

/** 各导入器的注册表：按顺序尝试 canHandle。 */
object Importers {
    val all: List<Importer> = listOf(
        TesseraImporter(),
        GoogleAuthenticatorImporter(),
        OtpAuthTextImporter(),
        TwoFasImporter(),
        AegisImporter(),
        StratumImporter(),
        AndOtpImporter(),
        RaivoImporter(),
        LastPassImporter()
    )

    /** 自动识别来源并解析。 */
    fun detectAndParse(fileName: String, content: String): ImportResult {
        for (importer in all) {
            if (runCatching { importer.canHandle(fileName, content) }.getOrDefault(false)) {
                val result = runCatching { importer.parse(content) }.getOrElse { error ->
                    return ImportResult(emptyList(), listOf(ImportIssue(importer.source.label, error.message ?: "解析异常")))
                }
                if (result.accounts.isNotEmpty() || result.encrypted) return result
            }
        }
        return ImportResult(emptyList(), listOf(ImportIssue(fileName, "无法识别的格式")))
    }

    /** Tessera 专属格式：用用户输入的密码解密后解析（明文备份传空密码也可）。 */
    fun parseTesseraWithPassword(content: String, password: String): ImportResult =
        runCatching { TesseraImporter().parseEncrypted(content, password) }.getOrElse { error ->
            ImportResult(emptyList(), listOf(ImportIssue(ImportSource.TESSERA.label, error.message ?: "解析异常")))
        }

    /** 只解析指定来源（用户手动指定时用）。 */
    fun parseWith(source: ImportSource, content: String): ImportResult {
        val importer = all.firstOrNull { it.source == source }
            ?: return ImportResult(emptyList(), listOf(ImportIssue(source.label, "没有对应解析器")))
        return runCatching { importer.parse(content) }.getOrElse { error ->
            ImportResult(emptyList(), listOf(ImportIssue(source.label, error.message ?: "解析异常")))
        }
    }
}

internal fun clampDigits(value: Int?): Int = (value ?: 6).coerceIn(4, 10)

internal fun clampPeriod(value: Int?): Int = (value ?: 30).coerceIn(5, 300)

internal fun splitIssuer(label: String): String {
    val index = label.indexOf(":")
    return if (index > 0) label.substring(0, index).trim() else ""
}

internal fun splitAccount(label: String): String {
    val index = label.indexOf(":")
    return if (index >= 0) label.substring(index + 1).trim() else label.trim()
}
