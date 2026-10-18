package com.tessera.otp.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新日志 Markdown 的解析：标题、日期、正文与外层说明的忽略。 */
class ChangelogTest {

    @Test
    fun `解析版本标题日期与正文`() {
        val entries = parseChangelog(
            """
            # Tessera 更新日志

            ## 1.2.0 · 2026-11-01
            - 新增功能 A
            - 修复问题 B

            ## 1.1.2 · 2026-10-18
            - 冷启动提速
            """.trimIndent()
        )
        assertEquals(2, entries.size)
        assertEquals("1.2.0", entries[0].version)
        assertEquals("2026-11-01", entries[0].date)
        assertEquals("- 新增功能 A\n- 修复问题 B", entries[0].notes)
        assertEquals("1.1.2", entries[1].version)
        assertEquals("- 冷启动提速", entries[1].notes)
    }

    @Test
    fun `乱序版本按版本号倒序排列`() {
        val entries = parseChangelog(
            """
            ## 1.0.0 · 2026-10-13
            - 首个版本

            ## 1.10.0 · 2026-12-01
            - 新版本

            ## 1.1.1 · 2026-10-17
            - 旧版本
            """.trimIndent()
        )
        assertEquals(listOf("1.10.0", "1.1.1", "1.0.0"), entries.map { it.version })
    }

    @Test
    fun `没有日期或正文也能解析`() {
        val entries = parseChangelog(
            """
            ## 1.1.0
            ## 1.0.9 · 2026-10-01
            """.trimIndent()
        )
        assertEquals(2, entries.size)
        assertEquals("1.1.0", entries[0].version)
        assertEquals("", entries[0].date)
        assertEquals("", entries[0].notes)
        assertEquals("2026-10-01", entries[1].date)
    }

    @Test
    fun `忽略一级标题与没有版本号的二级标题`() {
        val entries = parseChangelog(
            """
            # 更新日志
            ## 说明
            这一段不属于任何版本
            ## 1.0.0 · 2026-10-13
            - 首个版本
            """.trimIndent()
        )
        assertEquals(1, entries.size)
        assertEquals("1.0.0", entries[0].version)
        assertTrue(entries[0].notes.contains("首个版本"))
    }

    @Test
    fun `内置更新日志包含全部历史版本`() {
        val file = java.io.File("src/main/assets/changelog.md")
        if (!file.exists()) return
        val versions = parseChangelog(file.readText()).map { it.version }
        assertTrue(versions.containsAll(listOf("1.0.0", "1.1.0", "1.1.1", "1.1.2")))
    }
}
