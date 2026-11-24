package ly.anoma.hex.bubble

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.speech.SpeechPhase
import ly.anoma.hex.useInter
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** The floating dictation bubble (`TYPE_ACCESSIBILITY_OVERLAY`, non-focusable so SwiftKey or any other
 * keyboard stays open):
 * - Tap when idle: start hands-free dictation; tap again to finish & insert.
 * - Hold (≥350 ms): push-to-talk; bubble rises above your finger, release finishes & inserts.
 * - Double-tap (<260 ms): opens the non-focus-stealing [BubbleQuickMenu] (Undo, Paste last, Language, Style, etc.).
 * - Long-press (≥550 ms) while recording or transcribing: cancels recording or aborts Whisper inference.
 * - Drag: repositions the bubble and snaps to the nearer screen edge. */
internal class BubbleOverlay(
    private val service: HexAccessibilityService,
    private val prefs: BubblePreferences,
    private val quickMenu: BubbleQuickMenu,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = DotScopeBubbleView(service)
    private var shown = false
    private var phase = SpeechPhase.Idle
    private var targetWidth = DotScopeBubbleView.REST_WIDTH
    private var pillAnimator: ValueAnimator? = null
    private var lift = 0f
    private var liftAnimator: ValueAnimator? = null

    private var noticeView: TextView? = null
    private val hideNotice = Runnable { dismissNotice() }

    private val params = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Hex Bubble"
    }

    init {
        scope.setOnTouchListener(Gestures())
        scope.setOnClickListener {
            when (phase) {
                SpeechPhase.Opening, SpeechPhase.Listening -> service.finishBubbleSpeech()
                SpeechPhase.Idle, SpeechPhase.Transcribing -> service.beginBubbleSpeech()
            }
        }
        scope.contentDescription = service.getString(R.string.bubble_accessibility_label)
        restyle()
    }

    fun restyle() {
        scope.refreshTheme()
        scope.shape = prefs.shape
        scope.size = prefs.size
        scope.pillWidth = DotScopeBubbleView.REST_WIDTH
        targetWidth = DotScopeBubbleView.REST_WIDTH
        resize()
        if (shown) place()
    }

    fun show() {
        if (shown) return
        scope.refreshTheme()
        resize()
        place()
        runCatching {
            windows.addView(scope, params)
            shown = true
        }
    }

    fun hide() {
        quickMenu.hide()
        dismissNotice()
        if (!shown) return
        pillAnimator?.cancel()
        liftAnimator?.cancel()
        lift = 0f
        scope.magnify = 1f
        runCatching { windows.removeViewImmediate(scope) }
        shown = false
    }

    fun onConfigurationChanged() {
        scope.refreshTheme()
        quickMenu.hide()
        if (shown) place()
    }

    fun render(nextPhase: SpeechPhase, note: String?, isError: Boolean) {
        phase = nextPhase
        scope.updateState(nextPhase, isError = isError)
        if (scope.shape == BubbleShape.Pill) {
            val target = when (nextPhase) {
                SpeechPhase.Opening, SpeechPhase.Listening -> DotScopeBubbleView.RECORDING_WIDTH
                SpeechPhase.Transcribing -> DotScopeBubbleView.PROCESSING_WIDTH
                SpeechPhase.Idle -> DotScopeBubbleView.REST_WIDTH
            }
            if (target != targetWidth) animatePill(target)
        }
        if (!note.isNullOrEmpty() && shown) showNotice(note)
    }

    fun pushLevel(rms: Float) {
        scope.pushLevel(rms)
    }

    fun showNotice(message: String) {
        if (!shown) return
        main.removeCallbacks(hideNotice)
        dismissNotice()
        val label = TextView(service).apply {
            text = message
            textSize = 13f
            useInter(500)
            includeFontPadding = false
            setTextColor(service.getColor(R.color.ink))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(service.getColor(R.color.sheet))
                setStroke(dp(1), service.getColor(R.color.rule))
            }
            elevation = dp(6).toFloat()
        }
        val bounds = windows.currentWindowMetrics.bounds
        val maxW = (bounds.width() * 0.65f).toInt().coerceAtLeast(dp(160))
        label.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(bounds.height(), View.MeasureSpec.AT_MOST)
        )
        val nw = label.measuredWidth
        val nh = label.measuredHeight
        val margin = dp(8)
        val nx = if (prefs.bubbleRight) {
            (params.x - nw - margin).coerceAtLeast(margin)
        } else {
            (params.x + params.width + margin).coerceAtMost(max(margin, bounds.width() - nw - margin))
        }
        val ny = (params.y + (params.height - nh) / 2).coerceIn(margin, max(margin, bounds.height() - nh - margin))
        val noticeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = nx
            y = ny
            title = "Hex Notice"
        }
        runCatching {
            windows.addView(label, noticeParams)
            noticeView = label
            main.postDelayed(hideNotice, 2600L)
        }
    }

    private fun dismissNotice() {
        val current = noticeView ?: return
        noticeView = null
        runCatching { windows.removeViewImmediate(current) }
    }

    private fun resize() {
        val (w, h) = scope.viewPx()
        params.width = w
        params.height = h
    }

    private fun animatePill(target: Float) {
        targetWidth = target
        pillAnimator?.cancel()
        pillAnimator = ValueAnimator.ofFloat(scope.pillWidth, target).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                scope.pillWidth = it.animatedValue as Float
                place()
            }
            start()
        }
    }

    private fun holdLift(active: Boolean) {
        liftAnimator?.cancel()
        liftAnimator = ValueAnimator.ofFloat(lift, if (active) 1f else 0f).apply {
            duration = if (active) 180 else 140
            interpolator = if (active) OvershootInterpolator(1.2f) else null
            addUpdateListener {
                lift = it.animatedValue as Float
                scope.magnify = 1f + HOLD_GROWTH * lift
                place()
            }
            start()
        }
    }

    private fun screen(): Pair<Int, Int> {
        val bounds = windows.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun restingHeight(): Float {
        val (_, plate) = scope.plateDp()
        val d = service.resources.displayMetrics.density
        return (plate / scope.magnify + 2f * DotScopeBubbleView.PAD) * d
    }

    private fun place() {
        val (width, height) = screen()
        val resting = restingHeight()
        resize()
        params.x = if (prefs.bubbleRight) width - params.width else 0
        val top = prefs.bubbleTop * height
        params.y = (top - lift * (params.height - resting * 0.25f)).toInt()
        clamp()
        if (shown) runCatching { windows.updateViewLayout(scope, params) }
    }

    private fun clamp() {
        val (width, height) = screen()
        params.x = params.x.coerceIn(0, max(0, width - params.width))
        params.y = params.y.coerceIn(0, max(0, height - params.height))
    }

    private fun snap() {
        val (width, height) = screen()
        prefs.bubbleRight = params.x + params.width / 2 > width / 2
        if (height > 0) prefs.bubbleTop = params.y.toFloat() / height
        place()
    }

    private fun haptic(kind: Int) {
        if (prefs.haptics) scope.performHapticFeedback(kind)
    }

    private fun dp(n: Int) = (service.resources.displayMetrics.density * n).roundToInt()

    private inner class Gestures : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downAt = 0L
        private var lastTapStartedAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var startedHere = false
        private var locked = false
        private var consumed = false
        private var holding = false

        private val holdStart = Runnable {
            if (!dragging && startedHere && (phase == SpeechPhase.Opening || phase == SpeechPhase.Listening)) {
                holding = true
                holdLift(true)
            }
        }

        private val longPressCancel = Runnable {
            val recordingLocked = locked && (phase == SpeechPhase.Opening || phase == SpeechPhase.Listening)
            val transcribing = phase == SpeechPhase.Transcribing
            if (!dragging && !startedHere && (recordingLocked || transcribing)) {
                consumed = true
                locked = false
                service.cancelBubbleSpeech(silent = false)
                haptic(HapticFeedbackConstants.REJECT)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = SystemClock.uptimeMillis()
                    downAt = now
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    consumed = false
                    startedHere = false
                    holding = false

                    if (quickMenu.isShowing) {
                        quickMenu.hide()
                        consumed = true
                        return true
                    }

                    // Second tap within DOUBLE_TAP_MS of an idle-started tap opens the Quick Menu instead of dictating.
                    if (locked && now - lastTapStartedAt <= DOUBLE_TAP_MS &&
                        (phase == SpeechPhase.Opening || phase == SpeechPhase.Listening)
                    ) {
                        locked = false
                        lastTapStartedAt = 0L
                        consumed = true
                        service.cancelBubbleSpeech(silent = true)
                        haptic(HapticFeedbackConstants.CLOCK_TICK)
                        quickMenu.show(params.x, params.y, params.width, params.height, prefs.bubbleRight)
                        return true
                    }

                    if (phase == SpeechPhase.Idle) {
                        startedHere = service.beginBubbleSpeech()
                        locked = false
                        if (startedHere) haptic(HapticFeedbackConstants.CONFIRM)
                    }
                    v.postDelayed(longPressCancel, CANCEL_MS)
                    v.postDelayed(holdStart, HOLD_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (holding) return true
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        v.removeCallbacks(longPressCancel)
                        v.removeCallbacks(holdStart)
                        if (startedHere) {
                            service.cancelBubbleSpeech(silent = true)
                            startedHere = false
                        }
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clamp()
                        runCatching { windows.updateViewLayout(scope, params) }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressCancel)
                    v.removeCallbacks(holdStart)
                    if (holding) {
                        holding = false
                        holdLift(false)
                    }
                    if (dragging) {
                        snap()
                        return true
                    }
                    if (consumed || event.actionMasked == MotionEvent.ACTION_CANCEL) return true
                    val held = SystemClock.uptimeMillis() - downAt
                    when {
                        startedHere && held >= HOLD_MS -> {
                            service.finishBubbleSpeech()
                            haptic(HapticFeedbackConstants.CONFIRM)
                        }
                        startedHere -> {
                            locked = true
                            lastTapStartedAt = downAt
                        }
                        locked && (phase == SpeechPhase.Opening || phase == SpeechPhase.Listening) -> {
                            locked = false
                            lastTapStartedAt = 0L
                            service.finishBubbleSpeech()
                            haptic(HapticFeedbackConstants.CONFIRM)
                        }
                        phase == SpeechPhase.Transcribing && held < CANCEL_MS -> {
                            // Short tap while an earlier clip transcribes starts the next queued dictation, matching official Hex.
                            startedHere = service.beginBubbleSpeech()
                            if (startedHere) {
                                locked = true
                                lastTapStartedAt = downAt
                                haptic(HapticFeedbackConstants.CONFIRM)
                            }
                        }
                    }
                }
            }
            return true
        }
    }

    companion object {
        const val DOUBLE_TAP_MS = 260L
        const val HOLD_MS = 350L
        const val CANCEL_MS = 550L
        const val HOLD_GROWTH = 0.85f
    }
}
