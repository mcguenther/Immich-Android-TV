package nl.giejay.mediaslider.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.viewpager.widget.PagerAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.zeuskartik.mediaslider.R
import nl.giejay.mediaslider.player.AmlogicSafeRenderersFactory
import nl.giejay.mediaslider.config.EdgeFill
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.view.ExoPlayerListener
import nl.giejay.mediaslider.view.ExoPlayerView
import nl.giejay.mediaslider.view.FitCalculator
import nl.giejay.mediaslider.view.FitResult
import nl.giejay.mediaslider.view.TouchImageView
import timber.log.Timber
import kotlin.math.abs


class ScreenSlidePagerAdapter(private val context: Context,
                              private var items: List<SliderItemViewHolder>,
                              private val config: MediaSliderConfiguration,
                              private val currentIndex: () -> Int,
                              private val exoPlayerListener: ExoPlayerListener) : PagerAdapter() {
    private companion object {
        /** Relative aspect ratio difference below which metadata and decoded size count as equal. */
        const val ASPECT_TOLERANCE = 0.01
        val IMAGE_VIEW_IDS = intArrayOf(R.id.mBigImage, R.id.left_image, R.id.right_image,
            R.id.edge_main, R.id.edge_left, R.id.edge_right)
    }

    private val progressBars: MutableMap<Int, ProgressBar> = HashMap()
    private val failedPositions = mutableSetOf<String>()

    /** Called with a page root right before it is removed, so motion state referring to its views can be dropped. */
    var onPageDestroyed: ((View) -> Unit)? = null

    /** Called with the page position when a photo's real size changed its layout after it was shown (running motion is stale). */
    var onFitChanged: ((Int) -> Unit)? = null

    fun setItems(items: List<SliderItemViewHolder>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun hideProgressBar(position: Int) {
        val progressBar = progressBars[position]
        if (progressBar != null) {
            progressBar.visibility = GONE
            progressBars.remove(position)
        }
    }

    override fun getItemPosition(`object`: Any): Int {
        return POSITION_NONE
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        var view: View? = null
        val model = items[position]
        if (model.type == SliderItemType.IMAGE) {
            if (model.hasSecondaryItem()) {
                view = inflater.inflate(R.layout.image_double_item, container, false)
                val (slotW, slotH) = slotSize(container, true)
                val gap = view.findViewById<View>(R.id.pair_gap)
                gap.layoutParams = gap.layoutParams.apply { width = FitCalculator.pairGap(slotW * 2) }
                loadImageIntoView(view, R.id.edge_left, R.id.left_image, position, model.mainItem, slotW, slotH)
                loadImageIntoView(view, R.id.edge_right, R.id.right_image, position, model.secondaryItem!!, slotW, slotH)
            } else {
                view = inflater.inflate(R.layout.image_item, container, false)
                val (slotW, slotH) = slotSize(container, false)
                loadImageIntoView(view, R.id.edge_main, R.id.mBigImage, position, model.mainItem, slotW, slotH)
            }
        } else if (model.type == SliderItemType.VIDEO) {
            // Use texture view for vertical videos OR if this position previously failed with SurfaceView
            val useTextureView = model.mainItem.orientation != 1 || failedPositions.contains(model.url)
            view = ExoPlayerView(context, if (useTextureView) R.layout.video_item_texture_view else R.layout.video_item)
            view.setupPlayer(config, AmlogicSafeRenderersFactory(context), exoPlayerListener) { player, error ->
                val shouldRetry = !useTextureView && !failedPositions.contains(model.url)
                Timber.e(error,
                    "Player error at position $position for url ${model.url}. Already failed: ${failedPositions.contains(model.url)}." +
                            "Should retry: $shouldRetry. " +
                            "Current index: ${currentIndex()}. " +
                            "Did use texture view: ${useTextureView}. Error: ${error.message}")

                if (shouldRetry) {
                    model.url?.let { failedPositions.add(it) }
                    Timber.w("MediaCodec error at position $position, retrying with TextureView")
                    // Release the current player to avoid memory leaks
                    player.release()
                    // Trigger recreation of this item
                    notifyDataSetChanged()
                    true
                } else {
                    Toast.makeText(context, "Cannot play video: ${error.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
        view?.tag = "view$position"
        container.addView(view)
        return view!!
    }

    /**
     * Size in pixels of one image slot on a page of [pager]: the whole page, or one half of it (minus
     * the gap) for a portrait pair. Falls back to the display size while the pager has not been
     * measured yet, which is the case for the first pages. Use the same values when preloading.
     */
    fun slotSize(pager: View, paired: Boolean): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val pageW = (pager.width - pager.paddingLeft - pager.paddingRight).takeIf { it > 0 }
            ?: (pager.measuredWidth - pager.paddingLeft - pager.paddingRight).takeIf { it > 0 }
            ?: metrics.widthPixels
        val pageH = (pager.height - pager.paddingTop - pager.paddingBottom).takeIf { it > 0 }
            ?: (pager.measuredHeight - pager.paddingTop - pager.paddingBottom).takeIf { it > 0 }
            ?: metrics.heightPixels
        return FitCalculator.slotWidth(pageW, paired) to pageH
    }

    /**
     * Binds one photo to its slot.
     *
     * Page structure (also for the next agent's motion code): the clipping slot is the parent of the mover (ids slot_main/slot_left/slot_right),
     * `moverId` (a [TouchImageView]) is the "mover" laid out at the [FitResult]'s displayW x displayH,
     * centered in the slot (larger than the slot when the photo covers it, so the cropped parts still
     * exist and can be revealed by translating the mover). The current [FitResult] sits in the tag
     * [R.id.slider_fit_result] of the mover; it is absent while the photo size is still unknown.
     * `edgeId` is the blurred backdrop behind a fitted photo.
     */
    private fun loadImageIntoView(imageRootLayout: View,
                                  edgeId: Int,
                                  moverId: Int,
                                  position: Int,
                                  model: SliderItem,
                                  slotW: Int,
                                  slotH: Int) {
        val mover = imageRootLayout.findViewById<TouchImageView>(moverId)
        val edge = imageRootLayout.findViewById<ImageView>(edgeId)
        val progressBar = imageRootLayout.findViewById<ProgressBar>(R.id.mProgressBar)
        if (progressBar != null) {
            progressBars[position] = progressBar
        }
        var imageW = model.width ?: 0
        var imageH = model.height ?: 0
        var edgeRequested = false

        fun applyFit() {
            val fit = FitCalculator.compute(imageW, imageH, slotW, slotH, config.display)
            mover.setTag(R.id.slider_fit_result, fit)
            // A covering mover must fill its own bounds even if the real aspect differs slightly from the
            // metadata (no sliver at an edge); a fitted one shows the whole photo.
            mover.scaleType = if (fit.fills) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            val lp = mover.layoutParams as FrameLayout.LayoutParams
            if (lp.width != fit.displayW || lp.height != fit.displayH) {
                lp.width = fit.displayW
                lp.height = fit.displayH
                lp.gravity = Gravity.CENTER
                mover.layoutParams = lp
            }
            if (!fit.fills && config.display.edgeFill == EdgeFill.BLUR && model.thumbnailUrl != null) {
                edge.visibility = View.VISIBLE
                if (!edgeRequested) {
                    edgeRequested = true
                    SliderImageRequests.blurredBackdrop(context, model.thumbnailUrl).into(edge)
                }
            } else {
                edge.visibility = View.GONE
            }
        }
        if (imageW > 0 && imageH > 0) {
            applyFit()
        }

        SliderImageRequests.request(context, config, model, slotW, slotH)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?,
                                          model: Any?,
                                          target: com.bumptech.glide.request.target.Target<Drawable>,
                                          isFirstResource: Boolean): Boolean {
                    Timber.e(e, "Could not fetch image: %s", model)
                    hideProgressBar(position)
                    return false
                }

                override fun onResourceReady(resource: Drawable,
                                             model: Any,
                                             target: com.bumptech.glide.request.target.Target<Drawable>,
                                             dataSource: com.bumptech.glide.load.DataSource,
                                             isFirstResource: Boolean): Boolean {
                    // Runs before the drawable is set on the view, so the new layout and the new
                    // drawable are applied in the same pass. Metadata can be missing or wrong (edited
                    // photos), so the real aspect ratio wins when it differs.
                    val rw = resource.intrinsicWidth
                    val rh = resource.intrinsicHeight
                    if (rw > 0 && rh > 0) {
                        val known = imageW > 0 && imageH > 0
                        val differs = known && abs((rw.toDouble() / rh) / (imageW.toDouble() / imageH) - 1.0) > ASPECT_TOLERANCE
                        if (!known || differs) {
                            imageW = rw
                            imageH = rh
                            applyFit()
                            // The mover is re-laid out; a motion planned for the old size is stale.
                            onFitChanged?.invoke(position)
                        }
                    }
                    hideProgressBar(position)
                    return false
                }

            })
            .into(mover)
    }

    override fun getCount(): Int {
        return items.size
    }

    override fun isViewFromObject(view: View, o: Any): Boolean {
        return (view === o)
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        val view = `object` as View
        onPageDestroyed?.invoke(view)
        if (view is ExoPlayerView) {
            view.releasePlayer()
        } else {
            IMAGE_VIEW_IDS.forEach { id ->
                view.findViewById<View>(id)?.let { Glide.with(context).clear(it) }
            }
        }
        container.removeView(view)
    }
}