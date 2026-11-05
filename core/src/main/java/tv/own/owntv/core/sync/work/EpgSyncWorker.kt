package tv.own.owntv.core.sync.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.ForegroundInfo
import kotlinx.coroutines.CancellationException
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.sync.EpgActivityTracker
import tv.own.owntv.core.util.isTransientSyncError

class EpgSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val epgRepository: EpgRepository,
    private val store: EpgSourceStore,
    private val connectivity: ConnectivityObserver,
    private val activityTracker: EpgActivityTracker,
    private val recordings: tv.own.owntv.core.recording.RecordingManager,
    private val localeStore: LocaleStore,
    private val watchSession: tv.own.owntv.core.live.WatchSession,
) : CoroutineWorker(context, params) {

    /**
     * WorkManager asks for this when it promotes the work; [doWork] also sets it directly, because
     * only there is the source's name known. A guide fetch is minutes of network and parsing, and a
     * plain background worker does not survive that with the screen off — see [EpgSyncNotifications].
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        EpgSyncNotifications.foregroundInfo(
            applicationContext,
            inputData.getLong(KEY_SOURCE_ID, 0L),
            sourceName = null,
            programmes = inputData.getInt(KEY_BASE_PROGRAMMES, 0),
            localeStore = localeStore,
        )

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_SOURCE_ID, Long.MIN_VALUE)
        val quiet = inputData.getString(KEY_REASON) == tv.own.owntv.core.sync.AutoRefresh.REASON
        // An automatic refresh shows nothing (no pill, no notification): see SyncQuiet.
        if (quiet) tv.own.owntv.core.sync.SyncQuiet.epg(id, true)
        try { return runWork() } finally { if (quiet) tv.own.owntv.core.sync.SyncQuiet.epg(id, false) }
    }

    private suspend fun runWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, Long.MIN_VALUE)
        val reason = inputData.getString(KEY_REASON) ?: "unknown"
        if (sourceId == Long.MIN_VALUE) return Result.failure()

        val source = store.getAll().firstOrNull { it.id == sourceId } ?: run {
            Log.i(TAG, "Skipping stale EPG sync sourceId=$sourceId reason=$reason")
            return Result.success()
        }

        // An automatic refresh never runs under a film or channel: it waits until playback stops
        // (WorkManager restarts it later if the wait outlives the job's time limit).
        if (reason == tv.own.owntv.core.sync.AutoRefresh.REASON && watchSession.watching.value.isNotEmpty()) {
            Log.i(TAG, "auto refresh waiting for playback to stop sourceId=$sourceId")
            watchSession.awaitIdle()
        }
        val baseProgrammes = inputData.getInt(KEY_BASE_PROGRAMMES, 0)
        val progress = ProgressPublisher(baseProgrammes, source.id, source.name) { channels, programmes ->
            activityTracker.progress(source.id, channels, programmes)
        }
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Starting EPG sync sourceId=${source.id} reason=$reason")
        activityTracker.started(source.id, source.name)
        // Best effort, always: Android 12 and later refuse a foreground service started from the
        // background in some states, and a guide that syncs the old way is what shipped until now.
        // Losing the promotion must never lose the sync with it.
        if (reason != tv.own.owntv.core.sync.AutoRefresh.REASON) runCatching { setForeground(progress.foregroundInfo()) }
            .onFailure { Log.i(TAG, "EPG sync stays in the background: ${it.message}") }

        try {
            val programmes = epgRepository.refreshUrl(source.id, source.url, source.userAgent) { channels, count ->
                progress.publish(channels, count)
            }
            progress.flush()
            store.setSynced(source.id, System.currentTimeMillis(), null)
            // A series rule is a standing instruction about programmes that do not exist in the
            // database until the guide mentioning them is fetched — so this is the moment "record
            // every showing" becomes actual timers. Best-effort: a rule that could not be
            // applied is re-applied on the next refresh, and it must never fail the EPG sync.
            runCatching { recordings.applyRules() }
                .onFailure { Log.w(TAG, "series rules not applied after EPG sync: ${it.message}") }
            Log.i(
                TAG,
                "EPG sync finished sourceId=${source.id} reason=$reason programmes=$programmes ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            return Result.success()
        } catch (c: CancellationException) {
            Log.i(TAG, "EPG sync cancelled sourceId=${source.id} reason=$reason")
            throw c
        } catch (e: Exception) {
            val online = connectivity.isOnlineNow()
            // Persist the original exception text. Classification belongs to the EPG Compose renderer;
            // storing a translated sentence would freeze the language active during the background run.
            // The English comparison needles remain in ErrorMessages.kt and are stable protocol keys.
            val rawMessage = e.message
            // Record the failure WITHOUT updating lastSyncAt — staleness-based auto-refresh treats
            // lastSyncAt as the last *successful* sync, so a failed attempt must leave it untouched
            // (otherwise a flaky network would falsely reset the threshold and stop retries).
            store.markError(source.id, rawMessage)
            // Transient network trouble → WorkManager retry with backoff instead of staying stale
            // until the next scheduled window. Permanent errors (bad URL, malformed XML) stay terminal.
            return if (shouldRetryEpgSync(e, online, runAttemptCount)) {
                Log.w(TAG, "EPG sync failed transiently sourceId=${source.id} reason=$reason attempt=$runAttemptCount — will retry", e)
                Result.retry()
            } else {
                Log.w(TAG, "EPG sync failed sourceId=${source.id} reason=$reason", e)
                Result.failure()
            }
        } finally {
            // Always clear the pill entry — success, failure, retry, or cancellation — so it never sticks.
            activityTracker.finished(source.id)
        }
    }

    private inner class ProgressPublisher(
        private val baseProgrammes: Int,
        private val sourceId: Long,
        private val sourceName: String?,
        private val onEmit: (channels: Int, programmes: Int) -> Unit,
    ) {
        private var lastEmitAtMs = 0L
        private var lastChannels = -1
        private var lastProgrammes = -1
        private var pendingChannels = 0
        private var pendingProgrammes = 0
        private var hasPending = false

        /** Its own clock: the notification is for a human reading it, not for the progress bar. */
        private var lastNotifiedAtMs = 0L

        fun foregroundInfo(programmes: Int = baseProgrammes) =
            EpgSyncNotifications.foregroundInfo(
                applicationContext,
                sourceId,
                sourceName,
                programmes,
                localeStore,
            )

        fun publish(channels: Int, programmes: Int) {
            pendingChannels = channels
            pendingProgrammes = programmes
            hasPending = true
            val now = SystemClock.elapsedRealtime()
            if (lastEmitAtMs == 0L || (now - lastEmitAtMs >= PROGRESS_MIN_INTERVAL_MS && shouldEmit())) {
                emit(now)
            }
        }

        fun flush() {
            if (hasPending) emit(SystemClock.elapsedRealtime())
        }

        private fun shouldEmit(): Boolean =
            pendingChannels != lastChannels || pendingProgrammes != lastProgrammes

        private fun emit(now: Long) {
            if (!hasPending) return
            val channels = pendingChannels
            val programmes = pendingProgrammes
            if (channels == lastChannels && programmes == lastProgrammes && lastEmitAtMs != 0L) {
                hasPending = false
                return
            }
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_CHANNELS to channels,
                    KEY_PROGRESS_PROGRAMMES to programmes,
                    KEY_BASE_PROGRAMMES to baseProgrammes,
                ),
            )
            // Also push the counts to the shell status pill (independent of WorkManager progress).
            onEmit(channels, programmes)
            // And to the ongoing notification, far more slowly — it is read by a person, and
            // rebuilding it at the parser's rate is work for nothing. Async and swallowed: the
            // service is already running by now, and a refused update must not end the sync.
            if (now - lastNotifiedAtMs >= NOTIFICATION_INTERVAL_MS) {
                lastNotifiedAtMs = now
                runCatching { setForegroundAsync(foregroundInfo(programmes)) }
            }
            lastEmitAtMs = now
            lastChannels = channels
            lastProgrammes = programmes
            hasPending = false
        }
    }

    companion object {
        const val TAG = "EpgSyncWorker"
        private const val PROGRESS_MIN_INTERVAL_MS = 750L

        /** The same cadence [tv.own.owntv.core.download.DownloadWorker] refreshes its own at. */
        private const val NOTIFICATION_INTERVAL_MS = 2_000L
        const val KEY_SOURCE_ID = "sourceId"
        const val KEY_REASON = "reason"
        const val KEY_PROGRESS_CHANNELS = "channels"
        const val KEY_PROGRESS_PROGRAMMES = "programmes"
        const val KEY_BASE_PROGRAMMES = "baseProgrammes"
    }
}

internal const val MAX_EPG_RETRY_ATTEMPTS = 3

/**
 * Whether a failed EPG sync is worth another attempt.
 *
 * Extracted so the classification can be tested without WorkManager — the same reason
 * [shouldScheduleTrendingRefresh] is a function of its own.
 *
 * The retry test is "is this an IOException", which reads any of these as network trouble because
 * that is what they are built on. They are not: in each one the server did everything asked of it
 * and the answer was simply *nothing*. Repeating the request produces the same nothing, so what the
 * user got was "Connecting…" through three backoffs before a failure that was certain from the
 * first request. A definitive answer is reported once, and the source stays on the list to be tried
 * again whenever the user — or the next scheduled refresh — wants to.
 *
 * Anything genuinely broken mid-flight (a dropped connection, a truncated download) still arrives
 * as itself and is still retried.
 */
internal fun shouldRetryEpgSync(error: Throwable, online: Boolean, runAttemptCount: Int): Boolean {
    val definitive = error is tv.own.owntv.core.stalker.StalkerEpgLoader.PortalHasNoGuideException ||
        error is tv.own.owntv.core.repository.EpgRepository.NoProgrammesInWindowException ||
        error is tv.own.owntv.core.repository.EpgRepository.PortalGuideSourceGoneException
    if (definitive) return false
    val transient = error is java.io.IOException || isTransientSyncError(error.message, online)
    return transient && runAttemptCount < MAX_EPG_RETRY_ATTEMPTS
}
