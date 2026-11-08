package com.erronai.wakeywakey

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Запись в конкретный день: для повторяющихся — время именно этого раза. */
data class Occurrence(val r: Reminder, val at: Long)

/** Все срабатывания записи в промежутке [from, to). */
fun occurrences(r: Reminder, from: Long, to: Long): List<Long> {
    if (r.repeat == Repeat.NONE || r.done) return if (r.time in from until to) listOf(r.time) else emptyList()
    val out = mutableListOf<Long>()
    var t = r.time
    var guard = 0
    while (t < to && guard++ < 2000) {
        if (t >= from) out += t
        t = r.repeat.next(t, t)
    }
    return out
}

fun monthStart(year: Int, month: Int): Calendar = Calendar.getInstance().apply {
    clear(); set(year, month, 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNew: (Long) -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val items by Repo.items.collectAsState()
    val ringing by AlarmService.ringing.collectAsState()
    val update by Updater.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { App.messages.receiveAsFlow().collect { snackbar.showSnackbar(it) } }

    // «сейчас» обновляется раз в минуту — для пометок «просрочено»
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val today = remember { Calendar.getInstance() }
    var year by remember { mutableIntStateOf(today.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(today.get(Calendar.MONTH)) }
    var selected by remember { mutableLongStateOf(dayIndex(System.currentTimeMillis())) }
    var showAll by remember { mutableStateOf(false) }

    val tick = resumeTick()
    val missing = remember(tick) { (ctx as? Activity)?.let { a -> Perms.list(a).filter { !it.ok } }.orEmpty() }

    // точки на календаре: день → (есть незавершённые, есть завершённые)
    val marks = remember(items, year, month) {
        val start = monthStart(year, month)
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val m = HashMap<Long, Pair<Boolean, Boolean>>()
        items.forEach { r ->
            occurrences(r, start.timeInMillis, end.timeInMillis).forEach { t ->
                val d = dayIndex(t)
                val (a, b) = m[d] ?: (false to false)
                m[d] = if (r.done) a to true else true to b
            }
        }
        m
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WakeyWakey", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNew(selected) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Запись") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (missing.isNotEmpty()) item {
                Card(
                    onClick = onSettings,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text("Будильник может не сработать: не включено «${missing.joinToString("», «") { it.title }}». Нажмите, чтобы исправить",
                            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (update is Updater.State.Available || update is Updater.State.Ready) item {
                val ver = (update as? Updater.State.Available)?.release?.version ?: (update as Updater.State.Ready).release.version
                Card(
                    onClick = onSettings,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Вышла версия $ver — обновить в настройках", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                MonthCalendar(year, month, selected, marks,
                    onMonth = { d ->
                        val c = monthStart(year, month).apply { add(Calendar.MONTH, d) }
                        year = c.get(Calendar.YEAR); month = c.get(Calendar.MONTH)
                    },
                    onToday = {
                        val c = Calendar.getInstance()
                        year = c.get(Calendar.YEAR); month = c.get(Calendar.MONTH); selected = dayIndex(c); showAll = false
                    },
                    onSelect = { selected = it; showAll = false },
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SegmentedButton(!showAll, { showAll = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("На дату") }
                    SegmentedButton(showAll, { showAll = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Все записи") }
                }
            }
            if (!showAll) {
                val dayStart = Calendar.getInstance().apply {
                    timeInMillis = selected * 86_400_000L; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.let { c ->
                    // selected — индекс местного дня; подгоняем начало дня точно
                    while (dayIndex(c) < selected) c.add(Calendar.DAY_OF_YEAR, 1)
                    while (dayIndex(c) > selected) c.add(Calendar.DAY_OF_YEAR, -1)
                    c.timeInMillis
                }
                val dayEnd = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
                val list = items.flatMap { r -> occurrences(r, dayStart, dayEnd).map { Occurrence(r, it) } }.sortedBy { it.at }
                item {
                    Text(fmtDay(dayStart).replaceFirstChar { it.uppercase() } +
                        SimpleDateFormat(if (fmtDay(dayStart).first().isDigit()) "" else " · d MMMM", Locale("ru")).format(Date(dayStart)),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                if (list.isEmpty()) item { Empty("На этот день ничего нет. Нажмите «Запись», чтобы добавить") }
                items(list, key = { "${it.r.id}-${it.at}" }) { o ->
                    ReminderCard(o.r, o.at, now, ringing == o.r.id, onOpen)
                }
            } else {
                val active = items.filter { !it.done }
                val attention = active.filter { it.time <= now || it.nextRing != null }.sortedBy { it.time }
                val upcoming = active.filter { it !in attention }.sortedBy { it.time }
                val done = items.filter { it.done }.sortedByDescending { it.doneAt }
                if (items.isEmpty()) item { Empty("Записей пока нет. Нажмите «Запись», чтобы добавить первую") }
                if (attention.isNotEmpty()) {
                    item { SectionTitle("Ждут отметки «Завершено»") }
                    items(attention, key = { "a${it.id}" }) { ReminderCard(it, it.time, now, ringing == it.id, onOpen) }
                }
                if (upcoming.isNotEmpty()) {
                    item { SectionTitle("Предстоящие") }
                    items(upcoming, key = { "u${it.id}" }) { ReminderCard(it, it.time, now, false, onOpen) }
                }
                if (done.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionTitle("Завершённые") }
                            TextButton(onClick = { Repo.deleteDone() }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) { Text("Очистить") }
                        }
                    }
                    items(done, key = { "d${it.id}" }) { ReminderCard(it, it.time, now, false, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
}

@Composable
fun ReminderCard(r: Reminder, at: Long, now: Long, isRinging: Boolean, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val mode = r.effMode()
    val late = !r.done && at <= now && r.time <= now
    Card(
        onClick = { onOpen(r.id) },
        colors = CardDefaults.cardColors(
            containerColor = if (isRinging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(r.done, onCheckedChange = { if (it) Actions.done(ctx, r.id) else Actions.undone(ctx, r.id) })
            Column(Modifier.weight(1f)) {
                OneLine(r.title.ifBlank { "Без названия" }, MaterialTheme.typography.titleMedium.copy(
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                    fontWeight = FontWeight.SemiBold),
                    color = if (r.done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                val status = when {
                    isRinging -> "Звонит сейчас"
                    r.done -> "Завершено"
                    r.nextRing != null -> "Снова позвонит в ${fmtTime(r.nextRing)}"
                    late -> "Просрочено"
                    else -> null
                }
                val line = buildString {
                    append(fmtWhen(at))
                    if (r.repeat != Repeat.NONE) append(" · ").append(r.repeat.title.lowercase())
                }
                OneLine(line, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant)
                if (status != null) OneLine(status, MaterialTheme.typography.bodySmall,
                    if (late || isRinging || r.nextRing != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(modeIcon(mode), mode.title, tint = if (mode == Mode.EXTREME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MonthCalendar(
    year: Int, month: Int, selected: Long, marks: Map<Long, Pair<Boolean, Boolean>>,
    onMonth: (Int) -> Unit, onToday: () -> Unit, onSelect: (Long) -> Unit,
) {
    val first = monthStart(year, month)
    val title = SimpleDateFormat("LLLL yyyy", Locale("ru")).format(first.time).replaceFirstChar { it.uppercase() }
    val todayIdx = dayIndex(System.currentTimeMillis())
    // понедельник — первый день недели
    val shift = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstIdx = dayIndex(first)

    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(-1) }) { Icon(Icons.Default.ChevronLeft, "Предыдущий месяц") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { onMonth(1) }) { Icon(Icons.Default.ChevronRight, "Следующий месяц") }
        }
        TextButton(onClick = onToday, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Default.Today, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Сегодня")
        }
        Row {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { i, d ->
                Text(d, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = if (i >= 5) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        val rows = (shift + days + 6) / 7
        for (row in 0 until rows) {
            Row {
                for (col in 0 until 7) {
                    val n = row * 7 + col - shift + 1
                    Box(Modifier.weight(1f).aspectRatio(1.3f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..days) {
                            val idx = firstIdx + n - 1
                            val isSel = idx == selected
                            val isToday = idx == todayIdx
                            val mark = marks[idx]
                            Box(
                                Modifier.fillMaxHeight().aspectRatio(1f).clip(CircleShape)
                                    .then(if (isSel) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
                                    .then(if (isToday && !isSel) Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), CircleShape) else Modifier)
                                    .clickable { onSelect(idx) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$n", style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isToday || isSel) FontWeight.Bold else null,
                                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                if (mark != null) Box(
                                    Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).clip(RoundedCornerShape(3.dp))
                                        .background(
                                            when {
                                                isSel -> MaterialTheme.colorScheme.onPrimary
                                                mark.first -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.outline
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
