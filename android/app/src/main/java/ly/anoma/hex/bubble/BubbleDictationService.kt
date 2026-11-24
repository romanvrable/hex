package ly.anoma.hex.bubble

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import ly.anoma.hex.MainActivity
import ly.anoma.hex.R
import ly.anoma.hex.speech.SpeechPhase

/** Keeps Android's background microphone permission active while the floating bubble is turned on.
 * Unlike always-loaded engines, the Whisper weights themselves are owned by [ly.anoma.hex.speech.SpeechModel]
 * (0 MB idle, warm-loaded on bubble press, and freed after 2 minutes idle or on memory pressure). */
class BubbleDictationService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.bubble_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.bubble_channel_detail)
                setShowBadge(false)
            }
        )
        try {
            startForeground(NOTIFICATION_ID, buildNotification(SpeechPhase.Idle), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        running = true
        instance = this
        HexAccessibilityService.instance?.refreshBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TURN_OFF -> {
                BubblePreferences(this).enabled = false
                HexAccessibilityService.instance?.cancelBubbleSpeech(silent = true)
                HexAccessibilityService.instance?.refreshBubble()
                stopSelf()
            }
            ACTION_PASTE_LAST -> {
                HexAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                Handler(Looper.getMainLooper()).postDelayed({
                    HexAccessibilityService.instance?.pasteLastDictation()
                }, 550L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        HexAccessibilityService.instance?.refreshBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun updatePhase(phase: SpeechPhase) {
        if (!running) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(phase))
        }
    }

    private fun buildNotification(phase: SpeechPhase): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_BUBBLE)
        val open = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val subtitle = getString(
            when (phase) {
                SpeechPhase.Opening, SpeechPhase.Listening -> R.string.bubble_notif_listening
                SpeechPhase.Transcribing -> R.string.home_transcribing
                SpeechPhase.Idle -> R.string.bubble_notif_ready
            }
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.hex_mark)
            .setContentTitle(getString(R.string.bubble_notif_title))
            .setContentText(subtitle)
            .setContentIntent(open)
            .setOngoing(true)
        if (!DictationHistory.last(this).isNullOrEmpty()) {
            builder.addAction(
                Notification.Action.Builder(null, getString(R.string.bubble_paste_last), servicePending(ACTION_PASTE_LAST, 1)).build()
            )
        }
        builder.addAction(
            Notification.Action.Builder(null, getString(R.string.bubble_turn_off), servicePending(ACTION_TURN_OFF, 2)).build()
        )
        return builder.build()
    }

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this,
        code,
        Intent(this, BubbleDictationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "hex_bubble"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TURN_OFF = "ly.anoma.hex.action.BUBBLE_OFF"
        const val ACTION_PASTE_LAST = "ly.anoma.hex.action.PASTE_LAST"

        @Volatile var running = false
            private set

        @Volatile internal var instance: BubbleDictationService? = null
            private set

        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context.applicationContext, BubbleDictationService::class.java))
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context.applicationContext, BubbleDictationService::class.java)) }
        }
    }
}
