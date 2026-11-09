package tv.own.owntv.core.sync

import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * The playlist and guide "Auto refresh" settings, for both apps. It used to live in the TV app's
 * shell, so the phone stored the setting and never acted on it.
 *
 * - `includeStartup = true` (cold start): runs once per process, refreshes STARTUP sources
 *   unconditionally and interval sources whose data is at least as old as their threshold. Never
 *   time-throttled, so STARTUP always fires on a real cold start.
 * - `includeStartup = false` (app back in the foreground): skips STARTUP sources and refreshes
 *   interval sources past their threshold. Throttled to once per [RESUME_THROTTLE_MS].
 *
 * Enqueues use [ExistingWorkPolicy.KEEP], so a source already syncing or queued is left alone.
 * Manual re-syncs use REPLACE at their own call sites. A job enqueued here carries
 * [REASON], and the workers hold such a job back while something is playing ([tv.own.owntv.core.live.WatchSession]).
 *
 * A singleton, so the once-per-process rule holds even when the Activity is rebuilt.
 */
class AutoRefresh(
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val profileDao: ProfileDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgSyncScheduler: EpgSyncScheduler,
    private val epgSourceStore: EpgSourceStore,
    private val epgDao: EpgDao,
    private val importFinalizer: ImportFinalizer,
    private val vodResort: M3uVodResort,
) {
    private var coldStartCheckDone = false
    private var lastResumeCheckAtElapsed = 0L

    suspend fun check(includeStartup: Boolean) {
        synchronized(this) {
            if (includeStartup) {
                if (coldStartCheckDone) return
                coldStartCheckDone = true
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastResumeCheckAtElapsed < RESUME_THROTTLE_MS) return
                lastResumeCheckAtElapsed = now
            }
        }
        val nowMs = System.currentTimeMillis()
        val pid = profileDao.resolveExistingProfileId(settings.activeProfileId.first()) ?: return
        // --- Playlist sources ---
        val playlistModes = settings.playlistAutoRefresh.first()
        Log.d(TAG, "checkAutoRefresh startup=$includeStartup playlistModes=$playlistModes")
        if (playlistModes.isNotEmpty()) {
            sourceRepository.observeSources(pid).first().forEach { source ->
                val mode = playlistModes[source.id] ?: PlaylistRefresh.OFF
                Log.d(TAG, "checkAutoRefresh playlist sourceId=${source.id} mode=$mode lastSyncAgoMin=${source.lastSyncAt?.let { (nowMs - it) / 60_000 }}")
                if (shouldRefresh(mode, source.lastSyncAt, nowMs, includeStartup)) {
                    val counts = importFinalizer.contentCounts(source.id)
                    Log.d(TAG, "checkAutoRefresh playlist sourceId=${source.id} mode=$mode — enqueuing")
                    catalogSyncScheduler.enqueueSync(
                        source.id,
                        reason = REASON,
                        contentTypes = SyncContentTypes.enabledOf(source),
                        baseItemCount = counts.channels + counts.movies + counts.series,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }
        // --- EPG sources ---
        val epgModes = settings.epgAutoRefresh.first()
        Log.d(TAG, "checkAutoRefresh startup=$includeStartup epgModes=$epgModes")
        if (epgModes.isNotEmpty()) {
            epgSourceStore.getAll().forEach { src ->
                val mode = epgModes[src.id] ?: EpgRefresh.OFF
                if (shouldRefreshEpg(mode, src.lastSyncAt, nowMs, includeStartup) || guideRunningDry(mode, src.id, src.lastSyncAt, nowMs)) {
                    val base = epgDao.countForSources(listOf(src.id))
                    Log.d(TAG, "checkAutoRefresh epg sourceId=${src.id} mode=$mode — enqueuing")
                    epgSyncScheduler.enqueueSync(
                        src.id,
                        reason = REASON,
                        baseProgrammes = base,
                        policy = ExistingWorkPolicy.KEEP,
                    )
                }
            }
        }
        if (includeStartup) {
            refillGuideEmptiedByMigration()
            vodResort.runOnce()
        }
    }

    /**
     * Refill a guide that `MIGRATION_8_9` emptied.
     *
     * That migration deletes every `epg_programmes` row and nothing schedules a re-fetch, so an
     * upgrading user's Guide is simply blank until they think to re-sync EPG by hand. Runs **once per
     * install** (so it also catches users who passed through 8→9 in an earlier version) and only for
     * sources that had previously synced successfully but now hold zero programmes — that is the
     * exact signature of the wipe.
     *
     * EPG is opt-in by design, and this respects that: adding an EPG source *is* the opt-in, and a
     * source the user has never synced is left alone rather than silently downloaded. The enqueue is
     * an ordinary [EpgSyncScheduler] job, so it shows the standard EPG-syncing pill.
     *
     * The one-shot flag is read first and the DB is touched only when it is unset, so this adds no
     * work to a normal cold start.
     */
    private suspend fun refillGuideEmptiedByMigration() {
        if (settings.epgRefillChecked.first()) return
        runCatching {
            val sources = epgSourceStore.getAll().filter { (it.lastSyncAt ?: 0L) > 0L }
            for (src in sources) {
                if (epgDao.countForSources(listOf(src.id)) > 0) continue
                Log.i(TAG, "epgRefill sourceId=${src.id} — synced before but guide is empty, re-fetching")
                epgSyncScheduler.enqueueSync(
                    src.id,
                    reason = "migration_refill",
                    baseProgrammes = 0,
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
        }.onFailure { Log.w(TAG, "epgRefill check failed", it) }
        // Marked regardless: a failed check must not retry on every launch forever, and a failed
        // *sync* is already retried by the scheduler's own policy.
        settings.markEpgRefillChecked()
    }

    /**
     * Whether a playlist source should auto-refresh now. OFF never; STARTUP only on cold start
     * ([includeStartup]); interval modes when `now - lastSyncAt >= threshold` (a null lastSyncAt — never
     * successfully synced — counts as infinitely stale so recovery happens).
     */
    private fun shouldRefresh(
        refresh: PlaylistRefresh,
        lastSyncAt: Long?,
        now: Long,
        includeStartup: Boolean,
    ): Boolean = when (refresh.mode) {
        PlaylistAutoRefresh.OFF -> false
        PlaylistAutoRefresh.STARTUP -> includeStartup
        else -> (now - (lastSyncAt ?: 0L)) >= (refresh.thresholdMs ?: Long.MAX_VALUE)
    }

    /**
     * A guide that is about to run out of programmes is refreshed whatever the interval says: an interval that is
     * longer than what the provider's feed reaches ahead (a 48 h interval on a 48 h feed) leaves the evening blank.
     * Not more often than every [DRY_MIN_GAP_MS], so a feed that is short by nature does not loop.
     */
    private suspend fun guideRunningDry(mode: EpgRefresh, sourceId: Long, lastSyncAt: Long?, now: Long): Boolean {
        if (mode.mode == EpgAutoRefresh.OFF || mode.mode == EpgAutoRefresh.MANUAL) return false
        if (lastSyncAt != null && now - lastSyncAt < DRY_MIN_GAP_MS) return false
        // No programmes at all counts as dry too: an empty guide is the driest there is.
        val latest = epgDao.latestStop(listOf(sourceId))
        val dry = latest == null || latest - now < DRY_AHEAD_MS
        Log.d(TAG, "guideRunningDry sourceId=$sourceId mode=${mode.mode} lastSyncAgoMin=${lastSyncAt?.let { (now - it) / 60_000 }} " +
            "latestStopAheadMin=${latest?.let { (it - now) / 60_000 }} dry=$dry")
        return dry
    }

    /** EPG equivalent of [shouldRefresh]. */
    private fun shouldRefreshEpg(
        refresh: EpgRefresh,
        lastSyncAt: Long?,
        now: Long,
        includeStartup: Boolean,
    ): Boolean = when (refresh.mode) {
        EpgAutoRefresh.OFF -> false
        EpgAutoRefresh.STARTUP -> includeStartup
        // MANUAL's threshold is its day count; every other mode carries its own. Identical shape to
        // [shouldRefresh], which is the point of the parity.
        else -> (now - (lastSyncAt ?: 0L)) >= (refresh.thresholdMs ?: Long.MAX_VALUE)
    }

    companion object {
        private const val TAG = "OwnTVHome"
        /** The work reason the sync workers recognise as automatic, and hold back during playback. */
        const val REASON = "auto_refresh"
        /** Minimum gap between resume-triggered checks (rotation, rapid background/foreground). */
        private const val RESUME_THROTTLE_MS = 60_000L
        /** Refresh early when the guide reaches less than this far ahead... */
        private const val DRY_AHEAD_MS = 8 * 3_600_000L
        /** ...but not within this long of the last refresh. */
        private const val DRY_MIN_GAP_MS = 3 * 3_600_000L
    }
}
