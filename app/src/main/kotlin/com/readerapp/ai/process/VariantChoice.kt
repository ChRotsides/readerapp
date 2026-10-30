package com.readerapp.ai.process

import com.readerapp.data.db.AutoProcessMode
import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.TextVariant

/** Which text variant the reader shows. */
object VariantChoice {
    /**
     * The user's [selected] variant if it exists; otherwise the novel's preferred variant if it exists
     * (TRANSLATE -> translated, FIX -> fixed); otherwise the original.
     */
    fun pick(selected: TextVariant?, available: Set<TextVariant>, mode: AutoProcessMode): TextVariant {
        if (selected != null && selected in available) return selected
        if (selected == null) {
            val preferred = when (mode) {
                AutoProcessMode.TRANSLATE -> TextVariant.TRANSLATED
                AutoProcessMode.FIX -> TextVariant.FIXED
                AutoProcessMode.OFF -> TextVariant.ORIGINAL
            }
            if (preferred in available) return preferred
        }
        return TextVariant.ORIGINAL
    }

    /** Variant order for background work that wants the best text (summaries): translated > fixed > original. */
    fun best(texts: List<ChapterText>): ChapterText? =
        listOf(TextVariant.TRANSLATED, TextVariant.FIXED, TextVariant.ORIGINAL).firstNotNullOfOrNull { v -> texts.firstOrNull { it.variant == v } }
}
