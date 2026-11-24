package ly.anoma.hex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ly.anoma.hex.bubble.*

@Composable
internal fun BubbleSettingsContent(
    setup: Setup,
    bubble: BubblePreferences,
    revision: Int,
    openSoundSets: () -> Unit,
    openAccessibility: () -> Unit,
) {
    val context = LocalContext.current
    val enabled = remember(revision) { bubble.enabled }
    val inputMode = remember(revision) { bubble.inputMode }
    val shape = remember(revision) { bubble.shape }
    val size = remember(revision) { bubble.size }
    val visibility = remember(revision) { bubble.visibility }
    val insertMode = remember(revision) { bubble.insertMode }
    val haptics = remember(revision) { bubble.haptics }
    val sounds = remember(revision) { bubble.sounds }
    val soundSet = remember(revision) { bubble.soundSet }
    val duckAudio = remember(revision) { bubble.duckAudio }
    var practice by remember { mutableStateOf("") }
    var previewTrigger by remember { mutableIntStateOf(0) }

    // Live DotScope preview card with Preview button.
    Card {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 116.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            var lastPlayed by remember { mutableIntStateOf(0) }
            AndroidView(
                factory = { ctx ->
                    DotScopeBubbleView(ctx).apply {
                        this.shape = shape
                        this.size = size
                    }
                },
                update = { view ->
                    view.refreshTheme()
                    if (view.shape != shape) view.shape = shape
                    if (view.size != size) view.size = size
                    if (previewTrigger != lastPlayed) {
                        lastPlayed = previewTrigger
                        view.playPreview()
                    }
                },
                modifier = Modifier.align(Alignment.Center)
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorResource(R.color.page))
                    .clickable(role = Role.Button) { previewTrigger++ }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Label(stringResource(R.string.bubble_preview), size = 13, weight = FontWeight.Medium)
            }
        }
        Rule()
        Label(
            stringResource(R.string.bubble_gestures_hint),
            size = 13,
            muted = true,
            lines = 3,
            mod = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }

    Card {
        Toggle(
            stringResource(R.string.bubble_enabled),
            stringResource(R.string.bubble_enabled_detail),
            enabled
        ) { on ->
            bubble.enabled = on
            if (on && setup.mic && setup.accessibility) {
                BubbleDictationService.start(context)
            } else if (!on) {
                BubbleDictationService.stop(context)
            }
            HexAccessibilityService.instance?.refreshBubble()
        }
        if (!setup.accessibility) {
            Rule()
            Entry(
                stringResource(R.string.home_accessibility_settings),
                stringResource(R.string.setup_bubble_detail),
                "↗",
                openAccessibility
            )
        }
        Rule()
        SegmentedSetting(
            title = stringResource(R.string.bubble_input_mode),
            options = listOf(
                InputMode.Bubble to stringResource(R.string.setup_mode_bubble),
                InputMode.Keyboard to stringResource(R.string.setup_mode_keyboard),
                InputMode.Both to "Both",
            ),
            selected = inputMode,
            note = when (inputMode) {
                InputMode.Bubble -> stringResource(R.string.bubble_mode_bubble)
                InputMode.Keyboard -> stringResource(R.string.bubble_mode_keyboard)
                InputMode.Both -> stringResource(R.string.bubble_mode_both)
            },
            onSelect = { next ->
                bubble.inputMode = next
                if (next.let { it == InputMode.Bubble || it == InputMode.Both } && bubble.enabled && setup.mic && setup.accessibility) {
                    BubbleDictationService.start(context)
                } else if (next == InputMode.Keyboard) {
                    BubbleDictationService.stop(context)
                }
                HexAccessibilityService.instance?.refreshBubble()
            }
        )
    }

    Card {
        ShapeSelectorRow(selected = shape) { next ->
            bubble.shape = next
            HexAccessibilityService.instance?.restyleBubble()
        }
        Rule()
        SegmentedSetting(
            title = stringResource(R.string.bubble_size),
            options = BubbleSize.entries.map { it to stringResource(it.labelRes) },
            selected = size,
            onSelect = { next ->
                bubble.size = next
                HexAccessibilityService.instance?.restyleBubble()
            }
        )
        Rule()
        SegmentedSetting(
            title = stringResource(R.string.bubble_show),
            options = BubbleVisibility.entries.map { it to stringResource(it.labelRes) },
            selected = visibility,
            onSelect = { next ->
                bubble.visibility = next
                HexAccessibilityService.instance?.refreshBubble()
            }
        )
        Rule()
        SegmentedSetting(
            title = stringResource(R.string.bubble_insert),
            options = InsertMode.entries.map { it to stringResource(it.labelRes) },
            selected = insertMode,
            note = stringResource(R.string.bubble_insert_detail),
            onSelect = { bubble.insertMode = it }
        )
    }

    Card {
        Toggle(stringResource(R.string.bubble_haptics), stringResource(R.string.bubble_haptics_detail), haptics) {
            bubble.haptics = it
        }
        Rule()
        Toggle(stringResource(R.string.bubble_sounds), stringResource(R.string.bubble_sounds_detail), sounds) {
            bubble.sounds = it
            if (it) BubbleSounds.play(context, bubble.soundSet, BubbleSounds.Cue.Start)
        }
        if (sounds) {
            Rule()
            Entry(
                stringResource(R.string.bubble_sound_set),
                stringResource(soundSet.detailRes),
                stringResource(soundSet.labelRes),
                openSoundSets
            )
        }
        Rule()
        Toggle(stringResource(R.string.bubble_duck_audio), stringResource(R.string.bubble_duck_audio_detail), duckAudio) {
            bubble.duckAudio = it
        }
    }

    val ink = colorResource(R.color.ink)
    Card {
        BasicTextField(
            value = practice,
            onValueChange = { practice = it },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            textStyle = TextStyle(color = ink, fontFamily = inter, fontSize = 16.sp),
            cursorBrush = SolidColor(ink),
            decorationBox = { field ->
                Box {
                    if (practice.isEmpty()) Label(stringResource(R.string.bubble_test_field), muted = true, lines = 2)
                    field()
                }
            }
        )
    }
}

@Composable
internal fun SoundSetsPage(bubble: BubblePreferences, revision: Int, done: () -> Unit) {
    val context = LocalContext.current
    val current = remember(revision) { bubble.soundSet }
    Card {
        BubbleSounds.SoundSet.entries.forEachIndexed { index, set ->
            if (index != 0) Rule()
            Entry(
                title = stringResource(set.labelRes),
                note = stringResource(set.detailRes),
                value = if (set == current) "✓" else "",
                action = {
                    bubble.soundSet = set
                    BubbleSounds.play(context, set, BubbleSounds.Cue.Start)
                    done()
                }
            )
        }
    }
}

@Composable
internal fun RecentDictationsPage(revision: Int) {
    val context = LocalContext.current
    var clearCount by remember { mutableIntStateOf(0) }
    val items = remember(revision, clearCount) { DictationHistory.items(context) }
    var copiedIndex by remember { mutableIntStateOf(-1) }
    if (items.isEmpty()) {
        Card {
            Entry(stringResource(R.string.recent_empty))
        }
        return
    }
    Card {
        items.forEachIndexed { i, entry ->
            if (i != 0) Rule()
            val value = if (copiedIndex == i && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                stringResource(R.string.home_copied)
            } else {
                stringResource(R.string.home_copy)
            }
            Entry(
                title = entry.text,
                value = value,
                action = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), entry.text))
                    copiedIndex = i
                }
            )
        }
    }
    Card {
        Entry(stringResource(R.string.recent_clear), value = "") {
            DictationHistory.clear(context)
            clearCount++
        }
    }
}

@Composable
private fun ShapeSelectorRow(selected: BubbleShape, onSelect: (BubbleShape) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(stringResource(R.string.bubble_shape), weight = FontWeight.Medium)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colorResource(R.color.page))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            BubbleShape.entries.forEach { shape ->
                val active = shape == selected
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorResource(if (active) R.color.ink else R.color.page))
                        .clickable(role = Role.RadioButton) { onSelect(shape) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(shape.iconRes),
                        contentDescription = stringResource(shape.labelRes),
                        modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(colorResource(if (active) R.color.page else R.color.ink))
                    )
                }
            }
        }
    }
}

@Composable
internal fun <T> SegmentedSetting(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    note: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(title, weight = FontWeight.Medium)
        if (note != null) Label(note, size = 13, muted = true, lines = 2)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colorResource(R.color.page))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (option, label) ->
                val active = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorResource(if (active) R.color.ink else R.color.page))
                        .clickable(role = Role.RadioButton) { onSelect(option) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Label(label, size = 13, weight = FontWeight.Medium, inverted = active)
                }
            }
        }
    }
}

internal fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
