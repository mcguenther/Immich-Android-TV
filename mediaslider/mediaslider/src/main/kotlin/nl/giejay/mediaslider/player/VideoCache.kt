package nl.giejay.mediaslider.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import timber.log.Timber
import java.io.File

/**
 * Process-wide on-disk video cache. A [SimpleCache] must exist only once per directory, so the slider
 * views (activity, screensaver, preview) share it through [acquire] / [release]; it is closed when the
 * last holder releases it. Playback and [VideoPrecacher] both read and write through
 * [cacheDataSourceFactory], so bytes pre-cached for an upcoming video are used when it starts.
 */
@OptIn(UnstableApi::class)
object VideoCache {
    private const val DIR_NAME = "slider-video"
    private val lock = Any()
    private var cache: SimpleCache? = null
    private var holders = 0

    /** Takes a reference; the cache is created on first use with room for [maxMb] megabytes. Null if it cannot be opened. */
    fun acquire(context: Context, maxMb: Int): Cache? = synchronized(lock) {
        // Only a successful acquire counts as a holder, so the caller (which releases only when it got a
        // cache) always has a matching release.
        cache?.also { holders++ } ?: try {
            val app = context.applicationContext
            SimpleCache(
                File(app.cacheDir, DIR_NAME),
                LeastRecentlyUsedCacheEvictor(maxMb.coerceAtLeast(1) * 1024L * 1024L),
                StandaloneDatabaseProvider(app)
            ).also { cache = it; holders++ }
        } catch (e: Exception) {
            Timber.e(e, "Could not open the video cache, playing without it")
            null
        }
    }

    /** Drops a reference taken with [acquire]; the cache is released with the last one. */
    fun release() = synchronized(lock) {
        if (holders > 0) holders--
        if (holders == 0) {
            try {
                cache?.release()
            } catch (e: Exception) {
                Timber.w(e, "Could not release the video cache")
            }
            cache = null
        }
    }

    /** Wraps [upstream] (the authenticated HTTP factory) with [cache]; a cache error falls back to upstream. */
    fun cacheDataSourceFactory(cache: Cache, upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
