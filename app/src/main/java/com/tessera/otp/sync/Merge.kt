package com.tessera.otp.sync

import com.tessera.otp.data.db.entity.CategoryEntity

/** 合并统计，用于同步状态展示。 */
data class MergeOutcome(
    val merged: List<VaultEntry>,
    val localWins: Int,
    val remoteWins: Int,
    val addedLocally: Int,
    val addedRemotely: Int
)

/** 分类合并结果。idRemap：被判为「同名重复」的失败方 id -> 保留方 id，条目引用要跟着改。 */
data class CategoryMergeOutcome(
    val merged: List<VaultCategory>,
    val idRemap: Map<String, String>
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

    /**
     * 分类合并。
     *
     * 规则与条目一致（按 id 比 updatedAt，删除在时间相同时优先），但多一步**同名去重**：
     * 分类表在 name 上有唯一索引，两台设备各自新建同名分类会产生两条同名活分类，
     * 直接落库会互相覆盖（静默丢数据）。因此同名组只保留 updatedAt 最新者（相同取 id 小者，
     * 保证双方结果一致），其余改写成墓碑（name 换成含 id 的唯一串）并把引用重映射到保留方。
     */
    fun mergeCategories(
        local: List<VaultCategory>,
        remote: List<VaultCategory>,
        now: Long = System.currentTimeMillis()
    ): CategoryMergeOutcome {
        val byId = LinkedHashMap<String, VaultCategory>(local.size + remote.size)
        for (category in local) byId[category.id] = category
        for (category in remote) {
            val current = byId[category.id]
            byId[category.id] = if (current == null || preferRemoteCategory(current, category)) category else current
        }

        val idRemap = HashMap<String, String>()
        val dropped = HashSet<String>()
        byId.values
            .filter { !it.isDeleted }
            .groupBy { it.name.trim().lowercase() }
            .forEach { (_, group) ->
                if (group.size <= 1) return@forEach
                val winner = group
                    .sortedWith(compareByDescending<VaultCategory> { it.updatedAt }.thenBy { it.id })
                    .first()
                for (category in group) {
                    if (category.id == winner.id) continue
                    idRemap[category.id] = winner.id
                    dropped += category.id
                }
            }

        val tombstones = dropped.map { id ->
            val original = byId.getValue(id)
            VaultCategory(
                id = id,
                name = CategoryEntity.tombstoneName(id),
                colorArgb = original.colorArgb,
                sortOrder = original.sortOrder,
                updatedAt = maxOf(original.updatedAt, now),
                deletedAt = now
            )
        }

        val merged = (byId.values.filter { it.id !in dropped } + tombstones).sortedBy { it.id }
        return CategoryMergeOutcome(merged, idRemap)
    }

    /** true 表示采用远端分类版本；时间相同时「已删除」优先，保证删除能传播。 */
    private fun preferRemoteCategory(local: VaultCategory, remote: VaultCategory): Boolean = when {
        remote.updatedAt != local.updatedAt -> remote.updatedAt > local.updatedAt
        else -> remote.isDeleted && !local.isDeleted
    }
}
