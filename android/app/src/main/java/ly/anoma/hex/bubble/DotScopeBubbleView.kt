package ly.anoma.hex.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import ly.anoma.hex.speech.SpeechPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Desktop Hex's Dot scope HUD (`src/rune_hud/dot_scope.rs`) rendered inside the floating bubble:
 * a plate of dots lit by a braided pair of traces whose amplitude follows the voice and travels out
 * from the middle. Warm pearl with a breathing coral rim while listening, agent blue shimmer while
 * transcribing, and coral flash on error. */
internal class DotScopeBubbleView(context: Context) : View(context) {
    private val density get() = resources.displayMetrics.density

    var shape: BubbleShape = BubbleShape.Circle
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var size: BubbleSize = BubbleSize.Medium
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var magnify: Float = 1f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var pillWidth: Float = REST_WIDTH
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plate = RectF()
    private val outline = Path()

    private var you = FloatArray(3)
    private var youCore = FloatArray(3)
    private var agent = FloatArray(3)
    private var agentCore = FloatArray(3)
    private var coral = FloatArray(3)

    private var phase = SpeechPhase.Idle
    private var rawLevel = 0f
    private var level = 0f
    private var velocity = 0f
    private var peak = 0.35f
    private var braid = 0f
    private var cool = 0f
    private var fail = 0f
    private var live = 0f
    private var lastFrame = 0L
    private var processingSince = 0L
    private var settleUntil = 0L
    private val history = FloatArray(HISTORY)
    private var historyHead = 0
    private var dots = FloatArray(0)
    private var previewAnimator: Runnable? = null
    private var pillAnimator: ValueAnimator? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        refreshTheme()
    }

    fun refreshTheme() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyTheme(dark)
    }

    fun applyTheme(dark: Boolean) {
        val d = density
        platePaint.color = if (dark) Color.argb(242, 22, 22, 26) else Color.argb(246, 252, 251, 249)
        platePaint.setShadowLayer(4f * d, 0f, 1.5f * d, if (dark) Color.argb(140, 0, 0, 0) else Color.argb(56, 20, 24, 40))
        rimPaint.strokeWidth = 3f * d
        rimPaint.color = if (dark) Color.argb(72, 255, 255, 255) else Color.argb(40, 0, 0, 0)
        you = if (dark) floatArrayOf(0.86f, 0.83f, 0.77f) else floatArrayOf(0.62f, 0.53f, 0.40f)
        youCore = if (dark) floatArrayOf(0.98f, 0.96f, 0.92f) else floatArrayOf(0.47f, 0.38f, 0.26f)
        agent = if (dark) floatArrayOf(0.70f, 0.78f, 0.90f) else floatArrayOf(0.40f, 0.47f, 0.58f)
        agentCore = if (dark) floatArrayOf(0.92f, 0.95f, 1.0f) else floatArrayOf(0.24f, 0.31f, 0.43f)
        coral = if (dark) floatArrayOf(1.0f, 0.38f, 0.31f) else floatArrayOf(0.90f, 0.27f, 0.21f)
        invalidate()
    }

    fun plateDp(): Pair<Float, Float> = when (shape) {
        BubbleShape.Pill -> (pillWidth * size.pillDp / BubbleSize.Medium.pillDp * magnify) to (size.pillDp * magnify)
        else -> (size.roundDp * magnify) to (size.roundDp * magnify)
    }

    fun viewPx(): Pair<Int, Int> {
        val (w, h) = plateDp()
        val d = density
        return ((w + 2 * PAD) * d).toInt() to ((h + 2 * PAD) * d).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = viewPx()
        setMeasuredDimension(w, h)
    }

    fun updateState(nextPhase: SpeechPhase, rmsLevel: Float = rawLevel, isError: Boolean = false) {
        val previous = phase
        phase = nextPhase
        rawLevel = rmsLevel
        val now = SystemClock.uptimeMillis()
        if (phase == SpeechPhase.Transcribing && previous != SpeechPhase.Transcribing) processingSince = now
        if (isError) fail = 1f
        if (previous != phase || isError) settleUntil = now + 1500L
        invalidate()
    }

    fun pushLevel(rms: Float) {
        rawLevel = rms
        invalidate()
    }

    /** Plays a 4.8 s synthetic dictation preview in Settings without opening the microphone. */
    fun playPreview() {
        previewAnimator?.let(main::removeCallbacks)
        val started = SystemClock.uptimeMillis()
        fun animatePillTo(target: Float) {
            if (shape != BubbleShape.Pill || pillWidth == target) return
            pillAnimator?.cancel()
            pillAnimator = ValueAnimator.ofFloat(pillWidth, target).apply {
                duration = 260
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener { pillWidth = it.animatedValue as Float }
                start()
            }
        }
        animatePillTo(RECORDING_WIDTH)
        val runnable = object : Runnable {
            override fun run() {
                val t = (SystemClock.uptimeMillis() - started) / 1000.0
                when {
                    t < 3.2 -> {
                        val syllable = 0.5 + 0.5 * sin(2 * Math.PI * 5.1 * t)
                        val word = (sin(2 * Math.PI * 0.9 * t) + 0.35).coerceAtLeast(0.0)
                        val syntheticRms = (0.002 + 0.09 * syllable * word * (0.7 + 0.3 * sin(13.0 * t))).toFloat()
                        updateState(SpeechPhase.Listening, syntheticRms)
                        main.postDelayed(this, 16L)
                    }
                    t < 4.8 -> {
                        if (phase != SpeechPhase.Transcribing) animatePillTo(PROCESSING_WIDTH)
                        updateState(SpeechPhase.Transcribing, 0f)
                        main.postDelayed(this, 50L)
                    }
                    else -> {
                        animatePillTo(REST_WIDTH)
                        updateState(SpeechPhase.Idle, 0f)
                        previewAnimator = null
                    }
                }
            }
        }
        previewAnimator = runnable
        main.post(runnable)
    }

    override fun onDetachedFromWindow() {
        previewAnimator?.let(main::removeCallbacks)
        previewAnimator = null
        pillAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val d = density
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        step(dt)

        val pad = PAD * d
        plate.set(pad, pad, width - pad, height - pad)
        buildOutline()
        canvas.drawPath(outline, platePaint)
        canvas.save()
        canvas.clipPath(outline)
        canvas.drawPath(outline, rimPaint)
        if (live > 0.01f) {
            val pulse = 0.75f + 0.25f * sin(now / 1000f * 2f * PI.toFloat() * 1.1f)
            livePaint.strokeWidth = 5f * d * magnify
            livePaint.color = Color.argb(
                (255 * live * pulse).toInt().coerceIn(0, 255),
                (coral[0] * 255).toInt(),
                (coral[1] * 255).toInt(),
                (coral[2] * 255).toInt()
            )
            canvas.drawPath(outline, livePaint)
        }
        canvas.restore()

        val pitch = PITCH * d * magnify
        val inset = pitch * 1.1f
        val innerW = plate.width() - 2f * (if (shape == BubbleShape.Pill) plate.height() * 0.35f else inset)
        val innerH = plate.height() - 2f * inset
        val columns = max(3, (innerW / pitch).toInt() + 1)
        val rows = ((innerH / pitch).toInt() + 1).let { if (it % 2 == 0) it - 1 else it }.coerceAtLeast(3)
        if (dots.size != columns * rows) dots = FloatArray(columns * rows)

        val x0 = plate.centerX() - (columns - 1) * pitch / 2f
        val y0 = plate.centerY() - (rows - 1) * pitch / 2f
        val decay = exp(-dt / PHOSPHOR)
        val recording = phase == SpeechPhase.Listening || phase == SpeechPhase.Opening
        val processing = phase == SpeechPhase.Transcribing
        val beamAt = if (processing) ((now - processingSince) / 1000f / SHIMMER_PASS) % 1f else -1f
        val dotRadius = DOT * d * magnify
        val processingAmplitude = if (processing) 0.32f else 0f
        val travel = plate.width() / 2f / d
        val sigma = TRACE_ROWS * 2f / (rows - 1)

        for (c in 0 until columns) {
            val u = (c / (columns - 1f)) * 2f - 1f
            val amplitude = max(sampleHistory(abs(u) * travel / HISTORY_SPEED) * 0.82f, processingAmplitude)
            val a = amplitude * sin(PI.toFloat() * HELIX * u + braid)
            val flow = if (beamAt >= 0f) exp(-((u + 1f) / 2f - beamAt).let { it * it } / (2f * 0.1f * 0.1f)) else 0f
            for (r in 0 until rows) {
                val x = x0 + c * pitch
                val y = y0 + r * pitch
                if (!inside(x, y, inset * 0.55f)) continue
                val v = (r / (rows - 1f)) * 2f - 1f
                val l1 = exp(-((v - a) * (v - a)) / (2f * sigma * sigma))
                val l2 = exp(-((v + a) * (v + a)) / (2f * sigma * sigma))
                var power = 1f - (1f - l1) * (1f - l2)
                if (processing) power = min(1f, power * (0.3f + 0.9f * flow))
                val idx = c * rows + r
                dots[idx] = max(power, dots[idx] * decay)
                drawDot(canvas, x, y, dots[idx], dotRadius)
            }
        }
        if (recording || processing || fail > 0.01f || level > 0.01f || live > 0.01f || now < settleUntil) {
            postInvalidateOnAnimation()
        } else {
            lastFrame = 0L
        }
    }

    private fun buildOutline() {
        outline.reset()
        val cx = plate.centerX()
        val cy = plate.centerY()
        when (shape) {
            BubbleShape.Pill -> outline.addRoundRect(plate, plate.height() / 2f, plate.height() / 2f, Path.Direction.CW)
            BubbleShape.Circle -> outline.addCircle(cx, cy, plate.width() / 2f, Path.Direction.CW)
            BubbleShape.Square -> plate.width().let { outline.addRoundRect(plate, it * 0.26f, it * 0.26f, Path.Direction.CW) }
            BubbleShape.Hexagon -> {
                val r = plate.height() / 2f
                for (i in 0 until 6) {
                    val angle = (-90.0 + 60.0 * i) * PI / 180.0
                    val x = cx + r * cos(angle).toFloat()
                    val y = cy + r * sin(angle).toFloat()
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                outline.close()
                val corner = CornerPathEffect(r * 0.22f)
                platePaint.pathEffect = corner
                rimPaint.pathEffect = corner
                livePaint.pathEffect = corner
                return
            }
        }
        platePaint.pathEffect = null
        rimPaint.pathEffect = null
        livePaint.pathEffect = null
    }

    private fun inside(x: Float, y: Float, margin: Float): Boolean {
        val dx = x - plate.centerX()
        val dy = y - plate.centerY()
        return when (shape) {
            BubbleShape.Pill, BubbleShape.Square -> true
            BubbleShape.Circle -> hypot(dx, dy) <= plate.width() / 2f - margin
            BubbleShape.Hexagon -> {
                val r = plate.height() / 2f - margin * 1.3f
                abs(dx) <= r * sqrt(3f) / 2f && abs(dy) + abs(dx) / sqrt(3f) <= r
            }
        }
    }

    private fun normalized(rms: Float, dt: Float): Float {
        val db = 20f * log10(max(rms, 1e-6f))
        val above = ((db + 60f) / 60f).coerceIn(0f, 1f)
        peak = max(above, peak * exp(-dt / 3.0f)).coerceAtLeast(0.35f)
        return (above / peak).coerceIn(0f, 1f).let { it * it }
    }

    private fun step(dt: Float) {
        val recording = phase == SpeechPhase.Listening || phase == SpeechPhase.Opening
        val processing = phase == SpeechPhase.Transcribing
        val target = if (recording) normalized(rawLevel, dt) else 0f
        if (target >= level) {
            val w = 2f * PI.toFloat() * RESPONSE
            velocity += (w * w * (target - level) - 2f * w * velocity) * dt
            level += velocity * dt
        } else {
            velocity = 0f
            level += (target - level) * (1f - exp(-dt / RELEASE))
        }
        level = level.coerceIn(0f, 1f)
        history[historyHead] = level
        historyHead = (historyHead + 1) % HISTORY
        val speed = when {
            recording -> 1f
            processing -> 0.7f
            else -> 0f
        }
        braid += dt * speed * 2f * PI.toFloat() * 0.45f
        val coolTarget = if (processing) 1f else 0f
        cool += (coolTarget - cool) * (1f - exp(-dt / 0.25f))
        fail *= exp(-dt / 0.6f)
        val liveTarget = if (recording) 1f else 0f
        live += (liveTarget - live) * (1f - exp(-dt / 0.12f))
    }

    private fun sampleHistory(age: Float): Float {
        val back = (age * 60f).toInt().coerceIn(0, HISTORY - 1)
        return history[(historyHead - 1 - back + 2 * HISTORY) % HISTORY]
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, power: Float, radius: Float) {
        val dim = if (phase == SpeechPhase.Idle) 0.70f else 1f
        val p = power * dim
        val alpha = ((0.07f + 0.93f * p).coerceIn(0f, 1f) * 255).toInt()
        dotPaint.color = Color.argb(alpha, channel(0, p), channel(1, p), channel(2, p))
        canvas.drawCircle(x, y, radius * (0.8f + 0.4f * p), dotPaint)
    }

    private fun channel(i: Int, p: Float): Int {
        val base = mix(mix(you[i], agent[i], cool), coral[i], fail)
        val core = mix(mix(youCore[i], agentCore[i], cool), coral[i], fail)
        return (mix(base, core, p) * 255).toInt().coerceIn(0, 255)
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun performClick(): Boolean = super.performClick()

    companion object {
        const val REST_WIDTH = 58f
        const val RECORDING_WIDTH = 136f
        const val PROCESSING_WIDTH = 96f
        const val PAD = 6f
        private const val PITCH = 3.1f
        private const val DOT = 0.9f
        private const val HELIX = 1.6f
        private const val RESPONSE = 6.5f
        private const val RELEASE = 0.15f
        private const val HISTORY_SPEED = 150f
        private const val PHOSPHOR = 0.16f
        private const val SHIMMER_PASS = 0.55f
        private const val TRACE_ROWS = 0.48f
        private const val HISTORY = 120
    }
}
