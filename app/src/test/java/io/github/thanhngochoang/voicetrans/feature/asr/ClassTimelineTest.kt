package io.github.thanhngochoang.voicetrans.feature.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassTimelineTest {
    @Test fun majority_ofWindowsInRange() {
        val t = ClassTimeline()
        t.add(0, AudioClass.MUSIC); t.add(1000, AudioClass.MUSIC); t.add(2000, AudioClass.SPEECH)
        assertEquals(AudioClass.MUSIC, t.majority(0, 3000))
        assertEquals(AudioClass.SPEECH, t.majority(2000, 3000))
    }

    @Test fun tie_prefersSpeech() {
        val t = ClassTimeline()
        t.add(0, AudioClass.MUSIC); t.add(1000, AudioClass.SPEECH)
        assertEquals(AudioClass.SPEECH, t.majority(0, 2000))
    }

    @Test fun empty_isOther() = assertEquals(AudioClass.OTHER, ClassTimeline().majority(0, 1000))

    @Test fun keepsOnlyLast60s() {
        val t = ClassTimeline()
        t.add(0, AudioClass.MUSIC)
        t.add(70_000, AudioClass.SPEECH)
        assertEquals(AudioClass.OTHER, t.majority(0, 1000))
    }
}
