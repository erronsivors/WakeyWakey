package com.erronai.wakeywakey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление самого приложения. Готовые APK лежат в релизах публичного репозитория erronsivors/WakeyWakey.
 * Android ставит новый APK поверх, только если подпись та же, а versionCode больше.
 */
object Updater {
    private const val API = "https://api.github.com/repos/erronsivors/WakeyWakey/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val apkName: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** «1.2.10» > «1.2.9»: сравниваем числа, а не строки. */
    fun newer(a: String, b: String): Boolean {
        val x = a.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Один универсальный APK на релиз (нативных библиотек нет — делить по процессорам незачем). */
    private fun pickApk(assets: List<JSONObject>): JSONObject? =
        assets.firstOrNull { it.optString("name").endsWith(".apk") }

    private var checkedInProcess = false

    /** Скачанный ранее APK этой версии: после выдачи разрешения на установку Android перезапускает процесс. */
    private fun cached(context: Context, r: Release) =
        File(context.cacheDir, "update/${r.apkName}").takeIf { it.exists() && (r.apkSize <= 0 || it.length() == r.apkSize) }

    /** silent = проверка при запуске: раз за жизнь процесса и без сообщений об ошибке. */
    fun check(context: Context, silent: Boolean) {
        if (silent && checkedInProcess) return
        checkedInProcess = true
        val app = context.applicationContext
        val s = state.value
        if (s is State.Checking || s is State.Downloading || s is State.Ready) return
        state.value = State.Checking
        scope.launch {
            state.value = try {
                val c = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "WakeyWakey/${BuildConfig.VERSION_NAME}")
                }
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val ver = j.optString("tag_name").trimStart('v')
                val assets = j.optJSONArray("assets")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                val apk = pickApk(assets)
                if (apk != null && newer(ver, BuildConfig.VERSION_NAME)) {
                    val r = Release(ver, j.optString("body"), apk.getString("browser_download_url"), apk.optLong("size"), apk.getString("name"))
                    cached(app, r)?.let { State.Ready(r, it) } ?: State.Available(r)
                } else State.UpToDate
            } catch (e: Exception) {
                if (silent) State.Idle else State.Failed("Не удалось проверить обновления. Проверьте интернет")
            }
        }
    }

    fun download(context: Context, r: Release) {
        if (state.value is State.Downloading) return
        state.value = State.Downloading(r, 0f)
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, r.apkName)
        scope.launch {
            state.value = try {
                val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "WakeyWakey/${BuildConfig.VERSION_NAME}")
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastEmit > 250) {
                                lastEmit = now
                                state.value = State.Downloading(r, done.toFloat() / total)
                            }
                        }
                    }
                }
                if (r.apkSize > 0 && file.length() != r.apkSize) throw IllegalStateException("size")
                State.Ready(r, file)
            } catch (e: Exception) {
                file.delete()
                State.Failed("Не удалось скачать обновление. Проверьте интернет и попробуйте ещё раз")
            }
        }
    }

    /** Открыть системное окно установки. На Android 8+ сначала нужно разрешить установку из WakeyWakey. */
    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            App.messages.trySend("Разрешите WakeyWakey устанавливать приложения и вернитесь назад")
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun dismiss() {
        state.value = State.Idle
    }
}
