package tv.own.owntv.core.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Automatic refreshes run without the status pill: nobody asked for them, so nothing should pop up over what is
 * being watched. A worker marks its source quiet for the length of an automatic run; the activity trackers then
 * leave that source out of what the pill shows (a manual sync of the same source is never quiet).
 */
object SyncQuiet {
    private val catalog: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val epg: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun catalog(sourceId: Long, quiet: Boolean) { if (quiet) catalog.add(sourceId) else catalog.remove(sourceId) }
    fun epg(sourceId: Long, quiet: Boolean) { if (quiet) epg.add(sourceId) else epg.remove(sourceId) }
    fun isCatalogQuiet(sourceId: Long): Boolean = sourceId in catalog
    fun isEpgQuiet(sourceId: Long): Boolean = sourceId in epg
}
