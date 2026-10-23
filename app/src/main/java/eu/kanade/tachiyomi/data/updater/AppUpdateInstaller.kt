package eu.kanade.tachiyomi.data.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads the new app version and installs it through [PackageInstaller], asking Android to skip the
 * confirmation. Android only honors that when this app is the installer of record of itself, so the first
 * update after a manual install still shows the system prompt and the following ones don't.
 */
@Inject
@SingleIn(AppScope::class)
class AppUpdateInstaller(
    private val context: Context,
    private val network: NetworkHelper,
) {

    private val apkFile: File
        get() = File(context.externalCacheDir, "update.apk")

    private val busy = AtomicBoolean(false)

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android wants the user to confirm this one
                    val confirm = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
                    if (confirm == null) {
                        busy.set(false)
                        return
                    }
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> busy.set(false)
                else -> {
                    logcat(LogPriority.INFO) {
                        "The app update failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
                    }
                    busy.set(false)
                }
            }
        }
    }

    private val receiverRegistered = AtomicBoolean(false)

    /**
     * @return false if an update is already in progress.
     */
    suspend fun downloadAndInstall(downloadLink: String): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            withContext(Dispatchers.IO) {
                network.client.newCall(GET(downloadLink)).awaitSuccess().body.source().saveTo(apkFile)
                install(apkFile)
            }
        } catch (e: Exception) {
            busy.set(false)
            throw e
        }
        return true
    }

    private fun install(file: File) {
        if (receiverRegistered.compareAndSet(false, true)) {
            ContextCompat.registerReceiver(
                context,
                resultReceiver,
                IntentFilter(INSTALL_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("update.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intentSender = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(INSTALL_ACTION).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(intentSender)
            }
        } catch (e: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw e
        }
    }
}

private const val INSTALL_ACTION = "AppUpdateInstaller.INSTALL_ACTION"
