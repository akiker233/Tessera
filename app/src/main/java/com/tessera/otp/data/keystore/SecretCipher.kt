package com.tessera.otp.data.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TOTP 密钥的加解密。
 *
 * 与数据库口令共用同一把 Keystore 主密钥，但每次加密使用独立随机 IV，输出 Base64(iv || ct || tag)，
 * 因此同一条密钥每次的密文都不同（防止离线比对）。
 */
class SecretCipher {

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > NONCE_BYTES) { "密钥密文长度非法" }
        val iv = raw.copyOfRange(0, NONCE_BYTES)
        val ct = raw.copyOfRange(NONCE_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /**
     * 主密钥只从 Keystore 取一次。
     *
     * 每次解密都重新 load KeyStore + getEntry 会为每条密钥付一次 IPC，
     * 几十条账号累计就是几百毫秒；SecretKey 只是 Keystore 句柄，可安全复用。
     */
    @Volatile private var cachedKey: SecretKey? = null

    private fun masterKey(): SecretKey =
        cachedKey ?: synchronized(this) {
            cachedKey ?: loadOrCreateKey().also { cachedKey = it }
        }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tessera_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
    }
}
