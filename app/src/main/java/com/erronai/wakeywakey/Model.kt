package com.erronai.wakeywakey

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Как напоминать. */
enum class Mode(val title: String, val short: String) {
    LIGHT("Лайтово", "Одно уведомление в шторке со звуком уведомления. Не повторяется — просто напоминает."),
    PERSISTENT("Настойчиво", "Уведомление с вашей мелодией (до 15 секунд) и повтором через заданный интервал, пока не нажмёте «Завершено»."),
    EXTREME("Экстрим", "Будильник на весь экран: звонит поверх блокировки и беззвучного режима и повторяется через заданный интервал, пока не отметите «Завершено»."),
}

/** Обычная запись или день рождения (повторяется каждый год, время — из настроек). */
enum class Kind { NOTE, BIRTHDAY }

enum class Repeat(val title: String) {
    NONE("Не повторять"), DAILY("Каждый день"), WEEKDAYS("По будням"), WEEKLY("Каждую неделю"),
    MONTHLY("Каждый месяц"), YEARLY("Каждый год");

    /** Следующее срабатывание после [from] (строго позже [after]). */
    fun next(from: Long, after: Long): Long {
        if (this == NONE) return from
        val c = Calendar.getInstance().apply { timeInMillis = from }
        var guard = 0
        do {
            when (this) {
                DAILY -> c.add(Calendar.DAY_OF_YEAR, 1)
                WEEKDAYS -> do c.add(Calendar.DAY_OF_YEAR, 1) while (c.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY })
                WEEKLY -> c.add(Calendar.WEEK_OF_YEAR, 1)
                MONTHLY -> c.add(Calendar.MONTH, 1)
                YEARLY -> c.add(Calendar.YEAR, 1)
                NONE -> {}
            }
        } while (c.timeInMillis <= after && ++guard < 100_000)
        return c.timeInMillis
    }
}

/**
 * Запись ежедневника.
 * [time] — когда событие (для повторяющихся — ближайшее). [nextRing] — когда позвонить в следующий раз,
 * если звонок уже был или отложен (null — звонить в [time]). [fired] — лайтовое уведомление уже показано.
 */
data class Reminder(
    val id: Long,
    val title: String,
    val note: String = "",
    val time: Long,
    val repeat: Repeat = Repeat.NONE,
    val mode: Mode? = null,          // null — как в настройках
    val sound: String? = null,       // null — как в настройках
    val interval: Int? = null,       // минуты между повторами; null — как в настройках
    val advance: Int = 0,            // минут до события — заранее тихое уведомление; 0 — нет; -1 — как в настройках
    val done: Boolean = false,
    val doneAt: Long = 0,
    val nextRing: Long? = null,
    val fired: Boolean = false,
    val kind: Kind = Kind.NOTE,
    val bdMonth: Int = 0,            // день рождения: месяц (0–11 как в Calendar)
    val bdDay: Int = 0,              // число
    val bdYear: Int = 0,             // год рождения; 0 — неизвестен
) {
    val isBirthday get() = kind == Kind.BIRTHDAY

    /** Следующее срабатывание после [after] (и строго позже текущего [time]). */
    fun nextAfter(after: Long): Long =
        if (isBirthday) Birthdays.next(this, maxOf(after, time)) else repeat.next(time, after)

    val overdue get() = !done && time <= System.currentTimeMillis()

    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("note", note); put("time", time)
        put("repeat", repeat.name); mode?.let { put("mode", it.name) }; sound?.let { put("sound", it) }
        interval?.let { put("interval", it) }; put("advance", advance); put("done", done); put("doneAt", doneAt)
        nextRing?.let { put("nextRing", it) }; put("fired", fired)
        put("kind", kind.name); put("bdMonth", bdMonth); put("bdDay", bdDay); put("bdYear", bdYear)
    }

    companion object {
        fun fromJson(j: JSONObject) = Reminder(
            id = j.getLong("id"),
            title = j.optString("title"),
            note = j.optString("note"),
            time = j.getLong("time"),
            repeat = runCatching { Repeat.valueOf(j.optString("repeat")) }.getOrDefault(Repeat.NONE),
            mode = j.optString("mode").takeIf { it.isNotEmpty() }?.let { runCatching { Mode.valueOf(it) }.getOrNull() },
            sound = j.optString("sound").takeIf { it.isNotEmpty() },
            interval = if (j.has("interval")) j.getInt("interval") else null,
            advance = j.optInt("advance"),
            done = j.optBoolean("done"),
            doneAt = j.optLong("doneAt"),
            nextRing = if (j.has("nextRing")) j.getLong("nextRing") else null,
            fired = j.optBoolean("fired"),
            kind = runCatching { Kind.valueOf(j.optString("kind")) }.getOrDefault(Kind.NOTE),
            bdMonth = j.optInt("bdMonth"),
            bdDay = j.optInt("bdDay"),
            bdYear = j.optInt("bdYear"),
        )
    }
}

/** Все записи в одном JSON-файле. Записей мало, поэтому читаем и пишем целиком. */
object Repo {
    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "reminders.json")
        file = f
        _items.value = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { Reminder.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(id: Long) = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(r: Reminder) {
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list.add(r)
        save(list)
    }

    @Synchronized
    fun delete(id: Long) = save(_items.value.filterNot { it.id == id })

    @Synchronized
    fun deleteDone() = save(_items.value.filterNot { it.done })

    private fun save(list: List<Reminder>) {
        _items.value = list.sortedBy { it.time }
        val f = file ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun newId() = System.currentTimeMillis()
}
