package com.erronai.wakeywakey

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*

sealed interface Screen {
    data object Home : Screen
    data class Edit(val id: Long?, val day: Long) : Screen
    data object Settings : Screen
    data class Birthday(val id: Long?, val day: Long?) : Screen
}

class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf<Screen>(Screen.Home)
    private val tab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        App.boot(this)
        // на случай, если система потеряла будильники (принудительная остановка, сбой) — ставим заново
        if (savedInstanceState == null) { Birthdays.rollPast(this); Scheduler.rescheduleAll(this) }
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Prefs.s.value.autoUpdate) Updater.check(this, silent = true)
        setContent {
            WakeyTheme {
                val s by screen
                BackHandler(enabled = s != Screen.Home) { screen.value = Screen.Home }
                when (val cur = s) {
                    Screen.Home -> HomeScreen(
                        tab = tab.intValue, onTab = { tab.intValue = it },
                        onNew = { day -> screen.value = Screen.Edit(null, day) },
                        onNewBirthday = { day -> screen.value = Screen.Birthday(null, day) },
                        onOpen = { id -> screen.value = open(id) },
                        onSettings = { screen.value = Screen.Settings },
                    )
                    is Screen.Birthday -> BirthdayEditScreen(cur.id, cur.day, onClose = { screen.value = Screen.Home })
                    is Screen.Edit -> EditScreen(cur.id, cur.day, onClose = { screen.value = Screen.Home })
                    Screen.Settings -> SettingsScreen(onBack = { screen.value = Screen.Home })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Нажали на уведомление — открываем запись. */
    private fun handle(intent: Intent?) {
        val id = intent?.getLongExtra("open", -1) ?: -1
        if (id >= 0 && Repo.get(id) != null) screen.value = open(id)
    }

    private fun open(id: Long): Screen =
        if (Repo.get(id)?.isBirthday == true) Screen.Birthday(id, null) else Screen.Edit(id, 0)
}
