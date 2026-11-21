package nl.giejay.android.tv.immich.shared

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.LiveSharedPreferences
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_PREFETCH_COUNT
import nl.giejay.android.tv.immich.shared.util.GlideMemorySizing
import java.io.InputStream


@GlideModule
class CustomGlideModule : AppGlideModule() {
    private var factory: OkHttpUrlLoader.Factory = OkHttpUrlLoader.Factory()
    private var registry: Registry? = null
    private val prefs: LiveSharedPreferences =
        LiveSharedPreferences(PreferenceManager.sharedPreference)

    init {
        prefs.listenMultiple(listOf(API_KEY.key(), DEBUG_MODE.key(), DISABLE_SSL_VERIFICATION.key()))
            .observeForever {
                reloadFactory(
                    PreferenceManager.get(API_KEY), // listenMultiple's raw value is still encrypted
                    it[DISABLE_SSL_VERIFICATION.key()] == true
                )
            }
    }

    private fun reloadFactory(apiKey: String, disableSsl: Boolean) {
        factory = if (PreferenceManager.isLoggedId()) {
            OkHttpUrlLoader.Factory(
                ApiClientFactory.getClient(
                    disableSsl,
                    apiKey,
                    // never enable debug mode of responses in glide, too much data
                    false
                )
            )
        } else {
            OkHttpUrlLoader.Factory()
        }
        this.registry?.replace(
            GlideUrl::class.java,
            InputStream::class.java, factory
        )
    }

    /**
     * Enlarges the memory cache (at most 1/5 of the heap; Glide's default bitmap pool is kept) so the
     * slides prefetched by the slideshow stay in memory until they are shown. Glide's default is kept on low-RAM devices. The size is computed
     * once when Glide starts, so a later change of the prefetch count applies after a restart.
     */
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val largeHeap = (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
        val heapMb = if (largeHeap) activityManager.largeMemoryClass else activityManager.memoryClass
        val metrics = context.resources.displayMetrics
        val bytes = GlideMemorySizing.memoryCacheBytes(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            prefetchCount = prefetchCount(),
            heapMb = heapMb,
            isLowRamDevice = activityManager.isLowRamDevice
        ) ?: return
        builder.setMemoryCache(LruResourceCache(bytes))
    }

    /** The preferences are initialised already: the `prefs` field reads them at construction. */
    private fun prefetchCount(): Int {
        return PreferenceManager.get(SLIDESHOW_PREFETCH_COUNT)
    }

    override fun registerComponents(
        context: Context, glide: Glide, registry: Registry
    ) {
        this.registry = registry
        // override default loader with one that attaches headers
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java, factory
        )
    }
}