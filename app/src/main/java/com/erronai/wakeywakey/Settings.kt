package com.erronai.wakeywakey

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Своя мелодия: файл, скопированный в память приложения, или системный рингтон. */
data class Sound(val id: String, val name: String, val uri: String)

data class Settings(
    val mode: Mode = Mode.EXTREME,
    val interval: Int = 5,           // минуты между повторами
    val ringSeconds: Int = 60,       // сколько звонит экстрим, прежде чем уйти на повтор
    val snooze: Int = 0,             // минуты «Отложить»; 0 — как интервал
    val sound: String = SOUND_DEFAULT,
    val vibrate: Boolean = true,
    val rampUp: Boolean = true,      // нарастающая громкость
    val forceVolume: Int = 0,        // % громкости будильника в экстриме; 0 — не менять
    val mathToStop: Boolean = false, // задачка, чтобы нажать «Завершено» в экстриме
    val autoUpdate: Boolean = true,
    val sounds: List<Sound> = emptyList(),
) {
    fun snoozeMinutes() = if (snooze > 0) snooze else interval
}

const val SOUND_DEFAULT = "default"
const val SOUND_SILENT = "silent"

object Prefs {
    private val _s = MutableStateFlow(Settings())
    val s: StateFlow<Settings> = _s
    private var ctx: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (ctx != null) return
        val app = context.applicationContext
        ctx = app
        val p = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = Settings()
        _s.value = Settings(
            mode = runCatching { Mode.valueOf(p.getString("mode", d.mode.name)!!) }.getOrDefault(d.mode),
            interval = p.getInt("interval", d.interval),
            ringSeconds = p.getInt("ringSeconds", d.ringSeconds),
            snooze = p.getInt("snooze", d.snooze),
            sound = p.getString("sound", d.sound)!!,
            vibrate = p.getBoolean("vibrate", d.vibrate),
            rampUp = p.getBoolean("rampUp", d.rampUp),
            forceVolume = p.getInt("forceVolume", d.forceVolume),
            mathToStop = p.getBoolean("mathToStop", d.mathToStop),
            autoUpdate = p.getBoolean("autoUpdate", d.autoUpdate),
            sounds = runCatching {
                val a = JSONArray(p.getString("sounds", "[]"))
                (0 until a.length()).map { a.getJSONObject(it).let { j -> Sound(j.getString("id"), j.getString("name"), j.getString("uri")) } }
            }.getOrDefault(emptyList()),
        )
    }

    fun update(f: (Settings) -> Settings) {
        val n = f(_s.value)
        _s.value = n
        ctx?.getSharedPreferences("settings", Context.MODE_PRIVATE)?.edit()?.apply {
            putString("mode", n.mode.name); putInt("interval", n.interval); putInt("ringSeconds", n.ringSeconds)
            putInt("snooze", n.snooze); putString("sound", n.sound); putBoolean("vibrate", n.vibrate)
            putBoolean("rampUp", n.rampUp); putInt("forceVolume", n.forceVolume); putBoolean("mathToStop", n.mathToStop)
            putBoolean("autoUpdate", n.autoUpdate)
            putString("sounds", JSONArray().apply {
                n.sounds.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri)) }
            }.toString())
            apply()
        }
    }
}

object Sounds {
    /** Название для показа. */
    fun name(id: String?): String = when (id) {
        null, SOUND_DEFAULT -> "Стандартный будильник"
        SOUND_SILENT -> "Без звука"
        else -> Prefs.s.value.sounds.firstOrNull { it.id == id }?.name ?: "Стандартный будильник"
    }

    /** Что играть. null — без звука. Пропавшая мелодия → стандартная, чтобы будильник не молчал. */
    fun uri(context: Context, id: String?): Uri? {
        if (id == SOUND_SILENT) return null
        Prefs.s.value.sounds.firstOrNull { it.id == id }?.let { s ->
            val u = Uri.parse(s.uri)
            if (u.scheme != "file" || File(u.path ?: "").exists()) return u
        }
        return RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /** Копируем выбранный файл к себе: доступ к чужому файлу может пропасть, а будильник должен звонить. */
    fun importFile(context: Context, src: Uri): Sound? = runCatching {
        val cr = context.contentResolver
        var name = "Мелодия"
        cr.query(src, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "audio"
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "sounds").apply { mkdirs() }
        val out = File(dir, "$id.$ext")
        cr.openInputStream(src)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        val s = Sound(id, name.substringBeforeLast('.'), Uri.fromFile(out).toString())
        Prefs.update { it.copy(sounds = it.sounds + s) }
        s
    }.getOrNull()

    fun addSystem(context: Context, uri: Uri): Sound {
        val title = runCatching { RingtoneManager.getRingtone(context, uri).getTitle(context) }.getOrNull() ?: "Системная мелодия"
        val s = Sound(UUID.randomUUID().toString(), title, uri.toString())
        Prefs.update { it.copy(sounds = it.sounds + s) }
        return s
    }

    fun remove(context: Context, s: Sound) {
        Uri.parse(s.uri).takeIf { it.scheme == "file" }?.path?.let { File(it).delete() }
        Prefs.update { st -> st.copy(sounds = st.sounds - s, sound = if (st.sound == s.id) SOUND_DEFAULT else st.sound) }
        // записи с этой мелодией переходят на мелодию из настроек
        Repo.items.value.filter { it.sound == s.id }.forEach { Repo.upsert(it.copy(sound = null)) }
    }
}
