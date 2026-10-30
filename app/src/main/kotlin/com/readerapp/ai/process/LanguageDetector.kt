package com.readerapp.ai.process

/** Coarse source-language classes the script heuristic can tell apart. */
enum class Script { CHINESE, JAPANESE, KOREAN, LATIN, UNKNOWN }

data class SourceLanguage(
    /** English name used in prompts, e.g. "Chinese". For unlabelled Latin script this is "the source language". */
    val name: String,
    val script: Script,
    /** True when [name] came from the novel's own language field rather than the script heuristic. */
    val declared: Boolean,
)

object LanguageDetector {
    private val NAMES = mapOf(
        "en" to "English", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean", "es" to "Spanish",
        "fr" to "French", "de" to "German", "ru" to "Russian", "pt" to "Portuguese", "it" to "Italian",
        "id" to "Indonesian", "th" to "Thai", "vi" to "Vietnamese", "tr" to "Turkish", "pl" to "Polish", "ar" to "Arabic",
    )

    /** "zh-CN", "ZH", "chinese" ... -> "Chinese"; unknown labels are returned trimmed. */
    fun languageName(label: String): String {
        val l = label.trim()
        NAMES[l.lowercase().substringBefore('-').substringBefore('_')]?.let { return it }
        return NAMES.values.firstOrNull { it.equals(l, ignoreCase = true) } ?: l
    }

    fun scriptOf(name: String): Script = when (name.lowercase()) {
        "chinese" -> Script.CHINESE
        "japanese" -> Script.JAPANESE
        "korean" -> Script.KOREAN
        "" -> Script.UNKNOWN
        else -> Script.LATIN
    }

    /** Script heuristic over (a sample of) the text: kana => Japanese, Hangul => Korean, Han only => Chinese, else Latin. */
    fun detectScript(text: String): Script {
        var han = 0; var kana = 0; var hangul = 0; var latin = 0
        for (ch in text.take(SAMPLE_CHARS)) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.LATIN -> if (ch.isLetter()) latin++
                else -> Unit
            }
        }
        val total = han + kana + hangul + latin
        if (total < MIN_LETTERS) return Script.UNKNOWN
        return when {
            kana >= total * 0.05 -> Script.JAPANESE
            hangul >= total * 0.2 -> Script.KOREAN
            han >= total * 0.2 -> Script.CHINESE
            latin >= total * 0.5 -> Script.LATIN
            else -> Script.UNKNOWN
        }
    }

    /** [novelLanguage] (if set) wins over the heuristic. */
    fun detect(novelLanguage: String?, paragraphs: List<String>): SourceLanguage {
        novelLanguage?.takeIf { it.isNotBlank() }?.let {
            val name = languageName(it)
            return SourceLanguage(name, scriptOf(name), declared = true)
        }
        return when (val script = detectScript(paragraphs.joinToString("\n"))) {
            Script.CHINESE -> SourceLanguage("Chinese", script, false)
            Script.JAPANESE -> SourceLanguage("Japanese", script, false)
            Script.KOREAN -> SourceLanguage("Korean", script, false)
            Script.LATIN -> SourceLanguage("the source language", script, false)
            Script.UNKNOWN -> SourceLanguage("the source language", script, false)
        }
    }

    /**
     * Whether translating into [target] makes sense. Undeclared Latin-script text cannot be told apart from the
     * target (it is most likely already English), so it is only translated when the target is not English.
     */
    fun needsTranslation(source: SourceLanguage, target: String): Boolean {
        val t = languageName(target)
        if (source.declared) return !source.name.equals(t, ignoreCase = true)
        return when (source.script) {
            Script.CHINESE -> !t.equals("Chinese", true)
            Script.JAPANESE -> !t.equals("Japanese", true)
            Script.KOREAN -> !t.equals("Korean", true)
            Script.LATIN -> !t.equals("English", true)
            Script.UNKNOWN -> false
        }
    }

    private const val SAMPLE_CHARS = 4_000
    private const val MIN_LETTERS = 20
}
