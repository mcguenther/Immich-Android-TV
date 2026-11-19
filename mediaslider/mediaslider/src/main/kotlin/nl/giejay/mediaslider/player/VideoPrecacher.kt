package nl.giejay.mediaslider.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.CacheWriter
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Downloads the first [PRECACHE_BYTES] of videos (or the head plus an excerpt range, see [precache]) into the [VideoCache] on a background thread, one at a
 * time, so they start without waiting for the network. Each job can be cancelled; a cancelled or failed
 * job just leaves the partial bytes in the cache.
 *
 * @param factory current cache-backed data source factory, or null while the cache is unavailable
 */
@OptIn(UnstableApi::class)
class VideoPrecacher(private val factory: () -> CacheDataSource.Factory?) {
    companion object {
        /** How much of the start of a video is pre-cached. */
        const val PRECACHE_BYTES = 8L * 1024 * 1024

        /** Head of a video that is cached when its excerpt starts later: container header (moov) and first samples. */
        const val HEAD_BYTES = 1L * 1024 * 1024

        /** Size of the range cached around the estimated start of an excerpt. */
        const val EXCERPT_BYTES = 6L * 1024 * 1024

        /** The estimated byte offset is moved this far back so a keyframe before the start is covered too. */
        const val EXCERPT_LEAD_BYTES = 512L * 1024
    }

    private class Job {
        @Volatile var cancelled = false
        @Volatile var writer: CacheWriter? = null
        var future: Future<*>? = null
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "slider-video-precache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val jobs = HashMap<String, Job>()

    /**
     * Starts caching [url] unless it is already queued. With [startFraction] > 0 (the excerpt starts that
     * far into the video) only the [HEAD_BYTES] head plus a range around the estimated byte offset of the
     * start are cached (offset = fraction x content length, skipped while the length is unknown); a
     * rough estimate is fine, the player fetches what is missing. Call on the main thread.
     */
    fun precache(url: String, startFraction: Double = 0.0) {
        if (jobs.containsKey(url)) return
        val source = factory() ?: return
        val job = Job()
        jobs[url] = job
        job.future = executor.submit {
            if (job.cancelled) return@submit
            try {
                val excerpt = startFraction > 0.0
                val head = DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0)
                    .setLength(if (excerpt) HEAD_BYTES else PRECACHE_BYTES).build()
                val dataSource = source.createDataSource()
                cacheRange(job, dataSource, head)
                if (!excerpt || job.cancelled) return@submit
                // The head request stored the content length in the cache metadata (unknown: skip).
                val key = CacheKeyFactory.DEFAULT.buildCacheKey(head)
                val length = ContentMetadata.getContentLength(dataSource.cache.getContentMetadata(key))
                if (length <= HEAD_BYTES) return@submit
                val offset = (startFraction * length).toLong() - EXCERPT_LEAD_BYTES
                if (offset <= HEAD_BYTES) return@submit
                cacheRange(job, source.createDataSource(), head.buildUpon().setPosition(offset).setLength(EXCERPT_BYTES).build())
            } catch (e: Exception) {
                if (!job.cancelled) Timber.w(e, "Could not pre-cache video start: %s", url)
            }
        }
    }

    private fun cacheRange(job: Job, dataSource: CacheDataSource, spec: DataSpec) {
        val writer = CacheWriter(dataSource, spec, null, null)
        job.writer = writer
        if (!job.cancelled) writer.cache()
    }

    /** Cancels the job for [url], if any. Call on the main thread. */
    fun cancel(url: String) {
        val job = jobs.remove(url) ?: return
        job.cancelled = true
        job.writer?.cancel()
        job.future?.cancel(false)
    }

    /** Cancels everything and stops the thread. */
    fun shutdown() {
        jobs.keys.toList().forEach { cancel(it) }
        executor.shutdownNow()
    }
}
