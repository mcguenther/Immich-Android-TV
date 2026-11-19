package nl.giejay.android.tv.immich.shared.util

import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.prefs.PortraitPairing
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_LOAD_EDITED_PHOTO
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Turns assets into slider pages, pairing portrait photos per [options].
 *
 * @param keepOrder keep the given order (albums, folders, timeline) instead of shuffling
 * @param keepAssetId an asset the user explicitly opened; it is never filtered out
 */
fun List<Asset>.toSliderItems(keepOrder: Boolean,
                              options: SliderItemOptions,
                              keepAssetId: String? = null): List<SliderItemViewHolder> {
    // TODO(pairing rework): apply options.pairing rules and options.showPortraitVideos/keepAssetId.
    val mergePortrait = options.pairing != PortraitPairing.OFF
    if (!mergePortrait) {
        return this.map { SliderItemViewHolder(it.toSliderItem()) }
    }
    if (!keepOrder) {
        val portraits = this.filter { it.isPortraitImage() }
        val queue = portraits.toMutableList()
        val portraitSliders = mutableListOf<SliderItemViewHolder>()

        while (queue.isNotEmpty()) {
            val first = queue.removeAt(0)
            val firstDate = first.fileModifiedAt?.time

            // pick the second portrait image that is most furthest away in time to the first one
            val second = if (first.people?.isNotEmpty() == true && firstDate != null) {
                // Try strict match: same people size and city
                queue.filter { candidate ->
                    candidate.people?.size == first.people.size &&
                            candidate.people.firstOrNull()?.id == first.people.first().id &&
                            candidate.exifInfo?.city != null && first.exifInfo?.city != null &&
                            candidate.exifInfo.city == first.exifInfo.city &&
                            candidate.exifInfo.dateTimeOriginal != null
                }.maxByOrNull { candidate ->
                    kotlin.math.abs(candidate.exifInfo!!.dateTimeOriginal!!.time - firstDate)
                } ?: // Fallback: only same people id
                queue.filter { candidate ->
                    candidate.people?.firstOrNull()?.id == first.people.first().id &&
                            candidate.exifInfo?.dateTimeOriginal != null
                }.maxByOrNull { candidate ->
                    kotlin.math.abs(candidate.exifInfo!!.dateTimeOriginal!!.time - firstDate)
                }
            } else null // no people or date info, or simply no match with the first, just add a random one next to it

            if (second != null) {
                portraitSliders.add(SliderItemViewHolder(first.toSliderItem(), second.toSliderItem()))
                queue.remove(second)
            } else {
                portraitSliders.add(SliderItemViewHolder(first.toSliderItem(), queue.removeFirstOrNull()?.toSliderItem()))
            }
        }

        val landscapeItems = this - portraits.toSet()
        return (landscapeItems.map { SliderItemViewHolder(it.toSliderItem()) } + portraitSliders).shuffled()
    }

    val queue = this.toMutableList()
    val items = mutableListOf<SliderItemViewHolder>()

    while (queue.isNotEmpty()) {
        val first = queue.removeAt(0)
        val second = queue.firstOrNull()

        if (first.isPortraitImage() && second?.isPortraitImage() == true) {
            items.add(SliderItemViewHolder(first.toSliderItem(), second.toSliderItem()))
            queue.remove(second)
        } else {
            items.add(SliderItemViewHolder(first.toSliderItem()))
        }
    }
    return items
}

fun Asset.toSliderItem(): SliderItem {
    val itemType = SliderItemType.valueOf(this.type.uppercase())
    // EXIF width/height are stored before rotation; orientations 5..8 are rotated by 90 degrees.
    val exifW = this.exifInfo?.exifImageWidth?.takeIf { it > 0 }
    val exifH = this.exifInfo?.exifImageHeight?.takeIf { it > 0 }
    val swap = (this.exifInfo?.orientation ?: 1) in 5..8
    return SliderItem(
        this.id,
        ApiUtil.getFileUrl(
            this.id,
            this.type,
            PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
            PreferenceManager.get(SLIDER_LOAD_EDITED_PHOTO)
        ),
        itemType,
        this.exifInfo?.orientation ?: if (itemType == SliderItemType.IMAGE) 1 else 6,
        AssetMetaDataMapping.providersFor(this),
        ApiUtil.getThumbnailUrl(this.id, "preview", PreferenceManager.get(SLIDER_LOAD_EDITED_PHOTO)),
        isPanorama = this.isPanoramaImage(),
        isFavorite = this.isFavorite,
        width = if (swap) exifH else exifW,
        height = if (swap) exifW else exifH
    )
}

internal fun formatAssetDate(date: Date): String {
    val calendar = Calendar.getInstance()
    calendar.time = date
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val isEnglish = locale.language == "en"
    val day = calendar[Calendar.DATE]
    val formatString = if (isEnglish) {
        // English: Friday, 7th April 2006
        when (day) {
            1, 21, 31 -> "EEEE, d'st' MMMM yyyy"
            2, 22 -> "EEEE, d'nd' MMMM yyyy"
            3, 23 -> "EEEE, d'rd' MMMM yyyy"
            else -> "EEEE, d'th' MMMM yyyy"
        }
    } else {
        // All other locales: Freitag, 7. April 2006
        "EEEE, d. MMMM yyyy"
    }
    return SimpleDateFormat(formatString, locale).format(date)
}

internal fun formatAssetTime(date: Date): String {
    val calendar = Calendar.getInstance()
    calendar.time = date
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val formatString = "H:mm:ss"
    return SimpleDateFormat(formatString, locale).format(date)
}

fun Asset.isPortraitImage(): Boolean {
    val aspectRatio = this.getAspectRatio()
    return (this.exifInfo?.orientation == 6 || this.exifInfo?.orientation == 8 || (aspectRatio != null && aspectRatio > 0.56 && aspectRatio <= 1.1)) && this.type == SliderItemType.IMAGE.toString()
}

fun Asset.isPanoramaImage(): Boolean {
    val aspectRatio = this.getAspectRatio()
    return (aspectRatio != null && (aspectRatio <= 0.56 || aspectRatio > 2.0)) && this.type == SliderItemType.IMAGE.toString()
}

fun Asset.getAspectRatio(): Double? {
    return if(this.exifInfo != null && this.exifInfo.exifImageHeight != null && this.exifInfo.exifImageWidth != null && this.exifInfo.exifImageHeight > 0)
        this.exifInfo.exifImageWidth.toDouble() / this.exifInfo.exifImageHeight.toDouble()
    else
        null
}

fun List<Asset>.toCards(): List<Card> {
    return this.map {
        it.toCard()
    }
}

fun Asset.toCard(): Card {
    return Card(this.originalFileName ?: this.deviceAssetId ?: "",
        this.exifInfo?.description ?: "",
        this.id,
        ApiUtil.getThumbnailUrl(this.id, "thumbnail"),
        ApiUtil.getThumbnailUrl(this.id, "preview"))
}
