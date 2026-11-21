@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

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
import com.zeuskartik.mediaslider.R
import nl.giejay.mediaslider.player.VideoCache
import nl.giejay.mediaslider.player.VideoPrecacher
import nl.giejay.mediaslider.util.OverlayFade
import nl.giejay.mediaslider.util.PrefetchDeferral
import nl.giejay.mediaslider.util.SlidePrefetcher
import nl.giejay.mediaslider.util.TransitionFrameMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.adapter.ScreenSlidePagerAdapter
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.config.TransitionType
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.MetadataViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPluginContext
import nl.giejay.mediaslider.util.ClipPlanner
import nl.giejay.mediaslider.util.FixedSpeedScroller
import nl.giejay.mediaslider.util.LoadMoreResult
import nl.giejay.mediaslider.util.PageList
import timber.log.Timber
import java.lang.reflect.Field
import kotlin.math.abs

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
    private val clipPlanner = ClipPlanner()
    private var prefetcher: SlidePrefetcher? = null
    private var videoPrecacher: VideoPrecacher? = null
    private var loading = false

    /** Load-more requests in a row that added no page, and until when (uptime ms) no new request is made. */
    @Volatile private var emptyLoads = 0
    @Volatile private var loadMoreBlockedUntil = 0L

    // Slide transition bookkeeping (see the pager listener): while a transition animates nothing heavy may start.
    private var transitionActive = false
    private var jumpCandidate = false
    private var playerStoppedForScroll = false
    private var lastSelected = -1
    private var transitionSeq = 0
    private var transitionImageStart = ""
    private val frameMonitor = TransitionFrameMonitor { 1000.0 / (display?.refreshRate?.takeIf { it > 1f } ?: 60f) }
    private val prefetchRunnable = Runnable {
        if (!destroyed) {
            prefetcher?.onPageChanged(mPager.currentItem, config.items, playingBackwards())
            warmUpNextVideo()
        }
    }
    private val monitorTimeoutRunnable = Runnable { frameMonitor.stop("no idle signal, stopped by timeout") }
    private var destroyed = false
    private var pluginsDestroyed = false
    /** Between [onPause] and [onResume]. */
    private var paused = false
    /** Photo motion (Ken Burns) of the pages; created on first use because [mPager] is only set in init. */
    private val motion by lazy(LazyThreadSafetyMode.NONE) {
        PageMotionController(mPager, mainHandler, { isConfigReady }, { config }, { controller.slideShowPlaying }) {
            effectiveTransitionMs()
        }
    }
    /** True while the first page is hidden until its photos are loaded ([MediaSliderConfiguration.revealWhenLoaded]). */
    private var revealPending = false
    private var revealWaitedMs = 0L
    private val revealRunnable = Runnable { checkReveal() }
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
        controller.pageReady = { position -> pagerAdapter?.isPageReady(position) ?: true }
        controller.canStartAssetTimer = { !revealPending }
        playButton.setOnClickListener { controller.toggleSlideshow(true) }
        controller.onSlideshowPlayingChanged = { playing ->
            if (playing) {
                motion.resumeMotion()
                // The direction may have changed with this toggle.
                prefetcher?.onPageChanged(mPager.currentItem, config.items, controller.isReverseSlideshow)
            } else {
                motion.freezeMotion()
            }
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
        // A video is always followed by a photo (see warmUpNextVideo). Not with a chosen start asset: its index must stay.
        if (config.startPosition < 0) config.items = PageList.separateVideos(config.items)
        controller.initialize(config)
        controller.clips.planner = clipPlanner
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
        if (!transition.isAnimated) {
            return
        }
        try {
            // The label cross-fade uses the same easing, see OverlayFade.
            val interpolator = OverlayFade.interpolator(transition)
            val mScroller: Field = ViewPager::class.java.getDeclaredField("mScroller")
            mScroller.isAccessible = true
            mScroller.set(mPager, FixedSpeedScroller(mPager.context, interpolator, transition.durationMillis))
        } catch (e: Exception) {
            Timber.e(e, "Could not set scroller")
        }
        if (transition.type == TransitionType.CROSSFADE) {
            // LAYER_TYPE_NONE: by default the pager turns every page into a hardware layer for the length of the
            // transition. That costs a full-screen texture per page created at the first frame (and torn down at
            // the last one), and the incoming page's Ken Burns motion would invalidate its layer on every frame.
            // Without layers the outgoing page is drawn directly and only the incoming page needs an offscreen
            // pass for its alpha.
            mPager.setPageTransformer(false, CrossfadePageTransformer { mPager.currentItem }, View.LAYER_TYPE_NONE)
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

        pagerAdapter?.onPageDestroyed = { page -> motion.dropMotionOfPage(page) }
        pagerAdapter?.onFitChanged = { position -> motion.restartMotionAfterRelayout(position) }
        pagerAdapter?.isTransitionActive = { transitionActive }

        applyTransition()
        setUpPrefetching()

        mPager.setAdapter(pagerAdapter)
        mPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(sliderItemIndex: Int, v: Float, i1: Int) {
                if (config.loadMore != null && mPager.currentItem > config.items.size - 40 && !loading &&
                    SystemClock.uptimeMillis() >= loadMoreBlockedUntil) {
                    loading = true
                    ioScope.launch {
                        val loadMore = config.loadMore ?: return@launch
                        var added = 0
                        try {
                            val result: LoadMoreResult = loadMore()
                            added = addItemsMain(result.items)
                            if (!result.canLoadMore) {
                                config.loadMore = null
                            }
                        } finally {
                            // The first request delivers what follows the initial pages; the slideshow was
                            // holding on the last of them for it (also when it failed: then it loops).
                            config.holdOnLastPageUntilLoaded = false
                            loading = false
                        }
                        // A source that only returns assets shown lately (e.g. a random source that has
                        // delivered the whole library) would otherwise be asked again on every scroll.
                        emptyLoads = if (added > 0) 0 else emptyLoads + 1
                        loadMoreBlockedUntil = if (added > 0) 0L
                        else SystemClock.uptimeMillis() + PageList.emptyLoadBackoffMs(emptyLoads)
                    }
                }
                // Stop the player once per transition (and at rest), not on every scroll frame.
                if (v == 0f || !playerStoppedForScroll) {
                    controller.stopPlayer()
                    playerStoppedForScroll = v != 0f
                }
                if (sliderItemIndex != mPager.currentItem) {
                    return
                }
                // While a transition runs the pager reports offsets for the current page too (e.g. when paging
                // backwards); the settle work below (plugins, controller rebuild, player) must only run once, at rest.
                if (v != 0f) return
                onTransitionEnded()
                if (jumpCandidate) {
                    // No animated scroll followed the selection (wrap-around jump, transition NONE): hard cut.
                    jumpCandidate = false
                    viewPlugins.forEach { it.plugin.onTransitionSkipped(viewPluginContext, it.state) }
                }
                // Non-smooth navigation (transition NONE) dispatches no SCROLL_STATE_IDLE, so clean up here too.
                motion.resetMotionOfOtherPages()
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
                    // From now on the page's events count (a warmed-up player was silent until now).
                    viewTag.warmingUp = false
                    // Thumbnail over the black surface until the first frame is drawn (not needed for a warmed-up player).
                    viewTag.showLoadingPosterIfNeeded(mainItem.thumbnailUrl)
                    controller.setCurrentPlayer(player)
                    // Where the clip starts (0 outside clip mode); known before anything is shown.
                    val clipStartMs = controller.beginVideoClip(sliderItem.mainItem, player)
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.seekTo(0, clipStartMs)
                        if (sliderItem.url != null) {
                            prepareMedia(sliderItem.url!!, player, playbackDataSourceFactory(), clipStartMs)
                        }
                    } else if (abs(player.currentPosition - clipStartMs) > WARM_UP_SEEK_TOLERANCE_MS) {
                        // A warmed-up player already sits at the start: seeking to it again would flush the decoder and
                        // bring back the wait for the first frame.
                        player.seekTo(0, clipStartMs)
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
                        motion.startMotionForPage(sliderItemIndex)
                    }
                }
            }

            override fun onPageSelected(i: Int) {
                transitionActive = hasAnimatedTransition()
                jumpCandidate = true
                viewPlugins.forEach { it.plugin.onPageSelected(viewPluginContext, i, it.state) }
                // Prefetching decodes big photos and starts video I/O: wait for the transition to be over.
                mainHandler.removeCallbacks(prefetchRunnable)
                mainHandler.postDelayed(
                    prefetchRunnable,
                    if (transitionActive) PrefetchDeferral.fallbackDelayMs(effectiveTransitionMs()) else PrefetchDeferral.SETTLE_DELAY_MS
                )
                startFrameMonitor(i)
                if (!controller.isControllerVisible) {
                    controller.restorePagerFocus()
                }
                // Without an animated transition the old page is gone at once; with one it is reset when idle.
                if (!hasAnimatedTransition()) motion.resetMotionOfOtherPages()
                motion.startMotionForPage(i)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager.SCROLL_STATE_IDLE) {
                    jumpCandidate = false
                } else {
                    onTransitionEnded()
                    motion.resetMotionOfOtherPages()
                }
            }
        })
        // Register the listener before selecting the start page so the first asset
        // still gets onPageSelected / onPageSettled (metadata, date overlay, etc.).
        if (config.revealWhenLoaded) hideUntilLoaded()
        setStartPosition()
        prefetcher?.onPageChanged(mPager.currentItem, config.items)
        // The neighbouring page only exists after the first layout pass.
        mainHandler.postDelayed(prefetchRunnable, PrefetchDeferral.SETTLE_DELAY_MS)
        if (revealPending) mainHandler.post(revealRunnable)
    }

    private fun setContentAlpha(alpha: Float) {
        mPager.alpha = alpha
        findViewById<View>(R.id.plugin_layer)?.alpha = alpha
    }

    /** Keeps the pager and its overlays invisible (the background is black) until [checkReveal] fades them in. */
    private fun hideUntilLoaded() {
        revealPending = true
        revealWaitedMs = 0L
        setContentAlpha(0f)
    }

    /**
     * Fades the slider in as soon as the start page exists and all its photos are loaded (or after
     * [REVEAL_TIMEOUT_MS], when the network is slow: the page is then shown as it is).
     */
    private fun checkReveal() {
        if (!revealPending || destroyed) return
        val index = mPager.currentItem
        val inflated = mPager.findViewWithTag<View>("view$index") != null
        val ready = inflated && (config.items.getOrNull(index)?.type != SliderItemType.IMAGE || pagerAdapter?.isPageReady(index) == true)
        if (!ready && revealWaitedMs < REVEAL_TIMEOUT_MS) {
            revealWaitedMs += REVEAL_POLL_MS
            mainHandler.postDelayed(revealRunnable, REVEAL_POLL_MS)
            return
        }
        revealPending = false
        val fadeMs = effectiveTransitionMs().takeIf { it > 0 }?.toLong() ?: REVEAL_FALLBACK_FADE_MS
        val interpolator = OverlayFade.interpolator(config.transition)
        listOf<View?>(mPager, findViewById(R.id.plugin_layer)).forEach { view ->
            view?.animate()?.alpha(1f)?.setDuration(fadeMs)?.setInterpolator(interpolator)?.start()
        }
        // The first photo's screen time starts now that it is visible.
        controller.onAssetTimerGateOpened()
    }

    /**
     * The transition (if any) is over: stops the jank measurement, applies postponed photo relayouts and
     * starts the postponed prefetching after a short quiet time. Idempotent (called for both the settle and idle signals).
     */
    private fun onTransitionEnded() {
        val wasActive = transitionActive
        transitionActive = false
        if (frameMonitor.isRecording) {
            mainHandler.removeCallbacks(monitorTimeoutRunnable)
            frameMonitor.stop("image ${transitionImageStart} -> ${pagerAdapter?.imageState(mPager.currentItem)}")
        }
        pagerAdapter?.flushPendingFits()
        if (wasActive) {
            mainHandler.removeCallbacks(prefetchRunnable)
            mainHandler.postDelayed(prefetchRunnable, PrefetchDeferral.SETTLE_DELAY_MS)
        }
    }

    /** Starts the per-transition frame timing (one INFO line, tag SlideTransition, when it ends). */
    private fun startFrameMonitor(index: Int) {
        if (!frameMonitor.enabled) return
        val previous = lastSelected
        lastSelected = index
        if (previous < 0 || !transitionActive) return
        transitionSeq++
        transitionImageStart = pagerAdapter?.imageState(index) ?: "?"
        frameMonitor.start(
            "#$transitionSeq ${previous}->${index} ${config.transition.type.name.lowercase()} ${config.transition.durationMillis}ms" +
                (if (playingBackwards()) " reverse" else "") + (if (controller.slideShowPlaying) " playing" else " manual")
        )
        mainHandler.removeCallbacks(monitorTimeoutRunnable)
        mainHandler.postDelayed(monitorTimeoutRunnable, effectiveTransitionMs() + MONITOR_TIMEOUT_MARGIN_MS)
    }

    /**
     * Prepares (paused) the player of the video page next in the slideshow direction, so its decoder is
     * running and its first frame is drawn before the page is shown; otherwise opening the container,
     * starting the codec and the first frame all happen after the page appeared, a black half second.
     * Only the neighbouring page exists (offscreen limit 1) and only while the current page is a photo: two
     * hardware decoders at once are not available on every TV box. The caching of its bytes is [SlidePrefetcher]'s job.
     */
    private fun warmUpNextVideo() {
        if (destroyed || paused || !isConfigReady) return
        val items = config.items
        val current = mPager.currentItem
        if (items.getOrNull(current)?.type != SliderItemType.IMAGE) return
        val next = current + if (playingBackwards()) -1 else 1
        val slide = items.getOrNull(next)?.takeIf { it.type == SliderItemType.VIDEO } ?: return
        val url = slide.url ?: return
        val page = mPager.findViewWithTag<ExoPlayerView>("view$next") ?: return
        val player = page.getPlayer() ?: return
        if (player.playbackState != Player.STATE_IDLE) return
        page.warmingUp = true
        player.playWhenReady = false
        prepareMedia(url, player, playbackDataSourceFactory(), controller.plannedVideoClipStart(slide.mainItem))
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
        prefetcher = SlidePrefetcher(context, config, precacher, clipPlanner) { paired ->
            pagerAdapter?.slotSize(mPager, paired) ?: (width to height)
        }
    }

    private fun tearDownPrefetching() {
        mainHandler.removeCallbacks(prefetchRunnable)
        prefetcher?.clear()
        prefetcher = null
        videoPrecacher?.shutdown()
        videoPrecacher = null
        if (videoCache != null) {
            videoCache = null
            VideoCache.release()
        }
    }

    /** Transition length in ms that actually runs: 0 for NONE or a zero duration. */
    private fun effectiveTransitionMs(): Int {
        return config.transition.effectiveMs
    }

    private fun hasAnimatedTransition(): Boolean = isConfigReady && effectiveTransitionMs() > 0

    private fun playingBackwards(): Boolean = controller.slideShowPlaying && controller.isReverseSlideshow

    /**
     * Lifecycle (the fragment calls these; another activity, e.g. an external video player, pauses and resumes it):
     * - [onPause] stops what must not run in the background: prefetching is torn down, the next-slide timer and the
     *   story progress stop, the photo motion freezes and the current player is released (frees the decoder).
     *   The slideshow keeps its playing state and the view plugins (date, map, metadata) stay attached: they have
     *   no background work of their own besides one-shot lookups.
     * - [onResume] sets prefetching up again and continues the slideshow: a photo gets a fresh timer and its motion
     *   continues; a video page is rebuilt, because its player was released, and plays again once it settles.
     * - [onDestroy] is the final teardown; only it destroys the plugins.
     */
    fun onPause() {
        tearDownPrefetching()
        if (isConfigReady) {
            paused = true
            motion.freezeMotion()
            // Stops the timer and tells the controller plugins (story progress) the slideshow is on hold.
            controller.cancelNextAssetTimer()
        }
        controller.onDestroy()
    }

    /** Continues after [onPause], see there. */
    fun onResume() {
        if (!paused || !isConfigReady || destroyed || pagerAdapter == null) return
        paused = false
        if (prefetcher == null) {
            setUpPrefetching()
            prefetcher?.onPageChanged(mPager.currentItem, config.items, playingBackwards())
        }
        val index = mPager.currentItem
        when (config.items.getOrNull(index)?.type) {
            SliderItemType.VIDEO -> mPager.findViewWithTag<ExoPlayerView>("view$index")?.let { page ->
                // Its player was released: without the page item the adapter treats the page as stale, rebuilds
                // it with a new player and the pager settles on it again (which prepares and starts the video).
                page.setTag(R.id.slider_page_item, null)
                pagerAdapter?.setItems(config.items)
            }
            SliderItemType.IMAGE -> if (controller.slideShowPlaying) {
                controller.setKeepScreenOnFlags()
                controller.startTimerNextAsset()
                motion.resumeMotion()
            }
            null -> Unit
        }
    }

    /** Final teardown; also safe after [onPause]. */
    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        mainHandler.removeCallbacks(revealRunnable)
        tearDownPrefetching()
        ioScope.cancel()
        destroyPluginsOnce()
        controller.onDestroy()
    }

    private fun destroyPluginsOnce() {
        if (pluginsDestroyed) return
        pluginsDestroyed = true
        viewPlugins.forEach { it.plugin.onDestroy(viewPluginContext, it.state) }
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        this.defaultExoFactory = defaultExoFactory
    }

    /** Appends [items] on the main thread; returns the number of pages added. */
    suspend fun addItemsMain(items: List<SliderItemViewHolder>): Int = withContext(Dispatchers.Main) {
        addItems(items)
    }

    /** Appends [items], dropping pages that would show an asset again too soon (see [PageList.appendNew]). */
    private fun addItems(items: List<SliderItemViewHolder>): Int {
        val merged = PageList.appendNew(config.items, items)
        val added = merged.size - config.items.size
        if (added > 0) setItems(merged)
        return added
    }

    fun setItems(items: List<SliderItemViewHolder>) {
        if (items.isEmpty()) {
            Toast.makeText(context, "No items received to show in slideshow", Toast.LENGTH_SHORT).show()
            return
        }
        // When the current page stays (items were appended) the adapter keeps its view: its photo, its
        // running motion and the next-slide timer carry on untouched. Restarting them would make the
        // photo jump back to the start of its motion and stretch the slide's screen time.
        val current = mPager.currentItem
        val currentPageKept = PageList.canKeepPage(config.items.getOrNull(current), current, items)
        if (controller.slideShowPlaying && !currentPageKept) {
            // Cancel any pending auto-advance to avoid timing races while the current page is rebuilt.
            controller.cancelNextAssetTimer()
        }
        config.items = items
        pagerAdapter!!.setItems(items)
        prefetcher?.onPageChanged(mPager.currentItem, items, playingBackwards())
        if (!currentPageKept && controller.slideShowPlaying && currentItemType() == SliderItemType.IMAGE) {
            controller.startTimerNextAsset()
            // The current page was rebuilt (its stale motion entries were dropped as it was destroyed),
            // so start its motion again once its view exists.
            mainHandler.post { motion.startMotionWhenPageReady(mPager.currentItem) }
        }
    }

    protected fun currentItem(): SliderItemViewHolder = config.items[mPager.currentItem]
    protected fun currentItemType(): SliderItemType = currentItem().type

    fun isControllerVisible(): Boolean = controller.isControllerVisible

    /** Delegates to [MediaSliderController.toggleSlideshow]. Part of the public API for external callers. */
    open fun toggleSlideshow(showPlayIndicator: Boolean) = controller.toggleSlideshow(showPlayIndicator)


    companion object {
        /** Slack after the transition length before a measurement without idle signal is closed. */
        private const val MONITOR_TIMEOUT_MARGIN_MS = 1500L

        /** A warmed-up video is not seeked again at settle when it already sits this close to its clip start. */
        private const val WARM_UP_SEEK_TOLERANCE_MS = 200L

        /** Check interval, longest wait and fade length (transition NONE) of the initial reveal. */
        private const val REVEAL_POLL_MS = 50L
        private const val REVEAL_TIMEOUT_MS = 4000L
        private const val REVEAL_FALLBACK_FADE_MS = 600L

        @SuppressLint("UnsafeOptInUsageError")
        fun prepareMedia(mediaUrl: String, player: ExoPlayer, factory: DataSource.Factory, startPositionMs: Long = 0L) {
            val mediaUri = Uri.parse(mediaUrl)
            val mediaItem = MediaItem.fromUri(mediaUri)
            val mediaSource = ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, startPositionMs)
            player.prepare()
        }
    }
}
