package com.laradars.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps the camera list and the app itself up to date from a small manifest online:
 * {"data_version": "2026-11-04", "data_url": "...cameras.json",
 *  "app_version_code": 3, "app_version_name": "1.2", "apk_url": "...LA-Radars.apk"}
 */
object DataUpdater {
    private const val CHECK_EVERY_MS = 24L * 3600 * 1000

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Manifest(
        val dataVersion: String, val dataUrl: String,
        val appVersionCode: Int, val appVersionName: String, val apkUrl: String,
    )

    data class Result(val dataUpdated: Boolean, val dataVersion: String, val newApp: Manifest?, val error: String?)

    /** Called when a trip starts: at most one check a day, silently. */
    fun checkIfDue(context: Context) {
        val prefs = Prefs(context)
        if (System.currentTimeMillis() - prefs.lastDataCheckMs < CHECK_EVERY_MS) return
        check(context) { }
    }

    fun check(context: Context, callback: (Result) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = try {
                val m = fetchManifest()
                val prefs = Prefs(app)
                prefs.lastDataCheckMs = System.currentTimeMillis()
                var updated = false
                if (m.dataVersion != prefs.dataVersion) {
                    val json = get(m.dataUrl)
                    val cams = CameraRepository.parse(json)
                    if (cams.size < 50) throw IllegalStateException("data looks truncated (${cams.size} cameras)")
                    CameraRepository.dataFile(app).writeText(json)
                    CameraRepository.invalidate()
                    prefs.dataVersion = m.dataVersion
                    updated = true
                    main.post { TrackingService.notifyDataChanged(app) }
                }
                val newApp = if (m.appVersionCode > BuildConfig.VERSION_CODE) m else null
                Result(updated, m.dataVersion, newApp, null)
            } catch (e: Exception) {
                Result(false, Prefs(app).dataVersion, null, e.message ?: e.toString())
            }
            main.post { callback(result) }
        }
    }

    private fun fetchManifest(): Manifest {
        val o = JSONObject(get(BuildConfig.MANIFEST_URL))
        return Manifest(
            o.getString("data_version"), o.getString("data_url"),
            o.getInt("app_version_code"), o.getString("app_version_name"), o.getString("apk_url"),
        )
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "LA-Radars/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Hands the APK to the system downloader; Android then offers to install it. */
    fun downloadApk(context: Context, m: Manifest) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(m.apkUrl))
            .setTitle("LA Radars ${m.appVersionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "LA-Radars-${m.appVersionName}.apk")
        dm.enqueue(req)
    }

    /** Opens the installer for a finished download (from the download-complete broadcast). */
    fun installDownloaded(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }
}
