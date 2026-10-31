package tv.own.owntv.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import java.io.File
import java.io.IOException

/**
 * In-app updates straight from GitHub Releases: checks the repo's latest release, compares its tag
 * with the installed version, downloads the release APK, and hands it to the system installer.
 * No server of our own — each app's releases CI already publishes one APK per ABI flavour per tag
 * (`OwnTV-vX.Y.Z.apk` / `OwnTV-x86_64-vX.Y.Z.apk` on the television, `OwnTV-Mobile-…` on the phone),
 * and the asset matching this device's ABI is chosen, so updates also work on an x86_64 emulator.
 * **Which repository is asked is the host app's to say** — see [CoreBuildInfo.releaseRepo].
 */
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
) {
    data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

    sealed interface Failure {
        data class CheckHttp(val code: Int) : Failure
        data object NoCompatibleApk : Failure
        data object InvalidReleaseResponse : Failure
        data object CheckNetwork : Failure
        data class DownloadHttp(val code: Int) : Failure
        data object EmptyDownload : Failure
        data object DownloadNetwork : Failure
        data object Install : Failure

        /** Internal storage cannot hold the APK twice (download + install session). */
        data class NotEnoughSpace(val requiredBytes: Long) : Failure

        /** The file that arrived is truncated or not a readable OwnTV package — never install it. */
        data object DamagedDownload : Failure

        /** The system installer refused the package; [message] is its own wording when it gave one. */
        data class InstallRejected(val message: String?) : Failure
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val percent: Int) : State
        data class Failed(val failure: Failure, val retryInfo: UpdateInfo? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private class CheckHttpException(val code: Int) : IOException()
    private class DownloadHttpException(val code: Int) : IOException()
    private class NoCompatibleApkException : IOException()
    private class InvalidReleaseResponseException : IOException()
    private class EmptyDownloadException : IOException()
    private class NotEnoughSpaceException(val requiredBytes: Long) : IOException()
    private class DamagedDownloadException(reason: String) : IOException(reason)

    private fun failureFor(error: Throwable, checking: Boolean): Failure = when (error) {
        is CheckHttpException -> Failure.CheckHttp(error.code)
        is DownloadHttpException -> Failure.DownloadHttp(error.code)
        is NoCompatibleApkException -> Failure.NoCompatibleApk
        is InvalidReleaseResponseException -> Failure.InvalidReleaseResponse
        is EmptyDownloadException -> Failure.EmptyDownload
        is NotEnoughSpaceException -> Failure.NotEnoughSpace(error.requiredBytes)
        is DamagedDownloadException -> Failure.DamagedDownload
        else -> if (checking) Failure.CheckNetwork else Failure.DownloadNetwork
    }

    val currentVersion: String = CoreBuildInfo.versionName

    /** Queries GitHub's latest release; moves to Available / UpToDate / a semantic failure. */
    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                val request = Request.Builder()
                    // AuraTV fork: a rolling pre-release tag, when the host names one, replaces "latest".
                    .url(
                        "https://api.github.com/repos/${CoreBuildInfo.releaseRepo}/releases/" +
                            CoreBuildInfo.rollingReleaseTag.takeIf { it.isNotBlank() }?.let { "tags/$it" }.orEmpty().ifEmpty { "latest" },
                    )
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "OwnTV")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                    val body = resp.body.string()
                    if (body.isBlank()) throw InvalidReleaseResponseException()
                    val o = runCatching { JSONObject(body) }.getOrElse { throw InvalidReleaseResponseException() }
                    val rolling = CoreBuildInfo.rollingReleaseTag.isNotBlank()
                    // Rolling builds all share one tag and one versionName: identify them by the commit.
                    val remoteCommit = o.optString("target_commitish")
                    val version = if (rolling) {
                        remoteCommit.takeIf { it.isNotBlank() }?.let { "dev-${it.take(7)}" }
                    } else {
                        o.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() }
                    } ?: throw InvalidReleaseResponseException()
                    val notes = o.optString("body").take(16_000)
                    val assets = o.optJSONArray("assets") ?: throw InvalidReleaseResponseException()
                    // Releases carry one APK per ABI flavor (arm = generic, x86_64 suffixed). Never
                    // silently install an APK for the wrong ABI.
                    val wantX86 = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
                    val apkUrl = (0 until assets.length())
                        .asSequence()
                        .mapNotNull { assets.optJSONObject(it) }
                        .mapNotNull { asset ->
                            val name = asset.optString("name")
                            val url = asset.optString("browser_download_url")
                            if (!name.endsWith(".apk") || url.isBlank()) return@mapNotNull null
                            val isX86 = name.contains("x86_64", ignoreCase = true)
                            if (isX86 == wantX86) url else null
                        }
                        .firstOrNull()
                        ?: throw NoCompatibleApkException()
                    val info = UpdateInfo(version, notes, apkUrl)
                    settings.recordUpdateCheck(System.currentTimeMillis())
                    val newer = if (rolling) {
                        // Unknown local commit (a local build) never nags; otherwise any other commit is an update.
                        CoreBuildInfo.buildCommit.isNotBlank() && !remoteCommit.equals(CoreBuildInfo.buildCommit, ignoreCase = true)
                    } else {
                        isNewer(version, currentVersion)
                    }
                    if (newer) _state.value = State.Available(info) else _state.value = State.UpToDate
                }
            }.onFailure { error ->
                Log.w(TAG, "update check failed: ${error.message}", error)
                _state.value = State.Failed(failureFor(error, checking = true))
            }
        }
    }

    /** Downloads the release APK with progress, then opens the system installer. */
    fun downloadAndInstall() {
        val info = (_state.value as? State.Available)?.info ?: return
        _state.value = State.Downloading(0)
        scope.launch {
            runCatching {
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, "owntv-update.apk")
                out.delete() // never build on top of a previous half-download
                val request = Request.Builder().url(info.apkUrl).header("User-Agent", "OwnTV").build()
                try {
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw DownloadHttpException(resp.code)
                        val body = resp.body
                        val total = body.contentLength()
                        // The APK is written once here and copied again into the install session, so
                        // the download needs room for two of it. Checking up front turns a silent short
                        // write — which reaches the installer as "App not installed" — into a real message.
                        if (total > 0 && dir.usableSpace < total * 2) throw NotEnoughSpaceException(total * 2)
                        var copied = 0L
                        body.byteStream().use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    copied += n
                                    if (total > 0) _state.value = State.Downloading((copied * 100 / total).toInt())
                                }
                            }
                        }
                        if (copied == 0L) throw EmptyDownloadException()
                        if (total > 0 && copied != total) {
                            throw DamagedDownloadException("truncated: got $copied of $total bytes")
                        }
                    }
                    verifyApk(out)
                    install(out)
                } catch (e: Throwable) {
                    out.delete() // a bad file must not linger and be retried as-is
                    throw e
                }
                _state.value = State.Available(info) // dialog stays sane if the user cancels install
            }.onFailure { error ->
                Log.w(TAG, "update download failed: ${error.message}", error)
                val failure = if (error is InstallException) Failure.Install else failureFor(error, checking = false)
                _state.value = State.Failed(failure, retryInfo = info)
            }
        }
    }

    /**
     * Refuses anything the system cannot read back as this app's package. A truncated or corrupted
     * APK is otherwise only rejected by the installer itself, which reports nothing to us and shows
     * the user a bare "App not installed" — the failure this whole guard exists to explain.
     */
    private fun verifyApk(apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw DamagedDownloadException("unparseable APK (${apk.length()} bytes)")
        if (info.packageName != context.packageName) {
            throw DamagedDownloadException("wrong package ${info.packageName}")
        }
    }

    /**
     * Installs through [PackageInstaller] rather than an `ACTION_VIEW` intent on a FileProvider URI.
     * Two reasons: the session reads the APK from our own storage (no cross-app URI grant to go wrong
     * on older TV installers), and its status broadcast carries the real reason a package was refused
     * — insufficient storage, a signature conflict, a bad file — instead of the system's silent
     * "App not installed".
     */
    private fun install(apk: File) {
        registerInstallReceiver()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            throw InstallException(e)
        }
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(INSTALL_STATUS_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, 0, intent, flags).intentSender)
        }
        apk.delete() // the session owns its own copy now
    }

    /** Registered once, for the app's lifetime: the confirmation round trip outlives any one call. */
    private fun registerInstallReceiver() {
        if (installReceiverRegistered) return
        installReceiverRegistered = true
        ContextCompat.registerReceiver(
            context,
            installStatusReceiver,
            IntentFilter(INSTALL_STATUS_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private var installReceiverRegistered = false

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                // The user still has to approve the install — this is the system prompt, not a failure.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { confirm?.let { context.startActivity(it) } }
                        .onFailure { _state.value = State.Failed(Failure.Install) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "update installed")
                // Declining the system prompt is a choice, not an error — leave the dialog as it was.
                PackageInstaller.STATUS_FAILURE_ABORTED -> Log.i(TAG, "install cancelled by user")
                else -> {
                    Log.w(TAG, "install refused status=$status message=$message")
                    _state.value = State.Failed(Failure.InstallRejected(message?.takeIf { it.isNotBlank() }))
                }
            }
        }
    }

    /** Retries the failed phase without losing a successfully resolved release asset. */
    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        val info = failed.retryInfo
        if (info != null && failed.failure !is Failure.CheckHttp && failed.failure !is Failure.NoCompatibleApk &&
            failed.failure !is Failure.InvalidReleaseResponse && failed.failure !is Failure.CheckNetwork
        ) {
            _state.value = State.Available(info)
            downloadAndInstall()
        } else {
            check()
        }
    }

    fun reset() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    /** Numeric segment-wise compare: "1.10.0" > "1.9.3"; non-numeric junk compares as 0. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private class InstallException(cause: Throwable) : IOException(cause)

    companion object {
        private const val TAG = "UpdateManager"
        private const val SESSION_ENTRY = "owntv-update"
        private const val INSTALL_STATUS_ACTION = "tv.own.owntv.UPDATE_INSTALL_STATUS"
    }
}
