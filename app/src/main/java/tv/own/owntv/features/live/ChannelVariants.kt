package tv.own.owntv.features.live

import tv.own.owntv.core.database.dao.ChannelVariantRow

/**
 * Fork (sonnysini/owntv): fold the quality variants providers ship for one channel — `Rai 1 FHD`,
 * `Rai 1 HD`, `Rai 1 SD`, `RAI 1 HEVC`, `Rai 1 RAW` — into a single Live row. The row shows the
 * variant the user last picked for that channel (best quality until then); the others stay reachable
 * from the player's Quality button.
 *
 * Variants only fold inside one playlist and one provider category, so the same name in two
 * different folders stays two rows.
 */
data class ChannelVariant(
    val id: Long,
    /** `FHD`, `HD`, `RAW`… — or the channel name when the provider put no quality in it. */
    val label: String,
    /** Identifies the group across syncs (channel ids are not stable): `source:category:groupKey`. */
    val groupKey: String,
)

class ChannelVariantIndex private constructor(
    /** Variants that are folded away: never shown as rows, never stepped onto by CH+/CH−. */
    val hidden: Set<Long>,
    private val groups: Map<Long, List<ChannelVariant>>,
) {
    /** Every variant of [channelId]'s channel, best first; empty when the channel has only one. */
    fun variantsOf(channelId: Long): List<ChannelVariant> = groups[channelId].orEmpty()

    /** The ids of the other variants of [channelId]'s channel. */
    fun siblingsOf(channelId: Long): List<Long> = variantsOf(channelId).map { it.id }.filter { it != channelId }

    fun isVisible(channelId: Long): Boolean = channelId !in hidden

    companion object {
        val EMPTY = ChannelVariantIndex(emptySet(), emptyMap())

        /** [picked] maps a group key to the variant id the user chose for it. */
        fun build(rows: List<ChannelVariantRow>, picked: Map<String, Long>): ChannelVariantIndex {
            data class Entry(val row: ChannelVariantRow, val rank: Int, val label: String)

            val byGroup = HashMap<String, MutableList<Entry>>()
            for (row in rows) {
                val n = ChannelNameNormalizer.normalize(row.name)
                if (n.groupKey.isEmpty()) continue
                val key = "${row.sourceId}:${row.categoryId ?: -1}:${n.groupKey}"
                byGroup.getOrPut(key) { ArrayList(2) } += Entry(row, n.qualityRank, n.qualityLabel)
            }
            val hidden = HashSet<Long>()
            val groups = HashMap<Long, List<ChannelVariant>>()
            for ((key, entries) in byGroup) {
                if (entries.size < 2) continue
                val ordered = entries.sortedWith(
                    compareByDescending<Entry> { it.rank }.thenBy { it.row.sortOrder }.thenBy { it.row.id },
                )
                val variants = ordered.map { ChannelVariant(it.row.id, it.label.ifBlank { it.row.name }, key) }
                val shown = picked[key]?.takeIf { id -> variants.any { it.id == id } } ?: variants.first().id
                for (v in variants) {
                    groups[v.id] = variants
                    if (v.id != shown) hidden += v.id
                }
            }
            return ChannelVariantIndex(hidden, groups)
        }
    }
}

/**
 * Turns the channel names IPTV providers ship into a group key and a quality rank/label.
 *
 * Ported from OpenTV (opentvproject/opentv, GPL-3.0-or-later, `ChannelNameNormalizer`), trimmed to
 * what variant folding needs.
 */
object ChannelNameNormalizer {

    data class Normalized(
        /** Lowercase alphanumerics of the name without quality tags: `rai1`. */
        val groupKey: String,
        /** Higher is better. 0 when the name carries no resolution. */
        val qualityRank: Int,
        /** `FHD`, `HD`, `RAW`, `HEVC`… or `""`. */
        val qualityLabel: String,
    )

    private val QUALITY_RANKS = mapOf(
        "8K" to 500,
        "4K" to 400, "UHD" to 400, "FUHD" to 400, "2160P" to 400,
        "FHD" to 300, "1080" to 300, "1080P" to 300, "FULLHD" to 300,
        "HD" to 200, "720P" to 200, "720" to 200,
        "SD" to 100, "LQ" to 100, "480P" to 100, "576P" to 100,
    )

    /** Tokens that describe the stream but not its resolution: stripped from the key, kept in the label. */
    private val EXTRA_TOKENS = setOf(
        "RAW", "HEVC", "H265", "H.265", "H264", "H.264", "AV1",
        "50FPS", "60FPS", "25FPS", "30FPS", "FPS",
        "HDR", "DOLBY", "VISION", "PLUS",
    )

    /** Superscript/modifier letters providers decorate names with (`ᴴᴰ`, `ʰᵉᵛᶜ`), folded to ASCII. */
    private val SUPERSCRIPT_MAP: Map<Char, Char> = buildMap {
        val upper = "ᴬ:A,ᴮ:B,ꟲ:C,ᴰ:D,ᴱ:E,ꟳ:F,ᴳ:G,ᴴ:H,ᴵ:I,ᴶ:J,ᴷ:K,ᴸ:L,ᴹ:M,ᴺ:N," +
            "ᴼ:O,ᴾ:P,ꟴ:Q,ᴿ:R,ᵀ:T,ᵁ:U,ⱽ:V,ᵂ:W,ᔆ:S"
        val lower = "ᵃ:a,ᵇ:b,ᶜ:c,ᵈ:d,ᵉ:e,ᶠ:f,ᵍ:g,ʰ:h,ᶦ:i,ʲ:j,ᵏ:k,ˡ:l,ᵐ:m,ⁿ:n," +
            "ᵒ:o,ᵖ:p,ʳ:r,ˢ:s,ᵗ:t,ᵘ:u,ᵛ:v,ʷ:w,ˣ:x,ʸ:y,ᶻ:z"
        val digits = "⁰:0,¹:1,²:2,³:3,⁴:4,⁵:5,⁶:6,⁷:7,⁸:8,⁹:9"
        for (chunk in "$upper,$lower,$digits".split(',')) {
            if (chunk.length >= 3) put(chunk[0], chunk[2])
        }
    }

    /** Leading country tag: `UK|`, `IT:`, `[UK]`, `(US)`. Kept out of the key so it cannot split a group. */
    private val COUNTRY_PREFIX = Regex("""^\s*(?:[\[(]([A-Z]{2,3})[\])]\s*[|:\-–—]?|([A-Z]{2,3})\s*[|:\-–—])\s*""")
    /** Two-word tags folded to their one-word form first: `Full HD` → `FHD`, `Ultra HD` → `UHD`. */
    private val FULL_HD = Regex("""(?i)\bfull\s*hd\b""")
    private val ULTRA_HD = Regex("""(?i)\bultra\s*hd\b""")
    private val DECORATION = Regex("""[^\p{L}\p{Nd} &+.\-/:|()']+""")
    private val CAMEL_BOUNDARY = Regex("""([a-z])([A-Z])""")
    private val NON_ALNUM = Regex("""[^a-zA-Z0-9]+""")
    private val NUMBER_WORDS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )

    private fun foldSuperscripts(raw: String): String {
        if (raw.none { it in SUPERSCRIPT_MAP }) return raw
        return buildString(raw.length) { for (c in raw) append(SUPERSCRIPT_MAP[c] ?: c) }
    }

    fun normalize(raw: String): Normalized {
        var working = foldSuperscripts(raw.trim())
        COUNTRY_PREFIX.find(working)?.let { working = working.removeRange(it.range) }
        working = ULTRA_HD.replace(FULL_HD.replace(working, "FHD"), "UHD")
        working = DECORATION.replace(working, " ")

        var bestRank = 0
        val labelParts = ArrayList<String>(2)
        val kept = ArrayList<String>()
        for (token in working.split(' ', '\t', '/')) {
            val bare = token.trim('.', ',', ':', ';', '|', '-', '(', ')', '[', ']')
            if (bare.isEmpty()) continue
            val upper = bare.uppercase()
            val rank = QUALITY_RANKS[upper]
            when {
                rank != null -> {
                    if (rank > bestRank) bestRank = rank
                    labelParts += upper
                }
                upper in EXTRA_TOKENS -> labelParts += upper
                else -> kept += bare
            }
        }
        val key = groupKeyOf(kept.joinToString(" "))
        return Normalized(
            groupKey = key.ifEmpty { groupKeyOf(raw) },
            qualityRank = bestRank,
            qualityLabel = labelParts.joinToString(" "),
        )
    }

    /** Lowercase letters and digits only, number words folded (`BBC One` = `BBC 1` = `bbc1`). */
    fun groupKeyOf(name: String): String =
        CAMEL_BOUNDARY.replace(name, "$1 $2").lowercase()
            .split(NON_ALNUM)
            .filter { it.isNotEmpty() }
            .joinToString("") { NUMBER_WORDS[it] ?: it }
}
