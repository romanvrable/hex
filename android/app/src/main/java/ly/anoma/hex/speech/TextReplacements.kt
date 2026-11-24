package ly.anoma.hex.speech

/** Phrase-boundary-aware word and phrase replacements (`heard phrase => output`), matching desktop Hex's
 * `src/dictation/text_replacements.rs`: longest match wins, custom vocabulary terms stay protected, and
 * empty outputs clean up surrounding spaces, commas and sentence-start capitals. */
internal object TextReplacements {
    data class Rule(val phrase: String, val output: String)

    private class Compiled(val regex: Regex, val output: String, val protected: Boolean)
    private data class Candidate(val start: Int, val end: Int, val rule: Int)

    private val unspacedScript = Regex(
        "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}\\p{IsTibetan}]"
    )
    private val horizontalWhitespace = charArrayOf(' ', '\t')
    private val clausePunctuation = charArrayOf('.', '!', '?', ',', ';', ':')
    private val sentenceEndings = charArrayOf('.', '!', '?', '\n')

    fun parse(raw: String): List<Rule> = raw.lines().mapNotNull { line ->
        val at = line.indexOf("=>")
        if (at < 0) return@mapNotNull null
        val phrase = line.substring(0, at).trim()
        if (phrase.isEmpty()) null else Rule(phrase, line.substring(at + 2).trim())
    }

    fun serialize(rules: List<Rule>): String =
        rules.filter { it.phrase.isNotBlank() }.joinToString("\n") { "${it.phrase.trim()} => ${it.output.trim()}" }

    fun apply(rules: List<Rule>, text: String, protectedTerms: List<String> = emptyList()): String {
        if (rules.isEmpty() || text.isEmpty()) return text
        val compiled = buildList {
            for (term in protectedTerms) compile(term, "", protected = true)?.let(::add)
            for (rule in rules) compile(rule.phrase, rule.output, protected = false)?.let(::add)
        }
        if (compiled.isEmpty()) return text

        val candidates = ArrayList<Candidate>()
        for ((index, rule) in compiled.withIndex()) {
            for ((start, end) in phraseMatches(rule.regex, text)) {
                candidates += Candidate(start, end, index)
            }
        }
        if (candidates.isEmpty()) return text

        candidates.sortWith(
            compareByDescending<Candidate> { it.end - it.start }
                .thenBy { it.start }
                .thenBy { it.rule }
        )
        val selected = ArrayList<Candidate>()
        for (candidate in candidates) {
            if (selected.any { candidate.start < it.end && it.start < candidate.end }) continue
            selected += candidate
        }
        if (selected.all { compiled[it.rule].protected }) return text
        selected.sortBy { it.start }

        val output = StringBuilder(text.length)
        var cursor = 0
        var capitalizeNext = false
        for ((index, candidate) in selected.withIndex()) {
            if (cursor < candidate.start) {
                capitalizeNext = pushSegment(output, text.substring(cursor, candidate.start), capitalizeNext)
            }
            val rule = compiled[candidate.rule]
            if (rule.protected) {
                output.append(text, candidate.start, candidate.end)
                capitalizeNext = false
                cursor = candidate.end
                continue
            }
            if (rule.output.isNotEmpty()) {
                capitalizeNext = pushSegment(output, rule.output, capitalizeNext)
                cursor = candidate.end
                continue
            }
            val nextLimit = selected.getOrNull(index + 1)?.start ?: text.length
            val between = text.substring(candidate.end, nextLimit)
            if (atSentenceStart(output)) {
                val matched = text.substring(candidate.start, candidate.end)
                if (matched.firstOrNull(Char::isLetter)?.isUpperCase() == true) {
                    capitalizeNext = true
                }
                val afterPunct = between.trimStart(*clausePunctuation)
                val afterSpaces = afterPunct.trimStart(*horizontalWhitespace)
                cursor = candidate.end + (between.length - afterSpaces.length)
                if ((afterSpaces.isEmpty() && nextLimit == text.length) || afterSpaces.startsWith('\n')) {
                    trimTrailingHorizontalSpaces(output)
                }
            } else {
                val remaining = text.substring(candidate.end).trimStart(*horizontalWhitespace)
                if (between.firstOrNull()?.let { it in clausePunctuation } == true ||
                    remaining.isEmpty() || remaining.startsWith('\n')
                ) {
                    trimTrailingHorizontalSpaces(output)
                    if (output.endsWith(',')) output.deleteCharAt(output.length - 1)
                    if (remaining.isEmpty() || remaining.startsWith('\n')) {
                        val afterSpaces = between.trimStart(*horizontalWhitespace)
                        cursor = candidate.end + (between.length - afterSpaces.length)
                    } else {
                        cursor = candidate.end
                    }
                } else if (output.lastOrNull()?.let { it in horizontalWhitespace } == true) {
                    val afterSpaces = between.trimStart(*horizontalWhitespace)
                    cursor = candidate.end + (between.length - afterSpaces.length)
                } else {
                    cursor = candidate.end
                }
            }
        }
        if (cursor < text.length) {
            pushSegment(output, text.substring(cursor), capitalizeNext)
        }
        return output.toString()
    }

    private fun compile(phrase: String, output: String, protected: Boolean): Compiled? {
        val clean = phrase.trim()
        if (clean.isEmpty()) return null
        val regex = runCatching { Regex(Regex.escape(clean), setOf(RegexOption.IGNORE_CASE)) }.getOrNull() ?: return null
        return Compiled(regex, output, protected)
    }

    private fun atSentenceStart(output: CharSequence): Boolean {
        var i = output.length - 1
        while (i >= 0 && output[i] in horizontalWhitespace) i--
        return i < 0 || output[i] in sentenceEndings
    }

    private fun trimTrailingHorizontalSpaces(output: StringBuilder) {
        while (output.isNotEmpty() && output[output.length - 1] in horizontalWhitespace) {
            output.deleteCharAt(output.length - 1)
        }
    }

    private fun pushSegment(output: StringBuilder, segment: String, capitalizeNext: Boolean): Boolean {
        if (!capitalizeNext || segment.isEmpty()) {
            output.append(segment)
            return if (segment.any { !it.isWhitespace() }) false else capitalizeNext
        }
        val index = segment.indexOfFirst { !it.isWhitespace() }
        if (index < 0) {
            output.append(segment)
            return true
        }
        output.append(segment, 0, index)
        val cp = segment.codePointAt(index)
        val count = Character.charCount(cp)
        val ch = segment.substring(index, index + count)
        if (Character.isLowerCase(cp) && atSentenceStart(output)) {
            output.append(ch.uppercase())
        } else {
            output.append(ch)
        }
        output.append(segment, index + count, segment.length)
        return false
    }

    private fun phraseMatches(regex: Regex, text: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var from = 0
        while (from <= text.length) {
            val match = regex.find(text, from) ?: break
            val start = match.range.first
            val end = match.range.last + 1
            if (phraseBoundary(text, start, end)) {
                result += start to end
                from = maxOf(end, start + 1)
            } else {
                from = start + (if (start < text.length) Character.charCount(text.codePointAt(start)) else 1)
            }
        }
        return result
    }

    private fun phraseBoundary(text: String, start: Int, end: Int): Boolean {
        val before = if (start > 0) Character.codePointBefore(text, start) else -1
        val after = if (end < text.length) Character.codePointAt(text, end) else -1
        return !isWordCodePoint(before) && !isWordCodePoint(after)
    }

    private fun isWordCodePoint(cp: Int): Boolean {
        if (cp < 0) return false
        if (!(Character.isLetterOrDigit(cp) || cp == '_'.code)) return false
        val str = String(Character.toChars(cp))
        return !unspacedScript.matches(str)
    }
}
