package com.tessera.otp.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页文件夹拖动的纯逻辑：本地顺序应用与空分类占位合并。 */
class HomeGroupOrderTest {

    private fun group(key: String) = HomeGroup(key, key, null, emptyList())

    @Test
    fun applyGroupOrder_reordersAndKeepsUnknownAtEnd() {
        val groups = listOf(group("a"), group("b"), group("ungrouped"))

        val result = applyGroupOrder(groups, listOf("b", "a"))

        assertEquals(listOf("b", "a", "ungrouped"), result.map { it.key })
    }

    @Test
    fun applyGroupOrder_ignoresStaleKeys() {
        val groups = listOf(group("a"), group("ungrouped"))

        val result = applyGroupOrder(groups, listOf("deleted", "a"))

        assertEquals(listOf("a", "ungrouped"), result.map { it.key })
    }

    @Test
    fun applyGroupOrder_nullKeepsDatabaseOrder() {
        val groups = listOf(group("a"), group("b"))

        assertEquals(groups, applyGroupOrder(groups, null))
    }

    @Test
    fun mergeCategoryOrder_keepsEmptyCategoriesInPlace() {
        val all = listOf("empty1", "visible1", "empty2", "visible2")

        val merged = mergeCategoryOrder(all, listOf("visible2", "visible1"))

        assertEquals(listOf("empty1", "visible2", "empty2", "visible1"), merged)
    }

    @Test
    fun mergeCategoryOrder_unchangedOrderStaysSame() {
        val all = listOf("a", "b", "c")

        assertEquals(all, mergeCategoryOrder(all, listOf("a", "c")))
    }

    @Test
    fun mergeCategoryOrder_ignoresIdsNoLongerPresent() {
        val all = listOf("a", "b")

        val merged = mergeCategoryOrder(all, listOf("gone", "b", "a"))

        assertEquals(listOf("b", "a"), merged)
    }

    @Test
    fun mergeCategoryOrder_emptyVisibleLeavesAll() {
        val all = listOf("a", "b")

        assertEquals(all, mergeCategoryOrder(all, emptyList()))
    }

    // ---- 「未分类」参与排序 ----

    @Test
    fun orderHomeGroups_nullAnchorKeepsUngroupedLast() {
        val groups = listOf(group("a"), group("b"), group("ungrouped"))

        val result = orderHomeGroups(groups, listOf("a", "b"), null)

        assertEquals(listOf("a", "b", "ungrouped"), result.map { it.key })
    }

    @Test
    fun orderHomeGroups_emptyAnchorMovesUngroupedFirst() {
        val groups = listOf(group("a"), group("b"), group("ungrouped"))

        val result = orderHomeGroups(groups, listOf("a", "b"), "")

        assertEquals(listOf("ungrouped", "a", "b"), result.map { it.key })
    }

    @Test
    fun orderHomeGroups_anchorAfterCategory() {
        val groups = listOf(group("a"), group("b"), group("ungrouped"))

        val result = orderHomeGroups(groups, listOf("a", "b"), "a")

        assertEquals(listOf("a", "ungrouped", "b"), result.map { it.key })
    }

    @Test
    fun orderHomeGroups_emptyCategoryStillPositionsUngrouped() {
        // empty 分类没有条目、首页不显示，但锚点仍按全量分类定位
        val groups = listOf(group("a"), group("b"), group("ungrouped"))

        val result = orderHomeGroups(groups, listOf("a", "empty", "b"), "empty")

        assertEquals(listOf("a", "ungrouped", "b"), result.map { it.key })
    }

    @Test
    fun orderHomeGroups_staleAnchorFallsBackToEnd() {
        val groups = listOf(group("a"), group("ungrouped"))

        val result = orderHomeGroups(groups, listOf("a"), "deleted")

        assertEquals(listOf("a", "ungrouped"), result.map { it.key })
    }

    @Test
    fun orderHomeGroups_unchangedWithoutUngroupedGroup() {
        val groups = listOf(group("a"), group("b"))

        assertEquals(groups, orderHomeGroups(groups, listOf("a", "b"), ""))
    }

    @Test
    fun ungroupedAnchorOf_firstIsEmpty() {
        assertEquals("", ungroupedAnchorOf(listOf("ungrouped", "a", "b")))
    }

    @Test
    fun ungroupedAnchorOf_returnsPreviousCategory() {
        assertEquals("a", ungroupedAnchorOf(listOf("a", "ungrouped", "b")))
        assertEquals("b", ungroupedAnchorOf(listOf("a", "b", "ungrouped")))
    }

    @Test
    fun ungroupedAnchorOf_missingGroupIsEmpty() {
        assertEquals("", ungroupedAnchorOf(listOf("a", "b")))
    }
}
