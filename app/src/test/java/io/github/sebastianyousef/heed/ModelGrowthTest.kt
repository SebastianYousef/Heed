package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.score.Features
import io.github.sebastianyousef.heed.score.OnlineClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What happens to everything the model has learned when a feature is added.
 *
 * Adding the sender block grew the feature vector, and the previous loader threw the
 * whole weight array away whenever the length changed. That would have quietly wiped
 * months of a user's training on an ordinary upgrade — the one kind of data loss this
 * project has been careful about everywhere else.
 */
class ModelGrowthTest {

    /** A serialized model from "before", with a smaller layout. */
    private fun oldModel(dim: Int, fill: Float): ByteArray {
        val buf = ByteBuffer.allocate(4 + dim * 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dim)
        repeat(dim) { buf.putFloat(fill) }
        repeat(dim) { buf.putFloat(0.5f) }
        return buf.array()
    }

    @Test
    fun `a shorter stored vector is grown, not discarded`() {
        val classifier = OnlineClassifier(dim = 100)
        classifier.load(oldModel(60, 0.25f), bias = 0.3f, examplesSeen = 42)

        assertEquals(42, classifier.examplesSeen)
        assertEquals(0.3f, classifier.bias, 1e-6f)
        // Everything it had learned is still there, at the same indices.
        for (i in 0 until 60) assertEquals(0.25f, classifier.weights[i], 1e-6f)
        // And the new block starts neutral.
        for (i in 60 until 100) assertEquals(0f, classifier.weights[i], 1e-6f)
    }

    @Test
    fun `a longer stored vector is refused rather than truncated`() {
        val classifier = OnlineClassifier(dim = 50)
        classifier.load(oldModel(100, 0.25f), bias = 0.9f, examplesSeen = 7)
        // Truncating would keep weights whose meaning we cannot vouch for.
        assertEquals(0, classifier.examplesSeen)
        assertTrue(classifier.weights.all { it == 0f })
    }

    @Test
    fun `a grown model still scores the features it knew`() {
        val old = OnlineClassifier(dim = 60)
        repeat(30) {
            old.train(Features(intArrayOf(3, 17), floatArrayOf(1f, 1f)), label = 1f, weight = 1f)
        }
        val trained = old.predict(Features(intArrayOf(3, 17), floatArrayOf(1f, 1f)))
        assertTrue("should have learned a positive", trained > 0.6f)

        val grown = OnlineClassifier(dim = 100)
        grown.load(old.serialize(), old.bias, old.examplesSeen)
        assertEquals(trained, grown.predict(Features(intArrayOf(3, 17), floatArrayOf(1f, 1f))), 1e-5f)
        assertNotEquals(0f, grown.weights[3])
    }
}
