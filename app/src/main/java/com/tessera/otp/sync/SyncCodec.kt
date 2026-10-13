package com.tessera.otp.sync

import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.domain.Base32
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 上传到云端的单条记录（格式 v2）。
 *
 * 安全边界：整包（本类序列化出的 JSON）已用**同步口令**经 PBKDF2 派生的 AES-256-GCM 加密，
 * 所以这里携带的是**可移植的明文 Base32 密钥**——换台设备只要口令相同就能恢复。
 * v1 曾把「生成备份那台设备的 Keystore 密文」放进来，别的设备永远解不开，故已废弃。
 */
@Serializable
data class VaultEntry(
    @SerialName("id") val id: String,
    @SerialName("issuer") val issuer: String = "",
    @SerialName("account") val accountName: String = "",
    @SerialName("secret") val secretBase32: String = "",
    @SerialName("algo") val algorithm: String = "SHA1",
    @SerialName("digits") val digits: Int = 6,
    @SerialName("period") val period: Int = 30,
    @SerialName("steam") val isSteam: Boolean = false,
    @SerialName("hotp") val isHotp: Boolean = false,
    @SerialName("counter") val counter: Long = 0L,
    @SerialName("category") val categoryId: String? = null,
    @SerialName("icon") val iconKey: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("createdAt") val createdAt: Long = 0L,
    @SerialName("updatedAt") val updatedAt: Long = 0L,
    @SerialName("deletedAt") val deletedAt: Long? = null,
    @SerialName("rev") val syncRev: Long = 0L
)

@Serializable
data class VaultSnapshot(
    @SerialName("version") val version: Int = 1,
    @SerialName("updatedAt") val updatedAt: Long = 0L,
    @SerialName("entries") val entries: List<VaultEntry> = emptyList()
)

/**
 * 同步包编解码。
 *
 * 安全设计：云端文件是 AES-256-GCM 加密的 JSON，密钥由「同步口令」经 PBKDF2-HMAC-SHA256 派生
 * （与 App 的 PIN、Android Keystore 主密钥完全独立）。因此：
 *  - 云服务商/GitHub 等第三方看不到任何密钥或账号信息；
 *  - 换机只需同步口令 + 云端文件即可恢复（v2 起包内携带明文密钥，这是跨设备恢复的前提）；
 *  - 代价要说清楚：**口令即钥匙**——拿到口令的人可以解出全部密钥，请用足够强的口令，
 *    并且不要把口令与备份放在同一处。本地库仍由设备 Keystore 加密，口令泄露不影响未同步的本地数据。
 */
object SyncCodec {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16

    /** 当前包格式：条目密钥为明文 Base32（外层已由同步口令加密）。 */
    const val CURRENT_VERSION = 2

    fun toSnapshot(
        entries: List<Pair<AccountEntity, ByteArray>>,
        now: Long = System.currentTimeMillis()
    ): VaultSnapshot =
        VaultSnapshot(
            version = CURRENT_VERSION,
            updatedAt = now,
            entries = entries.map { (entity, secret) ->
                VaultEntry(
                    id = entity.id,
                    issuer = entity.issuer,
                    accountName = entity.accountName,
                    secretBase32 = Base32.encode(secret),
                    algorithm = entity.algorithm,
                    digits = entity.digits,
                    period = entity.period,
                    isSteam = entity.isSteam,
                    isHotp = entity.isHotp,
                    counter = entity.counter,
                    categoryId = entity.categoryId,
                    iconKey = entity.iconKey,
                    note = entity.note,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    deletedAt = entity.deletedAt,
                    syncRev = entity.syncRev
                )
            }
        )

    /**
     * 加密为可上传文本。
     *
     * 用 java.util.Base64（API 26+ 自带）而不是 android.util.Base64，这样编解码逻辑能在
     * 纯 JVM 单测里跑完整往返——换机恢复的前提正是这段代码，必须有测试守。
     */
    fun encode(snapshot: VaultSnapshot, passphrase: String): String {
        val plain = json.encodeToString(VaultSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val payload = buildString {
            append("{\"v\":1,\"kdf\":\"pbkdf2-sha256\",\"iter\":")
            append(ITERATIONS)
            append(",\"salt\":\"")
            append(java.util.Base64.getEncoder().encodeToString(salt))
            append("\",\"nonce\":\"")
            append(java.util.Base64.getEncoder().encodeToString(iv))
            append("\",\"ct\":\"")
            append(java.util.Base64.getEncoder().encodeToString(ct))
            append("\"}")
        }
        return payload
    }

    /** 解密云端文本；口令错误或密文被篡改会抛异常（GCM 认证失败）。 */
    fun decode(payload: String, passphrase: String): VaultSnapshot {
        val root = json.parseToJsonElement(payload.trim()).jsonObject
        val decoder = java.util.Base64.getDecoder()
        val salt = decoder.decode(root.string("salt"))
        val nonce = decoder.decode(root.string("nonce"))
        val ct = decoder.decode(root.string("ct"))
        val iterations = root["iter"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ITERATIONS
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val plain = cipher.doFinal(ct)
        return json.decodeFromString(VaultSnapshot.serializer(), String(plain, Charsets.UTF_8))
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("缺少字段：" + key)

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}

/**
 * 把云端包统一成**可移植格式**（v2）。
 *
 * v1 包里的 "secret" 是「生成备份那台设备的 Keystore 密文」，只有那台设备解不开才怪；
 * 换机/重装（Keystore 条目随之丢失）后本机**永远**解不开，旧版本正是这样把毒条目写进库，
 * 导致首页每次读取都解密失败、应用一打开就崩且无法自救。
 *
 * @param decryptLegacy 旧格式密文的解密器，本机解不开时返回 null
 * @return 归一化后的包 to 被丢弃的条目数（丢弃的都是本机永远解不开的）
 */
fun VaultSnapshot.toPortable(decryptLegacy: (String) -> ByteArray?): Pair<VaultSnapshot, Int> {
    if (version >= SyncCodec.CURRENT_VERSION) return this to 0
    var skipped = 0
    val converted = entries.mapNotNull { entry ->
        val secret = decryptLegacy(entry.secretBase32)
        if (secret == null || secret.isEmpty()) {
            skipped++
            null
        } else {
            entry.copy(secretBase32 = Base32.encode(secret))
        }
    }
    return VaultSnapshot(version = SyncCodec.CURRENT_VERSION, updatedAt = updatedAt, entries = converted) to skipped
}

/**
 * 可移植条目 -> (Room 实体, 明文密钥)；密钥缺失或 Base32 非法时返回 null（调用方跳过该条）。
 * 实体里的 secretEnc 留空：真正的加密由仓库用本机主密钥完成，同步层不碰 Keystore。
 */
fun VaultEntry.toEntityAndSecret(): Pair<AccountEntity, ByteArray>? {
    val secret = runCatching { Base32.decode(secretBase32) }.getOrNull() ?: return null
    if (secret.isEmpty()) return null
    return AccountEntity(
        id = id,
        issuer = issuer,
        accountName = accountName,
        secretEnc = "",
        algorithm = algorithm,
        digits = digits,
        period = period,
        isSteam = isSteam,
        isHotp = isHotp,
        counter = counter,
        categoryId = categoryId,
        iconKey = iconKey,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncRev = syncRev
    ) to secret
}
