package com.readerapp.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsModelsTest {
    @Test
    fun registryHasThreeModelsWithUniqueIdsTagsAndDirs() {
        assertEquals(3, TtsModels.ALL.size)
        assertEquals(3, TtsModels.ALL.map { it.id }.toSet().size)
        assertEquals(3, TtsModels.ALL.map { it.tag }.toSet().size)
        assertEquals(3, TtsModels.ALL.map { it.spec.dirName }.toSet().size)
    }

    @Test
    fun defaultModelKeepsTheDirectoryOfTheInstalledCopy() {
        assertEquals("kokoro-en-v0_19", TtsModels.DEFAULT_ID)
        assertEquals("kokoro-en-v0_19", TtsModels.DEFAULT.spec.dirName)
        assertEquals(11, TtsModels.DEFAULT.voices.size)
        assertEquals(1, TtsModels.DEFAULT.defaultVoice)
    }

    @Test
    fun kokoroV1HasTheEnglishVoicesInOrder() {
        val m = TtsModels.byId("kokoro-multi-lang-v1_0")
        assertEquals(28, m.voices.size)
        assertEquals((0..27).toList(), m.voices.map { it.id })
        assertEquals("Heart (US female)", m.voiceLabel(m.defaultVoice))
        assertEquals("Alloy (US female)", m.voiceLabel(0))
        assertEquals("Adam (US male)", m.voiceLabel(11))
        assertEquals("Alice (UK female)", m.voiceLabel(20))
        assertEquals("Lewis (UK male)", m.voiceLabel(27))
        assertEquals("en-us", m.lang)
        assertEquals(listOf("lexicon-us-en.txt", "lexicon-zh.txt"), m.lexiconFiles)
    }

    @Test
    fun supertonicVoices() {
        val m = TtsModels.byId("supertonic-int8")
        assertEquals(EngineKind.SUPERTONIC, m.engine)
        assertEquals(10, m.voices.size)
        assertEquals("Female 1", m.voiceLabel(0))
        assertEquals("Female 5", m.voiceLabel(4))
        assertEquals("Male 1", m.voiceLabel(5))
        assertEquals("Male 5", m.voiceLabel(9))
        assertEquals(0, m.defaultVoice)
    }

    @Test
    fun voicesAreValidatedAgainstTheModelAndUnknownIdsFallBack() {
        val st = TtsModels.byId("supertonic-int8")
        assertEquals(7, st.validVoice(7))
        assertEquals(0, st.validVoice(10))
        assertEquals(0, st.validVoice(-1))
        assertEquals(TtsModels.DEFAULT, TtsModels.byId("nope"))
        assertEquals(TtsModels.DEFAULT, TtsModels.byId(null))
        assertTrue(TtsModels.ALL.all { it.validVoice(it.defaultVoice) == it.defaultVoice })
    }
}
