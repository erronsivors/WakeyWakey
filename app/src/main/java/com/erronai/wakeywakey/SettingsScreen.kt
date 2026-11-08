package com.erronai.wakeywakey

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.receiveAsFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by Prefs.s.collectAsState()
    val update by Updater.state.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    val tick = resumeTick()
    val perms = remember(tick) { (ctx as? Activity)?.let { Perms.list(it) }.orEmpty() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { App.messages.receiveAsFlow().collect { snackbar.showSnackbar(it) } }

    fun set(f: (Settings) -> Settings) {
        val before = Prefs.s.value
        Prefs.update(f)
        val after = Prefs.s.value
        // время дней рождения поменялось — переставляем их на новое время
        if (after.bdHour != before.bdHour || after.bdMinute != before.bdMinute) Birthdays.retime(ctx)
        // режим и «заранее» влияют на то, как поставлены будильники у записей «как в настройках»
        else if (after.mode != before.mode || after.bdMode != before.bdMode || after.bdAdvanceDays != before.bdAdvanceDays ||
            after.noteAdvance != before.noteAdvance)
            Scheduler.rescheduleAll(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SectionTitle("Как напоминать по умолчанию")
                Mode.entries.forEach { m -> RadioRow(m.title, m.short, s.mode == m) { set { it.copy(mode = m) } } }
                Text("У каждой записи режим можно выбрать отдельно.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item {
                SectionTitle("Дни рождения")
                ValueRow("Время напоминания", "В день рождения в " + "%02d:%02d".format(s.bdHour, s.bdMinute),
                    Icons.Default.Schedule) { dialog = "bdTime" }
                ValueRow("Предупредить заранее",
                    if (s.bdAdvanceDays == 0) "Нет, только в сам день" else "За ${minutesText(s.bdAdvanceDays * 1440)} — тихим уведомлением",
                    Icons.Default.NotificationsNone) { dialog = "bdAdvance" }
                Text("Как напоминать о днях рождения", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                Mode.entries.forEach { m -> RadioRow(m.title, null, s.bdMode == m) { set { it.copy(bdMode = m) } } }
            }
            item {
                SectionTitle("Предупреждение заранее")
                ValueRow("Для записей", advanceText(s.noteAdvance) + if (s.noteAdvance > 0) " — тихое уведомление в шторке" else "",
                    Icons.Default.NotificationsNone) { dialog = "noteAdvance" }
                Text("Например, накануне вылета придёт уведомление «завтра, 06:00». У записи можно выбрать своё.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item {
                SectionTitle("Повтор сигнала")
                ValueRow("Повторять каждые", minutesText(s.interval), Icons.Default.Timer) { dialog = "interval" }
                ValueRow("Сколько звонит экстрим", secondsText(s.ringSeconds) + ", потом — пауза до следующего повтора",
                    Icons.Default.HourglassBottom) { dialog = "ring" }
                ValueRow("Кнопка «Отложить»", if (s.snooze == 0) "Как интервал повтора (${minutesText(s.interval)})" else "На ${minutesText(s.snooze)}",
                    Icons.Default.Snooze) { dialog = "snooze" }
            }
            item {
                SectionTitle("Звук")
                ValueRow("Мелодия по умолчанию", Sounds.name(s.sound), Icons.Default.MusicNote) { dialog = "sound" }
                SwitchRow("Вибрация", null, s.vibrate) { v -> set { it.copy(vibrate = v) } }
                SwitchRow("Нарастающая громкость", "Начинает тихо и за 20 секунд доходит до полной", s.rampUp) { v -> set { it.copy(rampUp = v) } }
                ValueRow("Громкость экстрим-будильника",
                    if (s.forceVolume == 0) "Как выставлено в телефоне" else "Ставить ${s.forceVolume}% на время звонка",
                    Icons.AutoMirrored.Filled.VolumeUp) { dialog = "volume" }
                SwitchRow("Задачка для выключения", "В экстриме «Завершено» нажимается только после решения примера — чтобы точно проснуться",
                    s.mathToStop) { v -> set { it.copy(mathToStop = v) } }
            }
            item {
                SectionTitle("Мои мелодии")
                if (s.sounds.isEmpty()) Text("Пока нет. Добавьте свою мелодию с телефона — её можно выбрать для любой записи.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                s.sounds.forEach { snd ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        OneLine(snd.name, MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { Preview.toggle(ctx, snd.id) }) {
                            Icon(if (Preview.playing == snd.id) Icons.Default.Stop else Icons.Default.PlayArrow, "Послушать")
                        }
                        IconButton(onClick = { Preview.stop(); Sounds.remove(ctx, snd) }) { Icon(Icons.Default.Delete, "Удалить") }
                    }
                }
                OutlinedButton(onClick = { dialog = "add" }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить мелодию")
                }
            }
            item {
                SectionTitle("Надёжность звонка")
                perms.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (p.ok) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                            tint = if (p.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge)
                            Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!p.ok) TextButton(onClick = { (ctx as? Activity)?.let { runCatching { p.fix(it) } } }) { Text("Включить") }
                    }
                }
                Text("На Xiaomi, Huawei, Honor, Samsung дополнительно разрешите WakeyWakey автозапуск и уберите ограничения фоновой работы в настройках батареи телефона.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                Button(onClick = {
                    val r = Reminder(id = Repo.newId(), title = "Проверка WakeyWakey", note = "Нажмите «Завершено», чтобы выключить",
                        time = System.currentTimeMillis() + 10_000L, mode = Mode.EXTREME)
                    Actions.save(ctx, r)
                    App.messages.trySend("Будильник зазвонит через 10 секунд — можно заблокировать экран")
                }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                    Icon(Icons.Default.Alarm, null); Spacer(Modifier.width(8.dp)); Text("Пробный экстрим-будильник через 10 с")
                }
            }
            item {
                SectionTitle("Обновления")
                ValueRow("Версия", BuildConfig.VERSION_NAME, Icons.Default.Info) {}
                UpdateBlock(update)
                SwitchRow("Проверять при запуске", "Сообщит, если на GitHub вышла новая версия", s.autoUpdate) { v -> set { it.copy(autoUpdate = v) } }
            }
        }
    }

    when (dialog) {
        "noteAdvance" -> ChoiceDialog("Предупреждать о записях заранее",
            ADVANCES.map { it to if (it == 0) "Нет" else advanceText(it) }, s.noteAdvance,
            { v -> set { it.copy(noteAdvance = v) } }) { dialog = null }
        "bdAdvance" -> ChoiceDialog("Предупредить заранее",
            listOf(0 to "Нет, только в сам день") + listOf(1, 2, 3, 7, 14).map { it to "За ${minutesText(it * 1440)}" },
            s.bdAdvanceDays, { v -> set { it.copy(bdAdvanceDays = v) } }) { dialog = null }
        "bdTime" -> {
            val st = rememberTimePickerState(s.bdHour, s.bdMinute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Время напоминания о днях рождения") },
                text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(st) } },
                confirmButton = {
                    TextButton(onClick = { set { it.copy(bdHour = st.hour, bdMinute = st.minute) }; dialog = null }) { Text("Готово") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
            )
        }
        "interval" -> ChoiceDialog("Повторять каждые", INTERVALS.map { it to minutesText(it) }, s.interval, { v -> set { it.copy(interval = v) } }) { dialog = null }
        "ring" -> ChoiceDialog("Сколько звонит экстрим", listOf(30, 60, 120, 180, 300, 600).map { it to secondsText(it) }, s.ringSeconds,
            { v -> set { it.copy(ringSeconds = v) } }) { dialog = null }
        "snooze" -> ChoiceDialog("Отложить на", listOf(0 to "Как интервал повтора") + listOf(1, 3, 5, 10, 15, 30, 60).map { it to minutesText(it) },
            s.snooze, { v -> set { it.copy(snooze = v) } }) { dialog = null }
        "volume" -> ChoiceDialog("Громкость экстрим-будильника",
            listOf(0 to "Как выставлено в телефоне", 50 to "50%", 75 to "75%", 100 to "100% — максимум"), s.forceVolume,
            { v -> set { it.copy(forceVolume = v) } }) { dialog = null }
        "sound" -> SoundDialog(s.sound, inherit = false, onPick = { v -> set { it.copy(sound = v ?: SOUND_DEFAULT) } }) { dialog = null }
        // тот же диалог: выбранная там новая мелодия просто добавляется в список, мелодию по умолчанию не меняем
        "add" -> SoundDialog(null, inherit = false, onPick = {}) { dialog = null }
    }
}

fun secondsText(sec: Int) = if (sec % 60 == 0) minutesText(sec / 60) else plural(sec, "секунда", "секунды", "секунд")

@Composable
private fun UpdateBlock(st: Updater.State) {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        when (st) {
            Updater.State.Idle, Updater.State.UpToDate, is Updater.State.Failed -> {
                if (st == Updater.State.UpToDate) Text("У вас последняя версия", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(4.dp))
                if (st is Updater.State.Failed) Text(st.message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
                OutlinedButton(onClick = { Updater.check(ctx, silent = false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Проверить обновления")
                }
            }
            Updater.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Проверяю…")
            }
            is Updater.State.Available -> {
                Text("Вышла версия ${st.release.version}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp))
                if (st.release.notes.isNotBlank()) Text(st.release.notes.take(600), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                Button(onClick = { Updater.download(ctx, st.release) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp))
                    Text("Скачать и обновить" + if (st.release.apkSize > 0) " (${st.release.apkSize / 1_048_576 + 1} МБ)" else "")
                }
            }
            is Updater.State.Downloading -> {
                Text("Скачиваю ${st.release.version}… ${(st.progress * 100).toInt()}%", modifier = Modifier.padding(4.dp))
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(4.dp))
            }
            is Updater.State.Ready -> Button(onClick = { Updater.install(ctx, st.file) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.InstallMobile, null); Spacer(Modifier.width(8.dp)); Text("Установить ${st.release.version}")
            }
        }
    }
}
