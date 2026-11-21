package nl.giejay.mediaslider.view

import android.os.Handler
import android.os.SystemClock
import android.view.View
import androidx.viewpager.widget.ViewPager
import com.zeuskartik.mediaslider.R
import nl.giejay.mediaslider.adapter.SliderImageRequests
import nl.giejay.mediaslider.animation.KenBurnsAnimator
import nl.giejay.mediaslider.animation.KenBurnsPlanner
import nl.giejay.mediaslider.animation.MotionPlan
import nl.giejay.mediaslider.animation.MotionTiming
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemType

/**
 * Photo motion (Ken Burns) of the pages of [pager]. Behaviour:
 *  - starts as soon as a page becomes the target page (onPageSelected fires when the scroll
 *    starts), so it already moves while it fades in; the settled callback is a safe fallback;
 *  - runs for interval + 2 x transition duration (it starts with the incoming transition), so it
 *    keeps moving while the page fades out;
 *  - when the scroll is idle, every other page is cancelled and reset, so no page ever
 *    reappears mid-zoom;
 *  - pausing the slideshow freezes the motion in place; resuming continues towards the
 *    planned end, timed to end when the next transition (restarted with a full interval) ends;
 *  - a running motion is never restarted from its beginning (that looks like the photo jumping
 *    back): appending items keeps the current page, and a relayout of the photo continues the
 *    motion from where it is.
 * Only while the slideshow plays and the interval is at least [MIN_MOTION_INTERVAL] seconds.
 *
 * Owned by [MediaSliderView], which forwards the pager, lifecycle and slideshow events to it. Main thread only.
 *
 * @param isConfigReady whether [config] may be read yet
 * @param slideShowPlaying whether the slideshow currently plays
 * @param transitionMs length in ms of the slide transition that actually runs (0 without one)
 */
internal class PageMotionController(
    private val pager: ViewPager,
    private val mainHandler: Handler,
    private val isConfigReady: () -> Boolean,
    private val config: () -> MediaSliderConfiguration,
    private val slideShowPlaying: () -> Boolean,
    private val transitionMs: () -> Int
) {
    /**
     * A running (or frozen) motion. It runs in segments: the first from progress 0, after a resume or
     * a relayout from the progress it had reached; each segment ends at progress 1.
     */
    internal class ActiveMotion(
        val pageIndex: Int,
        val plan: MotionPlan,
        val seed: Long
    ) {
        private var segmentStartProgress = 0f
        private var segmentStartedAt = 0L
        private var segmentDurationMs = 0L

        /** Progress where the motion was frozen; negative while it runs. */
        var frozenProgress = -1f
            private set

        val isFrozen: Boolean get() = frozenProgress >= 0f

        fun beginSegment(now: Long, startProgress: Float, durationMs: Long) {
            segmentStartProgress = startProgress
            segmentStartedAt = now
            segmentDurationMs = durationMs
            frozenProgress = -1f
        }

        fun freeze(now: Long) {
            if (!isFrozen) frozenProgress = progressAt(now)
        }

        fun progressAt(now: Long): Float =
            if (isFrozen) frozenProgress
            else MotionTiming.progress(segmentStartProgress, now - segmentStartedAt, segmentDurationMs)

        fun remainingMs(now: Long): Long = (segmentDurationMs - (now - segmentStartedAt)).coerceAtLeast(0L)
    }

    /** Where a motion that has to be planned again (new layout) continues: same seed, same progress. */
    private class MotionContinuation(val seed: Long, val progress: Float, val remainingMs: Long, val frozen: Boolean)

    private val activeMotions = HashMap<View, ActiveMotion>()
    private val waitingMovers = HashSet<View>()

    /**
     * Motion starts with the incoming transition (t = 0), the next transition starts one interval
     * after the page settled and takes the transition time, so the motion lasts interval + 2 x transition.
     */
    private fun motionDurationMs(): Long = MotionTiming.fullDurationMs(config().interval, transitionMs())

    private fun moversOf(page: View): List<View> {
        val single = page.findViewById<View>(R.id.mBigImage)
        return if (single != null) listOf(single)
        else listOfNotNull(page.findViewById<View>(R.id.left_image), page.findViewById<View>(R.id.right_image))
    }

    fun startMotionForPage(index: Int) = startMotionForPage(index, null)

    private fun startMotionForPage(index: Int, continuation: MotionContinuation?) {
        val playingOrFrozen = slideShowPlaying() || continuation?.frozen == true
        if (!isConfigReady() || !playingOrFrozen || config().interval < MIN_MOTION_INTERVAL) return
        val item = config().items.getOrNull(index) ?: return
        if (item.type != SliderItemType.IMAGE) return
        val page = pager.findViewWithTag<View>("view$index") ?: return
        val movers = moversOf(page).filter { !activeMotions.containsKey(it) && !waitingMovers.contains(it) }
        if (movers.isEmpty()) return
        val panorama = page.findViewById<View>(R.id.mBigImage) != null && SliderImageRequests.scrollsAsPanorama(config(), item.mainItem)
        startMotionGroup(movers, index, panorama, continuation?.seed ?: System.nanoTime(), continuation)
    }

    /**
     * Starts the motion of all [movers] of one page together (a portrait pair must move in sync): waits
     * until every mover is laid out at its fit size, then plans each with the same seed and starts
     * them in the same moment.
     */
    private fun startMotionGroup(
        movers: List<View>,
        index: Int,
        panorama: Boolean,
        seed: Long,
        continuation: MotionContinuation? = null
    ) {
        waitingMovers.addAll(movers)
        var tries = 0
        var finished = false
        fun finish() {
            finished = true
            waitingMovers.removeAll(movers.toSet())
            // A continued motion left its movers frozen mid-move; any that did not get a new motion go back to rest.
            if (continuation != null) movers.forEach { if (!activeMotions.containsKey(it)) KenBurnsAnimator.cancel(it) }
        }
        fun isReady(mover: View): Boolean {
            val fit = mover.getTag(R.id.slider_fit_result) as? FitResult ?: return false
            val slot = mover.parent as? View ?: return false
            return mover.width == fit.displayW && mover.height == fit.displayH && slot.width > 0 && slot.height > 0
        }
        fun startAll(ready: List<View>) {
            val now = SystemClock.uptimeMillis()
            val startProgress = continuation?.progress ?: 0f
            val duration = continuation?.remainingMs ?: motionDurationMs()
            ready.forEach { mover ->
                val fit = mover.getTag(R.id.slider_fit_result) as FitResult
                val slot = mover.parent as View
                val sw = slot.width.toFloat()
                val sh = slot.height.toFloat()
                val dw = fit.displayW.toFloat()
                val dh = fit.displayH.toFloat()
                val plan = (if (panorama) KenBurnsPlanner.planPanorama(sw, sh, dw, dh, kotlin.random.Random(seed)) else null)
                    ?: KenBurnsPlanner.plan(sw, sh, dw, dh, config().motion, kotlin.random.Random(seed))
                if (plan != null) {
                    val motion = ActiveMotion(index, plan, seed)
                    motion.beginSegment(now, startProgress, duration)
                    KenBurnsAnimator.start(mover, plan.from(startProgress), duration)
                    if (continuation?.frozen == true) {
                        // Paused: show the photo where its motion stood, keep it still until resumed.
                        KenBurnsAnimator.freeze(mover)
                        motion.freeze(now)
                    }
                    activeMotions[mover] = motion
                }
            }
            finish()
        }
        /** True when this group is done (started or abandoned). [last] starts whatever is ready. */
        fun attempt(last: Boolean): Boolean {
            if (finished) return true
            val playingOrFrozen = slideShowPlaying() || continuation?.frozen == true
            if (pager.currentItem != index || !playingOrFrozen || movers.any { !it.isAttachedToWindow }) {
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
        val layoutListener = object : View.OnLayoutChangeListener {
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
    fun resetMotionOfOtherPages() {
        val current = pager.currentItem
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
    fun dropMotionOfPage(page: View) {
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

    /**
     * A photo's real size changed its layout: the plan of a running motion is stale, so plan it again
     * for the new size, with the same seed (same kind of move and direction) and continued from the
     * progress it had reached. Restarting it from the beginning would make the photo jump back.
     */
    fun restartMotionAfterRelayout(position: Int) {
        if (!isConfigReady() || position != pager.currentItem) return
        val page = pager.findViewWithTag<View>("view$position") ?: return
        val now = SystemClock.uptimeMillis()
        val movers = moversOf(page)
        val previous = movers.firstNotNullOfOrNull { activeMotions[it] }
        val continuation = previous?.let {
            MotionContinuation(it.seed, it.progressAt(now), it.remainingMs(now), it.isFrozen)
        }
        movers.forEach { mover ->
            // No reset to the identity transform: the new plan is applied in the same frame.
            if (activeMotions.remove(mover) != null) KenBurnsAnimator.freeze(mover)
            waitingMovers.remove(mover)
        }
        startMotionForPage(position, continuation)
    }

    /**
     * Starts the motion of the current page once its page view exists, e.g. after the adapter rebuilt
     * all pages. Gives up after about a second.
     */
    fun startMotionWhenPageReady(index: Int, attempt: Int = 0) {
        if (!isConfigReady() || pager.currentItem != index) return
        if (pager.findViewWithTag<View>("view$index") != null) {
            startMotionForPage(index)
        } else if (attempt < 20) {
            mainHandler.postDelayed({ startMotionWhenPageReady(index, attempt + 1) }, 50)
        }
    }

    private fun pageIndexOf(view: View): Int {
        var v: View? = view
        while (v != null && v.parent !== pager) v = v.parent as? View
        return (v?.tag as? String)?.removePrefix("view")?.toIntOrNull() ?: -1
    }

    fun freezeMotion() {
        val now = SystemClock.uptimeMillis()
        activeMotions.forEach { (view, motion) ->
            if (!motion.isFrozen) {
                motion.freeze(now)
                KenBurnsAnimator.freeze(view)
            }
        }
        waitingMovers.clear()
    }

    fun resumeMotion() {
        val now = SystemClock.uptimeMillis()
        val current = pager.currentItem
        var resumed = false
        // Resuming restarts the next-slide timer with a full interval, so the motion lasts until that
        // transition has ended; otherwise the photo would stand still before the next slide.
        val remaining = MotionTiming.resumeDurationMs(config().interval, transitionMs())
        activeMotions.forEach { (view, motion) ->
            if (motion.isFrozen && motion.pageIndex == current) {
                KenBurnsAnimator.resume(view, motion.plan, remaining)
                motion.beginSegment(now, motion.frozenProgress, remaining)
                resumed = true
            }
        }
        if (!resumed) startMotionForPage(current)
    }

    companion object {
        /** Shortest slide interval (seconds) that still gets photo motion. */
        private const val MIN_MOTION_INTERVAL = 4
    }
}
