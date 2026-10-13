package com.tessera.otp.feature.exporter

import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.data.repo.Account
import com.tessera.otp.domain.Base32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出器测试。
 *
 * 重点覆盖两类最容易出错的点：
 *  1. otpauth URI 的百分号编码（空格必须是 %20 而不是 +；+ @ & 等必须被编码）；
 *  2. JSON / CSV 的转义（引号、反斜杠、换行、逗号）。
 */
class ExporterTest {

    private fun account(
        issuer: String,
        name: String,
        secret: String = "JBSWY3DPEHPK3PXP",
        algorithm: String = "SHA1",
        digits: Int = 6,
        period: Int = 30,
        hotp: Boolean = false
    ) = Account(
        entity = AccountEntity(
            issuer = issuer,
            accountName = name,
            algorithm = algorithm,
            digits = digits,
            period = period,
            isHotp = hotp
        ),
        secret = Base32.decode(secret)
    )

    @Test
    fun `otpauth 空格编码为 %20 而不是加号`() {
        val uri = Exporter.otpAuthUri(account("ACME Co", "john@example.com"))
        assertTrue("issuer 应使用 %20 编码：" + uri, uri.contains("issuer=ACME%20Co"))
        assertFalse("不应出现加号形式的空格：" + uri, uri.contains("issuer=ACME+Co"))
        assertTrue(uri.startsWith("otpauth://totp/"))
        assertTrue("label 应为 issuer:account：" + uri, uri.contains("ACME%20Co:john%40example.com"))
    }

    @Test
    fun `otpauth 对 & 与 + 做百分号编码`() {
        val uri = Exporter.otpAuthUri(account("A&B", "c+d"))
        assertTrue("& 必须编码为 %26：" + uri, uri.contains("issuer=A%26B"))
        assertTrue("+ 必须编码为 %2B：" + uri, uri.contains("c%2Bd"))
    }

    @Test
    fun `otpauth 携带算法位数周期且 HOTP 类型正确`() {
        val totp = Exporter.otpAuthUri(account("ACME", "a", algorithm = "SHA256", digits = 8, period = 60))
        assertTrue(totp.contains("secret=" + Base32.encode(Base32.decode("JBSWY3DPEHPK3PXP"))))
        assertTrue(totp.contains("&algorithm=SHA256"))
        assertTrue(totp.contains("&digits=8"))
        assertTrue(totp.contains("&period=60"))

        val hotp = Exporter.otpAuthUri(account("ACME", "a", hotp = true))
        assertTrue("HOTP 应导出为 otpauth://hotp/：" + hotp, hotp.startsWith("otpauth://hotp/"))
    }

    @Test
    fun `JSON 含引号反斜杠换行的字段可被解析回原值`() {
        val nasty = "AC\"ME\\Co\nLine2"
        val json = Exporter.build(listOf(account(nasty, "a\"b,c")), ExportFormat.JSON)

        val root = Json.parseToJsonElement(json).jsonObject
        val accounts = root["accounts"]!!.jsonArray
        assertEquals(1, accounts.size)
        val first = accounts[0].jsonObject
        assertEquals("引号/反斜杠/换行必须能原样还原", nasty, first["issuer"]!!.jsonPrimitive.content)
        assertEquals("a\"b,c", first["accountName"]!!.jsonPrimitive.content)
        assertEquals("JBSWY3DPEHPK3PXP", first["secret"]!!.jsonPrimitive.content)
    }

    @Test
    fun `JSON 条目数与密钥往返一致`() {
        val list = listOf(
            account("One", "a"),
            account("Two", "b", secret = "GEZDGNBVGY3TQOJQ")
        )
        val json = Exporter.build(list, ExportFormat.JSON)
        val accounts = Json.parseToJsonElement(json).jsonObject["accounts"]!!.jsonArray
        assertEquals(2, accounts.size)
        assertEquals("One", accounts[0].jsonObject["issuer"]!!.jsonPrimitive.content)
        assertEquals("GEZDGNBVGY3TQOJQ", accounts[1].jsonObject["secret"]!!.jsonPrimitive.content)
        // 每条都应带可直接回导的 otpauth URI
        assertTrue(accounts[0].jsonObject["otpauth"]!!.jsonPrimitive.content.startsWith("otpauth://totp/"))
    }

    @Test
    fun `CSV 对逗号与引号做包裹与双写`() {
        val csv = Exporter.build(listOf(account("ACME, Inc", "say \"hi\"")), ExportFormat.CSV)
        val lines = csv.trim().split("\n")
        assertEquals("表头应固定", "issuer,account_name,secret,algorithm,digits,period,type,otpauth", lines[0])
        val row = lines[1]
        assertTrue("含逗号的单元格要用双引号包裹：" + row, row.contains("\"ACME, Inc\""))
        assertTrue("内部引号要双写：" + row, row.contains("\"say \"\"hi\"\"\""))
        assertTrue(row.endsWith("totp", ignoreCase = false) || row.contains(",totp,"))
    }

    @Test
    fun `otpauth 文本格式每行一条`() {
        val text = Exporter.build(listOf(account("One", "a"), account("Two", "b")), ExportFormat.OTPAUTH)
        val lines = text.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("otpauth://totp/") })
    }
}
