package com.erronai.wakeywakey

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Играет мелодию и вибрирует. Экстрим — по кругу [Settings.ringSeconds] секунд, настойчивый — до 15 секунд.
 * Следующий звонок уже поставлен в [Actions.fire], поэтому по окончании служба просто уходит.
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var current: Long = -1
    private var mode = Mode.EXTREME
    private var savedVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        App.boot(this)
        val id = intent?.getLongExtra("id", -1) ?: -1
        val m = intent?.getStringExtra("mode")?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.EXTREME
        val r = Repo.get(id)
        if (r == null || r.done) {
            if (current < 0) stopSelf()
            return START_NOT_STICKY
        }
        // Новый звонок поверх текущего: прежний уже стоит на повтор, его уведомление остаётся в шторке
        if (current >= 0 && current != id) finish(missed = true)

        current = id
        mode = m
        val n = Notifs.ringing(this, r, m)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(notifId(id), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(notifId(id), n)
        ringing.value = id

        val s = Prefs.s.value
        val seconds = if (m == Mode.EXTREME) s.ringSeconds else 15
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeywakey:ring").apply { acquire(seconds * 1000L + 10_000L) }
        play(r, m, s)
        vibrate(m, s)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ timeUp() }, seconds * 1000L)
        if (s.rampUp) ramp(0)
        return START_NOT_STICKY
    }

    private fun play(r: Reminder, m: Mode, s: Settings) {
        stopPlayer()
        val uri = Sounds.uri(this, r.sound ?: s.sound) ?: return
        val extreme = m == Mode.EXTREME
        if (extreme && s.forceVolume > 0) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, (max * s.forceVolume / 100).coerceIn(1, max), 0) }
        }
        val attrs = AudioAttributes.Builder()
            // экстрим идёт через поток будильника — его не глушит беззвучный режим
            .setUsage(if (extreme) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun tryPlay(u: android.net.Uri) = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(this@AlarmService, u)
                isLooping = extreme
                if (s.rampUp) setVolume(0.15f, 0.15f)
                if (!extreme) setOnCompletionListener { handler.post { timeUp() } }
                prepare()
                start()
            }
        }.getOrNull()
        player = tryPlay(uri) ?: Sounds.uri(this, SOUND_DEFAULT)?.let { tryPlay(it) }
    }

    /** Нарастающая громкость: от 15% до 100% за ~20 секунд. */
    private fun ramp(step: Int) {
        val p = player ?: return
        val v = (0.15f + step * 0.0425f).coerceAtMost(1f)
        runCatching { p.setVolume(v, v) }
        if (v < 1f) handler.postDelayed({ ramp(step + 1) }, 1000)
    }

    private fun vibrate(m: Mode, s: Settings) {
        if (!s.vibrate) return
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        vibrator = v
        val effect = if (m == Mode.EXTREME) VibrationEffect.createWaveform(longArrayOf(0, 900, 600), 0)
        else VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, android.os.VibrationAttributes.createForUsage(
                if (m == Mode.EXTREME) android.os.VibrationAttributes.USAGE_ALARM else android.os.VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(
                if (m == Mode.EXTREME) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION).build())
        }
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (savedVolume >= 0) {
            runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
    }

    /**
     * Звонок закончился сам ([missed]) — оставляем в шторке уведомление с временем следующего звонка.
     * Нажали «Завершено»/«Отложить» — уведомление убирает [Actions].
     */
    private fun finish(missed: Boolean) {
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        val id = current
        current = -1
        ringing.value = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (missed && id >= 0) Repo.get(id)?.takeIf { !it.done }?.let { r ->
            val next = r.nextRing?.let { " · снова в ${fmtTime(it)}" } ?: ""
            if (mode == Mode.EXTREME) Notifs.status(this, r, "Пропущено$next")
            else Notifs.post(this, id, Notifs.base(this, App.CH_STATUS, r, r.note.ifBlank { "Напоминание$next" })
                .setSilent(true)
                .addAction(Notifs.doneAction(this, id))
                .addAction(Notifs.snoozeAction(this, id))
                .build())
        }
    }

    private fun timeUp() {
        finish(missed = true)
        stopSelf()
    }

    private fun stopRinging(id: Long) {
        if (current != id) return
        finish(missed = false)
        stopSelf()
    }

    override fun onDestroy() {
        if (current >= 0) finish(missed = true)
        instance = null
        super.onDestroy()
    }

    companion object {
        /** id записи, которая звонит сейчас. */
        val ringing = MutableStateFlow<Long?>(null)
        private var instance: AlarmService? = null

        fun start(context: Context, id: Long, mode: Mode) {
            runCatching {
                context.startForegroundService(Intent(context, AlarmService::class.java).putExtra("id", id).putExtra("mode", mode.name))
            }.onFailure {
                // запуск службы запрещён системой — хотя бы уведомление
                Repo.get(id)?.let { r -> Notifs.post(context, id, Notifs.ringing(context, r, mode)) }
            }
        }

        fun stopFor(id: Long) {
            Handler(Looper.getMainLooper()).post { instance?.stopRinging(id) }
        }
    }
}
