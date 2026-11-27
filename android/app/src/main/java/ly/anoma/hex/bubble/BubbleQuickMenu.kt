package ly.anoma.hex.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.label
import ly.anoma.hex.speech.DictationLanguage
import ly.anoma.hex.speech.SpeechPreferences
import ly.anoma.hex.speech.Style
import ly.anoma.hex.useInter
import kotlin.math.max
import kotlin.math.roundToInt

/** Non-focus-stealing quick menu anchored beside the floating bubble (`TYPE_ACCESSIBILITY_OVERLAY` with
 * `FLAG_NOT_FOCUSABLE | FLAG_WATCH_OUTSIDE_TOUCH`), so SwiftKey or any other active keyboard stays open
 * while switching language, style, translation, Undo dictation, or Paste last. */
internal class BubbleQuickMenu(
    private val service: HexAccessibilityService,
    private val speechPrefs: SpeechPreferences,
) {
    private enum class Screen { Main, Language, Style, NextDictation }

    private val windows = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var screen = Screen.Main

    val isShowing: Boolean get() = root != null

    fun toggle(anchorX: Int, anchorY: Int, anchorW: Int, anchorH: Int, dockRight: Boolean) {
        if (isShowing) hide() else show(anchorX, anchorY, anchorW, anchorH, dockRight)
    }

    fun hide() {
        val current = root ?: return
        root = null
        runCatching { windows.removeViewImmediate(current) }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, anchorW: Int, anchorH: Int, dockRight: Boolean) {
        hide()
        screen = Screen.Main
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(service.getColor(R.color.sheet))
                setStroke(dp(1), service.getColor(R.color.rule))
            }
            elevation = dp(8).toFloat()
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    hide()
                    true
                } else {
                    false
                }
            }
        }
        val menuWidth = dp(248)
        val maxHeight = dp(330)
        renderInto(card, maxHeight)

        val bounds = windows.currentWindowMetrics.bounds
        val screenW = bounds.width()
        val screenH = bounds.height()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val cardH = card.measuredHeight.coerceAtLeast(dp(120))
        val margin = dp(8)
        val x = if (dockRight) {
            (anchorX - menuWidth - margin).coerceIn(margin, max(margin, screenW - menuWidth - margin))
        } else {
            (anchorX + anchorW + margin).coerceIn(margin, max(margin, screenW - menuWidth - margin))
        }
        val y = (anchorY + anchorH / 2 - cardH / 2).coerceIn(margin, max(margin, screenH - cardH - margin))

        val params = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "Hex Bubble Menu"
        }
        runCatching {
            windows.addView(card, params)
            root = card
        }
    }

    private fun renderInto(card: LinearLayout, maxHeight: Int) {
        card.removeAllViews()
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(8))
        }
        if (screen != Screen.Main) {
            val back = text("‹  ", 16f, 600, R.color.ink).apply {
                setPadding(dp(2), dp(2), dp(6), dp(2))
                setOnClickListener { screen = Screen.Main; renderInto(card, maxHeight) }
            }
            header.addView(back)
        }
        val titleRes = when (screen) {
            Screen.Main -> R.string.keyboard_menu
            Screen.Language -> R.string.keyboard_menu_language
            Screen.Style -> R.string.keyboard_menu_style
            Screen.NextDictation -> R.string.keyboard_menu_next_dictation
        }
        header.addView(text(s(titleRes), 14f, 600, R.color.ink), LinearLayout.LayoutParams(0, -2, 1f))
        val close = text(s(R.string.keyboard_menu_done), 13f, 500, R.color.quiet).apply {
            setPadding(dp(8), dp(4), dp(6), dp(4))
            setOnClickListener { hide() }
        }
        header.addView(close)
        card.addView(header)
        card.addView(divider(full = true))

        val body = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val scroll = object : ScrollView(service) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val capped = MeasureSpec.makeMeasureSpec(maxHeight - dp(44), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            }
        }.apply {
            addView(body)
        }

        when (screen) {
            Screen.Main -> {
                var added = 0
                fun addRow(label: String, value: String, onClick: () -> Unit) {
                    if (added++ > 0) body.addView(divider())
                    body.addView(rowView(label, value, onClick))
                }
                if (BubbleInserter.canUndo(service)) {
                    addRow(s(R.string.keyboard_undo_dictation), "↶") {
                        BubbleInserter.undo(service)
                        hide()
                    }
                }
                val last = DictationHistory.last(service)
                if (!last.isNullOrEmpty()) {
                    addRow(s(R.string.bubble_paste_last), "⎘") {
                        hide()
                        service.pasteLastDictation()
                    }
                }
                addRow(s(R.string.keyboard_menu_language), more(speechPrefs.language?.label ?: automaticLabel())) {
                    screen = Screen.Language
                    renderInto(card, maxHeight)
                }
                addRow(s(R.string.keyboard_menu_style), more(s(speechPrefs.style.label))) {
                    screen = Screen.Style
                    renderInto(card, maxHeight)
                }
                if (speechPrefs.translation && speechPrefs.translatable) {
                    addRow(
                        s(R.string.keyboard_menu_next_dictation),
                        more(s(if (service.nextEnglish != false) R.string.keyboard_paste_english else R.string.keyboard_paste_spoken))
                    ) {
                        screen = Screen.NextDictation
                        renderInto(card, maxHeight)
                    }
                }
                addRow(s(R.string.keyboard_menu_clean), onOff(speechPrefs.clean)) {
                    speechPrefs.clean = !speechPrefs.clean
                    renderInto(card, maxHeight)
                }
                addRow(s(R.string.keyboard_menu_translate), onOff(speechPrefs.translation)) {
                    speechPrefs.translation = !speechPrefs.translation
                    service.nextEnglish = null
                    renderInto(card, maxHeight)
                }
                addRow(s(R.string.bubble_open_settings), "↗") {
                    hide()
                    service.openBubbleSettings()
                }
            }
            Screen.Language -> {
                val choices = listOf<DictationLanguage?>(null) + DictationLanguage.entries
                choices.forEachIndexed { i, lang ->
                    if (i > 0) body.addView(divider())
                    val label = lang?.label ?: automaticLabel(choices = true)
                    val chosen = if (speechPrefs.language == lang) s(R.string.keyboard_menu_chosen) else ""
                    body.addView(rowView(label, chosen) {
                        speechPrefs.language = lang
                        service.nextEnglish = null
                        hide()
                    })
                }
            }
            Screen.Style -> {
                Style.entries.forEachIndexed { i, st ->
                    if (i > 0) body.addView(divider())
                    val chosen = if (speechPrefs.style == st) s(R.string.keyboard_menu_chosen) else ""
                    body.addView(rowView(s(st.label), chosen) {
                        speechPrefs.style = st
                        screen = Screen.Main
                        renderInto(card, maxHeight)
                    })
                }
            }
            Screen.NextDictation -> {
                listOf(true, false).forEachIndexed { i, english ->
                    if (i > 0) body.addView(divider())
                    val label = s(if (english) R.string.keyboard_paste_english else R.string.keyboard_paste_spoken)
                    val chosen = if ((service.nextEnglish ?: true) == english) s(R.string.keyboard_menu_chosen) else ""
                    body.addView(rowView(label, chosen) {
                        service.nextEnglish = english
                        hide()
                    })
                }
            }
        }
        card.addView(scroll)
    }

    private fun rowView(label: String, value: String, onClick: () -> Unit): View = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(42)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
        addView(text(label, 14f, 500, R.color.ink), LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotEmpty()) addView(text(value, 13f, 450, R.color.quiet))
    }

    private fun divider(full: Boolean = false): View = View(service).apply {
        setBackgroundColor(service.getColor(R.color.rule))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            if (!full) marginStart = dp(14)
        }
    }

    private fun text(content: String, sp: Float, weight: Int, colorRes: Int) = TextView(service).apply {
        text = content
        textSize = sp
        useInter(weight)
        includeFontPadding = false
        setTextColor(service.getColor(colorRes))
    }

    private fun automaticLabel(choices: Boolean = false): String {
        val among = speechPrefs.automaticCandidates
        if (among.size <= 1) return s(R.string.keyboard_menu_automatic) + " · " + among.first().label
        val sep = s(R.string.language_list_separator)
        return s(R.string.keyboard_menu_automatic) + if (choices) " · " + among.joinToString(sep) { it.label } else ""
    }

    private fun s(id: Int) = service.getString(id)
    private fun more(v: String) = service.getString(R.string.keyboard_menu_more_value, v)
    private fun onOff(on: Boolean) = s(if (on) R.string.keyboard_on else R.string.keyboard_off)
    private fun dp(n: Int) = (service.resources.displayMetrics.density * n).roundToInt()
}
