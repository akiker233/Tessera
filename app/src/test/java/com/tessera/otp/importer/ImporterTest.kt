package com.tessera.otp.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入器测试。
 *
 * 样本全部按各软件的**公开导出格式**手工构造，不含任何真实密钥。
 */
class ImporterTest {

    @Test
    fun `2fas 明文导出解析`() {
        val json = """{
          "schemaVersion": 1,
          "services": [
            {
              "name": "GitHub",
              "secret": "JBSWY3DPEHPK3PXP",
              "updatedAt": 1700000000000,
              "otp": { "label": "me@example.com", "account": "me@example.com", "issuer": "GitHub", "digits": 6, "period": 30, "algorithm": "SHA1", "counter": 0 },
              "order": { "position": 0 },
              "icon": { "selected": "Brand", "brand": { "id": "github" } }
            },
            { "name": "Broken" }
          ]
        }"""
        val result = TwoFasImporter().parse(json)
        assertEquals(1, result.accounts.size)
        assertEquals("GitHub", result.accounts[0].issuer)
        assertEquals("me@example.com", result.accounts[0].accountName)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `2fas 加密导出被标记为需要口令`() {
        val json = """{"servicesEncrypted":"abc:def:ghi","reference":"x"}"""
        val result = TwoFasImporter().parse(json)
        assertTrue(result.encrypted)
        assertTrue(result.accounts.isEmpty())
    }

    @Test
    fun `aegis 导出解析并跳过 steam`() {
        val json = """{
          "version": 1,
          "header": { "slots": [] },
          "db": {
            "version": 2,
            "entries": [
              { "type": "totp", "uuid": "u1", "name": "alice", "issuer": "ACME", "note": "",
                "favorite": false, "icon": null,
                "info": { "secret": "JBSWY3DPEHPK3PXP", "algo": "SHA1", "digits": 6, "period": 30 } },
              { "type": "steam", "uuid": "u2", "name": "bob", "issuer": "Steam", "info": { "secret": "JBSWY3DPEHPK3PXP" } }
            ]
          }
        }"""
        val result = AegisImporter().parse(json)
        assertEquals(1, result.accounts.size)
        assertEquals("ACME", result.accounts[0].issuer)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `aegis 加密备份被标记为需要口令`() {
        val json = """{"version":1,"header":{"slots":[{"type":1,"uuid":"x","key":1,"key_params":{"nonce":"a","tag":"b"},"n":32768,"r":8,"p":1,"salt":"c"}]}}"""
        val result = AegisImporter().parse(json)
        assertTrue(result.encrypted)
    }

    @Test
    fun `andotp 数组导出解析`() {
        val json = """[
          {"secret":"JBSWY3DPEHPK3PXP","issuer":"ACME","label":"ACME:alice","digits":6,"type":"totp","period":30,"algorithm":"SHA1"},
          {"secret":"","issuer":"Empty","label":"Empty:bob","digits":6,"type":"totp","period":30,"algorithm":"SHA1"}
        ]"""
        val result = AndOtpImporter().parse(json)
        assertEquals(1, result.accounts.size)
        assertEquals("alice", result.accounts[0].accountName)
    }

    @Test
    fun `stratum 导出解析`() {
        val json = """{ "authenticators": [ { "type": "totp", "label": "ACME:alice", "issuer": "ACME", "secret": "JBSWY3DPEHPK3PXP", "period": 30, "digits": 6, "algorithm": "SHA1", "counter": 0 } ] }"""
        val result = StratumImporter().parse(json)
        assertEquals(1, result.accounts.size)
        assertEquals("ACME", result.accounts[0].issuer)
        assertEquals("alice", result.accounts[0].accountName)
    }

    @Test
    fun `raivo 导出解析 timer 作为 period`() {
        val json = """[ { "secret": "JBSWY3DPEHPK3PXP", "issuer": "ACME", "account": "alice", "algorithm": "SHA1", "digits": 6, "timer": 60, "kind": "TOTP" } ]"""
        val result = RaivoImporter().parse(json)
        assertEquals(1, result.accounts.size)
        assertEquals(60, result.accounts[0].period)
    }

    @Test
    fun `lastpass csv 支持 otpauth 与裸 base32 两种 totp 列`() {
        val csv = "url,username,password,extra,name,totp\n" +
            "https://acme.com,alice,pw,,ACME,otpauth://totp/ACME:alice?secret=JBSWY3DPEHPK3PXP&issuer=ACME\n" +
            "https://beta.com,bob,pw,,Beta,JBSWY3DPEHPK3PXP\n" +
            "https://bad.com,eve,pw,,Bad,not-base32!!\n"
        val result = LastPassImporter().parse(csv)
        assertEquals(2, result.accounts.size)
        assertEquals("ACME", result.accounts[0].issuer)
        assertEquals("Beta", result.accounts[1].issuer)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `自动识别来源`() {
        val twoFas = """{"services":[{"name":"X","secret":"JBSWY3DPEHPK3PXP","otp":{"digits":6,"period":30,"algorithm":"SHA1"}}]}"""
        assertEquals(ImportSource.TWO_FAS, Importers.detectAndParse("backup.2fas", twoFas).detectedSource)

        val andOtp = """[{"secret":"JBSWY3DPEHPK3PXP","issuer":"A","label":"A:b","type":"totp","digits":6,"period":30,"algorithm":"SHA1"}]"""
        assertEquals(ImportSource.ANDOTP, Importers.detectAndParse("andotp.json", andOtp).detectedSource)
    }

    @Test
    fun `无法识别时给出明确失败`() {
        val result = Importers.detectAndParse("random.txt", "hello world")
        assertTrue(result.accounts.isEmpty())
        assertFalse(result.issues.isEmpty())
    }

    @Test
    fun `otpauth 文本导入`() {
        val uri = "otpauth://totp/ACME%20Co:john@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60"
        val result = OtpAuthTextImporter().parse(uri)
        assertEquals(1, result.accounts.size)
        assertEquals("ACME Co", result.accounts[0].issuer)
        assertEquals("john@example.com", result.accounts[0].accountName)
        assertEquals("SHA256", result.accounts[0].algorithm)
        assertEquals(8, result.accounts[0].digits)
        assertEquals(60, result.accounts[0].period)
    }
}
