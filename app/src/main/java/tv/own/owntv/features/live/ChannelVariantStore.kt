@file:OptIn(FlowPreview::class)

package tv.own.owntv.features.live

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ChannelDao

/**
 * Fork: the one [ChannelVariantIndex] Live TV and the Guide fold their rows with, and the user's
 * picks of which variant a channel shows. App-wide, so a quality picked in the player is the row
 * the Guide shows too.
 */
class ChannelVariantStore(context: Context, private val channelDao: ChannelDao) {

    private val appContext = context.applicationContext

    /** Which variant the user picked per channel, keyed by [ChannelVariant.groupKey]. */
    private val picks = appContext.getSharedPreferences("fork_channel_variants", Context.MODE_PRIVATE)
    private val picksVersion = MutableStateFlow(0)

    /** The last index built or loaded, with the playlists it covers. */
    private val latest = MutableStateFlow<Pair<List<Long>, ChannelVariantIndex>?>(null)

    private val snapshotFile get() = java.io.File(appContext.filesDir, "fork_variant_index2.txt")

    /**
     * The index of [sourceIds]' channels, rebuilt after every sync (the channels table re-emits) and
     * after every pick. Last run's index comes first, so a list folds at once; the rebuild replaces it.
     */
    fun observe(sourceIds: List<Long>): Flow<ChannelVariantIndex> {
        // A sync rewrites the table in many batches: settle before rebuilding — except the first time.
        var first = true
        val built = channelDao.observeVariantRows(sourceIds)
            .debounce { if (first) { first = false; 0L } else 500L }
            .combine(picksVersion) { rows, _ ->
                withContext(Dispatchers.Default) {
                    ChannelVariantIndex.build(rows, currentPicks()).also { saveSnapshot(sourceIds, it) }
                }
            }
        return built
            .onStart { withContext(Dispatchers.IO) { loadSnapshot(sourceIds) }?.let { emit(it) } }
            .onEach { latest.value = sourceIds to it }
    }

    /** The index for a one-off read (the Guide's load): the live one, else last run's, else built now. */
    suspend fun current(sourceIds: List<Long>): ChannelVariantIndex {
        latest.value?.takeIf { it.first == sourceIds }?.let { return it.second }
        val index = withContext(Dispatchers.IO) { loadSnapshot(sourceIds) }
            ?: withContext(Dispatchers.Default) {
                ChannelVariantIndex.build(channelDao.observeVariantRows(sourceIds).first(), currentPicks())
                    .also { saveSnapshot(sourceIds, it) }
            }
        latest.value = sourceIds to index
        return index
    }

    /** Make [variant] the row its channel shows from now on. */
    fun pick(variant: ChannelVariant) {
        picks.edit().putLong(variant.groupKey, variant.id).apply()
        picksVersion.value++
    }

    private fun currentPicks(): Map<String, Long> =
        picks.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    private fun saveSnapshot(ids: List<Long>, index: ChannelVariantIndex) {
        runCatching { snapshotFile.writeText(ids.joinToString(",") + "\n" + index.serialize()) }
    }

    private fun loadSnapshot(ids: List<Long>): ChannelVariantIndex? = runCatching {
        val text = snapshotFile.takeIf { it.isFile }?.readText() ?: return null
        if (text.substringBefore('\n') != ids.joinToString(",")) return null
        ChannelVariantIndex.parse(text.substringAfter('\n'))
    }.getOrNull()
}
