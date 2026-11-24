package ly.anoma.hex.bubble

import android.text.InputType
import ly.anoma.hex.Setup
import ly.anoma.hex.keyboard.DictationContinuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleInserterTest {
    @Test fun midSentenceContinuationLowercasesEnglishCommonWordsAndKeepsGermanNounCapitals() {
        val cont = DictationContinuation()
        val en = BubbleInserter.prepareInsertion(
            formattedTranscript = "Thanks for checking.",
            language = "en",
            ownApp = false,
            signatureEnabled = false,
            signatureText = "Sent from Hex",
            beforeCursor = "Sounds great, ",
            afterCursor = null,
            continuationState = cont,
        )
        assertEquals("thanks for checking.", en.inserted)

        val de = BubbleInserter.prepareInsertion(
            formattedTranscript = "Danke für die Nachricht.",
            language = "de",
            ownApp = false,
            signatureEnabled = false,
            signatureText = "Sent from Hex",
            beforeCursor = "Alles klar, ",
            afterCursor = null,
            continuationState = cont,
        )
        assertEquals("Danke für die Nachricht.", de.inserted)
    }

    @Test fun addsMissingSpacesAroundCursorWithoutDoublingBesidePunctuation() {
        val cont = DictationContinuation()
        val middle = BubbleInserter.prepareInsertion(
            formattedTranscript = "Wait here",
            language = "en",
            ownApp = false,
            signatureEnabled = false,
            signatureText = "",
            beforeCursor = "Please",
            afterCursor = 'n',
            continuationState = cont,
            isWord = { it == "wait" },
        )
        assertEquals(" wait here ", middle.inserted)

        val properName = BubbleInserter.prepareInsertion(
            formattedTranscript = "Sarah called",
            language = "en",
            ownApp = false,
            signatureEnabled = false,
            signatureText = "",
            beforeCursor = "And then",
            afterCursor = '.',
            continuationState = cont,
            isWord = { it == "wait" },
        )
        assertEquals(" Sarah called", properName.inserted)

        val beforeComma = BubbleInserter.prepareInsertion(
            formattedTranscript = "yes",
            language = "en",
            ownApp = false,
            signatureEnabled = false,
            signatureText = "",
            beforeCursor = "(",
            afterCursor = ',',
            continuationState = cont,
        )
        assertEquals("yes", beforeComma.inserted)
    }

    @Test fun signatureAppendsInOtherAppsSkipsHexFieldsAndStartsNextDictationOnNewLine() {
        val cont = DictationContinuation()
        val own = BubbleInserter.prepareInsertion(
            formattedTranscript = "Practice text.",
            language = "en",
            ownApp = true,
            signatureEnabled = true,
            signatureText = "Sent from Hex",
            beforeCursor = "",
            afterCursor = null,
            continuationState = cont,
        )
        assertFalse(own.signed)
        assertEquals("Practice text.", own.inserted)

        val first = BubbleInserter.prepareInsertion(
            formattedTranscript = "On my way.",
            language = "en",
            ownApp = false,
            signatureEnabled = true,
            signatureText = "Sent from Hex",
            beforeCursor = "",
            afterCursor = null,
            continuationState = cont,
        )
        assertTrue(first.signed)
        assertEquals("On my way.\nSent from Hex", first.inserted)
        cont.record(first.inserted)

        val second = BubbleInserter.prepareInsertion(
            formattedTranscript = "See you soon.",
            language = "en",
            ownApp = false,
            signatureEnabled = false,
            signatureText = "Sent from Hex",
            beforeCursor = first.inserted,
            afterCursor = null,
            continuationState = cont,
        )
        assertEquals("\nSee you soon.", second.inserted)
    }

    @Test fun passwordEditorsAreDetectedAcrossTextAndNumberInputTypes() {
        assertTrue(BubbleInserter.isPasswordEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(BubbleInserter.isPasswordEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(BubbleInserter.isPasswordEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(BubbleInserter.isPasswordEditor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(BubbleInserter.isPasswordEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(BubbleInserter.isPasswordEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
    }

    @Test fun bubbleModeSetupReachesReadyWithoutSwitchingAwayFromCurrentKeyboard() {
        val swiftKeyBubbleSetup = Setup(
            mic = true,
            accessibility = true,
            enabled = false,
            chosen = false,
            mode = InputMode.Bubble,
        )
        assertTrue(swiftKeyBubbleSetup.ready)
        assertEquals(0, swiftKeyBubbleSetup.missingSteps)

        val keyboardSetup = Setup(
            mic = true,
            accessibility = true,
            enabled = true,
            chosen = false,
            mode = InputMode.Keyboard,
        )
        assertFalse(keyboardSetup.ready)
        assertEquals(1, keyboardSetup.missingSteps)
    }
}
