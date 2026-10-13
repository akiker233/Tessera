package com.tessera.otp.data.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 数据库口令管理。
 *
 * - 随机生成 32 字节口令，用 Android Keystore 中的 AES-256-GCM 主密钥加密后落盘；
 * - 主密钥不可导出（StrongBox/TEE 保护），因此备份出的 SharedPreferences 无法离线解密；
 * - 若 Keystore 条目丢失（清数据/恢复备份），调用方应把库视为不可解密并要求用户重新导入。
 */
object DatabasePassphrase {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "tessera_master_key"
    private const val PREFS = "tessera_secure"
    private const val PREF_KEY = "db_passphrase_v1"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    fun get(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_KEY, null)
        return if (existing == null) {
            val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            prefs.edit().putString(PREF_KEY, encrypt(fresh)).apply()
            fresh
        } else {
            decrypt(existing)
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, NONCE_BYTES)
        val ct = raw.copyOfRange(NONCE_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
