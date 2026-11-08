package com.erronai.wakeywakey

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val RU = Locale("ru")

fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", RU).format(Date(ms))

/** «сегодня», «завтра», «12 ноября, ср», с годом — если не текущий. */
fun fmtDay(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val diff = dayIndex(c) - dayIndex(today)
    return when (diff) {
        0L -> "сегодня"
        1L -> "завтра"
        -1L -> "вчера"
        else -> SimpleDateFormat(if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "d MMMM, EE" else "d MMMM yyyy", RU).format(Date(ms))
    }
}

fun fmtWhen(ms: Long) = "${fmtDay(ms)}, ${fmtTime(ms)}"

/** Номер дня от эпохи в местном времени — для сравнения дат. */
fun dayIndex(c: Calendar): Long = (c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L
fun dayIndex(ms: Long) = dayIndex(Calendar.getInstance().apply { timeInMillis = ms })

fun minutesText(m: Int): String = when {
    m % 1440 == 0 -> plural(m / 1440, "день", "дня", "дней")
    m % 60 == 0 -> plural(m / 60, "час", "часа", "часов")
    else -> plural(m, "минута", "минуты", "минут")
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val w = if (n % 10 == 1 && n % 100 != 11) one else if (n % 10 in 2..4 && n % 100 !in 12..14) few else many
    return "$n $w"
}

fun Reminder.effMode() = mode ?: if (isBirthday) Prefs.s.value.bdMode else Prefs.s.value.mode
/** За сколько минут предупредить заранее: у дней рождения — из настроек. */
fun Reminder.effAdvance() = when {
    isBirthday -> Prefs.s.value.bdAdvanceDays * 1440
    advance < 0 -> Prefs.s.value.noteAdvance
    else -> advance
}

/** «за 1 день», «за 3 часа» — подпись для «предупредить заранее». */
fun advanceText(m: Int) = if (m <= 0) "Нет" else "За ${minutesText(m)}"

/** Заголовок уведомления и экрана будильника. */
fun Reminder.displayTitle() = if (isBirthday) "День рождения: $title" else title.ifBlank { "Напоминание" }

/** Текст под заголовком. */
fun Reminder.body(): String = if (isBirthday) {
    val age = Birthdays.age(this, time)
    listOfNotNull(age?.let { "Исполняется ${plural(it, "год", "года", "лет")}" }, note.ifBlank { null })
        .joinToString(" · ").ifBlank { "Не забудьте поздравить" }
} else note.ifBlank { fmtWhen(time) }
fun Reminder.effInterval() = interval ?: Prefs.s.value.interval

fun notifId(id: Long) = (id % Int.MAX_VALUE).toInt().let { if (it < 100) it + 100 else it }

/** Расписание звонков через AlarmManager. */
object Scheduler {
    const val KIND_RING = "ring"
    const val KIND_ADVANCE = "advance"

    private fun pi(context: Context, id: Long, kind: String) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AlarmReceiver::class.java).setData(Uri.parse("wakey://r/$id/$kind"))
            .putExtra("id", id).putExtra("kind", kind),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pi(context, id, KIND_RING))
        am.cancel(pi(context, id, KIND_ADVANCE))
    }

    fun schedule(context: Context, r: Reminder) {
        cancel(context, r.id)
        if (r.done) return
        val mode = r.effMode()
        val shownBirthday = r.isBirthday && r.fired
        if (mode == Mode.LIGHT && r.fired && !shownBirthday) return
        val am = context.getSystemService(AlarmManager::class.java)
        // день рождения уже показан сегодня — следующий звонок через год
        val at = if (shownBirthday) Birthdays.next(r, maxOf(System.currentTimeMillis(), r.time)) else r.nextRing ?: r.time
        val ring = pi(context, r.id, KIND_RING)
        try {
            if (mode == Mode.LIGHT) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
            // setAlarmClock — самый надёжный способ: срабатывает в режиме сна и показывает значок будильника
            else am.setAlarmClock(AlarmManager.AlarmClockInfo(at, Notifs.openApp(context, r.id)), ring)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
        }
        val adv = r.effAdvance()
        if (adv > 0 && r.nextRing == null) {
            val a = (if (shownBirthday) at else r.time) - adv * 60_000L
            if (a > System.currentTimeMillis()) try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a, pi(context, r.id, KIND_ADVANCE))
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a, pi(context, r.id, KIND_ADVANCE))
            }
        }
    }

    fun rescheduleAll(context: Context) = Repo.items.value.forEach { schedule(context, it) }
}

/** Что происходит при звонке и по кнопкам «Завершено» / «Отложить». */
object Actions {
    fun fire(context: Context, id: Long, kind: String) {
        var r = Repo.get(id) ?: return
        if (r.done) return
        if (r.isBirthday && r.fired) {
            // показанный в прошлый раз день рождения — переходим к этому году
            val nt = Birthdays.next(r, r.time)
            r = r.copy(time = nt, fired = false)
            if (kind == Scheduler.KIND_RING) Repo.upsert(r)
        }
        if (kind == Scheduler.KIND_ADVANCE) {
            val text = if (r.isBirthday) "${fmtDay(r.time).replaceFirstChar { it.uppercase() }} день рождения · ${r.body()}"
            else "${fmtWhen(r.time).replaceFirstChar { it.uppercase() }} · ${r.effMode().title.lowercase()}" +
                r.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            Notifs.light(context, r, text, withDone = false)
            return
        }
        val now = System.currentTimeMillis()
        val s = Prefs.s.value
        when (val mode = r.effMode()) {
            Mode.LIGHT -> {
                Notifs.light(context, r, r.body(), withDone = true)
                // день рождения остаётся «сегодня» до «Поздравил»; повторяющаяся запись сама переходит на следующий раз
                val n = if (r.isBirthday) r.copy(fired = true, nextRing = null)
                else if (r.repeat != Repeat.NONE) r.copy(time = r.nextAfter(now), nextRing = null, fired = false)
                else r.copy(fired = true, nextRing = null)
                Repo.upsert(n)
                Scheduler.schedule(context, n)
            }
            else -> {
                var gap = r.effInterval() * 60_000L
                if (mode == Mode.EXTREME) gap = maxOf(gap, s.ringSeconds * 1000L + 30_000L)
                // следующий звонок ставим сразу: даже если телефон убьёт приложение, он всё равно прозвенит
                val n = r.copy(nextRing = now + gap)
                Repo.upsert(n)
                Scheduler.schedule(context, n)
                AlarmService.start(context, id, mode)
            }
        }
    }

    fun done(context: Context, id: Long) {
        val r = Repo.get(id) ?: return
        val now = System.currentTimeMillis()
        val n = if (r.repeat != Repeat.NONE) {
            r.copy(time = r.nextAfter(now), nextRing = null, fired = false, done = false).also {
                App.messages.trySend(if (r.isBirthday) "Отмечено. Напомню снова ${fmtDay(it.time)}" else "Готово. Следующий раз: ${fmtWhen(it.time)}")
            }
        } else r.copy(done = true, doneAt = now, nextRing = null)
        Repo.upsert(n)
        Scheduler.schedule(context, n)
        AlarmService.stopFor(id)
        Notifs.cancel(context, id)
    }

    fun undone(context: Context, id: Long) {
        val r = Repo.get(id) ?: return
        val n = r.copy(done = false, doneAt = 0, nextRing = null, fired = r.time <= System.currentTimeMillis() && r.effMode() == Mode.LIGHT)
        Repo.upsert(n)
        Scheduler.schedule(context, n)
    }

    fun snooze(context: Context, id: Long) {
        val r = Repo.get(id) ?: return
        val m = Prefs.s.value.snoozeMinutes()
        val n = r.copy(nextRing = System.currentTimeMillis() + m * 60_000L)
        Repo.upsert(n)
        Scheduler.schedule(context, n)
        AlarmService.stopFor(id)
        Notifs.status(context, n, "Отложено до ${fmtTime(n.nextRing!!)}")
    }

    fun delete(context: Context, id: Long) {
        Scheduler.cancel(context, id)
        AlarmService.stopFor(id)
        Notifs.cancel(context, id)
        Repo.delete(id)
    }

    /** Сохранить из редактора: новое время — значит, звонки начинаются заново. */
    fun save(context: Context, r: Reminder) {
        val n = r.copy(nextRing = null, fired = false, done = if (r.time > System.currentTimeMillis()) false else r.done)
        Repo.upsert(n)
        Scheduler.schedule(context, n)
        Notifs.cancel(context, n.id)
    }
}

object Notifs {
    fun openApp(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).setData(Uri.parse("wakey://open/$id")).putExtra("open", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun alarmScreen(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, AlarmActivity::class.java).setData(Uri.parse("wakey://alarm/$id")).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun action(context: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ActionReceiver::class.java).setAction(action).setData(Uri.parse("wakey://act/$id/$action")).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun base(context: Context, channel: String, r: Reminder, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(r.displayTitle())
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, r.id))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    fun doneAction(context: Context, id: Long) =
        NotificationCompat.Action(0, if (Repo.get(id)?.isBirthday == true) "Поздравил" else "Завершено",
            action(context, ActionReceiver.DONE, id))

    fun snoozeAction(context: Context, id: Long) =
        NotificationCompat.Action(0, "Отложить на ${minutesText(Prefs.s.value.snoozeMinutes())}", action(context, ActionReceiver.SNOOZE, id))

    fun light(context: Context, r: Reminder, text: String, withDone: Boolean) {
        val b = base(context, App.CH_LIGHT, r, text).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (withDone) b.addAction(doneAction(context, r.id))
        post(context, r.id, b.build())
    }

    fun status(context: Context, r: Reminder, text: String) {
        val b = base(context, App.CH_STATUS, r, text).setSilent(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (!(r.effMode() == Mode.EXTREME && Prefs.s.value.mathToStop)) b.addAction(doneAction(context, r.id))
        else b.setContentIntent(alarmScreen(context, r.id))
        post(context, r.id, b.build())
    }

    /** Уведомление во время звонка (оно же — уведомление фоновой службы). */
    fun ringing(context: Context, r: Reminder, mode: Mode): Notification {
        val extreme = mode == Mode.EXTREME
        val text = r.body()
        val b = base(context, if (extreme) App.CH_ALARM else App.CH_PERSISTENT, r, text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (extreme) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(extreme)
            // не setSilent: «тихая» группа запрещает полноэкранный показ; звука у канала и так нет — играет служба
        if (!(extreme && Prefs.s.value.mathToStop)) b.addAction(doneAction(context, r.id))
        b.addAction(snoozeAction(context, r.id))
        if (extreme) {
            val full = alarmScreen(context, r.id)
            b.setContentIntent(full).setFullScreenIntent(full, true)
        }
        return b.build()
    }

    fun post(context: Context, id: Long, n: Notification) {
        runCatching { nm(context).notify(notifId(id), n) } // без разрешения на уведомления — молча
    }

    fun cancel(context: Context, id: Long) = nm(context).cancel(notifId(id))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        App.boot(context)
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        Actions.fire(context, id, intent.getStringExtra("kind") ?: Scheduler.KIND_RING)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        App.boot(context)
        val id = intent.getLongExtra("id", -1)
        when (intent.action) {
            DONE -> Actions.done(context, id)
            SNOOZE -> Actions.snooze(context, id)
        }
    }

    companion object {
        const val DONE = "com.erronai.wakeywakey.DONE"
        const val SNOOZE = "com.erronai.wakeywakey.SNOOZE"
    }
}

/** После перезагрузки, смены времени или обновления приложения будильники нужно поставить заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        App.boot(context)
        Birthdays.rollPast(context)
        Scheduler.rescheduleAll(context)
    }
}
