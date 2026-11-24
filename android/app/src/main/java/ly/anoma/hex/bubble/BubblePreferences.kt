package ly.anoma.hex.bubble

import android.content.Context
import ly.anoma.hex.R
import org.json.JSONArray
import org.json.JSONObject

/** How Hex delivers dictation on Android. [Bubble] keeps the user's current keyboard (e.g. SwiftKey). */
internal enum class InputMode(val id: String) {
    Bubble("bubble"),
    Keyboard("keyboard"),
    Both("both");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Bubble
    }
}

/** The floating bubble's outline. [Pill] widens while listening and narrows while transcribing, like desktop Hex. */
internal enum class BubbleShape(val id: String, val iconRes: Int, val labelRes: Int) {
    Circle("circle", R.drawable.hex_shape_circle, R.string.bubble_shape_circle),
    Pill("pill", R.drawable.hex_shape_pill, R.string.bubble_shape_pill),
    Hexagon("hexagon", R.drawable.hex_shape_hexagon, R.string.bubble_shape_hexagon),
    Square("square", R.drawable.hex_shape_square, R.string.bubble_shape_square);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) } ?: Circle
    }
}

/** Bubble dimensions in dp: diameter for round shapes, height for the pill. */
internal enum class BubbleSize(val id: String, val roundDp: Float, val pillDp: Float, val labelRes: Int) {
    Small("small", 40f, 28f, R.string.bubble_size_small),
    Medium("medium", 52f, 34f, R.string.bubble_size_medium),
    Large("large", 68f, 42f, R.string.bubble_size_large);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) } ?: Medium
    }
}

/** When the floating bubble appears on screen while enabled. */
internal enum class BubbleVisibility(val id: String, val labelRes: Int) {
    Keyboard("keyboard", R.string.bubble_show_while_typing),
    Always("always", R.string.bubble_show_always);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Keyboard
    }
}

/** How finished dictations enter the focused text field. */
internal enum class InsertMode(val id: String, val labelRes: Int) {
    Auto("auto", R.string.bubble_insert_auto),
    Paste("paste", R.string.bubble_insert_paste);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Auto
    }
}

internal class BubblePreferences(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("bubble", Context.MODE_PRIVATE)

    var inputMode: InputMode
        get() = InputMode.of(prefs.getString("input_mode", InputMode.Bubble.id))
        set(value) { prefs.edit().putString("input_mode", value.id).apply() }

    /** Whether the floating bubble is turned on when Accessibility is granted. Defaults to true. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    var shape: BubbleShape
        get() = BubbleShape.of(prefs.getString("bubble_shape", BubbleShape.Circle.id))
        set(value) { prefs.edit().putString("bubble_shape", value.id).apply() }

    var size: BubbleSize
        get() = BubbleSize.of(prefs.getString("bubble_size", BubbleSize.Medium.id))
        set(value) { prefs.edit().putString("bubble_size", value.id).apply() }

    var visibility: BubbleVisibility
        get() = BubbleVisibility.of(prefs.getString("bubble_visibility", BubbleVisibility.Keyboard.id))
        set(value) { prefs.edit().putString("bubble_visibility", value.id).apply() }

    var insertMode: InsertMode
        get() = InsertMode.of(prefs.getString("insert_mode", InsertMode.Auto.id))
        set(value) { prefs.edit().putString("insert_mode", value.id).apply() }

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }

    var sounds: Boolean
        get() = prefs.getBoolean("sounds", false)
        set(value) { prefs.edit().putBoolean("sounds", value).apply() }

    var soundSet: BubbleSounds.SoundSet
        get() = BubbleSounds.SoundSet.of(prefs.getString("sound_set", BubbleSounds.SoundSet.Signature.id))
        set(value) { prefs.edit().putString("sound_set", value.id).apply() }

    var duckAudio: Boolean
        get() = prefs.getBoolean("duck_audio", false)
        set(value) { prefs.edit().putBoolean("duck_audio", value).apply() }

    var bubbleRight: Boolean
        get() = prefs.getBoolean("bubble_right", true)
        set(value) { prefs.edit().putBoolean("bubble_right", value).apply() }

    var bubbleTop: Float
        get() = prefs.getFloat("bubble_top", 0.42f).coerceIn(0.05f, 0.92f)
        set(value) { prefs.edit().putFloat("bubble_top", value.coerceIn(0.05f, 0.92f)).apply() }

    /** Labs › Recent dictations: off by default so no transcripts are written to storage unless opted in. */
    var recentHistory: Boolean
        get() = prefs.getBoolean("recent_history", false)
        set(value) { prefs.edit().putBoolean("recent_history", value).apply() }

    val usesBubble: Boolean get() = inputMode == InputMode.Bubble || inputMode == InputMode.Both
    val usesKeyboard: Boolean get() = inputMode == InputMode.Keyboard || inputMode == InputMode.Both
}

/** Keeps the latest dictation in memory for Paste Last during this session, and persists bounded recent
 * dictations only while Labs › Recent dictations is turned on. */
internal object DictationHistory {
    data class Entry(val text: String, val timestampMs: Long)

    private const val MAX_ITEMS = 50
    private const val MAX_TEXT_CHARS = 4_000
    @Volatile private var sessionLast: String? = null

    private fun store(context: Context) = context.applicationContext.getSharedPreferences("bubble_history", Context.MODE_PRIVATE)

    fun last(context: Context): String? =
        sessionLast ?: if (BubblePreferences(context).recentHistory) items(context).firstOrNull()?.text else null

    fun record(context: Context, text: String) {
        val trimmed = text.trim().take(MAX_TEXT_CHARS)
        if (trimmed.isEmpty()) return
        sessionLast = trimmed
        if (!BubblePreferences(context).recentHistory) return
        val updated = listOf(Entry(trimmed, System.currentTimeMillis())) + items(context)
        write(context, updated.take(MAX_ITEMS))
    }

    fun items(context: Context): List<Entry> {
        val raw = store(context).getString("entries", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                Entry(obj.optString("text"), obj.optLong("at"))
            }.filter { it.text.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        sessionLast = null
        store(context).edit().remove("entries").apply()
    }

    private fun write(context: Context, entries: List<Entry>) {
        val arr = JSONArray(entries.map { JSONObject().put("text", it.text).put("at", it.timestampMs) })
        store(context).edit().putString("entries", arr.toString()).apply()
    }
}
