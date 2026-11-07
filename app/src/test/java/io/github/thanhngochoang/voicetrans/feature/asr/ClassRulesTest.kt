package io.github.thanhngochoang.voicetrans.feature.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassRulesTest {
    @Test fun speechOverMusic_isSpeech() =
        assertEquals(AudioClass.SPEECH, classify(mapOf("Speech" to 0.6f, "Music" to 0.7f)))

    @Test fun singing_beatsSpeech() =
        assertEquals(AudioClass.SINGING, classify(mapOf("Singing" to 0.55f, "Speech" to 0.42f, "Music" to 0.8f)))

    @Test fun pureMusic() =
        assertEquals(AudioClass.MUSIC, classify(mapOf("Music" to 0.9f, "Speech" to 0.05f)))

    @Test fun quiet_isOther() = assertEquals(AudioClass.OTHER, classify(emptyMap()))

    @Test fun childLabelCounts() =
        assertEquals(AudioClass.SPEECH, classify(mapOf("Female speech, woman speaking" to 0.5f)))

    @Test fun quietSpeechOverLoudMusic_isSpeech() =
        assertEquals(AudioClass.SPEECH, classify(mapOf("Speech" to 0.25f, "Music" to 0.7f)))

    @Test fun confidentSpeech_neverBecomesSinging() =
        assertEquals(AudioClass.SPEECH, classify(mapOf("Speech" to 0.55f, "Singing" to 0.8f)))

    @Test fun quietSinging_overMusic_isSinging() =
        assertEquals(AudioClass.SINGING, classify(mapOf("Opera" to 0.2f, "Music" to 0.7f)))

    @Test fun mantraIsNotSinging_mandarinSpeechIsMisheardAsChant() =
        assertEquals(AudioClass.OTHER, classify(mapOf("Mantra" to 0.5f, "Music" to 0.1f)))
}
