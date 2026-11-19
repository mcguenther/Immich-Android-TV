package nl.giejay.mediaslider.util

/** Pure window arithmetic for [SlidePrefetcher], kept free of Android types so it can be unit tested. */
object PrefetchPlanner {
    /** Devices with a smaller per-app heap (MB) than this only prefetch a single slide. */
    const val LOW_MEMORY_CLASS_MB = 192

    /**
     * Number of slides to prefetch: [requested] (clamped to >= 0), at most 1 on low-RAM devices
     * (`isLowRamDevice` or a [memoryClassMb] below [LOW_MEMORY_CLASS_MB]).
     */
    fun effectiveCount(requested: Int, isLowRamDevice: Boolean, memoryClassMb: Int): Int {
        val n = requested.coerceAtLeast(0)
        return if (isLowRamDevice || memoryClassMb < LOW_MEMORY_CLASS_MB) n.coerceAtMost(1) else n
    }

    /**
     * Indices of the next [count] slides after [current] in a list of [size], wrapping around at the end
     * like the slider does. Never contains [current] and never repeats an index, so it is shorter than
     * [count] when the list is small. Empty for an empty list, a non-positive count or an invalid [current].
     */
    fun indicesToPrefetch(current: Int, count: Int, size: Int): List<Int> {
        if (count <= 0 || size <= 1 || current !in 0 until size) return emptyList()
        return (1..minOf(count, size - 1)).map { (current + it) % size }
    }

    /** Keys that are active but no longer wanted, i.e. the preloads to cancel. */
    fun <K> staleKeys(active: Collection<K>, wanted: Set<K>): List<K> = active.filter { it !in wanted }
}
