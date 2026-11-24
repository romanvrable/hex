package ly.anoma.hex.bubble

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import ly.anoma.hex.R
import ly.anoma.hex.keyboard.DictationContinuation
import ly.anoma.hex.keyboard.dictationInsertion
import ly.anoma.hex.speech.Signature
import ly.anoma.hex.speech.SpeechPreferences
import ly.anoma.hex.speech.SpokenText
import ly.anoma.hex.typing.TypingModel

/** Inserts finished dictations into the focused field of any app through [HexAccessibilityService] while
 * keeping the user's keyboard (such as Microsoft SwiftKey) untouched. Applies official Hex's mid-sentence
 * case continuation, smart spacing, Labs Signature, and 12-second Undo dictation. */
internal object BubbleInserter {
    enum class Outcome { Inserted, Pasted, CopiedNoField, CopiedRefused, BlockedPassword }

    data class FormattedInsertion(val inserted: String, val signed: Boolean)

    private const val UNDO_WINDOW_MS = 12_000L
    private data class UndoRecord(val inserted: String, val atElapsedMs: Long)

    private val continuation = DictationContinuation()
    private var lastUndo: UndoRecord? = null
    private val typingModels = HashMap<String, TypingModel?>()

    private fun wordLookup(context: Context, language: String): ((String) -> Boolean)? {
        val code = when (language.lowercase()) {
            "no", "nb", "nn" -> "nb"
            "en", "es", "de", "fr", "it" -> language.lowercase()
            else -> return null
        }
        val model = synchronized(typingModels) {
            if (code in typingModels) typingModels[code]
            else runCatching { TypingModel.asset(context.assets, code) }.getOrNull().also { typingModels[code] = it }
        } ?: return null
        return { w -> model.index(w) >= 0 && (model.capitals?.percent(w) ?: -1) < 50 }
    }

    /** Pure formatting logic shared by API 33+ InputMethod, node splicing, paste fallback, and unit tests. */
    fun prepareInsertion(
        formattedTranscript: String,
        language: String,
        ownApp: Boolean,
        signatureEnabled: Boolean,
        signatureText: String,
        beforeCursor: String?,
        afterCursor: Char?,
        continuationState: DictationContinuation = continuation,
        isWord: ((String) -> Boolean)? = null,
    ): FormattedInsertion {
        val signedText = if (ownApp) null else Signature.append(formattedTranscript, signatureText, signatureEnabled)
        val body = signedText ?: formattedTranscript
        val standalone = continuationState.standalone(beforeCursor)
        val joinable = signedText == null && !standalone
        val shaped = when {
            !joinable -> body
            isWord != null -> SpokenText.continuing(body, beforeCursor, language, isWord)
            else -> SpokenText.continuing(body, beforeCursor, language)
        }
        val inserted = dictationInsertion(shaped, beforeCursor, afterCursor, standalone)
        return FormattedInsertion(inserted, signedText != null)
    }

    fun clearContinuation() {
        continuation.clear()
        lastUndo = null
    }

    fun isPasswordEditor(inputType: Int): Boolean {
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (klass == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        if (klass == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        return false
    }

    fun isSecureField(service: HexAccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val info = runCatching { service.inputMethod?.currentInputEditorInfo }.getOrNull()
            if (info != null && isPasswordEditor(info.inputType)) return true
        }
        val node = focusedField(service)
        return node?.isPassword == true
    }

    fun insert(
        service: HexAccessibilityService,
        formattedTranscript: String,
        language: String,
        speechPrefs: SpeechPreferences,
        bubblePrefs: BubblePreferences,
        clockMs: () -> Long = SystemClock::elapsedRealtime,
    ): Outcome {
        if (isSecureField(service)) return Outcome.BlockedPassword
        val mode = bubblePrefs.insertMode

        if (mode == InsertMode.Auto && Build.VERSION.SDK_INT >= 33) {
            val typed = typeThroughInputMethod(service, formattedTranscript, language, speechPrefs, clockMs)
            if (typed != null) return typed
        }

        val node = focusedField(service)
        if (node == null) {
            val fallback = prepareInsertion(
                formattedTranscript = formattedTranscript,
                language = language,
                ownApp = false,
                signatureEnabled = speechPrefs.signature,
                signatureText = speechPrefs.signatureText,
                beforeCursor = null,
                afterCursor = null,
                isWord = wordLookup(service, language),
            )
            if (fallback.inserted.isNotEmpty()) copyToClipboard(service, fallback.inserted)
            return Outcome.CopiedNoField
        }
        if (node.isPassword) return Outcome.BlockedPassword

        val ownApp = node.packageName?.toString() == service.packageName
        if (mode == InsertMode.Auto && accepts(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) {
            if (spliceIntoNode(service, node, formattedTranscript, language, ownApp, speechPrefs, clockMs)) {
                return Outcome.Inserted
            }
        }

        val (before, after) = surroundingFromNode(node)
        val result = prepareInsertion(
            formattedTranscript = formattedTranscript,
            language = language,
            ownApp = ownApp,
            signatureEnabled = speechPrefs.signature,
            signatureText = speechPrefs.signatureText,
            beforeCursor = before,
            afterCursor = after?.firstOrNull(),
            isWord = wordLookup(service, language),
        )
        if (result.inserted.isEmpty()) return Outcome.Inserted
        copyToClipboard(service, result.inserted)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            continuation.record(if (result.signed) result.inserted else null)
            lastUndo = UndoRecord(result.inserted, clockMs())
            Outcome.Pasted
        } else {
            Outcome.CopiedRefused
        }
    }

    fun canUndo(service: HexAccessibilityService, clockMs: () -> Long = SystemClock::elapsedRealtime): Boolean {
        val undo = lastUndo ?: return false
        if (undo.inserted.isEmpty() || clockMs() - undo.atElapsedMs > UNDO_WINDOW_MS) {
            lastUndo = null
            return false
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val connection = runCatching { service.inputMethod?.currentInputConnection }.getOrNull()
            if (connection != null) {
                val around = runCatching { connection.getSurroundingText(undo.inserted.length + 4, 4, 0) }.getOrNull()
                if (around != null) {
                    val start = minOf(around.selectionStart, around.selectionEnd)
                    val end = maxOf(around.selectionStart, around.selectionEnd)
                    return start == end && start >= undo.inserted.length &&
                        around.text.subSequence(0, start).toString().endsWith(undo.inserted)
                }
            }
        }
        val node = focusedField(service) ?: return false
        val (before, _) = surroundingFromNode(node)
        return before != null && before.endsWith(undo.inserted)
    }

    fun undo(service: HexAccessibilityService, clockMs: () -> Long = SystemClock::elapsedRealtime): Boolean {
        val undo = lastUndo ?: return false
        if (!canUndo(service, clockMs)) return false
        if (Build.VERSION.SDK_INT >= 33) {
            val connection = runCatching { service.inputMethod?.currentInputConnection }.getOrNull()
            if (connection != null) {
                connection.deleteSurroundingText(undo.inserted.length, 0)
                lastUndo = null
                return true
            }
        }
        val node = focusedField(service) ?: return false
        if (!accepts(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return false
        val raw = node.text?.toString().orEmpty()
        val caret = node.textSelectionStart.takeIf { it == node.textSelectionEnd && it >= undo.inserted.length && it <= raw.length }
            ?: return false
        if (!raw.substring(0, caret).endsWith(undo.inserted)) return false
        val newStart = caret - undo.inserted.length
        val updated = SpannableStringBuilder(node.text).delete(newStart, caret)
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
        )
        if (!ok) return false
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newStart)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newStart)
            }
        )
        lastUndo = null
        return true
    }

    @TargetApi(33)
    private fun typeThroughInputMethod(
        service: AccessibilityService,
        formattedTranscript: String,
        language: String,
        speechPrefs: SpeechPreferences,
        clockMs: () -> Long,
    ): Outcome? {
        val im = service.inputMethod ?: return null
        val connection = im.currentInputConnection ?: return null
        val info = im.currentInputEditorInfo
        if (info != null && isPasswordEditor(info.inputType)) return Outcome.BlockedPassword
        val ownApp = info?.packageName == service.packageName
        val reach = maxOf(200, continuation.reach)
        val around = runCatching { connection.getSurroundingText(reach, 64, 0) }.getOrNull()
        var before: String? = null
        var afterChar: Char? = null
        if (around != null) {
            val all = around.text.toString()
            val start = minOf(around.selectionStart, around.selectionEnd)
            val end = maxOf(around.selectionStart, around.selectionEnd)
            if (start in 0..all.length && end in 0..all.length) {
                before = all.substring(0, start)
                afterChar = all.substring(end).firstOrNull()
            }
        }
        val result = prepareInsertion(
            formattedTranscript = formattedTranscript,
            language = language,
            ownApp = ownApp,
            signatureEnabled = speechPrefs.signature,
            signatureText = speechPrefs.signatureText,
            beforeCursor = before,
            afterCursor = afterChar,
            isWord = wordLookup(service, language),
        )
        if (result.inserted.isEmpty()) return Outcome.Inserted
        connection.commitText(result.inserted, 1, null)
        continuation.record(if (result.signed) result.inserted else null)
        lastUndo = UndoRecord(result.inserted, clockMs())
        return Outcome.Inserted
    }

    private fun spliceIntoNode(
        context: Context,
        node: AccessibilityNodeInfo,
        formattedTranscript: String,
        language: String,
        ownApp: Boolean,
        speechPrefs: SpeechPreferences,
        clockMs: () -> Long,
    ): Boolean {
        val raw = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        val current = if (node.isShowingHintText || (hint != null && raw == hint)) "" else raw
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0) {
            if (current.isNotEmpty()) return false
            start = 0
            end = 0
        }
        if (start > end) start = end.also { end = start }
        if (end > current.length) return false
        val before = current.substring(0, start)
        val after = current.substring(end).firstOrNull()
        val result = prepareInsertion(
            formattedTranscript = formattedTranscript,
            language = language,
            ownApp = ownApp,
            signatureEnabled = speechPrefs.signature,
            signatureText = speechPrefs.signatureText,
            beforeCursor = before,
            afterCursor = after,
            isWord = wordLookup(context, language),
        )
        if (result.inserted.isEmpty()) return true
        val updated = if (current.isEmpty()) SpannableStringBuilder(result.inserted)
        else SpannableStringBuilder(node.text).replace(start, end, result.inserted)
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
        )
        if (!ok) return false
        val caret = start + result.inserted.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
        )
        continuation.record(if (result.signed) result.inserted else null)
        lastUndo = UndoRecord(result.inserted, clockMs())
        return true
    }

    private fun surroundingFromNode(node: AccessibilityNodeInfo): Pair<String?, String?> {
        val raw = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        val current = if (node.isShowingHintText || (hint != null && raw == hint)) "" else raw
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0) return if (current.isEmpty()) "" to "" else null to null
        val lo = minOf(start, end).coerceIn(0, current.length)
        val hi = maxOf(start, end).coerceIn(0, current.length)
        return current.substring(0, lo) to current.substring(hi)
    }

    fun copyToClipboard(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    }

    fun takesText(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || accepts(node, AccessibilityNodeInfo.ACTION_SET_TEXT) || accepts(node, AccessibilityNodeInfo.ACTION_PASTE)

    private fun accepts(node: AccessibilityNodeInfo, action: Int): Boolean =
        node.actionList.any { it.id == action }

    fun focusedField(service: HexAccessibilityService): AccessibilityNodeInfo? {
        val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && takesText(focus)) return focus
        focus?.let { find(it) { n -> n.isFocused && takesText(n) } }?.let { return it }
        val app = runCatching { service.windows.firstOrNull { it.isFocused }?.root?.packageName }.getOrNull()
            ?: service.rootInActiveWindow?.packageName
        service.lastField
            ?.takeIf { it.refresh() && it.isVisibleToUser && takesText(it) && (it.isFocused || it.packageName == app) }
            ?.let { return it }
        for (window in runCatching { service.windows }.getOrDefault(emptyList())) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            find(root) { n -> n.isFocused && takesText(n) }?.let { return it }
        }
        return null
    }

    private fun find(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 400) {
            val node = queue.removeFirst()
            if (match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }
}
