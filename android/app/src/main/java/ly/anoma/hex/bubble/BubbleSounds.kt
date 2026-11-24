package ly.anoma.hex.bubble

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import ly.anoma.hex.R

/** Desktop Hex's seven feedback sound sets (`resources/audio/sounds/`), off unless turned on in Bubble settings.
 * Uses direct `R.raw.*` references so R8 resource shrinking keeps every audio cue automatically. */
internal object BubbleSounds {
    enum class SoundSet(val id: String, val labelRes: Int, val detailRes: Int) {
        Signature("signature", R.string.sound_signature, R.string.sound_signature_detail),
        Classic("classic", R.string.sound_classic, R.string.sound_classic_detail),
        Click("click", R.string.sound_click, R.string.sound_click_detail),
        Soft("soft", R.string.sound_soft, R.string.sound_soft_detail),
        Wood("wood", R.string.sound_wood, R.string.sound_wood_detail),
        Analog("analog", R.string.sound_analog, R.string.sound_analog_detail),
        Thock("thock", R.string.sound_thock, R.string.sound_thock_detail);

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Signature
        }
    }

    enum class Cue { Start, Stop, Cancel, Error, Done }

    private const val VOLUME = 0.5f
    private var pool: SoundPool? = null
    private val loaded = HashMap<Int, Int>()
    private val ready = HashSet<Int>()
    private val pending = HashSet<Int>()

    @Synchronized
    fun playIfEnabled(context: Context, cue: Cue) {
        val prefs = BubblePreferences(context)
        if (!prefs.sounds) return
        play(context, prefs.soundSet, cue)
    }

    @Synchronized
    fun play(context: Context, set: SoundSet, cue: Cue) {
        val resId = resource(set, cue) ?: return
        val soundPool = pool ?: SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { created ->
                pool = created
                created.setOnLoadCompleteListener { sp, sampleId, status ->
                    synchronized(this@BubbleSounds) {
                        if (status == 0) {
                            ready += sampleId
                            if (pending.remove(sampleId)) sp.play(sampleId, VOLUME, VOLUME, 1, 0, 1f)
                        } else {
                            pending.remove(sampleId)
                        }
                    }
                }
            }
        val sampleId = loaded.getOrPut(resId) { soundPool.load(context.applicationContext, resId, 1) }
        if (sampleId in ready) soundPool.play(sampleId, VOLUME, VOLUME, 1, 0, 1f) else pending += sampleId
    }

    private fun resource(set: SoundSet, cue: Cue): Int? = when (set) {
        SoundSet.Signature -> when (cue) {
            Cue.Start -> R.raw.sound_signature_start
            Cue.Stop -> R.raw.sound_signature_stop
            Cue.Cancel -> R.raw.sound_signature_cancel
            Cue.Error -> R.raw.sound_signature_error
            Cue.Done -> R.raw.sound_signature_done
        }
        SoundSet.Classic -> when (cue) {
            Cue.Start -> R.raw.sound_classic_start
            Cue.Stop -> R.raw.sound_classic_stop
            Cue.Cancel -> R.raw.sound_classic_cancel
            Cue.Error -> R.raw.sound_signature_error
            Cue.Done -> null
        }
        SoundSet.Click -> when (cue) {
            Cue.Start -> R.raw.sound_click_start
            Cue.Stop -> R.raw.sound_click_stop
            Cue.Cancel -> R.raw.sound_click_cancel
            Cue.Error -> R.raw.sound_click_error
            Cue.Done -> R.raw.sound_click_done
        }
        SoundSet.Soft -> when (cue) {
            Cue.Start -> R.raw.sound_soft_start
            Cue.Stop -> R.raw.sound_soft_stop
            Cue.Cancel -> R.raw.sound_soft_cancel
            Cue.Error -> R.raw.sound_soft_error
            Cue.Done -> R.raw.sound_soft_done
        }
        SoundSet.Wood -> when (cue) {
            Cue.Start -> R.raw.sound_wood_start
            Cue.Stop -> R.raw.sound_wood_stop
            Cue.Cancel -> R.raw.sound_wood_cancel
            Cue.Error -> R.raw.sound_wood_error
            Cue.Done -> R.raw.sound_wood_done
        }
        SoundSet.Analog -> when (cue) {
            Cue.Start -> R.raw.sound_analog_start
            Cue.Stop -> R.raw.sound_analog_stop
            Cue.Cancel -> R.raw.sound_analog_cancel
            Cue.Error -> R.raw.sound_analog_error
            Cue.Done -> R.raw.sound_analog_done
        }
        SoundSet.Thock -> when (cue) {
            Cue.Start -> R.raw.sound_thock_start
            Cue.Stop -> R.raw.sound_thock_stop
            Cue.Cancel -> R.raw.sound_thock_cancel
            Cue.Error -> R.raw.sound_thock_error
            Cue.Done -> null
        }
    }
}
