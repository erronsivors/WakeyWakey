package com.erronai.wakeywakey

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.channels.Channel

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        boot(this)
    }

    companion object {
        const val CH_LIGHT = "light"
        const val CH_PERSISTENT = "persistent"
        const val CH_ALARM = "alarm"
        const val CH_STATUS = "status"

        /** Короткие сообщения для экрана (snackbar). Очередь: сообщение дождётся открытия экрана. */
        val messages = Channel<String>(Channel.BUFFERED)

        /** Загрузка данных и каналов уведомлений. Повторный вызов ничего не делает, поэтому зовём из ресиверов и сервиса тоже. */
        fun boot(context: Context) {
            Repo.init(context)
            Prefs.init(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(listOf(
                NotificationChannel(CH_LIGHT, "Лайтовые напоминания", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Обычное уведомление со звуком уведомления"
                },
                // Звук и вибрацию для этих каналов включает само приложение — так работает своя мелодия у каждой записи
                NotificationChannel(CH_PERSISTENT, "Настойчивые напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Всплывающее уведомление с повтором, пока не нажмёте «Завершено»"
                    setSound(null, null); enableVibration(false)
                },
                NotificationChannel(CH_ALARM, "Экстрим-будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Будильник на весь экран"
                    setSound(null, null); enableVibration(false); setBypassDnd(true)
                },
                NotificationChannel(CH_STATUS, "Отложенные и пропущенные", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Когда будет следующий звонок"
                },
            ))
        }
    }
}
