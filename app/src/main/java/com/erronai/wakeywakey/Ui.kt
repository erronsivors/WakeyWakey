package com.erronai.wakeywakey

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

// ---------- Тема ----------

private val Amber = Color(0xFFFFB300)
private val Indigo = Color(0xFF2B2D6E)

private val DarkColors = darkColorScheme(
    primary = Amber, onPrimary = Color(0xFF231A00),
    primaryContainer = Color(0xFF5A4300), onPrimaryContainer = Color(0xFFFFDF9E),
    secondary = Color(0xFFB9BCFF), onSecondary = Color(0xFF1B1D5C),
    secondaryContainer = Color(0xFF34377A), onSecondaryContainer = Color(0xFFE0E0FF),
    background = Color(0xFF15162E), onBackground = Color(0xFFE6E1F0),
    surface = Color(0xFF15162E), onSurface = Color(0xFFE6E1F0),
    surfaceVariant = Color(0xFF2A2B48), onSurfaceVariant = Color(0xFFC7C4D9),
    surfaceContainer = Color(0xFF1E1F3A), surfaceContainerHigh = Color(0xFF26274A),
    surfaceContainerLow = Color(0xFF1A1B34), surfaceContainerHighest = Color(0xFF2F3055),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF6B1F1A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E8BA6), outlineVariant = Color(0xFF45465F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5900), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF9E), onPrimaryContainer = Color(0xFF261A00),
    secondary = Indigo, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0FF), onSecondaryContainer = Color(0xFF15175A),
    background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF),
    surfaceContainer = Color(0xFFF0EDF7), surfaceContainerHigh = Color(0xFFEAE7F2),
    surfaceContainerLow = Color(0xFFF5F2FC), surfaceContainerHighest = Color(0xFFE4E1EC),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
)

@Composable
fun WakeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

// ---------- Разрешения, нужные для надёжного звонка ----------

data class Perm(val title: String, val why: String, val ok: Boolean, val fix: (Activity) -> Unit)

object Perms {
    fun list(a: Activity): List<Perm> {
        val pkg = "package:${a.packageName}".toUri()
        val out = mutableListOf<Perm>()
        val notif = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else a.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        out += Perm("Уведомления", "Без них не будет ни напоминаний в шторке, ни кнопок «Завершено»", notif) {
            it.startActivity(Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, it.packageName))
        }
        out += Perm("Точные будильники", "Чтобы звонок был минута в минуту", Scheduler.canExact(a)) {
            if (Build.VERSION.SDK_INT >= 31) it.startActivity(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            out += Perm("Будильник на весь экран", "Экстрим-режим показывает экран будильника поверх блокировки",
                a.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
                it.startActivity(Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
        }
        out += Perm("Без ограничений батареи", "Иначе экономия заряда может задержать или заглушить звонок",
            a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName)) {
            runCatching {
                @Suppress("BatteryLife")
                it.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }.onFailure { _ -> it.startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        return out
    }
}

/** Меняется при каждом возврате в приложение — чтобы перечитать статус разрешений. */
@Composable
fun resumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

// ---------- Прослушивание мелодий ----------

object Preview {
    private var player: MediaPlayer? = null
    var playing by mutableStateOf<String?>(null)
        private set

    fun toggle(context: Context, id: String) {
        if (playing == id) { stop(); return }
        stop()
        val uri = Sounds.uri(context, id) ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                setOnCompletionListener { stop() }
                prepare(); start()
            }
        }.getOrNull()
        playing = if (player != null) id else null
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
    }
}

// ---------- Общие элементы ----------

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
}

/** Строка «название — значение», по нажатию открывает выбор. */
@Composable
fun ValueRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun RadioRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else null)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Диалог выбора одного варианта из списка. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (v, label) ->
                    RadioRow(label, null, v == selected) { onPick(v); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

fun modeIcon(m: Mode) = when (m) {
    Mode.LIGHT -> Icons.Default.Notifications
    Mode.PERSISTENT -> Icons.Default.NotificationsActive
    Mode.EXTREME -> Icons.Default.Alarm
}

val INTERVALS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 60)

/**
 * Выбор мелодии: стандартная, без звука, свои; тут же можно добавить файл с телефона или системную мелодию.
 * [inherit] — показать вариант «как в настройках» (значение null).
 */
@Composable
fun SoundDialog(current: String?, inherit: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val settings by Prefs.s.collectAsState()
    val close = { Preview.stop(); onDismiss() }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val s = Sounds.importFile(ctx, uri)
            if (s != null) { onPick(s.id); close() } else App.messages.trySend("Не удалось добавить этот файл")
        }
    }
    val pickSystem = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION")
        val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) { onPick(Sounds.addSystem(ctx, uri).id); close() }
    }
    val rows = buildList<Pair<String?, String>> {
        if (inherit) add(null to "Как в настройках (${Sounds.name(settings.sound)})")
        add(SOUND_DEFAULT to "Стандартный будильник")
        settings.sounds.forEach { add(it.id to it.name) }
        add(SOUND_SILENT to "Без звука (только вибрация)")
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Мелодия") },
        text = {
            LazyColumn {
                items(rows) { (id, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { RadioRow(label, null, id == current) { onPick(id); close() } }
                        val playId = id ?: settings.sound
                        if (playId != SOUND_SILENT) IconButton(onClick = { Preview.toggle(ctx, playId) }) {
                            Icon(if (Preview.playing == playId) Icons.Default.Stop else Icons.Default.PlayArrow, "Послушать")
                        }
                    }
                }
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { pickFile.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.LibraryMusic, null); Spacer(Modifier.width(8.dp)); Text("Своя мелодия с телефона")
                        }
                        OutlinedButton(onClick = {
                            pickSystem.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Системная мелодия"))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(8.dp)); Text("Системная мелодия")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Закрыть") } },
    )
}

/** Однострочный текст с многоточием. */
@Composable
fun OneLine(text: String, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified, modifier: Modifier = Modifier) =
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
