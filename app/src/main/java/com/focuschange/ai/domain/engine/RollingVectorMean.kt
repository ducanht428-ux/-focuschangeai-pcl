package com.focuschange.ai.domain.engine

/**
 * Thread-safe time-windowed mean of fixed-size vectors. Real-time values are averaged over the same
 * window length that is used when sessions are down-sampled, so live values are comparable to the
 * calibration distribution (single frames would be far noisier than the learned bounds).
 */
class RollingVectorMean(private val dimension: Int, private val windowMs: Long) {
    private val lock = Any()
    private val times = ArrayDeque<Long>()
    private val vectors = ArrayDeque<FloatArray>()
    private val sum = DoubleArray(dimension)

    fun add(timestampMs: Long, vector: FloatArray) {
        if (vector.size != dimension || !vector.all { it.isFinite() }) return
        synchronized(lock) {
            evict(timestampMs)
            times.addLast(timestampMs)
            vectors.addLast(vector.copyOf())
            for (i in 0 until dimension) sum[i] += vector[i].toDouble()
        }
    }

    fun mean(nowMs: Long): FloatArray? = synchronized(lock) {
        evict(nowMs)
        if (vectors.isEmpty()) null else FloatArray(dimension) { (sum[it] / vectors.size).toFloat() }
    }

    fun addValue(timestampMs: Long, value: Double?) {
        if (dimension != 1 || value == null || !value.isFinite()) return
        add(timestampMs, floatArrayOf(value.toFloat()))
    }

    fun meanValue(nowMs: Long): Double? = mean(nowMs)?.get(0)?.toDouble()

    fun clear() {
        synchronized(lock) {
            times.clear()
            vectors.clear()
            sum.fill(0.0)
        }
    }

    private fun evict(nowMs: Long) {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) {
            times.removeFirst()
            val old = vectors.removeFirst()
            for (i in 0 until dimension) sum[i] -= old[i].toDouble()
        }
        if (vectors.isEmpty()) sum.fill(0.0)
    }
}
