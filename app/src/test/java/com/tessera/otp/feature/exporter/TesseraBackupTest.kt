package com.tessera.otp.feature.exporter

import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.repo.Account
import com.tessera.otp.domain.Base32
import com.tessera.otp.importer.ImportSource
import com.tessera.otp.importer.Importers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tessera 专属备份（导出 -> 导入）测试。
 *
 * 覆盖：明文/加密两种形态、分类随包往返、密码错误与已删除分类不导出。
 */
class TesseraBackupTest {

    private val secret = Base32.decode("JBSWY3DPEHPK3PXP")

    private fun account(issuer: String, name: String, categoryId: String? = null) = Account(
        entity = AccountEntity(issuer = issuer, accountName = name, categoryId = categoryId),
        secret = secret
    )

    private val work = CategoryEntity(id = "c1", name = "工作", updatedAt = 5L)

    @Test
    fun `明文备份可被识别并原样往返`() {
        val text = Exporter.build(listOf(account("ACME", "alice", "c1")), ExportFormat.TESSERA, listOf(work), null)
        assertFalse("明文备份不应是加密信封：" + text, text.contains("\"ct\""))

        val result = Importers.detectAndParse("backup.tessera", text)
        assertEquals(ImportSource.TESSERA, result.detectedSource)
        assertFalse(result.encrypted)
        assertEquals(1, result.accounts.size)
        assertEquals("ACME", result.accounts[0].issuer)
        assertEquals("JBSWY3DPEHPK3PXP", result.accounts[0].secretBase32)
        assertEquals("工作", result.accounts[0].categoryName)
        assertEquals(listOf("工作"), result.categories.map { it.name })
    }

    @Test
    fun `加密备份必须用密码才能解析`() {
        val text = Exporter.build(listOf(account("ACME", "alice", "c1")), ExportFormat.TESSERA, listOf(work), "s3cret")

        val without = Importers.detectAndParse("backup.tessera", text)
        assertEquals(ImportSource.TESSERA, without.detectedSource)
        assertTrue("无密码时应收敛为「需要密码」", without.encrypted)
        assertTrue(without.accounts.isEmpty())

        val wrong = Importers.parseTesseraWithPassword(text, "nope")
        assertTrue("错误密码应报加密失败", wrong.encrypted)
        assertTrue(wrong.accounts.isEmpty())

        val ok = Importers.parseTesseraWithPassword(text, "s3cret")
        assertFalse(ok.encrypted)
        assertEquals("alice", ok.accounts.single().accountName)
        assertEquals("工作", ok.accounts.single().categoryName)
    }

    @Test
    fun `已删除的分类不导出且条目归属为空`() {
        val deleted = CategoryEntity(id = "c2", name = "__deleted__c2", updatedAt = 9L, deletedAt = 9L)
        val text = Exporter.build(
            listOf(account("ACME", "bob", "c2")),
            ExportFormat.TESSERA,
            listOf(work, deleted),
            null
        )
        val result = Importers.detectAndParse("backup.tessera", text)
        assertNull(result.accounts.single().categoryName)
        assertEquals(listOf("工作"), result.categories.map { it.name })
    }

    @Test
    fun `没有分类时分类为空且归属为空`() {
        val text = Exporter.build(listOf(account("ACME", "alice")), ExportFormat.TESSERA, emptyList(), null)
        val result = Importers.detectAndParse("backup.tessera", text)
        assertTrue(result.categories.isEmpty())
        assertNull(result.accounts.single().categoryName)
    }
}
