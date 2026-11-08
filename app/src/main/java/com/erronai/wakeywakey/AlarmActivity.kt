package com.erronai.wakeywakey

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Экран звонка экстрим-режима: поверх блокировки, с большой кнопкой «Завершено». */
class AlarmActivity : ComponentActivity() {
    private val reminderId = mutableLongStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // фон экрана всегда тёмный — значки строки состояния светлые
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        App.boot(this)
        reminderId.longValue = intent.getLongExtra("id", -1)
        setContent { WakeyTheme { AlarmUi() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        reminderId.longValue = intent.getLongExtra("id", reminderId.longValue)
    }

    @Composable
    private fun AlarmUi() {
        val id by reminderId
        val items by Repo.items.collectAsState()
        val ringing by AlarmService.ringing.collectAsState()
        val settings by Prefs.s.collectAsState()
        val r = items.firstOrNull { it.id == id }

        // звонок закончился или запись завершена в шторке — закрываем экран
        var wasRinging by remember { mutableStateOf(false) }
        LaunchedEffect(ringing, r?.done) {
            if (ringing == id) wasRinging = true
            if (r == null || r.done || (wasRinging && ringing != id)) finish()
        }
        BackHandler(enabled = ringing == id) { /* назад не выключает будильник */ }

        var clock by remember { mutableStateOf(fmtTime(System.currentTimeMillis())) }
        LaunchedEffect(Unit) { while (true) { clock = fmtTime(System.currentTimeMillis()); delay(1000) } }

        val math = settings.mathToStop
        val task = remember(id) { Random.nextInt(12, 60) to Random.nextInt(12, 60) }
        var answer by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }

        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B2D6E), Color(0xFF15162E))))
                .systemBarsPadding().padding(24.dp),
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(32.dp))
                Icon(Icons.Default.Alarm, null, tint = Color(0xFFFFB300), modifier = Modifier.size(56.dp))
                Text(clock, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(16.dp))
                Text(r?.title ?: "", color = Color.White, style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                if (!r?.note.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(r!!.note, color = Color(0xFFC7C4D9), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                if (r != null && ringing != id) {
                    Spacer(Modifier.height(8.dp))
                    Text(r.nextRing?.let { "Снова позвонит в ${fmtTime(it)}" } ?: "", color = Color(0xFFFF8A80))
                }
                Spacer(Modifier.weight(1f))

                if (math) {
                    Text("Чтобы завершить, решите: ${task.first} + ${task.second} = ?", color = Color.White,
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = answer, onValueChange = { answer = it.filter(Char::isDigit).take(4); wrong = false },
                        singleLine = true, isError = wrong,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.width(160.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = {
                        if (math && answer.toIntOrNull() != task.first + task.second) { wrong = true; return@Button }
                        Actions.done(this@AlarmActivity, id); finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color(0xFF231A00)),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(28.dp)); Spacer(Modifier.width(10.dp))
                    Text("Завершено", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { Actions.snooze(this@AlarmActivity, id); finish() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.Snooze, null); Spacer(Modifier.width(8.dp))
                    Text("Отложить на ${minutesText(settings.snoozeMinutes())}", fontSize = 17.sp)
                }
                if (ringing != id) TextButton(onClick = { finish() }) { Text("Закрыть", color = Color(0xFFC7C4D9)) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
