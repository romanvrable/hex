package ly.anoma.hex.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import ly.anoma.hex.MainActivity
import ly.anoma.hex.R
import ly.anoma.hex.keyboard.speechNote
import ly.anoma.hex.speech.DictationPlace
import ly.anoma.hex.speech.LocalSpeech
import ly.anoma.hex.speech.MicCapture
import ly.anoma.hex.speech.SpeechModel
import ly.anoma.hex.speech.SpeechOptions
import ly.anoma.hex.speech.SpeechPhase
import ly.anoma.hex.speech.SpeechPreferences

/** Owns the floating Hex bubble (`TYPE_ACCESSIBILITY_OVERLAY`) and inserts local Whisper / Parakeet dictations
 * directly into any app's focused field while keeping the user's keyboard (such as SwiftKey) active.
 * Never starts a foreground service in [onServiceConnected], preventing Android 14+ background FGS crashes
 * that would cause Android to disable the Accessibility toggle overnight. */
class HexAccessibilityService : AccessibilityService() {
    internal val speechPrefs by lazy { SpeechPreferences(this) }
    internal val bubblePrefs by lazy { BubblePreferences(this) }
    internal var nextEnglish: Boolean? = null

    private var overlay: BubbleOverlay? = null
    private var quickMenu: BubbleQuickMenu? = null
    private lateinit var capture: MicCapture
    private val main = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { if (!bubbleWanted()) overlay?.hide() }
    private val startCueDelayed = Runnable { BubbleSounds.playIfEnabled(this, BubbleSounds.Cue.Start) }
    private var stoppedAtMs = 0L
    private var lastFocusedPackage: CharSequence? = null
    private var focusRequest: AudioFocusRequest? = null

    var lastField: AccessibilityNodeInfo? = null
        private set

    private val bubblePrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        main.post {
            when (key) {
                "bubble_shape", "bubble_size" -> overlay?.restyle()
                "enabled", "input_mode", "bubble_visibility" -> refreshBubble()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        capture = MicCapture(
            recognizer = LocalSpeech(applicationContext),
            place = DictationPlace.Keyboard,
            update = ::onCaptureUpdate,
            meter = { overlay?.pushLevel(it) },
            insert = { text, _, options, _ -> commitBubbleSpeech(text, options) },
        )
        val menu = BubbleQuickMenu(this, speechPrefs)
        quickMenu = menu
        overlay = BubbleOverlay(this, bubblePrefs, menu)
        getSharedPreferences("bubble", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(bubblePrefListener)
        refreshBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            val src = event.source
            if (src != null && src.packageName != packageName && BubbleInserter.takesText(src)) {
                if (lastFocusedPackage != src.packageName) {
                    lastFocusedPackage = src.packageName
                    BubbleInserter.clearContinuation()
                }
                lastField = src
            }
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            refreshBubble()
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) {
            SpeechModel.releaseSoon()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(hideLater)
        main.removeCallbacks(startCueDelayed)
        releaseDuckFocus()
        BubbleDictationService.stop(this)
        getSharedPreferences("bubble", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(bubblePrefListener)
        if (::capture.isInitialized) capture.close()
        lastField = null
        quickMenu?.hide()
        quickMenu = null
        overlay?.hide()
        overlay = null
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    internal fun restyleBubble() = overlay?.restyle()

    internal fun refreshBubble() {
        val current = overlay ?: return
        main.removeCallbacks(hideLater)
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!interactive) {
            BubbleDictationService.stop(this)
            current.hide()
            return
        }
        if (bubbleWanted()) current.show()
        else main.postDelayed(hideLater, HIDE_DELAY_MS)
    }

    private fun bubbleWanted(): Boolean {
        if (!bubblePrefs.enabled || !bubblePrefs.usesBubble) return false
        val busy = ::capture.isInitialized && capture.phase != SpeechPhase.Idle
        if (busy || bubblePrefs.visibility == BubbleVisibility.Always) return true
        if (!keyboardOpen()) return false
        if (bubblePrefs.inputMode == InputMode.Both && hexImeSelected()) return false
        return true
    }

    private fun keyboardOpen(): Boolean =
        runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    private fun hexImeSelected(): Boolean = runCatching {
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        selected != null && selected.startsWith("$packageName/")
    }.getOrDefault(false)

    internal fun beginBubbleSpeech(fromArm: Boolean = false): Boolean {
        if (!::capture.isInitialized) return false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            overlay?.showNotice(getString(R.string.keyboard_hint_microphone))
            openBubbleSettings()
            return false
        }
        if (BubbleInserter.isSecureField(this)) {
            overlay?.showNotice(getString(R.string.keyboard_hint_password))
            return false
        }
        if (!capture.canStart) return false
        val opts = speechPrefs.options(nextEnglish)
        nextEnglish = null
        acquireDuckFocusIfWanted()
        capture.start(opts)
        main.removeCallbacks(startCueDelayed)
        main.postDelayed(startCueDelayed, 150L)
        return true
    }

    internal fun finishBubbleSpeech() {
        if (!::capture.isInitialized) return
        if (capture.phase == SpeechPhase.Opening || capture.phase == SpeechPhase.Listening) {
            main.removeCallbacks(startCueDelayed)
            releaseDuckFocus()
            stoppedAtMs = SystemClock.uptimeMillis()
            BubbleSounds.playIfEnabled(this, BubbleSounds.Cue.Stop)
            capture.finish()
        }
    }

    internal fun cancelBubbleSpeech(silent: Boolean) {
        if (!::capture.isInitialized) return
        val hadPendingCue = main.hasCallbacks(startCueDelayed)
        main.removeCallbacks(startCueDelayed)
        releaseDuckFocus()
        capture.cancelLatest()
        if (!silent && !hadPendingCue) {
            BubbleSounds.playIfEnabled(this, BubbleSounds.Cue.Cancel)
        }
    }

    internal fun pasteLastDictation() {
        val last = DictationHistory.last(this) ?: return
        val lang = speechPrefs.spoken.code
        deliverText(last, lang, recordHistory = false)
    }

    internal fun openBubbleSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_BUBBLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun acquireDuckFocusIfWanted() {
        releaseDuckFocus()
        if (!bubblePrefs.duckAudio) return
        val audio = getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
    }

    private fun releaseDuckFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) }
    }

    private fun launchArmActivity(startDictation: Boolean) {
        runCatching {
            startActivity(
                Intent(this, ArmActivity::class.java)
                    .putExtra(ArmActivity.EXTRA_START_DICTATION, startDictation)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            overlay?.showNotice(getString(R.string.bubble_note_open_app))
        }
    }

    private fun onCaptureUpdate(phase: SpeechPhase, rawNote: String?) {
        if (phase != SpeechPhase.Opening && phase != SpeechPhase.Listening) releaseDuckFocus()
        BubbleDictationService.instance?.updatePhase(phase)
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted && !BubbleDictationService.running &&
            (rawNote == "Allow the microphone in Hex" || rawNote == "Microphone unavailable. Try again.")
        ) {
            main.removeCallbacks(startCueDelayed)
            launchArmActivity(startDictation = true)
            overlay?.render(phase, null, isError = false)
            refreshBubble()
            return
        }
        val localized = speechNote(rawNote)
        val isError = rawNote != null && rawNote != "Stopped at 1 minute" && rawNote != "No speech heard"
        if (isError) BubbleSounds.playIfEnabled(this, BubbleSounds.Cue.Error)
        overlay?.render(phase, localized, isError)
        refreshBubble()
    }

    private fun commitBubbleSpeech(text: String, options: SpeechOptions) {
        DictationHistory.record(this, text)
        val language = if (options.translate) "en" else options.spoken.code
        val ok = deliverText(text, language, recordHistory = false)
        if (ok && stoppedAtMs > 0L && SystemClock.uptimeMillis() - stoppedAtMs >= 2000L) {
            BubbleSounds.playIfEnabled(this, BubbleSounds.Cue.Done)
        }
    }

    private fun deliverText(text: String, language: String, recordHistory: Boolean): Boolean {
        if (recordHistory) DictationHistory.record(this, text)
        return when (BubbleInserter.insert(this, text, language, speechPrefs, bubblePrefs)) {
            BubbleInserter.Outcome.Inserted, BubbleInserter.Outcome.Pasted -> true
            BubbleInserter.Outcome.BlockedPassword -> {
                overlay?.showNotice(getString(R.string.keyboard_hint_password))
                false
            }
            BubbleInserter.Outcome.CopiedNoField -> {
                overlay?.showNotice(getString(R.string.bubble_note_copied_no_field))
                false
            }
            BubbleInserter.Outcome.CopiedRefused -> {
                overlay?.showNotice(getString(R.string.bubble_note_copied_refused))
                false
            }
        }
    }

    companion object {
        private const val HIDE_DELAY_MS = 400L

        @Volatile var instance: HexAccessibilityService? = null
            private set

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val expected = ComponentName(context, HexAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
