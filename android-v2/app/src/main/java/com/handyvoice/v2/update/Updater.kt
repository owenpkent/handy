package com.handyvoice.v2.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.handyvoice.v2.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app self-update for sideloaded builds.
 *
 * Mirrors the alpha-osk approach — poll a release feed, compare versions,
 * download, hand off to the OS installer — but rebuilt for Android. The
 * source repo stays private; built APKs are published to a *public* releases
 * repo ([RELEASES_REPO]) so the app can fetch them without an embedded token.
 *
 * Flow:
 *   1. GET `/releases/latest` from the public repo's GitHub API.
 *   2. Compare its tag to [BuildConfig.VERSION_NAME] ([isNewer]).
 *   3. If newer, download the `.apk` asset to `cacheDir/updates/update.apk`.
 *   4. Launch the system package installer (a one-tap "Update" prompt).
 *
 * A fully silent install isn't possible for a sideloaded app — the OS always
 * shows its own installer UI, and the first time the user must grant "install
 * unknown apps" to this app. This is the closest Android allows.
 *
 * Signing note: Android only applies an update whose APK is signed with the
 * same key as the installed build. Keep building on the same machine (the
 * debug keystore at `~/.android/debug.keystore` is stable per machine) or
 * wire a dedicated release keystore, or the installer rejects the update with
 * a signature-mismatch error.
 */
class Updater(private val activity: AppCompatActivity) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Check for an update.
     *
     * @param manual true for the Settings "Check for updates" button — gives
     *   feedback when already up to date or on error. false for the quiet
     *   launch check — says nothing unless an update is actually available.
     */
    fun check(manual: Boolean) {
        activity.lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { fetchLatest() }.getOrNull()
            }
            if (release == null) {
                if (manual) toast("Couldn't check for updates. Check your connection.")
                return@launch
            }
            if (!isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                if (manual) toast("You're on the latest version (v${BuildConfig.VERSION_NAME}).")
                return@launch
            }
            promptInstall(release)
        }
    }

    /** Blocking network call — must run off the main thread. */
    private fun fetchLatest(): ReleaseInfo? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$RELEASES_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val gh = runCatching { Gson().fromJson(body, GhRelease::class.java) }.getOrNull()
                ?: return null
            // /releases/latest already excludes draft/prerelease, but be defensive.
            if (gh.draft || gh.prerelease) return null
            val tag = gh.tag_name?.trim()?.removePrefix("v")?.removePrefix("V")
            if (tag.isNullOrEmpty()) return null
            val asset = gh.assets
                ?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true && !it.browser_download_url.isNullOrEmpty() }
                ?: return null
            return ReleaseInfo(
                versionName = tag,
                notes = gh.body?.trim().orEmpty(),
                apkUrl = asset.browser_download_url!!,
            )
        }
    }

    private fun promptInstall(release: ReleaseInfo) {
        val msg = buildString {
            append("Version ${release.versionName} is available.")
            append("\n\nYou have v${BuildConfig.VERSION_NAME}.")
            if (release.notes.isNotBlank()) {
                append("\n\n")
                append(release.notes.take(NOTES_MAX_CHARS))
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(msg)
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> startDownload(release) }
            .show()
    }

    private fun startDownload(release: ReleaseInfo) {
        // No point downloading if we can't install. Ask once; the user grants
        // it and taps Update again.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            requestInstallPermission()
            return
        }
        val progress = AlertDialog.Builder(activity)
            .setTitle("Downloading update")
            .setMessage("Downloading v${release.versionName}…")
            .setCancelable(false)
            .create()
        progress.show()
        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { download(release.apkUrl) }.getOrNull()
            }
            progress.dismiss()
            if (file == null) {
                toast("Download failed. Try again.")
                return@launch
            }
            launchInstaller(file)
        }
    }

    /** Blocking download — must run off the main thread. */
    private fun download(url: String): File? {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "update.apk")
        if (dest.exists()) dest.delete()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val stream = resp.body?.byteStream() ?: return null
            dest.outputStream().use { out -> stream.copyTo(out) }
        }
        return dest
    }

    private fun launchInstaller(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { toast("Couldn't open the installer.") }
    }

    private fun requestInstallPermission() {
        AlertDialog.Builder(activity)
            .setTitle("Allow updates")
            .setMessage(
                "To install updates, allow this app to install apps. " +
                    "We'll open the setting now — turn it on, then tap Update again."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                runCatching { activity.startActivity(intent) }
            }
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    /** Parsed shape of the one release asset we care about. */
    data class ReleaseInfo(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
    )

    // GitHub Releases API response (only the fields we read). Field names
    // match the JSON keys so Gson maps them without annotations.
    private data class GhRelease(
        val tag_name: String? = null,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset>? = null,
    )

    private data class GhAsset(
        val name: String? = null,
        val browser_download_url: String? = null,
    )

    companion object {
        /** Public repo that holds the built APKs as GitHub Releases. */
        const val RELEASES_REPO = "owenpkent/handy-releases"

        private const val USER_AGENT = "HandyVoiceV2-Updater"
        private const val NOTES_MAX_CHARS = 600

        /**
         * True iff [remote] is a strictly higher dotted-integer version than
         * [local]. Compared component-wise as integers, so "0.10" > "0.9"
         * (plain string comparison gets that backwards). A leading "v" is
         * tolerated; anything non-numeric returns false so we never "update"
         * on a malformed tag.
         */
        fun isNewer(remote: String, local: String): Boolean {
            val r = parseVersion(remote) ?: return false
            val l = parseVersion(local) ?: return false
            val n = maxOf(r.size, l.size)
            for (i in 0 until n) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        private fun parseVersion(v: String): List<Int>? {
            val cleaned = v.trim().removePrefix("v").removePrefix("V")
            if (cleaned.isEmpty()) return null
            return cleaned.split(".").map { it.toIntOrNull() ?: return null }
        }
    }
}
