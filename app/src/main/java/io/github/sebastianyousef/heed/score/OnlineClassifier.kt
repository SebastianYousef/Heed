package io.github.sebastianyousef.heed.score

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Sparse logistic regression trained online with AdaGrad.
 *
 * Why this and not something bigger: the training set here is whatever the user has
 * reacted to, which on day one is nothing and after a month is maybe a few hundred
 * examples. In that regime a linear model on good features beats a small neural net,
 * trains in microseconds per example, and — because each weight maps to one hashed
 * token or one named structured slot — can explain itself in the UI.
 *
 * AdaGrad rather than plain SGD because the features are sparse and wildly unbalanced
 * in frequency: a per-feature learning rate lets a token seen twice still move the
 * needle while a token seen 500 times stops thrashing.
 */
class OnlineClassifier(
    private val dim: Int = FeatureExtractor.DIM,
    private val learningRate: Float = 0.35f,
    private val l2: Float = 1e-6f,
) {
    var weights = FloatArray(dim); private set
    private var accumulator = FloatArray(dim)
    var bias = 0f; private set
    private var biasAccumulator = 0f
    var examplesSeen = 0; private set

    /** Probability that this notification matters to the user, 0..1. */
    fun predict(f: Features): Float {
        var z = bias
        for (i in 0 until f.size) z += weights[f.indices[i]] * f.values[i]
        return sigmoid(z)
    }

    /**
     * One gradient step. [label] is 1 for "this mattered", 0 for "this was noise".
     * [weight] lets an explicit thumbs-up count for more than a passive swipe.
     */
    fun train(f: Features, label: Float, weight: Float = 1f) {
        val error = (predict(f) - label) * weight
        for (i in 0 until f.size) {
            val j = f.indices[i]
            val g = error * f.values[i] + l2 * weights[j]
            accumulator[j] += g * g
            weights[j] -= learningRate * g / (sqrt(accumulator[j]) + 1e-8f)
        }
        biasAccumulator += error * error
        bias -= learningRate * error / (sqrt(biasAccumulator) + 1e-8f)
        examplesSeen++
    }

    /**
     * How far to trust this model over the hand-written rules, 0..1. Starts at zero so a
     * fresh install behaves purely on rules, and saturates around a few hundred examples.
     */
    fun confidence(): Float {
        val k = 60f
        return examplesSeen / (examplesSeen + k)
    }

    /**
     * The structured slots that pushed hardest on this decision, for the "why" line in
     * the UI. Text buckets are omitted — a hashed n-gram has no readable name.
     */
    fun topStructuredContributions(f: Features, take: Int = 3): List<Pair<Int, Float>> =
        (0 until f.size)
            .filter { f.indices[it] >= FeatureExtractor.TEXT_DIM + FeatureExtractor.APP_DIM }
            .map { (f.indices[it] - FeatureExtractor.TEXT_DIM - FeatureExtractor.APP_DIM) to weights[f.indices[it]] * f.values[it] }
            .sortedByDescending { kotlin.math.abs(it.second) }
            .take(take)

    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(4 + dim * 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dim)
        for (w in weights) buf.putFloat(w)
        for (a in accumulator) buf.putFloat(a)
        return buf.array()
    }

    fun load(bytes: ByteArray, bias: Float, examplesSeen: Int) {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val storedDim = buf.int
        // Feature layout changed under us (app upgrade): start clean rather than
        // silently reading garbage weights.
        if (storedDim != dim) return
        val w = FloatArray(dim); val a = FloatArray(dim)
        for (i in 0 until dim) w[i] = buf.float
        for (i in 0 until dim) a[i] = buf.float
        weights = w; accumulator = a
        this.bias = bias
        this.examplesSeen = examplesSeen
    }

    fun reset() {
        weights = FloatArray(dim); accumulator = FloatArray(dim)
        bias = 0f; biasAccumulator = 0f; examplesSeen = 0
    }

    private fun sigmoid(z: Float): Float = 1f / (1f + exp(-z.coerceIn(-30f, 30f)))
}
