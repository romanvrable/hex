package ly.anoma.hex.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplacementsTest {
    @Test fun wholePhraseBoundariesNeverRewriteSubstringsInsideWords() {
        val rules = TextReplacements.parse("cat => dog\nat => in")
        assertEquals(
            "The dog sat in the categories.",
            TextReplacements.apply(rules, "The cat sat at the categories.")
        )
    }

    @Test fun longestMatchWinsWhenRulesOverlap() {
        val rules = TextReplacements.parse(
            """
            open code => OC
            open code app => OpenCode Studio
            """.trimIndent()
        )
        assertEquals(
            "Launch OpenCode Studio and OC.",
            TextReplacements.apply(rules, "Launch open code app and open code.")
        )
    }

    @Test fun customVocabularyTermsAreProtectedFromShorterReplacementRules() {
        val rules = TextReplacements.parse("hex => hexagon")
        assertEquals(
            "Hex Cloud uses hexagon shapes.",
            TextReplacements.apply(rules, "Hex Cloud uses hex shapes.", protectedTerms = listOf("Hex Cloud"))
        )
    }

    @Test fun emptyOutputDeletesPhraseAndCleansSurroundingPunctuationAndSentenceCase() {
        val rules = TextReplacements.parse("actually => ")
        assertEquals("We shipped today.", TextReplacements.apply(rules, "Actually, we shipped today."))
        assertEquals("We shipped.", TextReplacements.apply(rules, "We shipped, actually."))
    }

    @Test fun spokenTextFormatAppliesReplacementsAndGermanNotes() {
        val options = SpeechOptions(
            style = Style.Notes,
            spoken = DictationLanguage.German,
            clean = true,
            translate = false,
            replacements = TextReplacements.parse("kaffee => Kaffeebohnen"),
        )
        val formatted = SpokenText.format("Ähm Stichpunkt kaffee kaufen Stichpunkt Milch holen", options)
        assertEquals("- Kaffeebohnen kaufen\n- Milch holen", formatted)
    }

    @Test fun multilingualTranslationSupportsGermanSpanishFrenchAndBlocksEnglish() {
        assertTrue(DictationLanguage.canTranslateToEnglish(DictationLanguage.German))
        assertTrue(DictationLanguage.canTranslateToEnglish(DictationLanguage.Spanish))
        assertTrue(DictationLanguage.canTranslateToEnglish(DictationLanguage.French))
        assertFalse(DictationLanguage.canTranslateToEnglish(DictationLanguage.English))
    }
}
