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
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
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
import nl.giejay.mediaslider.animation.KenBurnsAnimator
import nl.giejay.mediaslider.animation.KenBurnsPlanner
import nl.giejay.mediaslider.animation.MotionPlan
import nl.giejay.mediaslider.animation.MotionTiming
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
    private val clipPlanner = ClipPlanner()
    private var prefetcher: SlidePrefetcher? = null
    private var videoPrecacher: VideoPrecacher? = null
    private var loading = false

    // Slide transition bookkeeping (see the pager listener): while a transition animates nothing heavy may start.
    private var transitionActive = false
    private var jumpCandidate = false
    private var playerStoppedForScroll = false
    private var lastSelected = -1
    private var transitionSeq = 0
    private var transitionImageStart = ""
    private val frameMonitor = TransitionFrameMonitor { 1000.0 / (display?.refreshRate?.takeIf { it > 1f } ?: 60f) }
    private val prefetchRunnable = Runnable {
        if (!destroyed) prefetcher?.onPageChanged(mPager.currentItem, config.items, playingBackwards())
    }
    private val monitorTimeoutRunnable = Runnable { frameMonitor.stop("no idle signal, stopped by timeout") }
    private var destroyed = false
    private var pluginsDestroyed = false
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
            if (playing) {
                resumeMotion()
                // The direction may have changed with this toggle.
                prefetcher?.onPageChanged(mPager.currentItem, config.items, controller.isReverseSlideshow)
            } else {
                freezeMotion()
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
        controller.initialize(config)
        controller.clipPlanner = clipPlanner
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

        pagerAdapter?.onPageDestroyed = { page -> dropMotionOfPage(page) }
        pagerAdapter?.onFitChanged = { position -> restartMotionAfterRelayout(position) }
        pagerAdapter?.isTransitionActive = { transitionActive }

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
                if (v == 0f) resetMotionOfOtherPages()
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
                    // Where the clip starts (0 outside clip mode); known before anything is shown.
                    val clipStartMs = controller.beginVideoClip(sliderItem.mainItem, player)
                    player.seekTo(0, clipStartMs)
                    if (player.playbackState == Player.STATE_IDLE && sliderItem.url != null) {
                        prepareMedia(sliderItem.url!!, player, playbackDataSourceFactory(), clipStartMs)
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
                if (!hasAnimatedTransition()) resetMotionOfOtherPages()
                startMotionForPage(i)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager.SCROLL_STATE_IDLE) {
                    jumpCandidate = false
                } else {
                    onTransitionEnded()
                    resetMotionOfOtherPages()
                }
            }
        })
        // Register the listener before selecting the start page so the first asset
        // still gets onPageSelected / onPageSettled (metadata, date overlay, etc.).
        setStartPosition()
        prefetcher?.onPageChanged(mPager.currentItem, config.items)
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

    // ---------------------------------------------------------------------------------------
    // Photo motion (Ken Burns). Behaviour:
    //  - starts as soon as a page becomes the target page (onPageSelected fires when the scroll
    //    starts), so it already moves while it fades in; the settled callback is a safe fallback;
    //  - runs for interval + 2 x transition duration (it starts with the incoming transition), so it
    //    keeps moving while the page fades out;
    //  - when the scroll is idle, every other page is cancelled and reset, so no page ever
    //    reappears mid-zoom;
    //  - pausing the slideshow freezes the motion in place; resuming continues towards the
    //    planned end, timed to end when the next transition (restarted with a full interval) ends.
    // Only while the slideshow plays and the interval is at least MIN_MOTION_INTERVAL seconds.
    // ---------------------------------------------------------------------------------------

    private class ActiveMotion(
        val pageIndex: Int,
        val plan: MotionPlan,
        var startedAt: Long,
        var frozenElapsed: Long = -1L
    )

    private val activeMotions = HashMap<View, ActiveMotion>()
    private val waitingMovers = HashSet<View>()

    /** Transition length in ms that actually runs: 0 for NONE or a zero duration. */
    private fun effectiveTransitionMs(): Int {
        val t = config.transition
        return if (t.type == TransitionType.NONE) 0 else t.durationMillis.coerceAtLeast(0)
    }

    private fun hasAnimatedTransition(): Boolean = isConfigReady && effectiveTransitionMs() > 0

    private fun playingBackwards(): Boolean = controller.slideShowPlaying && controller.isReverseSlideshow

    /**
     * Motion starts with the incoming transition (t = 0), the next transition starts one interval
     * after the page settled and takes the transition time, so the motion lasts interval + 2 x transition.
     */
    private fun motionDurationMs(): Long = MotionTiming.fullDurationMs(config.interval, effectiveTransitionMs())

    private fun moversOf(page: View): List<View> {
        val single = page.findViewById<View>(R.id.mBigImage)
        return if (single != null) listOf(single)
        else listOfNotNull(page.findViewById<View>(R.id.left_image), page.findViewById<View>(R.id.right_image))
    }

    private fun startMotionForPage(index: Int) {
        if (!isConfigReady || !controller.slideShowPlaying || config.interval < MIN_MOTION_INTERVAL) return
        val item = config.items.getOrNull(index) ?: return
        if (item.type != SliderItemType.IMAGE) return
        val page = mPager.findViewWithTag<View>("view$index") ?: return
        val movers = moversOf(page).filter { !activeMotions.containsKey(it) && !waitingMovers.contains(it) }
        if (movers.isEmpty()) return
        val panorama = page.findViewById<View>(R.id.mBigImage) != null && item.mainItem.isPanorama && config.motion.panoramaScroll
        startMotionGroup(movers, index, panorama, System.nanoTime())
    }

    /**
     * Starts the motion of all [movers] of one page together (a portrait pair must move in sync): waits
     * until every mover is laid out at its fit size, then plans each with the same seed and starts
     * them in the same moment.
     */
    private fun startMotionGroup(movers: List<View>, index: Int, panorama: Boolean, seed: Long) {
        waitingMovers.addAll(movers)
        var tries = 0
        var finished = false
        fun finish() {
            finished = true
            waitingMovers.removeAll(movers.toSet())
        }
        fun isReady(mover: View): Boolean {
            val fit = mover.getTag(R.id.slider_fit_result) as? FitResult ?: return false
            val slot = mover.parent as? View ?: return false
            return mover.width == fit.displayW && mover.height == fit.displayH && slot.width > 0 && slot.height > 0
        }
        fun startAll(ready: List<View>) {
            finish()
            val startedAt = SystemClock.uptimeMillis()
            val duration = motionDurationMs()
            ready.forEach { mover ->
                val fit = mover.getTag(R.id.slider_fit_result) as FitResult
                val slot = mover.parent as View
                val sw = slot.width.toFloat()
                val sh = slot.height.toFloat()
                val dw = fit.displayW.toFloat()
                val dh = fit.displayH.toFloat()
                val plan = (if (panorama) KenBurnsPlanner.planPanorama(sw, sh, dw, dh, kotlin.random.Random(seed)) else null)
                    ?: KenBurnsPlanner.plan(sw, sh, dw, dh, config.motion, kotlin.random.Random(seed))
                if (plan != null) {
                    KenBurnsAnimator.start(mover, plan, duration)
                    activeMotions[mover] = ActiveMotion(index, plan, startedAt)
                }
            }
        }
        /** True when this group is done (started or abandoned). [last] starts whatever is ready. */
        fun attempt(last: Boolean): Boolean {
            if (finished) return true
            if (mPager.currentItem != index || !controller.slideShowPlaying || movers.any { !it.isAttachedToWindow }) {
                finish()
                return true
            }
            val ready = movers.filter { isReady(it) }
            if (ready.size == movers.size || (last && ready.isNotEmpty())) {
                startAll(ready)
                return true
            }
            return false
        }
        if (attempt(false)) return
        // Not laid out yet (photo size still unknown): start when it is.
        val layoutListener = object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (attempt(false)) v?.removeOnLayoutChangeListener(this)
            }
        }
        movers.forEach { it.addOnLayoutChangeListener(layoutListener) }
        val retry = object : Runnable {
            override fun run() {
                if (finished) {
                    movers.forEach { it.removeOnLayoutChangeListener(layoutListener) }
                    return
                }
                if (attempt(++tries >= 20)) {
                    finish()
                    movers.forEach { it.removeOnLayoutChangeListener(layoutListener) }
                    return
                }
                mainHandler.postDelayed(this, 300)
            }
        }
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

    /** Drops all motion state of a page that is being destroyed (its views must not be kept alive). */
    private fun dropMotionOfPage(page: View) {
        fun belongsToPage(v: View): Boolean {
            var p: View? = v
            while (p != null && p !== page) p = p.parent as? View
            return p === page
        }
        val it = activeMotions.keys.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (belongsToPage(v)) {
                KenBurnsAnimator.cancel(v)
                it.remove()
            }
        }
        waitingMovers.removeAll { belongsToPage(it) }
    }

    /** A photo's real size changed its layout: the plan of a running motion is stale, so plan again. */
    private fun restartMotionAfterRelayout(position: Int) {
        if (!isConfigReady || position != mPager.currentItem) return
        val page = mPager.findViewWithTag<View>("view$position") ?: return
        moversOf(page).forEach { mover ->
            if (activeMotions.remove(mover) != null) KenBurnsAnimator.cancel(mover)
            waitingMovers.remove(mover)
        }
        startMotionForPage(position)
    }

    /**
     * Starts the motion of the current page once its page view exists, e.g. after the adapter rebuilt
     * all pages. Gives up after about a second.
     */
    private fun startMotionWhenPageReady(index: Int, attempt: Int = 0) {
        if (!isConfigReady || mPager.currentItem != index) return
        if (mPager.findViewWithTag<View>("view$index") != null) {
            startMotionForPage(index)
        } else if (attempt < 20) {
            mainHandler.postDelayed({ startMotionWhenPageReady(index, attempt + 1) }, 50)
        }
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
        // Resuming restarts the next-slide timer with a full interval, so the motion lasts until that
        // transition has ended; otherwise the photo would stand still before the next slide.
        val remaining = MotionTiming.resumeDurationMs(config.interval, effectiveTransitionMs())
        activeMotions.forEach { (view, motion) ->
            if (motion.frozenElapsed >= 0 && motion.pageIndex == current) {
                KenBurnsAnimator.resume(view, motion.plan, remaining)
                motion.startedAt = now - motion.frozenElapsed
                motion.frozenElapsed = -1L
                resumed = true
            }
        }
        if (!resumed) startMotionForPage(current)
    }

    /**
     * Pauses everything that must not run in the background: the player and timers are released and
     * prefetching is torn down. [onResume] sets prefetching up again.
     */
    fun onPause() {
        tearDownPrefetching()
        destroyPluginsOnce()
        controller.onDestroy()
    }

    /** Restores prefetching (and its video cache) after [onPause]. */
    fun onResume() {
        if (!isConfigReady || destroyed || prefetcher != null || pagerAdapter == null) return
        setUpPrefetching()
        prefetcher?.onPageChanged(mPager.currentItem, config.items, playingBackwards())
    }

    /** Final teardown; also safe after [onPause]. */
    fun onDestroy() {
        if (destroyed) return
        destroyed = true
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
        prefetcher?.onPageChanged(mPager.currentItem, items, playingBackwards())
        if (controller.slideShowPlaying && currentItemType() == SliderItemType.IMAGE) {
            controller.startTimerNextAsset()
            // notifyDataSetChanged rebuilt every page (stale motion entries were dropped as they were
            // destroyed); the current page lost its motion, so start it again once its view exists.
            mainHandler.post { startMotionWhenPageReady(mPager.currentItem) }
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

        /** Slack after the transition length before a measurement without idle signal is closed. */
        private const val MONITOR_TIMEOUT_MARGIN_MS = 1500L

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
