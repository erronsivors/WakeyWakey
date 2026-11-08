package com.erronai.wakeywakey

import android.content.Context
import java.util.Calendar

/**
 * Дни рождения — ежегодные записи. Дата хранится отдельно (bdMonth/bdDay), а [Reminder.time] — ближайший раз
 * в час, выбранный в настройках. 29 февраля в невисокосный год напоминаем 28-го.
 */
object Birthdays {
    /** Момент напоминания в году [year]. */
    fun at(year: Int, month: Int, day: Int): Long {
        val s = Prefs.s.value
        return Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.DAY_OF_MONTH, day.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, s.bdHour); set(Calendar.MINUTE, s.bdMinute)
        }.timeInMillis
    }

    private fun year(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

    /** Первый раз строго после [after]. */
    fun next(r: Reminder, after: Long): Long {
        var y = year(after)
        while (true) {
            val t = at(y, r.bdMonth, r.bdDay)
            if (t > after) return t
            y++
        }
    }

    /**
     * Ближайший раз для новой записи: если день рождения сегодня, а время уже прошло — сегодня (сработает сразу),
     * чтобы не пропустить сегодняшний праздник.
     */
    fun first(month: Int, day: Int): Long {
        val now = System.currentTimeMillis()
        val y = year(now)
        val t = at(y, month, day)
        return if (dayIndex(t) >= dayIndex(now)) t else at(y + 1, month, day)
    }

    /** Сколько исполняется в день [at]; null — год рождения неизвестен. */
    fun age(r: Reminder, at: Long): Int? = if (r.bdYear > 0) (year(at) - r.bdYear).takeIf { it > 0 } else null

    /** Все дни рождения записи в промежутке [from, to) — за любые годы, в том числе прошлые. */
    fun occurrences(r: Reminder, from: Long, to: Long): List<Long> =
        (year(from)..year(to)).map { at(it, r.bdMonth, r.bdDay) }.filter { it in from until to }

    /** Время в настройках поменялось — переставляем все дни рождения. Сегодняшний неотмеченный оставляем сегодня. */
    fun retime(context: Context) {
        val now = System.currentTimeMillis()
        Repo.items.value.filter { it.isBirthday }.forEach { r ->
            val today = at(year(now), r.bdMonth, r.bdDay)
            val pendingToday = dayIndex(today) == dayIndex(now) && dayIndex(r.time) == dayIndex(now)
            // уже поздравили сегодня (запись ушла на следующий год) — сегодня больше не напоминаем
            val endOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
            }.timeInMillis
            val t = if (pendingToday) today else next(r, if (dayIndex(r.time) > dayIndex(now)) maxOf(now, endOfToday) else now)
            val n = r.copy(time = t, nextRing = null, fired = pendingToday && r.fired) // уже показанный сегодня не повторяем
            Repo.upsert(n)
            Scheduler.schedule(context, n)
        }
    }

    /** Вчерашние и более ранние неотмеченные дни рождения — на следующий год (праздник прошёл). */
    fun rollPast(context: Context) {
        val now = System.currentTimeMillis()
        Repo.items.value.filter { it.isBirthday && dayIndex(it.time) < dayIndex(now) }.forEach { r ->
            val n = r.copy(time = next(r, now), nextRing = null, fired = false)
            Repo.upsert(n)
            Scheduler.schedule(context, n)
            Notifs.cancel(context, r.id)
        }
    }

    /** «через 5 дней», «завтра», «сегодня». */
    fun untilText(at: Long): String {
        val d = (dayIndex(at) - dayIndex(System.currentTimeMillis())).toInt()
        return when {
            d <= 0 -> "сегодня"
            d == 1 -> "завтра"
            else -> "через " + plural(d, "день", "дня", "дней")
        }
    }
}
