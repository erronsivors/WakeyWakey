package com.erronai.wakeywakey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Добавить или изменить день рождения. [day] — начало выбранного в календаре дня (для новой записи). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayEditScreen(id: Long?, day: Long?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val settings by Prefs.s.collectAsState()
    val orig = remember(id) { id?.let { Repo.get(it) } }
    val start = remember(id) { Calendar.getInstance().apply { if (day != null) timeInMillis = day } }

    var name by remember { mutableStateOf(orig?.title ?: "") }
    var note by remember { mutableStateOf(orig?.note ?: "") }
    var month by remember { mutableIntStateOf(orig?.bdMonth ?: start.get(Calendar.MONTH)) }
    var dom by remember { mutableIntStateOf(orig?.bdDay ?: start.get(Calendar.DAY_OF_MONTH)) }
    var year by remember { mutableIntStateOf(orig?.bdYear?.takeIf { it > 0 } ?: start.get(Calendar.YEAR)) }
    var knowYear by remember { mutableStateOf((orig?.bdYear ?: 0) > 0) }
    var picker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (orig == null) runCatching { focus.requestFocus() } }

    val dateText = remember(month, dom, year, knowYear) {
        val c = Calendar.getInstance().apply { clear(); set(if (knowYear) year else 2000, month, dom) }
        SimpleDateFormat(if (knowYear) "d MMMM yyyy" else "d MMMM", Locale("ru")).format(c.time)
    }

    fun save() {
        if (name.isBlank()) { error = true; return }
        val r = Reminder(
            id = orig?.id ?: Repo.newId(),
            title = name.trim(), note = note.trim(),
            // дату не меняли — оставляем ближайший раз как был (иначе уже поздравленный сегодня позвонит снова)
            time = if (orig != null && orig.bdMonth == month && orig.bdDay == dom) orig.time else Birthdays.first(month, dom),
            repeat = Repeat.YEARLY, kind = Kind.BIRTHDAY,
            bdMonth = month, bdDay = dom, bdYear = if (knowYear) year else 0,
        )
        Actions.save(ctx, r)
        App.messages.trySend(
            if (r.time <= System.currentTimeMillis()) "День рождения сегодня — напоминание уже в шторке. Дальше напомню каждый год"
            else "Напомню ${fmtDay(r.time)} в ${fmtTime(r.time)} и дальше каждый год")
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (orig == null) "Новый день рождения" else "День рождения") },
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
                value = name, onValueChange = { name = it; error = false },
                label = { Text("Чей день рождения") },
                placeholder = { Text("Например: Мама") },
                isError = error,
                supportingText = if (error) { { Text("Подпишите, чей это день рождения") } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(focus),
            )
            OutlinedCard(onClick = { picker = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Дата", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dateText, style = MaterialTheme.typography.titleMedium)
                }
            }
            SwitchRow("Знаю год рождения", "Тогда покажу, сколько исполняется", knowYear) { knowYear = it }
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("Заметка (необязательно)") },
                placeholder = { Text("Идея подарка, телефон…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Text(
                "Напоминание придёт каждый год в " + "%02d:%02d".format(settings.bdHour, settings.bdMinute) +
                    " (режим «${settings.bdMode.title}»" +
                    (if (settings.bdAdvanceDays > 0) ", и ещё за ${minutesText(settings.bdAdvanceDays * 1440)}" else "") +
                    "). Это меняется в настройках, в разделе «Дни рождения».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    if (picker) {
        val utc = remember {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month, dom) }.timeInMillis
        }
        val st = rememberDatePickerState(initialSelectedDateMillis = utc)
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let {
                        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }
                        year = u.get(Calendar.YEAR); month = u.get(Calendar.MONTH); dom = u.get(Calendar.DAY_OF_MONTH)
                    }
                    picker = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Отмена") } },
        ) { DatePicker(st) }
    }

    if (confirmDelete && orig != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить день рождения?") },
        text = { Text("Больше не буду напоминать о дне рождения «${orig.title}»") },
        confirmButton = { TextButton(onClick = { Actions.delete(ctx, orig.id); confirmDelete = false; onClose() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}
