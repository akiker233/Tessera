package com.tessera.otp.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 组内排序的纯逻辑：组外账号位置不变，组内成员按新顺序填回原槽位。 */
class GroupOrderTest {

    @Test
    fun mergeGroupOrder_keepsOtherGroupsInPlace() {
        val all = listOf("a1", "b1", "a2", "c1", "a3")

        val merged = mergeGroupOrder(all, listOf("a3", "a1", "a2"))

        assertEquals(listOf("a3", "b1", "a1", "c1", "a2"), merged)
    }

    @Test
    fun mergeGroupOrder_reordersContiguousGroup() {
        val all = listOf("x", "y", "z", "w")

        val merged = mergeGroupOrder(all, listOf("z", "w", "x", "y"))

        assertEquals(listOf("z", "w", "x", "y"), merged)
    }

    @Test
    fun mergeGroupOrder_unknownIdIsIgnored() {
        assertNull(mergeGroupOrder(listOf("a", "b"), listOf("a", "missing")))
    }

    @Test
    fun mergeGroupOrder_duplicateIdIsIgnored() {
        assertNull(mergeGroupOrder(listOf("a", "b", "c"), listOf("a", "a", "b")))
    }

    @Test
    fun mergeGroupOrder_singleMemberNeedsNoWrite() {
        assertNull(mergeGroupOrder(listOf("a", "b"), listOf("a")))
    }
}
