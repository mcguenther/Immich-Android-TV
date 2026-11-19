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
import kotlin.math.abs
import kotlin.random.Random

/**
 * Turns assets into slider pages, pairing portrait photos per [options].
 *
 * @param keepOrder keep the given order (albums, folders, timeline) instead of shuffling
 * @param keepAssetId an asset the user explicitly opened; it is never filtered out
 * @param random source of the shuffle used when [keepOrder] is false and pairing is enabled
 */
fun List<Asset>.toSliderItems(keepOrder: Boolean,
                              options: SliderItemOptions,
                              keepAssetId: String? = null,
                              random: Random = Random.Default): List<SliderItemViewHolder> {
    val groups = groupForSlider(this, keepOrder, options, keepAssetId)
    val shuffle = !keepOrder && options.pairing != PortraitPairing.OFF
    return (if (shuffle) groups.shuffled(random) else groups).map { group ->
        if (group.size == 2) {
            SliderItemViewHolder(group[0].toSliderItem(), group[1].toSliderItem())
        } else {
            SliderItemViewHolder(group[0].toSliderItem())
        }
    }
}

/**
 * Groups [assets] into slider pages: one asset alone, or two portrait photos side by side.
 * Pure function without Android dependencies, so the pairing rules are unit tested.
 *
 * Portrait videos are dropped unless [options] shows them or the asset is [keepAssetId].
 * With [keepOrder] only directly following assets are paired. Without it, each portrait photo
 * is paired with the matching remaining portrait photo closest in capture time; the groups are
 * returned unshuffled and [toSliderItems] shuffles them.
 */
internal fun groupForSlider(assets: List<Asset>,
                            keepOrder: Boolean,
                            options: SliderItemOptions,
                            keepAssetId: String?): List<List<Asset>> {
    val visible = assets.filter { options.showPortraitVideos || it.id == keepAssetId || !it.isPortraitVideo() }
    val pairing = options.pairing
    if (pairing == PortraitPairing.OFF) {
        return visible.map { listOf(it) }
    }
    if (keepOrder) {
        val groups = mutableListOf<List<Asset>>()
        var index = 0
        while (index < visible.size) {
            val first = visible[index]
            val second = visible.getOrNull(index + 1)
            if (second != null && first.canPairWith(second, pairing)) {
                groups.add(listOf(first, second))
                index += 2
            } else {
                groups.add(listOf(first))
                index += 1
            }
        }
        return groups
    }
    val others = visible.filter { !it.isPortraitImage() }
    val queue = visible.filter { it.isPortraitImage() }.toMutableList()
    val portraitGroups = mutableListOf<List<Asset>>()
    while (queue.isNotEmpty()) {
        val first = queue.removeAt(0)
        val partnerIndex = queue.indices
            .filter { first.canPairWith(queue[it], pairing) }
            .minByOrNull { captureDistance(first, queue[it]) }
        if (partnerIndex != null) {
            portraitGroups.add(listOf(first, queue.removeAt(partnerIndex)))
        } else {
            portraitGroups.add(listOf(first))
        }
    }
    return others.map { listOf(it) } + portraitGroups
}

/** True when the asset is a video whose displayed orientation (EXIF rotation 5-8 swaps width/height) is portrait. */
fun Asset.isPortraitVideo(): Boolean {
    if (this.type != SliderItemType.VIDEO.toString()) return false
    val width = this.exifInfo?.exifImageWidth ?: return false
    val height = this.exifInfo.exifImageHeight ?: return false
    if (width <= 0 || height <= 0) return false
    val rotated = this.exifInfo.orientation in listOf(5, 6, 7, 8)
    val displayWidth = if (rotated) height else width
    val displayHeight = if (rotated) width else height
    return displayHeight > displayWidth
}

/** Two portrait photos may share a page only if both are portrait photos and they match the pairing mode. */
private fun Asset.canPairWith(other: Asset, pairing: PortraitPairing): Boolean {
    if (!this.isPortraitImage() || !other.isPortraitImage()) return false
    return when (pairing) {
        PortraitPairing.OFF -> false
        PortraitPairing.SAME_DAY -> isSameLocalDay(this, other)
        PortraitPairing.SAME_DAY_OR_PLACE -> isSameLocalDay(this, other) || isSamePlace(this, other)
    }
}

/** Capture time: EXIF date taken, falling back to the file creation date. */
private fun Asset.captureTime(): Date? = this.exifInfo?.dateTimeOriginal ?: this.fileCreatedAt

private fun isSameLocalDay(a: Asset, b: Asset): Boolean {
    val timeA = a.captureTime() ?: return false
    val timeB = b.captureTime() ?: return false
    val calendarA = Calendar.getInstance().apply { time = timeA }
    val calendarB = Calendar.getInstance().apply { time = timeB }
    return calendarA.get(Calendar.YEAR) == calendarB.get(Calendar.YEAR) &&
            calendarA.get(Calendar.DAY_OF_YEAR) == calendarB.get(Calendar.DAY_OF_YEAR)
}

private fun isSamePlace(a: Asset, b: Asset): Boolean {
    val cityA = a.exifInfo?.city?.normalizedPlace() ?: return false
    val cityB = b.exifInfo?.city?.normalizedPlace() ?: return false
    if (cityA != cityB) return false
    val countryA = a.exifInfo?.country?.normalizedPlace()
    val countryB = b.exifInfo?.country?.normalizedPlace()
    return countryA == null || countryB == null || countryA == countryB
}

private fun String.normalizedPlace(): String? = this.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

/** Distance in milliseconds between capture times; assets without a time sort last. */
private fun captureDistance(a: Asset, b: Asset): Long {
    val timeA = a.captureTime()?.time ?: return Long.MAX_VALUE
    val timeB = b.captureTime()?.time ?: return Long.MAX_VALUE
    return abs(timeA - timeB)
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
