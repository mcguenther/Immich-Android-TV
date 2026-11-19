package nl.giejay.mediaslider.view

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.viewpager.widget.ViewPager
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import com.zeuskartik.mediaslider.R
import nl.giejay.mediaslider.player.VideoCache
import nl.giejay.mediaslider.player.VideoPrecacher
import nl.giejay.mediaslider.util.SlidePrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.adapter.ScreenSlidePagerAdapter
import nl.giejay.mediaslider.animation.KenBurnsAnimator
import nl.giejay.mediaslider.animation.KenBurnsPlanner
import nl.giejay.mediaslider.animation.MotionPlan
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.config.TransitionType
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.MetadataViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPluginContext
import nl.giejay.mediaslider.util.FixedSpeedScroller
import nl.giejay.mediaslider.util.LoadMoreResult
import timber.log.Timber
import java.lang.reflect.Field

/**
 * Pager + metadata shell for the media slider. Timeline/memories chrome is provided
 * via pluggable view/controller/key plugins.
 */
open class MediaSliderView(context: Context) : ConstraintLayout(context) {
    // view elements
    protected val mainHandler = Handler(Looper.getMainLooper())
    protected val mPager: ViewPager
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION" && isConfigReady) {
                if (currentItemType() == SliderItemType.VIDEO
                    && controller.currentPlayer?.isPlaying == true
                    && controller.currentPlayer?.volume == 0f
                ) {
                    Timber.i("Volume changed detected, unmuting video")
                    Toast.makeText(context, "Volume changed detected, unmuting video", Toast.LENGTH_SHORT).show()
                    controller.toggleMute()
                }
            }
        }
    }

    // config
    protected lateinit var config: MediaSliderConfiguration
        private set
    protected val isConfigReady: Boolean get() = this::config.isInitialized
    private var isVolumeReceiverRegistered = false

    // internal
    protected val controller: MediaSliderController
    private var defaultExoFactory = DefaultHttpDataSource.Factory()
    private var pagerAdapter: ScreenSlidePagerAdapter? = null
    private var videoCache: Cache? = null
    private var prefetcher: SlidePrefetcher? = null
    private var videoPrecacher: VideoPrecacher? = null
    private var loading = false
    private val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private data class ViewPluginEntry(val plugin: SliderViewPlugin<Any?>, val state: Any?)
    private val viewPlugins = mutableListOf<ViewPluginEntry>()
    private val viewPluginContext by lazy {
        SliderViewPluginContext(
            context,
            findViewById<ConstraintLayout>(R.id.plugin_layer),
            controller,
            ioScope
        ) { currentItem() }
    }

    init {
        inflate(getContext(), R.layout.slider, this)

        val playButton: View = findViewById(R.id.playPause)
        mPager = findViewById(R.id.pager)
        controller = MediaSliderController(context, mainHandler, mPager, playButton, this)
        playButton.setOnClickListener { controller.toggleSlideshow(true) }
        controller.onSlideshowPlayingChanged = { playing ->
            if (playing) resumeMotion() else freezeMotion()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isVolumeReceiverRegistered) {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            context.registerReceiver(volumeReceiver, filter)
            isVolumeReceiverRegistered = true
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (isVolumeReceiverRegistered) {
            try {
                context.unregisterReceiver(volumeReceiver)
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "volumeReceiver not registered")
            }
            isVolumeReceiverRegistered = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mPager.adapter == null) {
            return super.dispatchKeyEvent(event)
        }
        return controller.dispatchKeyEvent(event) { super.dispatchKeyEvent(event) }
    }

    open fun loadMediaSliderView(config: MediaSliderConfiguration) {
        this.config = config

        config.viewPlugins.forEach { plugin ->
            @Suppress("UNCHECKED_CAST")
            val typedPlugin = plugin as SliderViewPlugin<Any?>
            viewPlugins.add(ViewPluginEntry(typedPlugin, typedPlugin.createState(viewPluginContext, config)))
        }

        val listener: ExoPlayerListener = object : ExoPlayerListener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                controller.onPlaybackStateChanged(playbackState)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controller.onIsPlayingChanged(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                controller.onPlayerError(error)
            }
        }
        controller.initialize(config)
        viewPlugins.forEach { it.plugin.attachView(viewPluginContext.rootView, it.state) }
        viewPlugins.forEach { it.plugin.onLoadConfig(viewPluginContext, config, it.state) }
        initViewsAndSetAdapter(listener)
    }

    private fun setStartPosition() {
        if (config.startPosition >= 0) {
            if (config.startPosition > config.items.size) {
                mPager.currentItem = (config.items.size - 1)
                return
            }
            mPager.currentItem = config.startPosition
        } else {
            mPager.currentItem = 0
        }
        mPager.offscreenPageLimit = 1
        controller.restorePagerFocus()
    }

    /**
     * Configures the pager for [MediaSliderConfiguration.transition]. The scroll duration is forced
     * through [FixedSpeedScroller]; CROSSFADE additionally pins the pages and fades them.
     * NONE (or a zero duration) needs no scroller because navigation then jumps without animation.
     */
    private fun applyTransition() {
        val transition = config.transition
        if (transition.type == TransitionType.NONE || transition.durationMillis <= 0) {
            return
        }
        try {
            val interpolator = if (transition.type == TransitionType.CROSSFADE) {
                AccelerateDecelerateInterpolator()
            } else {
                DecelerateInterpolator(0.75F)
            }
            val mScroller: Field = ViewPager::class.java.getDeclaredField("mScroller")
            mScroller.isAccessible = true
            mScroller.set(mPager, FixedSpeedScroller(mPager.context, interpolator, transition.durationMillis))
        } catch (e: Exception) {
            Timber.e(e, "Could not set scroller")
        }
        if (transition.type == TransitionType.CROSSFADE) {
            mPager.setPageTransformer(false, CrossfadePageTransformer { mPager.currentItem })
        }
    }

    private fun initViewsAndSetAdapter(listener: ExoPlayerListener) {
        pagerAdapter = ScreenSlidePagerAdapter(
            context,
            config.items,
            config,
            { mPager.currentItem },
            listener
        )

        applyTransition()
        setUpPrefetching()

        mPager.setAdapter(pagerAdapter)
        mPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(sliderItemIndex: Int, v: Float, i1: Int) {
                if (config.loadMore != null && mPager.currentItem > config.items.size - 40 && !loading) {
                    loading = true
                    ioScope.launch {
                        val loadMore = config.loadMore ?: return@launch
                        val result: LoadMoreResult = loadMore()
                        addItemsMain(result.items)
                        if (!result.canLoadMore) {
                            config.loadMore = null
                        }
                        loading = false
                    }
                }
                controller.stopPlayer()
                if (sliderItemIndex != mPager.currentItem) {
                    return
                }
                val sliderItem = config.items[sliderItemIndex]
                val mainItem = sliderItem.mainItem

                viewPlugins.forEach { entry ->
                    entry.plugin.onPageSettled(viewPluginContext, config, sliderItem, sliderItemIndex, mainHandler, entry.state)
                }

                config.onAssetSelected(sliderItem)

                if (sliderItem.type == SliderItemType.VIDEO) {
                    val viewTag = mPager.findViewWithTag<ExoPlayerView>("view$sliderItemIndex") ?: return
                    if (!viewTag.isReady()) {
                        Timber.e("Player is not initialized properly, cannot play video.")
                        Toast.makeText(context, "Player is not initialized properly, cannot play video.", Toast.LENGTH_LONG).show()
                        return
                    }
                    val player = viewTag.getPlayer()!!
                    controller.setCurrentPlayer(player)
                    player.seekTo(0, 0)
                    if (player.playbackState == Player.STATE_IDLE && sliderItem.url != null) {
                        prepareMedia(sliderItem.url!!, player, playbackDataSourceFactory())
                    }
                    if (!config.isVideoSoundEnable) {
                        player.volume = 0f
                    }
                    player.playWhenReady = true
                    controller.configureController(sliderItem, sliderItemIndex)
                } else {
                    controller.setCurrentPlayer(null)
                    controller.configureController(sliderItem, sliderItemIndex)
                    if (controller.slideShowPlaying) {
                        controller.startTimerNextAsset()
                        startMotionForPage(sliderItemIndex)
                    }
                }
            }

            override fun onPageSelected(i: Int) {
                viewPlugins.forEach { it.plugin.onPageSelected(viewPluginContext, i, it.state) }
                prefetcher?.onPageChanged(i, config.items)
                if (!controller.isControllerVisible) {
                    controller.restorePagerFocus()
                }
                startMotionForPage(i)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager.SCROLL_STATE_IDLE) {
                    resetMotionOfOtherPages()
                }
            }
        })
        // Register the listener before selecting the start page so the first asset
        // still gets onPageSelected / onPageSettled (metadata, date overlay, etc.).
        setStartPosition()
        prefetcher?.onPageChanged(mPager.currentItem, config.items)
    }

    /** Authenticated HTTP factory, wrapped in the shared video cache when that could be opened. */
    private fun playbackDataSourceFactory(): DataSource.Factory =
        videoCache?.let { VideoCache.cacheDataSourceFactory(it, defaultExoFactory) } ?: defaultExoFactory

    private fun setUpPrefetching() {
        videoCache = VideoCache.acquire(context, config.loading.videoCacheMb)
        val precacher = videoCache?.let { cache ->
            VideoPrecacher { VideoCache.cacheDataSourceFactory(cache, defaultExoFactory) }
        }
        videoPrecacher = precacher
        prefetcher = SlidePrefetcher(context, config, precacher) { paired ->
            pagerAdapter?.slotSize(mPager, paired) ?: (width to height)
        }
    }

    private fun tearDownPrefetching() {
        prefetcher?.clear()
        prefetcher = null
        videoPrecacher?.shutdown()
        videoPrecacher = null
        if (videoCache != null) {
            videoCache = null
            VideoCache.release()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Photo motion (Ken Burns). Behaviour:
    //  - starts as soon as a page becomes the target page (onPageSelected fires when the scroll
    //    starts), so it already moves while it fades in; the settled callback is a safe fallback;
    //  - runs for interval + transition duration, so it keeps moving while the page fades out;
    //  - when the scroll is idle, every other page is cancelled and reset, so no page ever
    //    reappears mid-zoom;
    //  - pausing the slideshow freezes the motion in place; resuming continues towards the
    //    planned end for the remaining time (at least half an interval).
    // Only while the slideshow plays and the interval is at least MIN_MOTION_INTERVAL seconds.
    // ---------------------------------------------------------------------------------------

    private class ActiveMotion(
        val pageIndex: Int,
        val plan: MotionPlan,
        var startedAt: Long,
        val durationMs: Long,
        var frozenElapsed: Long = -1L
    )

    private val activeMotions = HashMap<View, ActiveMotion>()
    private val waitingMovers = HashSet<View>()

    private fun motionDurationMs(): Long {
        val t = config.transition
        return config.interval * 1000L + (if (t.type == TransitionType.NONE) 0 else t.durationMillis)
    }

    private fun startMotionForPage(index: Int) {
        if (!isConfigReady || !controller.slideShowPlaying || config.interval < MIN_MOTION_INTERVAL) return
        val item = config.items.getOrNull(index) ?: return
        if (item.type != SliderItemType.IMAGE) return
        val page = mPager.findViewWithTag<View>("view$index") ?: return
        val single = page.findViewById<View>(R.id.mBigImage)
        val movers = if (single != null) listOf(single)
        else listOfNotNull(page.findViewById<View>(R.id.left_image), page.findViewById<View>(R.id.right_image))
        val seed = System.nanoTime()
        val panorama = single != null && item.mainItem.isPanorama && config.motion.panoramaScroll
        movers.forEach { startMoverMotion(it, index, panorama, seed) }
    }

    private fun startMoverMotion(mover: View, index: Int, panorama: Boolean, seed: Long) {
        if (activeMotions.containsKey(mover) || !waitingMovers.add(mover)) return
        var tries = 0
        fun finish() {
            waitingMovers.remove(mover)
        }
        fun tryStart(): Boolean {
            if (mPager.currentItem != index || !controller.slideShowPlaying) {
                finish()
                return true
            }
            val fit = mover.getTag(R.id.slider_fit_result) as? FitResult ?: return false
            val slot = mover.parent as? View ?: return false
            if (mover.width != fit.displayW || mover.height != fit.displayH || slot.width <= 0 || slot.height <= 0) return false
            val random = kotlin.random.Random(seed)
            val sw = slot.width.toFloat()
            val sh = slot.height.toFloat()
            val dw = fit.displayW.toFloat()
            val dh = fit.displayH.toFloat()
            val plan = (if (panorama) KenBurnsPlanner.planPanorama(sw, sh, dw, dh, kotlin.random.Random(seed)) else null)
                ?: KenBurnsPlanner.plan(sw, sh, dw, dh, config.motion, random)
            finish()
            if (plan != null) {
                val duration = motionDurationMs()
                KenBurnsAnimator.start(mover, plan, duration)
                activeMotions[mover] = ActiveMotion(index, plan, SystemClock.uptimeMillis(), duration)
            }
            return true
        }
        if (tryStart()) return
        // Not laid out yet (photo size still unknown): start when it is.
        val retry = object : Runnable {
            override fun run() {
                if (!waitingMovers.contains(mover)) return
                if (tryStart() || ++tries >= 20) {
                    finish()
                    return
                }
                mainHandler.postDelayed(this, 300)
            }
        }
        mover.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (!waitingMovers.contains(mover)) {
                    mover.removeOnLayoutChangeListener(this)
                } else if (tryStart()) {
                    mover.removeOnLayoutChangeListener(this)
                }
            }
        })
        mainHandler.postDelayed(retry, 300)
    }

    /** Cancels and resets the motion of every page except the current one. */
    private fun resetMotionOfOtherPages() {
        val current = mPager.currentItem
        val it = activeMotions.entries.iterator()
        while (it.hasNext()) {
            val (view, motion) = it.next()
            if (motion.pageIndex != current || !view.isAttachedToWindow) {
                KenBurnsAnimator.cancel(view)
                it.remove()
            }
        }
        waitingMovers.retainAll { w -> w.isAttachedToWindow && w.parent != null && pageIndexOf(w) == current }
    }

    private fun pageIndexOf(view: View): Int {
        var v: View? = view
        while (v != null && v.parent !== mPager) v = v.parent as? View
        return (v?.tag as? String)?.removePrefix("view")?.toIntOrNull() ?: -1
    }

    private fun freezeMotion() {
        val now = SystemClock.uptimeMillis()
        activeMotions.forEach { (view, motion) ->
            if (motion.frozenElapsed < 0) {
                motion.frozenElapsed = now - motion.startedAt
                KenBurnsAnimator.freeze(view)
            }
        }
        waitingMovers.clear()
    }

    private fun resumeMotion() {
        val now = SystemClock.uptimeMillis()
        val current = mPager.currentItem
        var resumed = false
        activeMotions.forEach { (view, motion) ->
            if (motion.frozenElapsed >= 0 && motion.pageIndex == current) {
                val remaining = (motion.durationMs - motion.frozenElapsed).coerceAtLeast(config.interval * 500L)
                KenBurnsAnimator.resume(view, motion.plan, remaining)
                motion.startedAt = now - motion.frozenElapsed
                motion.frozenElapsed = -1L
                resumed = true
            }
        }
        if (!resumed) startMotionForPage(current)
    }

    fun onDestroy() {
        tearDownPrefetching()
        ioScope.cancel()
        viewPlugins.forEach { it.plugin.onDestroy(viewPluginContext, it.state) }
        controller.onDestroy()
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        this.defaultExoFactory = defaultExoFactory
    }

    suspend fun addItemsMain(items: List<SliderItemViewHolder>) = withContext(Dispatchers.Main) {
        addItems(items)
    }

    private fun addItems(items: List<SliderItemViewHolder>) {
        setItems(Lists.newArrayList(Iterables.concat(config.items, items)).distinct())
    }

    fun setItems(items: List<SliderItemViewHolder>) {
        if (items.isEmpty()) {
            Toast.makeText(context, "No items received to show in slideshow", Toast.LENGTH_SHORT).show()
            return
        }
        if (controller.slideShowPlaying) {
            // Cancel any pending auto-advance to avoid timing races when new items arrive.
            controller.cancelNextAssetTimer()
        }
        config.items = items
        pagerAdapter!!.setItems(items)
        prefetcher?.onPageChanged(mPager.currentItem, items)
        if (controller.slideShowPlaying && currentItemType() == SliderItemType.IMAGE) {
            controller.startTimerNextAsset()
        }
    }

    protected fun currentItem(): SliderItemViewHolder = config.items[mPager.currentItem]
    protected fun currentItemType(): SliderItemType = currentItem().type

    fun isControllerVisible(): Boolean = controller.isControllerVisible

    /** Delegates to [MediaSliderController.toggleSlideshow]. Part of the public API for external callers. */
    open fun toggleSlideshow(showPlayIndicator: Boolean) = controller.toggleSlideshow(showPlayIndicator)


    companion object {
        /** Shortest slide interval (seconds) that still gets photo motion. */
        private const val MIN_MOTION_INTERVAL = 4

        @SuppressLint("UnsafeOptInUsageError")
        fun prepareMedia(mediaUrl: String, player: ExoPlayer, factory: DataSource.Factory) {
            val mediaUri = Uri.parse(mediaUrl)
            val mediaItem = MediaItem.fromUri(mediaUri)
            val mediaSource = ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, 0L)
            player.prepare()
        }
    }
}
