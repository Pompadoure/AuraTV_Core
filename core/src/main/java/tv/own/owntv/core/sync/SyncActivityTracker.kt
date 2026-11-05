package tv.own.owntv.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "a catalog sync is running" signal for the shell's unobtrusive status pill. Every sync —
 * foreground add, backgrounded add, WorkManager remainder/refresh — funnels through
 * [SyncManager.sync], which reports here. Purely observational: nothing reads this to make decisions.
 */
class SyncActivityTracker {

    data class ActiveSync(val sourceId: Long, val sourceName: String, val stage: ImportStage? = null)

    data class CompletedSync(val sourceId: Long, val sourceName: String, val result: SyncResult, val timestamp: Long)

    private val _active = MutableStateFlow<Map<Long, ActiveSync>>(emptyMap())
    private val _lastCompleted = MutableStateFlow<CompletedSync?>(null)

    /** All currently-running syncs keyed by sourceId (usually 0 or 1 entries). */
    val active: StateFlow<Map<Long, ActiveSync>> = _active.asStateFlow()

    /** The last completed sync (success, failure, or cancellation). */
    val lastCompleted: StateFlow<CompletedSync?> = _lastCompleted.asStateFlow()

    fun started(sourceId: Long, sourceName: String) {
        if (SyncQuiet.isCatalogQuiet(sourceId)) return
        _active.value = _active.value + (sourceId to ActiveSync(sourceId, sourceName))
    }

    fun progress(sourceId: Long, stage: ImportStage) {
        if (SyncQuiet.isCatalogQuiet(sourceId)) return
        val existing = _active.value[sourceId] ?: return
        _active.value = _active.value + (sourceId to existing.copy(stage = stage))
    }

    fun finished(sourceId: Long, sourceName: String, result: SyncResult) {
        _active.value = _active.value - sourceId
        if (SyncQuiet.isCatalogQuiet(sourceId)) return // an automatic refresh leaves no "done" line either
        _lastCompleted.value = CompletedSync(sourceId, sourceName, result, android.os.SystemClock.elapsedRealtime())
    }

    /**
     * Clears a completed sync once the pill has picked it up for display, so it isn't re-shown on
     * every fresh composition of the pill (e.g. after exiting fullscreen playback). Guarded by
     * timestamp so a newer completion that arrived in the meantime is never dropped.
     */
    fun consumeCompleted(timestamp: Long) {
        if (_lastCompleted.value?.timestamp == timestamp) {
            _lastCompleted.value = null
        }
    }
}
