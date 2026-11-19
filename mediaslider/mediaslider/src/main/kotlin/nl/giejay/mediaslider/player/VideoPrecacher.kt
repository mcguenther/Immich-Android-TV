package nl.giejay.mediaslider.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Downloads the first [PRECACHE_BYTES] of videos into the [VideoCache] on a background thread, one at a
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

    /** Starts caching [url] unless it is already queued. Call on the main thread. */
    fun precache(url: String) {
        if (jobs.containsKey(url)) return
        val source = factory() ?: return
        val job = Job()
        jobs[url] = job
        job.future = executor.submit {
            if (job.cancelled) return@submit
            try {
                val spec = DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(PRECACHE_BYTES).build()
                val writer = CacheWriter(source.createDataSource(), spec, null, null)
                job.writer = writer
                if (!job.cancelled) writer.cache()
            } catch (e: Exception) {
                if (!job.cancelled) Timber.w(e, "Could not pre-cache video start: %s", url)
            }
        }
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
