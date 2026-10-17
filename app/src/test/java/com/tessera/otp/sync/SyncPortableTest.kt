package com.tessera.otp.sync

import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.domain.Base32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步包可移植格式（v2/v3）测试。
 *
 * 这一组用例守的是「换机能不能恢复」以及「旧格式毒条目会不会被写进库」：
 * v1 包里是别的设备 Keystore 的密文，本机解不开时必须丢弃，而不是让它落库后每次读取都崩。
 */
class SyncPortableTest {

    private val secret = Base32.decode("JBSWY3DPEHPK3PXP")

    private fun entry(id: String, secretField: String) = VaultEntry(
        id = id,
        issuer = "issuer",
        accountName = "acct",
        secretBase32 = secretField,
        syncRev = 1,
        updatedAt = 10L
    )

    @Test
    fun `v2 包原样保留且不丢条目`() {
        val snapshot = VaultSnapshot(version = 2, entries = listOf(entry("a", "JBSWY3DPEHPK3PXP")))
        val (out, skipped) = snapshot.toPortable { throw AssertionError("v2 不该调用旧格式解密器") }
        assertEquals(0, skipped)
        assertEquals(2, out.version)
        assertEquals("JBSWY3DPEHPK3PXP", out.entries.single().secretBase32)
    }

    @Test
    fun `v1 包在本机解得开时转成明文 Base32`() {
        val snapshot = VaultSnapshot(version = 1, entries = listOf(entry("a", "ciphertext")))
        val (out, skipped) = snapshot.toPortable { if (it == "ciphertext") secret else null }
        assertEquals(0, skipped)
        assertEquals(SyncCodec.CURRENT_VERSION, out.version)
        assertEquals(Base32.encode(secret), out.entries.single().secretBase32)
    }

    @Test
    fun `v1 包在本机解不开的条目被丢弃并计数`() {
        val snapshot = VaultSnapshot(
            version = 1,
            entries = listOf(entry("a", "foreign"), entry("b", "ciphertext"))
        )
        val (out, skipped) = snapshot.toPortable { if (it == "ciphertext") secret else null }
        assertEquals(1, skipped)
        assertEquals(listOf("b"), out.entries.map { it.id })
    }

    @Test
    fun `可移植条目能转出实体与明文密钥`() {
        val pair = entry("a", Base32.encode(secret)).toEntityAndSecret()
        assertTrue(pair != null)
        assertEquals("a", pair!!.first.id)
        assertEquals("", pair.first.secretEnc)
        assertEquals(secret.toList(), pair.second.toList())
    }

    @Test
    fun `非法或空密钥的条目被拒绝`() {
        assertNull(entry("a", "!!!!").toEntityAndSecret())
        assertNull(entry("a", "").toEntityAndSecret())
    }

    @Test
    fun `整包用同一口令能往返解出明文密钥与排序`() {
        val entity = AccountEntity(id = "a", issuer = "issuer", accountName = "acct", sortOrder = 7)
        val payload = SyncCodec.encode(SyncCodec.toSnapshot(listOf(entity to secret), now = 123L), "horse-battery")
        val snapshot = SyncCodec.decode(payload, "horse-battery")
        assertEquals(SyncCodec.CURRENT_VERSION, snapshot.version)
        assertEquals(123L, snapshot.updatedAt)
        assertEquals(Base32.encode(secret), snapshot.entries.single().secretBase32)
        // 手动排序必须跟着同步包走，否则换机后顺序会退化
        assertEquals(7, snapshot.entries.single().sortOrder)
        assertEquals(7, snapshot.entries.single().toEntityAndSecret()!!.first.sortOrder)
    }

    @Test
    fun `旧包缺少排序字段时默认为 0`() {
        val player = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val decoded = player.decodeFromString(
            VaultSnapshot.serializer(),
            """{"app":"tessera","version":3,"entries":[{"id":"a","secret":"JBSWY3DPEHPK3PXP"}]}"""
        )
        assertEquals(0, decoded.entries.single().sortOrder)
    }

    @Test
    fun `口令不对时整包解不开`() {
        val payload = SyncCodec.encode(SyncCodec.toSnapshot(emptyList(), now = 1L), "right")
        val failed = runCatching { SyncCodec.decode(payload, "wrong") }.isFailure
        assertTrue("错误口令必须解不开（GCM 认证失败）", failed)
    }

    @Test
    fun `空库也能生成并解出空包`() {
        val payload = SyncCodec.encode(SyncCodec.toSnapshot(emptyList(), now = 7L), "p")
        val snapshot = SyncCodec.decode(payload, "p")
        assertEquals(0, snapshot.entries.size)
        assertEquals(0, snapshot.toPortable { null }.second)
    }
}
