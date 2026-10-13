package com.tessera.otp.feature.exporter

import com.tessera.otp.data.repo.Account
import com.tessera.otp.domain.Base32

enum class ExportFormat(val label: String, val fileName: String, val mimeType: String) {
    JSON("JSON（含账户与密钥，可回导）", "tessera-export.json", "application/json"),
    OTPAUTH("otpauth:// 文本（每行一条）", "tessera-export.txt", "text/plain"),
    CSV("CSV（issuer,account,secret,...）", "tessera-export.csv", "text/csv")
}

/**
 * 导出器：把账号连同明文密钥导出，用于迁移到其它验证器或自行备份。
 *
 * 安全提示：导出内容包含可直接生成验证码的密钥，等价于最高敏感度凭据；
 * UI 必须先用确认弹窗明确告知，再由用户选择保存位置（SAF），不写入应用私有目录。
 */
object Exporter {

    fun build(accounts: List<Account>, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> buildJson(accounts)
        ExportFormat.OTPAUTH -> accounts.joinToString("\n") { otpAuthUri(it) }
        ExportFormat.CSV -> buildCsv(accounts)
    }

    fun secretOf(account: Account): String = Base32.encode(account.secret)

    fun otpAuthUri(account: Account): String {
        val issuer = encode(account.issuer)
        val label = if (issuer.isBlank()) encode(account.accountName) else issuer + ":" + encode(account.accountName)
        val spec = account.spec()
        val sb = StringBuilder()
        sb.append("otpauth://").append(if (account.isHotp) "hotp" else "totp").append("/").append(label)
        sb.append("?secret=").append(secretOf(account))
        if (issuer.isNotBlank()) sb.append("&issuer=").append(issuer)
        sb.append("&algorithm=").append(spec.algorithm.name)
        sb.append("&digits=").append(spec.digits)
        sb.append("&period=").append(spec.period)
        return sb.toString()
    }

    private fun buildJson(accounts: List<Account>): String {
        val sb = StringBuilder()
        sb.append("{\n  \"version\": 1,\n  \"exportedAt\": ").append(System.currentTimeMillis())
        sb.append(",\n  \"accounts\": [\n")
        accounts.forEachIndexed { index, account ->
            val spec = account.spec()
            sb.append("    {")
            sb.append("\"issuer\": ").append(quote(account.issuer))
            sb.append(", \"accountName\": ").append(quote(account.accountName))
            sb.append(", \"secret\": ").append(quote(secretOf(account)))
            sb.append(", \"algorithm\": ").append(quote(spec.algorithm.name))
            sb.append(", \"digits\": ").append(spec.digits)
            sb.append(", \"period\": ").append(spec.period)
            sb.append(", \"isSteam\": ").append(spec.isSteam)
            sb.append(", \"isHotp\": ").append(account.isHotp)
            sb.append(", \"counter\": ").append(account.entity.counter)
            sb.append(", \"note\": ").append(quote(account.entity.note.orEmpty()))
            sb.append(", \"otpauth\": ").append(quote(otpAuthUri(account)))
            sb.append("}")
            if (index != accounts.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    private fun buildCsv(accounts: List<Account>): String {
        val sb = StringBuilder("issuer,account_name,secret,algorithm,digits,period,type,otpauth\n")
        accounts.forEach { account ->
            val spec = account.spec()
            sb.append(csv(account.issuer)).append(",")
            sb.append(csv(account.accountName)).append(",")
            sb.append(csv(secretOf(account))).append(",")
            sb.append(spec.algorithm.name).append(",")
            sb.append(spec.digits).append(",")
            sb.append(spec.period).append(",")
            sb.append(if (account.isHotp) "hotp" else "totp").append(",")
            sb.append(csv(otpAuthUri(account))).append("\n")
        }
        return sb.toString()
    }

    private fun csv(value: String): String {
        val needsQuote = value.any { it == ',' || it == '\"' || it == '\n' }
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"" + escaped + "\""
    }
}
