package nl.giejay.mediaslider.util

import android.view.Choreographer
import com.zeuskartik.mediaslider.BuildConfig
import timber.log.Timber
import kotlin.math.roundToInt

/** Summary of the frame intervals measured during one slide transition. Pure data, see [FrameStats.summarize]. */
data class FrameSummary(
    val frames: Int,
    val avgMs: Double,
    val worstMs: Double,
    /** Frames that took longer than 1.5 x the vsync interval, i.e. were visibly late. */
    val slowFrames: Int,
    /** Estimated number of vsyncs that had no new frame (sum of round(interval / vsync) - 1). */
    val droppedVsyncs: Int
)

object FrameStats {
    /** An interval above this many vsyncs counts as a slow frame. */
    const val SLOW_FACTOR = 1.5

    /** Summarises the first [count] of [intervalsMs] (time between consecutive frames) against the display's [vsyncMs]. */
    fun summarize(intervalsMs: DoubleArray, count: Int, vsyncMs: Double): FrameSummary {
        if (count <= 0 || vsyncMs <= 0.0) return FrameSummary(0, 0.0, 0.0, 0, 0)
        var sum = 0.0
        var worst = 0.0
        var slow = 0
        var dropped = 0
        for (i in 0 until count) {
            val v = intervalsMs[i]
            sum += v
            if (v > worst) worst = v
            if (v > vsyncMs * SLOW_FACTOR) slow++
            dropped += ((v / vsyncMs).roundToInt() - 1).coerceAtLeast(0)
        }
        return FrameSummary(count, sum / count, worst, slow, dropped)
    }
}

/**
 * Cheap jank diagnostic, active in debug builds only: measures the intervals between frames (via [Choreographer]) from the
 * start of a slide transition until [stop], and logs one line per transition with Timber at INFO and tag
 * [TAG]. Main thread only. A late main thread (layout, decode callbacks, GC) shows up as long intervals;
 * pure GPU overload shows up as intervals that are a multiple of the vsync interval.
 */
class TransitionFrameMonitor(
    /** When false (release builds), [start] does nothing: no Choreographer callbacks and no log line. */
    val enabled: Boolean = BuildConfig.DEBUG,
    private val vsyncMs: () -> Double
) {
    private var recording = false
    private var startNanos = 0L
    private var lastFrameNanos = 0L
    private var firstFrameDelayMs = -1.0
    private var intervals = DoubleArray(256)
    private var count = 0
    private var description = ""

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!recording) return
            if (lastFrameNanos == 0L) {
                firstFrameDelayMs = (frameTimeNanos - startNanos) / 1e6
            } else {
                if (count == intervals.size) intervals = intervals.copyOf(count * 2)
                intervals[count++] = (frameTimeNanos - lastFrameNanos) / 1e6
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    val isRecording: Boolean get() = recording

    /** Starts measuring; a running measurement is closed first. [info] is repeated in the log line. */
    fun start(info: String) {
        if (!enabled) return
        if (recording) stop("interrupted by next transition")
        recording = true
        description = info
        startNanos = System.nanoTime()
        lastFrameNanos = 0L
        firstFrameDelayMs = -1.0
        count = 0
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** Stops and logs. [extra] is appended (e.g. the state of the incoming image). */
    fun stop(extra: String = "") {
        if (!recording) return
        recording = false
        Choreographer.getInstance().removeFrameCallback(callback)
        val elapsedMs = (System.nanoTime() - startNanos) / 1e6
        val vsync = vsyncMs()
        val s = FrameStats.summarize(intervals, count, vsync)
        Timber.tag(TAG).i(
            "%s ran %dms, %d frames (~%d expected), %d slow (>%.0fms), %d dropped, worst %.0fms, avg %.1fms, first frame +%.0fms, vsync %.1fms%s",
            description, elapsedMs.roundToInt(), s.frames + if (firstFrameDelayMs >= 0) 1 else 0,
            (elapsedMs / vsync).roundToInt(), s.slowFrames, vsync * FrameStats.SLOW_FACTOR, s.droppedVsyncs,
            s.worstMs, s.avgMs, firstFrameDelayMs.coerceAtLeast(0.0), vsync,
            if (extra.isEmpty()) "" else ", $extra"
        )
    }

    companion object {
        const val TAG = "SlideTransition"
    }
}
