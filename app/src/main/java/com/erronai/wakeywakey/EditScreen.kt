package com.erronai.wakeywakey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

val ADVANCES = listOf(0, 5, 10, 15, 30, 60, 120, 180, 1440, 2880, 10080)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(id: Long?, day: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val settings by Prefs.s.collectAsState()
    val orig = remember(id) { id?.let { Repo.get(it) } }

    // Начальное время новой записи: выбранный день, ближайший следующий час (сегодня) или 9:00
    val initial = remember(id) {
        orig?.time ?: Calendar.getInstance().run {
            val nowIdx = dayIndex(this)
            if (day > nowIdx) {
                add(Calendar.DAY_OF_YEAR, (day - nowIdx).toInt()); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            } else { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) }
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
    }

    var title by remember { mutableStateOf(orig?.title ?: "") }
    var note by remember { mutableStateOf(orig?.note ?: "") }
    var time by remember { mutableLongStateOf(initial) }
    var repeat by remember { mutableStateOf(orig?.repeat ?: Repeat.NONE) }
    var mode by remember { mutableStateOf(orig?.mode) }
    var sound by remember { mutableStateOf(orig?.sound) }
    var interval by remember { mutableStateOf(orig?.interval) }
    var advance by remember { mutableIntStateOf(orig?.advance ?: 0) }

    var dialog by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (orig == null) runCatching { focus.requestFocus() } }

    val effMode = mode ?: settings.mode

    fun save() {
        val r = Reminder(
            id = orig?.id ?: Repo.newId(),
            title = title.trim().ifBlank { "Напоминание" },
            note = note.trim(), time = time, repeat = repeat, mode = mode, sound = sound,
            interval = interval, advance = advance,
            done = orig?.done ?: false, doneAt = orig?.doneAt ?: 0,
        )
        Actions.save(ctx, r)
        if (r.time > System.currentTimeMillis()) App.messages.trySend("Напомню ${fmtWhen(r.time)}")
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (orig == null) "Новая запись" else "Запись") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (orig != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить") }
                    TextButton(onClick = ::save) { Text("Сохранить") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Что напомнить") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(focus),
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("Подробности (необязательно)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { dialog = "date" }, modifier = Modifier.weight(1.4f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Дата", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(SimpleDateFormat("d MMMM yyyy, EE", Locale("ru")).format(Date(time)), style = MaterialTheme.typography.titleMedium)
                    }
                }
                OutlinedCard(onClick = { dialog = "time" }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Время", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fmtTime(time), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            // быстрые варианты
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { time = roundMin(System.currentTimeMillis() + 15 * 60_000L) }, label = { Text("Через 15 мин") })
                AssistChip(onClick = { time = roundMin(System.currentTimeMillis() + 60 * 60_000L) }, label = { Text("Через час") })
                AssistChip(onClick = {
                    time = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }, label = { Text("Завтра 9:00") })
            }
            if (time <= System.currentTimeMillis()) {
                Text("Это время уже прошло — напоминание сработает сразу после сохранения",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }

            ValueRow("Повтор", repeat.title, Icons.Default.Repeat) { dialog = "repeat" }

            SectionTitle("Как напомнить")
            RadioRow("Как в настройках", "Сейчас там: ${settings.mode.title}", mode == null) { mode = null }
            Mode.entries.forEach { m -> RadioRow(m.title, m.short, mode == m) { mode = m } }

            if (effMode != Mode.LIGHT) {
                ValueRow("Мелодия", if (sound == null) "Как в настройках (${Sounds.name(settings.sound)})" else Sounds.name(sound),
                    Icons.Default.MusicNote) { dialog = "sound" }
                ValueRow("Повторять сигнал каждые",
                    if (interval == null) "Как в настройках (${minutesText(settings.interval)})" else minutesText(interval!!),
                    Icons.Default.Timer) { dialog = "interval" }
            }
            ValueRow("Предупредить заранее", if (advance == 0) "Нет" else "За ${minutesText(advance)} — тихим уведомлением",
                Icons.Default.NotificationsNone) { dialog = "advance" }

            if (orig != null && !orig.done) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { Actions.done(ctx, orig.id); onClose() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp))
                    Text(if (orig.repeat == Repeat.NONE) "Отметить «Завершено»" else "Завершено — перейти к следующему разу")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    when (dialog) {
        "date" -> {
            // DatePicker работает в UTC-полночи — переводим туда и обратно
            val utc = remember {
                val c = Calendar.getInstance().apply { timeInMillis = time }
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                }.timeInMillis
            }
            val st = rememberDatePickerState(initialSelectedDateMillis = utc)
            DatePickerDialog(
                onDismissRequest = { dialog = null },
                confirmButton = {
                    TextButton(onClick = {
                        st.selectedDateMillis?.let { sel ->
                            val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                            time = Calendar.getInstance().apply {
                                timeInMillis = time
                                set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                            }.timeInMillis
                        }
                        dialog = null
                    }) { Text("Готово") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
            ) { DatePicker(st) }
        }
        "time" -> {
            val c = remember { Calendar.getInstance().apply { timeInMillis = time } }
            val st = rememberTimePickerState(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), is24Hour = true)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Время") },
                text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(st) } },
                confirmButton = {
                    TextButton(onClick = {
                        time = Calendar.getInstance().apply {
                            timeInMillis = time; set(Calendar.HOUR_OF_DAY, st.hour); set(Calendar.MINUTE, st.minute)
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        dialog = null
                    }) { Text("Готово") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
            )
        }
        "repeat" -> ChoiceDialog("Повтор", Repeat.entries.map { it to it.title }, repeat, { repeat = it }) { dialog = null }
        "interval" -> ChoiceDialog("Повторять сигнал каждые",
            listOf<Pair<Int?, String>>(null to "Как в настройках (${minutesText(settings.interval)})") + INTERVALS.map { it to minutesText(it) },
            interval, { interval = it }) { dialog = null }
        "advance" -> ChoiceDialog("Предупредить заранее",
            ADVANCES.map { it to if (it == 0) "Нет" else "За ${minutesText(it)}" }, advance, { advance = it }) { dialog = null }
        "sound" -> SoundDialog(sound, inherit = true, onPick = { sound = it }) { dialog = null }
    }

    if (confirmDelete && orig != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить запись?") },
        text = { Text("«${orig.title}» исчезнет вместе со всеми будущими напоминаниями") },
        confirmButton = { TextButton(onClick = { Actions.delete(ctx, orig.id); confirmDelete = false; onClose() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}

private fun roundMin(ms: Long) = ms - ms % 60_000L
