package com.tessera.otp.sync

/** 合并统计，用于同步状态展示。 */
data class MergeOutcome(
    val merged: List<VaultEntry>,
    val localWins: Int,
    val remoteWins: Int,
    val addedLocally: Int,
    val addedRemotely: Int
)

/**
 * 条目级合并。
 *
 * 规则（刻意保持简单且可预测）：
 *  1. 以 id 为主键；只在一侧出现 => 收录，并统计"本地新增/远端新增"。
 *  2. 两侧都有 => 先比 syncRev（大者胜），相同再比 updatedAt（新者胜），仍相同则保留本地。
 *  3. 墓碑（deletedAt 非空）作为普通版本参与比较：删除也会被同步到其他设备。
 *  4. 结果对相同输入稳定（不依赖遍历顺序），便于测试与冲突复现。
 */
object Merge {

    fun merge(local: List<VaultEntry>, remote: List<VaultEntry>): MergeOutcome {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val ids = (localById.keys + remoteById.keys).sorted()

        val merged = ArrayList<VaultEntry>(ids.size)
        var localWins = 0
        var remoteWins = 0
        var addedLocally = 0
        var addedRemotely = 0

        for (id in ids) {
            val localEntry = localById[id]
            val remoteEntry = remoteById[id]
            when {
                localEntry != null && remoteEntry == null -> {
                    merged += localEntry
                    addedLocally++
                }
                localEntry == null && remoteEntry != null -> {
                    merged += remoteEntry
                    addedRemotely++
                }
                localEntry != null && remoteEntry != null -> {
                    if (preferRemote(localEntry, remoteEntry)) {
                        merged += remoteEntry
                        remoteWins++
                    } else {
                        merged += localEntry
                        localWins++
                    }
                }
            }
        }
        return MergeOutcome(merged, localWins, remoteWins, addedLocally, addedRemotely)
    }

    /** true 表示采用远端版本。 */
    private fun preferRemote(local: VaultEntry, remote: VaultEntry): Boolean = when {
        remote.syncRev != local.syncRev -> remote.syncRev > local.syncRev
        remote.updatedAt != local.updatedAt -> remote.updatedAt > local.updatedAt
        else -> false
    }
}
