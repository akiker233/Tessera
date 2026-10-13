package com.tessera.otp.di

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN 与锁定状态存储。
 *
 * PIN 不明文落盘：使用 PBKDF2-HMAC-SHA256（210000 次迭代 + 16 字节随机盐）派生出校验值；
 * 迭代次数与盐都显式保存，便于后续升级参数而不破坏已有用户。
 */
class LockPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("tessera_lock", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = prefs.contains(KEY_HASH)

    /** 设置或修改 PIN。 */
    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(KEY_ITER, ITERATIONS)
            .putInt(KEY_FAILS, 0)
            .apply()
    }

    /** 校验 PIN；成功后清零失败计数，失败则累加。 */
    fun verifyPin(pin: String): Boolean {
        val salt = decode(prefs.getString(KEY_SALT, null)) ?: return false
        val expected = decode(prefs.getString(KEY_HASH, null)) ?: return false
        val iterations = prefs.getInt(KEY_ITER, ITERATIONS)
        val actual = derive(pin, salt, iterations)
        val ok = MessageDigest.isEqual(expected, actual)
        if (ok) prefs.edit().putInt(KEY_FAILS, 0).apply() else registerFailure()
        return ok
    }

    fun clearPin() = prefs.edit().clear().apply()

    val failedAttempts: Int get() = prefs.getInt(KEY_FAILS, 0)

    /** 失败节流：第 5 次起要求等待，返回需要等待的秒数（0 表示无需等待）。 */
    fun throttleSeconds(): Int {
        val fails = failedAttempts
        return if (fails < FAIL_THRESHOLD) 0 else ((fails - FAIL_THRESHOLD + 1) * 5).coerceAtMost(300)
    }

    private fun registerFailure() {
        prefs.edit().putInt(KEY_FAILS, failedAttempts + 1).apply()
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun decode(value: String?): ByteArray? =
        value?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    companion object {
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_ITER = "pin_iterations"
        private const val KEY_FAILS = "pin_failures"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val ITERATIONS = 210_000
        private const val FAIL_THRESHOLD = 5
    }
}
