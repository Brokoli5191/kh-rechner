package app.brokoli5191.khrechner.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.IntentCompat
import app.brokoli5191.khrechner.KhRechnerApplication
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface DownloadResult {
    /** The APK, checked against GitHub's checksum and the installed app's signature. */
    data class Ready(val apk: File) : DownloadResult
    data class Failed(val reason: UpdateFailure) : DownloadResult
}

/** What Android answered after the APK was handed to it. */
sealed interface InstallResult {
    /** Android wants the user to confirm, the first time at least; [intent] shows its dialog. */
    data class NeedsConfirmation(val intent: Intent) : InstallResult

    /** Installed. Android ends the app's process for the update, so this is rarely seen. */
    data object Done : InstallResult

    /** The user said no in Android's dialog. */
    data object Cancelled : InstallResult
    data class Failed(val reason: UpdateFailure) : InstallResult
}

/** Loads an update and gives it to Android. Tests swap it for a fake. */
interface AppUpdater {

    /** @param progress percent, or null while the size is unknown; called on a background thread. */
    suspend fun download(release: ReleaseInfo, progress: (Int?) -> Unit): DownloadResult

    /** Hands [apk] to Android's installer and deletes it. The outcome arrives through [UpdateInstallReceiver]. */
    suspend fun install(apk: File)
}

/**
 * Self-update through [PackageInstaller]. With "install unknown apps" allowed for this app,
 * Android 12 and later let an app update itself without a dialog (see
 * [PackageInstaller.SessionParams.setRequireUserAction]); the first time, or where that does not
 * apply, Android asks the user through [InstallResult.NeedsConfirmation].
 *
 * Android only accepts an update signed with the key of the installed app. The check here comes
 * first so that a mismatch is reported in words instead of as a failed installation.
 */
class PackageInstallerUpdater(
    private val context: Context,
    private val userAgent: String,
    private val installedVersionCode: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdater {

    private val folder: File get() = File(context.cacheDir, "updates")

    override suspend fun download(release: ReleaseInfo, progress: (Int?) -> Unit): DownloadResult = withContext(io) {
        folder.deleteRecursively()
        if (!folder.mkdirs()) return@withContext DownloadResult.Failed(UpdateFailure.NO_STORAGE)
        val apk = File(folder, "kh-rechner-${release.version}.apk")
        val connection = try {
            URL(release.apkUrl).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return@withContext DownloadResult.Failed(UpdateFailure.NO_CONNECTION)
        }
        val outcome = try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", userAgent)
            // GitHub answers with a redirect to its file host, which HttpURLConnection follows.
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                DownloadResult.Failed(UpdateFailure.GITHUB_UNAVAILABLE)
            } else {
                val size = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
                val ok = connection.inputStream.use { input ->
                    apk.outputStream().use { output ->
                        ApkTransfer.copy(input, output, size, release.sha256) { percent ->
                            ensureActive()
                            progress(percent)
                        }
                    }
                }
                if (ok) verify(apk) else DownloadResult.Failed(UpdateFailure.DAMAGED)
            }
        } catch (e: IOException) {
            Log.w(TAG, "update download failed", e)
            DownloadResult.Failed(if (folder.usableSpace < MIN_FREE_BYTES) UpdateFailure.NO_STORAGE else UpdateFailure.NO_CONNECTION)
        } finally {
            connection.disconnect()
        }
        if (outcome !is DownloadResult.Ready) apk.delete()
        outcome
    }

    /** Is it this app, newer than the installed one, and signed with the same key? */
    private fun verify(apk: File): DownloadResult {
        val packages = context.packageManager
        val archive = packages.getPackageArchiveInfo(
            apk.path,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        ) ?: return DownloadResult.Failed(UpdateFailure.DAMAGED)
        if (archive.packageName != context.packageName) return DownloadResult.Failed(UpdateFailure.WRONG_SIGNATURE)
        if (archive.longVersionCode <= installedVersionCode) return DownloadResult.Failed(UpdateFailure.DAMAGED)
        val signing = archive.signingInfo ?: return DownloadResult.Failed(UpdateFailure.WRONG_SIGNATURE)
        val certificates = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val sameKey = certificates.orEmpty().any { certificate ->
            packages.hasSigningCertificate(context.packageName, certificate.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)
        }
        return if (sameKey) DownloadResult.Ready(apk) else DownloadResult.Failed(UpdateFailure.WRONG_SIGNATURE)
    }

    override suspend fun install(apk: File) = withContext(io) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Without a dialog where Android allows it; it still asks the first time.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // Mutable so Android can add the status; explicit so nothing else can receive it.
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: IOException) {
            installer.abandonSession(sessionId)
            throw e
        } catch (e: RuntimeException) {
            installer.abandonSession(sessionId)
            throw e
        } finally {
            // The session holds its own copy now.
            apk.delete()
        }
    }

    private companion object {
        const val TAG = "AppUpdater"
        const val TIMEOUT_MILLIS = 20_000
        const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }
}

/** Copies a download while counting and hashing it. Pure, so it is tested with byte arrays. */
object ApkTransfer {

    /** An APK larger than this is not this app; the download stops there. */
    const val MAX_BYTES: Long = 100L * 1024 * 1024

    /**
     * @param expectedSize bytes the server announced; null if it did not, then progress is null.
     * @param expectedSha256 lowercase hex; null skips the comparison.
     * @return true if the copy is complete and matches what was announced.
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long?,
        expectedSha256: String?,
        progress: (Int?) -> Unit,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastPercent = -1
        progress(if (expectedSize == null) null else 0)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > MAX_BYTES || (expectedSize != null && copied > expectedSize)) return false
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            if (expectedSize != null) {
                val percent = (copied * 100 / expectedSize).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    progress(percent)
                }
            }
        }
        if (expectedSize != null && copied != expectedSize) return false
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return expectedSha256 == null || actual == expectedSha256
    }
}

/** Receives Android's answer to [PackageInstallerUpdater.install] and passes it on. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val result = resultFor(status, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
        if (result is InstallResult.Failed) {
            Log.w(TAG, "update not installed: status $status, ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
        (context.applicationContext as? KhRechnerApplication)?.container?.updates?.onInstallResult(result)
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"

        fun resultFor(status: Int, confirmation: Intent?): InstallResult = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                confirmation?.let(InstallResult::NeedsConfirmation) ?: InstallResult.Failed(UpdateFailure.INSTALL_FAILED)
            PackageInstaller.STATUS_SUCCESS -> InstallResult.Done
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallResult.Failed(UpdateFailure.NO_STORAGE)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallResult.Failed(UpdateFailure.BLOCKED)
            // A different key or a lower version code: not an update Android will take.
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallResult.Failed(UpdateFailure.WRONG_SIGNATURE)
            PackageInstaller.STATUS_FAILURE_INVALID -> InstallResult.Failed(UpdateFailure.DAMAGED)
            else -> InstallResult.Failed(UpdateFailure.INSTALL_FAILED)
        }
    }
}
