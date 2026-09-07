package com.localfeed.app.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.localfeed.app.BuildConfig
import com.localfeed.app.ui.TaskCenter
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val checksumUrl: String?
)

/** GitHub Release updater. DownloadManager keeps the APK transfer alive across process restarts. */
class AppUpdater(
    private val activity: Activity,
    private val taskCenter: TaskCenter
) {
    private val prefs = activity.getSharedPreferences("localfeed_update", Context.MODE_PRIVATE)
    private val downloads = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun check() {
        val taskId = taskCenter.start("检查更新", "正在连接发布服务")
        Thread {
            runCatching { fetchLatest() }
                .onSuccess { info ->
                    taskCenter.finish(taskId, "最新版本 ${info.version}")
                    activity.runOnUiThread { showResult(info) }
                }
                .onFailure { error ->
                    taskCenter.fail(taskId, "检查失败：${error.message ?: "网络不可用"}")
                    activity.runOnUiThread { Toast.makeText(activity, "检查更新失败，请稍后重试", Toast.LENGTH_LONG).show() }
                }
        }.start()
    }

    fun resumePendingDownload() {
        val id = prefs.getLong("download_id", -1L)
        val taskId = prefs.getString("task_id", null) ?: return
        if (id < 0) return
        when (query(id).first) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_RUNNING -> {
                taskCenter.markRunning(taskId, "继续下载更新")
                monitor(id, taskId, notifyWhenDone = false)
            }
            DownloadManager.STATUS_SUCCESSFUL -> taskCenter.finish(taskId, "更新包已下载")
            DownloadManager.STATUS_FAILED -> taskCenter.fail(taskId, "更新下载失败，可重新检查更新")
        }
    }

    private fun fetchLatest(): UpdateInfo {
        var lastError: Throwable? = null
        UPDATE_MANIFESTS.forEach { endpoint ->
            try {
                val text = readText(endpoint + if (endpoint.contains('?')) "&t=${System.currentTimeMillis()}" else "?t=${System.currentTimeMillis()}")
                val root = JSONObject(text)
                val version = normalizeVersion(root.optString("version"))
                val apk = root.optString("apk_url")
                if (version.isNotBlank() && apk.isNotBlank()) {
                    val checksum = root.optString("checksum_url").takeIf { it.isNotBlank() }
                    return UpdateInfo(version, root.optString("notes"), apk, checksum)
                }
            } catch (error: Throwable) {
                lastError = error
            }
        }
        return try {
            parseRelease(readText(RELEASE_API, githubApi = true))
        } catch (error: Throwable) {
            throw IllegalStateException(lastError?.message ?: error.message ?: "更新服务暂时不可用", error)
        }
    }

    private fun parseRelease(text: String): UpdateInfo {
        val release = JSONObject(text)
        val version = normalizeVersion(release.optString("tag_name").ifBlank { release.optString("name") })
        val assets = release.getJSONArray("assets")
        var apk = ""
        var checksum: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true)) apk = url
            if (name.equals("SHA256SUMS.txt", ignoreCase = true)) checksum = url
        }
        require(version.isNotBlank() && apk.isNotBlank()) { "发布内容中没有可安装的 APK" }
        return UpdateInfo(version, release.optString("body"), apk, checksum)
    }

    private fun readText(url: String, githubApi: Boolean = false): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = 9_000
        if (githubApi) connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "LocalFeed/${BuildConfig.VERSION_NAME}")
        return try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun showResult(info: UpdateInfo) {
        if (compareVersions(info.version, BuildConfig.VERSION_NAME) <= 0) {
            AlertDialog.Builder(activity)
                .setTitle("已经是最新版")
                .setMessage("当前版本 ${BuildConfig.VERSION_NAME}")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val existing = updateFile(info.version)
        AlertDialog.Builder(activity)
            .setTitle("发现 LocalFeed ${info.version}")
            .setMessage(info.notes.ifBlank { "可以在应用内下载安装包。下载过程会显示在任务中心。" })
            .setNegativeButton("稍后", null)
            .setPositiveButton(if (existing.exists()) "安装" else "下载更新") { _, _ ->
                if (existing.exists()) install(existing) else startDownload(info)
            }
            .show()
    }

    private fun startDownload(info: UpdateInfo) {
        val target = updateFile(info.version)
        if (target.exists()) target.delete()
        val taskId = taskCenter.start("下载 LocalFeed ${info.version}", "等待系统下载服务")
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("LocalFeed ${info.version}")
            .setDescription("正在下载应用更新")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, target.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = downloads.enqueue(request)
        prefs.edit()
            .putLong("download_id", id)
            .putString("task_id", taskId)
            .putString("version", info.version)
            .putString("checksum_url", info.checksumUrl)
            .apply()
        monitor(id, taskId, notifyWhenDone = true)
        Toast.makeText(activity, "更新已加入任务中心", Toast.LENGTH_SHORT).show()
    }

    private fun monitor(downloadId: Long, taskId: String, notifyWhenDone: Boolean) {
        Thread {
            while (true) {
                val (status, progress) = query(downloadId)
                when (status) {
                    DownloadManager.STATUS_PENDING -> taskCenter.update(taskId, "等待下载", 0, 100)
                    DownloadManager.STATUS_PAUSED -> taskCenter.update(taskId, "下载已暂停，等待网络", progress, 100)
                    DownloadManager.STATUS_RUNNING -> taskCenter.update(taskId, "正在下载 $progress%", progress, 100)
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val version = prefs.getString("version", BuildConfig.VERSION_NAME).orEmpty()
                        val file = updateFile(version)
                        val checksumUrl = prefs.getString("checksum_url", null)
                        val valid = runCatching { verifyChecksum(file, checksumUrl) }.getOrDefault(false)
                        if (!valid) {
                            file.delete()
                            taskCenter.fail(taskId, "校验失败，更新包已删除")
                            if (notifyWhenDone) activity.runOnUiThread { Toast.makeText(activity, "更新包校验失败，请重新下载", Toast.LENGTH_LONG).show() }
                        } else {
                            taskCenter.finish(taskId, "下载及 SHA-256 校验完成")
                            if (notifyWhenDone) activity.runOnUiThread { install(file) }
                        }
                        return@Thread
                    }
                    DownloadManager.STATUS_FAILED -> {
                        taskCenter.fail(taskId, "下载失败，可重新检查更新")
                        return@Thread
                    }
                }
                Thread.sleep(900L)
            }
        }.start()
    }

    private fun query(id: Long): Pair<Int, Int> {
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return DownloadManager.STATUS_FAILED to 0
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val progress = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
            return status to progress
        }
    }

    private fun verifyChecksum(file: File, checksumUrl: String?): Boolean {
        if (!file.isFile || file.length() <= 0L || checksumUrl.isNullOrBlank()) return false
        val connection = URL(checksumUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "LocalFeed/${BuildConfig.VERSION_NAME}")
        val expected = connection.inputStream.bufferedReader().use { it.readText() }
            .trim().split(Regex("\\s+")).firstOrNull()?.lowercase(Locale.US) ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == expected
    }

    private fun install(file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "请允许 LocalFeed 安装应用，然后再次点击检查更新", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun updateFile(version: String): File {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        return File(dir, "LocalFeed-v$version.apk")
    }

    private fun normalizeVersion(raw: String): String = Regex("\\d+(?:\\.\\d+){1,3}").find(raw)?.value.orEmpty()

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    companion object {
        private val UPDATE_MANIFESTS = listOf(
            "https://cdn.jsdelivr.net/gh/wnbnbn/Oops_2021@localfeed-build/localfeed_ci/update.json",
            "https://raw.githubusercontent.com/wnbnbn/Oops_2021/localfeed-build/localfeed_ci/update.json"
        )
        private const val RELEASE_API = "https://api.github.com/repos/wnbnbn/Oops_2021/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
