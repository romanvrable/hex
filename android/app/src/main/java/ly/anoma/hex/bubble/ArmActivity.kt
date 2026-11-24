package ly.anoma.hex.bubble

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import ly.anoma.hex.MainActivity

/** Translucent trampoline activity: Android 14+ allows starting a `microphone` foreground service only
 * from a foreground context. If Android stopped [BubbleDictationService] in the background, tapping the
 * bubble briefly resumes this invisible activity to re-arm the service and start dictation seamlessly. */
class ArmActivity : Activity() {
    override fun onResume() {
        super.onResume()
        val autoDictate = intent?.getBooleanExtra(EXTRA_START_DICTATION, false) == true
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            val started = BubbleDictationService.start(this)
            if (started && autoDictate) {
                Handler(Looper.getMainLooper()).postDelayed({
                    HexAccessibilityService.instance?.beginBubbleSpeech(fromArm = true)
                }, 120L)
            } else if (!started) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_START_DICTATION = "ly.anoma.hex.extra.START_DICTATION"
    }
}
