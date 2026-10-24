package com.acousticguard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acousticguard.ml.MotionSnapshot
import com.acousticguard.ml.YamnetDistressClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YamnetDistressClassifierInstrumentedTest {
    @Test
    fun bundledModelLoadsAndClassifiesAQuietWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YamnetDistressClassifier(context).use { classifier ->
            assertTrue(classifier.requiredInputBufferSize > 0)
            val inference = classifier.infer(
                samples = ShortArray(classifier.requiredInputBufferSize),
                motion = MotionSnapshot(),
            )

            assertEquals("yamnet-audioset-1", inference.modelVersion)
            assertTrue(inference.screamProbability in 0f..1f)
            assertTrue(inference.respiratoryProbability in 0f..1f)
            assertTrue(inference.impactProbability in 0f..1f)
        }
    }
}
