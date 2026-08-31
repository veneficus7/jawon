package com.jawon.app.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** A release newer than the one running. */
data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val fraction: Float) : UpdateState
    data class Ready(val apk: File, val update: AvailableUpdate) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Checks whether a newer Jawon has been published, and fetches it.
 *
 * **Why the app does this itself.** Jawon is distributed as an APK to a handful of people, and
 * without this every update means the owner sending a file to everyone and everyone
 * remembering to install it. This is the smallest thing that removes that: the app asks a
 * static file whether there is something newer, and offers to fetch it.
 *
 * **The signing key is the real security boundary, not this code.** Android refuses to install
 * an update signed with a different key than the installed app — so even a compromised
 * manifest cannot replace Jawon with something else; it can only fail. That is worth knowing
 * because it is what makes a self-updater defensible at all, and it is also why the key must
 * never change: the day it does, every install stops being updatable and has to be replaced by
 * hand, losing the user's library with it.
 *
 * [MANIFEST_URL] points at a small JSON file in a **public** repository holding only releases.
 * The source repository is private and stays that way — an updater cannot authenticate to a
 * private repo without shipping a token in the APK, where anyone could read it out.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The running build's version code — what a published release is compared against. */
    val installedVersionCode: Long by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        }.getOrDefault(0L)
    }

    suspend fun check() {
        _state.value = UpdateState.Checking
        _state.value = withContext(Dispatchers.IO) {
            runCatching {
                val body = fetch(MANIFEST_URL)?.decodeToString()
                    ?: return@runCatching UpdateState.Failed("Could not reach the update server")
                val json = JSONObject(body)
                val update = AvailableUpdate(
                    versionCode = json.getLong("versionCode"),
                    versionName = json.optString("versionName", "?"),
                    notes = json.optString("notes", ""),
                    apkUrl = json.getString("apkUrl"),
                    sizeBytes = json.optLong("sizeBytes", 0L)
                )
                // Compared on version *code*, never the name. The name is for people and can
                // be anything; the code is the only thing Android itself orders releases by,
                // and it is what decides whether an install counts as an update at all.
                if (update.versionCode > installedVersionCode) {
                    UpdateState.Available(update)
                } else {
                    UpdateState.UpToDate
                }
            }.getOrElse {
                Log.w(TAG, "update check failed: ${it.message}")
                UpdateState.Failed(it.message ?: "Update check failed")
            }
        }
    }

    /**
     * Downloads the APK and checks it is what it claims to be before offering to install it.
     *
     * The package-name and version check is not the security boundary — Android's signature
     * check is — but it turns "the install failed" into something a person can act on when
     * the manifest simply points at the wrong file.
     */
    suspend fun download(update: AvailableUpdate) {
        _state.value = UpdateState.Downloading(0f)
        _state.value = withContext(Dispatchers.IO) {
            runCatching {
                if (!update.apkUrl.startsWith("https://")) {
                    return@runCatching UpdateState.Failed("Update link is not HTTPS")
                }
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                // One file, replaced each time: a cache of half-downloaded APKs from every
                // release ever offered is not worth keeping.
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "jawon-${update.versionCode}.apk")

                val ok = downloadTo(update.apkUrl, target) { fraction ->
                    _state.value = UpdateState.Downloading(fraction)
                }
                if (!ok) return@runCatching UpdateState.Failed("Download failed")

                val info = context.packageManager
                    .getPackageArchiveInfo(target.absolutePath, 0)
                    ?: return@runCatching UpdateState.Failed("That file is not an app")
                if (info.packageName != context.packageName) {
                    target.delete()
                    return@runCatching UpdateState.Failed(
                        "That download is ${info.packageName}, not Jawon"
                    )
                }
                if (PackageInfoCompat.getLongVersionCode(info) <= installedVersionCode) {
                    target.delete()
                    return@runCatching UpdateState.Failed("That download is not newer")
                }
                UpdateState.Ready(target, update)
            }.getOrElse {
                Log.w(TAG, "update download failed: ${it.message}")
                UpdateState.Failed(it.message ?: "Download failed")
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /** Whether the platform will let this app ask to install one. */
    fun canRequestInstall(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    private fun downloadTo(
        url: String,
        target: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
            }
            try {
                when (val code = connection.responseCode) {
                    in 200..299 -> {
                        val total = connection.contentLengthLong
                        connection.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                        return true
                    }
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: return false
                        val next = URL(current, location)
                        // Release assets redirect to a CDN, which is fine — but never let a
                        // redirect walk the download off HTTPS.
                        if (next.protocol != "https") return false
                        current = next
                    }
                    else -> {
                        Log.w(TAG, "download got $code from $current")
                        return false
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    private fun fetch(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "UpdateChecker"
        const val USER_AGENT = "Jawon-updater"
        const val TIMEOUT_MS = 15_000
        const val MAX_REDIRECTS = 5

        /**
         * Where the current release is described.
         *
         * A plain file rather than the GitHub releases API: no rate limit on an unauthenticated
         * caller, no large response to parse, and the shape is ours rather than GitHub's — so
         * moving this to any static host later is a one-line change.
         *
         * `raw.githubusercontent.com` serves the file from the default branch. The repository
         * it names must be **public**; the source repository is private and stays private.
         */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/veneficus7/javon-releases/main/latest.json"
    }
}
