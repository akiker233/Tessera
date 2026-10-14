package com.tessera.otp.sync

import android.content.Context
import com.tessera.otp.data.keystore.SecretCipher
import com.tessera.otp.data.repo.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次同步的结果。 */
data class SyncOutcome(
    val success: Boolean,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val message: String,
    val lastSyncAt: Long = System.currentTimeMillis()
)

/**
 * 同步口令存储。
 *
 * 口令本身用 Keystore 主密钥加密后落盘（与数据库口令同一把主密钥、不同 IV），
 * 因此即使备份了 SharedPreferences，也无法在别的设备上离线解出同步口令。
 */
class SyncPassphraseStore(context: Context, private val cipher: SecretCipher) {

    private val prefs = context.getSharedPreferences("tessera_sync", Context.MODE_PRIVATE)

    var passphrase: String?
        get() = prefs.getString(KEY_PASSPHRASE, null)?.let { encoded ->
            runCatching { String(cipher.decrypt(encoded), Charsets.UTF_8) }.getOrNull()
        }
        set(value) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove(KEY_PASSPHRASE).apply()
            } else {
                val encoded = cipher.encrypt(value.toByteArray(Charsets.UTF_8))
                prefs.edit().putString(KEY_PASSPHRASE, encoded).apply()
            }
        }

    private companion object {
        const val KEY_PASSPHRASE = "sync_passphrase_v1"
    }
}

/**
 * 同步引擎：拉取 -> 合并 -> 推送。
 *
 * 合并策略是"远端不存在直接推送、存在则按 rev/updatedAt 逐条合并后再推送"；
 * 云端包（v2）携带明文 Base32 密钥、外层由同步口令加密——否则换机永远解不开；
 * 落库前再由仓库用本机 Keystore 主密钥加密，明文只在内存中短暂存在。
 */
class SyncEngine(
    private val repository: AccountRepository,
    private val passphraseStore: SyncPassphraseStore,
    private val providerOverride: SyncProvider? = null,
) {

    suspend fun sync(provider: SyncProvider = providerOverride ?: error("需要提供 SyncProvider")): SyncOutcome =
        syncAll(listOf(provider))

    /**
     * 多后端同步：先把所有后端拉下来合并成一份，再把最终结果推回每个后端。
     *
     * 要点：
     *  1. 逐个 pull，某个后端失败不影响其它后端（原因收集后一并展示）；
     *  2. 拉取失败或**解码失败**的后端不会被推送——避免把不完整结果覆盖上去；
     *     解码失败（多为口令不一致）绝不能当成「云端为空」，否则会拿本地覆盖别人；
     *  3. etag 只在同一个后端内复用（乐观并发），不跨后端；
     *  4. 所有后端都为空时，消息与单后端一致：已上传 N 条（云端此前为空）。
     */
    suspend fun syncAll(providers: List<SyncProvider>): SyncOutcome = withContext(Dispatchers.IO) {
        val passphrase = passphraseStore.passphrase
            ?: return@withContext SyncOutcome(false, message = "尚未设置同步口令")
        if (providers.isEmpty()) {
            return@withContext SyncOutcome(false, message = "尚未选择同步方式")
        }

        val names = providers.joinToString(" + ") { it.displayName }
        try {
            // 本地导出时必须带上明文密钥：云端包要能在别的设备上恢复
            val localPairs = repository.entitiesWithSecrets()
            var mergedEntries = SyncCodec.toSnapshot(localPairs).entries
            var mergedCategories = repository.categoriesWithDeleted().map { VaultCategory.from(it) }
            // 同名分类合并后，失败方条目的引用要改指向保留方
            val categoryRemap = HashMap<String, String>()
            val etags = HashMap<SyncProvider, String?>()
            val failed = LinkedHashMap<String, String>()
            var addedRemotely = 0
            var addedLocally = 0
            var remoteWins = 0
            var localWins = 0
            var remoteHadData = false
            // 旧格式（v1）同步包里本机解不开的条目数
            var skippedLegacy = 0

            for (provider in providers) {
                val remote = try {
                    provider.pull()
                } catch (t: Throwable) {
                    failed[provider.displayName] = t.message ?: t::class.java.simpleName
                    continue
                }
                if (remote == null || remote.content.isBlank()) {
                    etags[provider] = null
                    continue
                }
                val remoteSnapshot = try {
                    // v1 包里的密钥是别的设备 Keystore 的密文：能解开就转成可移植格式，解不开就丢弃
                    val portable = SyncCodec.decode(remote.content, passphrase)
                        .toPortable { encoded -> repository.tryDecryptSecret(encoded) }
                    skippedLegacy += portable.second
                    portable.first
                } catch (t: Throwable) {
                    failed[provider.displayName] = "解码失败（同步口令是否一致？）"
                    continue
                }
                remoteHadData = true
                val outcome = Merge.merge(mergedEntries, remoteSnapshot.entries)
                mergedEntries = outcome.merged
                addedRemotely += outcome.addedRemotely
                addedLocally += outcome.addedLocally
                remoteWins += outcome.remoteWins
                localWins += outcome.localWins

                val categoryOutcome = Merge.mergeCategories(mergedCategories, remoteSnapshot.categories)
                mergedCategories = categoryOutcome.merged
                categoryRemap.putAll(categoryOutcome.idRemap)

                etags[provider] = remote.etag
            }

            if (categoryRemap.isNotEmpty()) {
                mergedEntries = mergedEntries.map { entry ->
                    val remapped = entry.categoryId?.let { categoryRemap[it] }
                    if (remapped != null) entry.copy(categoryId = remapped) else entry
                }
            }

            // 只写「本机拿得到明文密钥」的条目，由仓库用本机主密钥重新加密后落库
            val mergedPairs = mergedEntries.mapNotNull { it.toEntityAndSecret() }
            repository.muted {
                repository.upsertCategories(mergedCategories.map(VaultCategory::toEntity))
                repository.replaceAllWithSecrets(mergedPairs)
            }

            val payload = SyncCodec.encode(
                SyncCodec.toSnapshot(mergedPairs, mergedCategories.map(VaultCategory::toEntity)),
                passphrase
            )
            for (provider in providers) {
                if (failed.containsKey(provider.displayName)) continue
                try {
                    provider.push(payload, etags[provider])
                } catch (t: Throwable) {
                    failed[provider.displayName] = t.message ?: t::class.java.simpleName
                }
            }

            val activeCategories = mergedCategories.count { !it.isDeleted }
            val categoryNote = if (activeCategories > 0) "，分类 " + activeCategories + " 个" else ""
            val base = if (remoteHadData) {
                names + " · 同步完成：" + mergedPairs.size + " 条" + categoryNote + "（远端新增 " + addedRemotely +
                    "，本地新增 " + addedLocally + "）"
            } else {
                names + " · 已上传 " + mergedPairs.size + " 条" + categoryNote + "（云端此前为空）"
            } + if (skippedLegacy > 0) {
                "；跳过 " + skippedLegacy + " 条本机解不开的旧格式条目（请到有原始数据的设备上再同步一次，把包升级成可移植格式）"
            } else ""
            SyncOutcome(
                success = failed.isEmpty(),
                pulled = addedRemotely + remoteWins,
                pushed = addedLocally + localWins,
                message = if (failed.isEmpty()) base
                else base + "；失败：" + failed.entries.joinToString("、") { it.key + "(" + it.value + ")" }
            )
        } catch (t: Throwable) {
            SyncOutcome(false, message = names + " · " + (t.message ?: t::class.java.simpleName))
        }
    }
}

