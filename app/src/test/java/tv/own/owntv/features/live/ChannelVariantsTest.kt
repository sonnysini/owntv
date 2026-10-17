package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelVariantRow

/** Fork: quality variants of one channel fold into one row; distinct channels never do. */
class ChannelVariantsTest {

    private fun row(id: Long, name: String, category: Long? = 1) = ChannelVariantRow(id, 1, category, name, id.toInt())

    private val rai = listOf(
        row(1, "Rai 1 RAW"),
        row(2, "RAI 1 HEVC"),
        row(3, "Rai 1 FHD"),
        row(4, "Rai 1 HD"),
        row(5, "Rai 1 +1 HD"),
        row(6, "Rai 1 SD"),
        row(7, "Rai 2 HD"),
        row(8, "Rai 2 SD"),
        row(9, "Rai 3 HD"),
    )

    @Test
    fun `variants fold under the best quality`() {
        val idx = ChannelVariantIndex.build(rai, emptyMap())
        assertEquals(listOf(3L, 4L, 6L, 1L, 2L), idx.variantsOf(1).map { it.id })
        assertEquals(listOf("FHD", "HD", "SD", "RAW", "HEVC"), idx.variantsOf(1).map { it.label })
        assertTrue(idx.isVisible(3))
        assertFalse(idx.isVisible(1))
        assertFalse(idx.isVisible(4))
    }

    @Test
    fun `timeshift and other channels stay their own rows`() {
        val idx = ChannelVariantIndex.build(rai, emptyMap())
        assertTrue(idx.isVisible(5))
        assertTrue(idx.variantsOf(5).isEmpty())
        assertTrue(idx.isVisible(9))
        assertEquals(listOf(7L, 8L), idx.variantsOf(8).map { it.id })
        assertEquals(setOf(1L, 2L, 4L, 6L, 8L), idx.hidden)
    }

    @Test
    fun `the picked variant becomes the row`() {
        val key = ChannelVariantIndex.build(rai, emptyMap()).variantsOf(1).first().groupKey
        val idx = ChannelVariantIndex.build(rai, mapOf(key to 4L))
        assertTrue(idx.isVisible(4))
        assertFalse(idx.isVisible(3))
        assertEquals(listOf(3L, 6L, 1L, 2L), idx.siblingsOf(4))
    }

    @Test
    fun `a pick for a channel that no longer exists falls back to the best`() {
        val key = ChannelVariantIndex.build(rai, emptyMap()).variantsOf(1).first().groupKey
        val idx = ChannelVariantIndex.build(rai, mapOf(key to 999L))
        assertTrue(idx.isVisible(3))
    }

    @Test
    fun `the same name in two categories is two rows`() {
        val idx = ChannelVariantIndex.build(listOf(row(1, "Rai 1 HD", 1), row(2, "Rai 1 SD", 2)), emptyMap())
        assertTrue(idx.hidden.isEmpty())
    }

    @Test
    fun `superscript and country decorations do not split a group`() {
        val idx = ChannelVariantIndex.build(listOf(row(1, "IT| Canale 5 ᴴᴰ"), row(2, "IT: CANALE 5 FHD")), emptyMap())
        assertEquals(listOf(2L, 1L), idx.variantsOf(1).map { it.id })
    }

    @Test
    fun `two-word quality tags fold too`() {
        val idx = ChannelVariantIndex.build(listOf(row(1, "Rai 2 4K"), row(2, "Rai 2 Full HD"), row(3, "Rai 2 Ultra HD")), emptyMap())
        assertEquals(listOf("4K", "UHD", "FHD"), idx.variantsOf(2).map { it.label })
    }
}
