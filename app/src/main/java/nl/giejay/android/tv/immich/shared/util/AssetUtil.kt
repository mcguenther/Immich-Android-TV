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
    return (if (shuffle) groups.shuffled(random) else groups).toSliderPages()
}

/** Turns groups of one asset, or two portrait photos side by side, into slider pages. */
fun List<List<Asset>>.toSliderPages(): List<SliderItemViewHolder> = map { group ->
    if (group.size == 2) {
        SliderItemViewHolder(group[0].toSliderItem(), group[1].toSliderItem())
    } else {
        SliderItemViewHolder(group[0].toSliderItem())
    }
}

/**
 * The candidate that may share a page with [portrait] under [pairing] and is closest to it in
 * capture time; on a tie the earlier one. Only candidates passing [accept] are considered.
 * Null when [portrait] is no portrait photo or no candidate matches.
 */
internal fun findClosestPartner(portrait: Asset,
                                candidates: List<Asset>,
                                pairing: PortraitPairing,
                                accept: (Asset) -> Boolean = { true }): Asset? {
    val keys = pairKeys(listOf(portrait) + candidates.filter { it.id != portrait.id && accept(it) })
    val first = keys.first()
    return keys.drop(1)
        .filter { first.canPairWith(it, pairing) }
        .minByOrNull { first.captureDistanceTo(it) }
        ?.asset
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
    val keys = pairKeys(visible)
    if (keepOrder) {
        val groups = mutableListOf<List<Asset>>()
        var index = 0
        while (index < keys.size) {
            val first = keys[index]
            val second = keys.getOrNull(index + 1)
            if (second != null && first.canPairWith(second, pairing)) {
                groups.add(listOf(first.asset, second.asset))
                index += 2
            } else {
                groups.add(listOf(first.asset))
                index += 1
            }
        }
        return groups
    }
    val others = keys.filter { !it.portrait }.map { listOf(it.asset) }
    return others + pairPortraits(keys.filter { it.portrait }, pairing)
}

/**
 * Pairing facts of one asset, computed once per [groupForSlider] call so the pairing never parses
 * dates or place names again.
 *
 * @param portrait the asset is a portrait photo (only those can be paired)
 * @param timeMs capture time in milliseconds, null without a capture time
 * @param day local calendar day as yyyymmdd, null without a capture time
 * @param city trimmed, lowercased city; null when missing or blank
 * @param country trimmed, lowercased country; null when missing or blank
 */
private class PairKey(
    val asset: Asset,
    val portrait: Boolean,
    val timeMs: Long?,
    val day: Int?,
    val city: String?,
    val country: String?
) {
    /** Two portrait photos may share a page only if both are portrait photos and they match the pairing mode. */
    fun canPairWith(other: PairKey, pairing: PortraitPairing): Boolean {
        if (!portrait || !other.portrait) return false
        return when (pairing) {
            PortraitPairing.OFF -> false
            PortraitPairing.SAME_DAY -> isSameDay(other)
            PortraitPairing.SAME_DAY_OR_PLACE -> isSameDay(other) || isSamePlace(other)
        }
    }

    private fun isSameDay(other: PairKey): Boolean = day != null && day == other.day

    /** Same city; a missing country on either side still allows the match. */
    private fun isSamePlace(other: PairKey): Boolean {
        if (city == null || city != other.city) return false
        return country == null || other.country == null || country == other.country
    }

    /** Distance in milliseconds between capture times; assets without a time sort last. */
    fun captureDistanceTo(other: PairKey): Long {
        val a = timeMs ?: return Long.MAX_VALUE
        val b = other.timeMs ?: return Long.MAX_VALUE
        return abs(a - b)
    }
}

/** Builds the [PairKey]s with one reused calendar in the device time zone. */
private fun pairKeys(assets: List<Asset>): List<PairKey> {
    val calendar = Calendar.getInstance()
    return assets.map { asset ->
        val time = asset.captureTime()
        val day = time?.let {
            calendar.time = it
            calendar.get(Calendar.YEAR) * 10_000 +
                    (calendar.get(Calendar.MONTH) + 1) * 100 +
                    calendar.get(Calendar.DAY_OF_MONTH)
        }
        PairKey(
            asset = asset,
            portrait = asset.isPortraitImage(),
            timeMs = time?.time,
            day = day,
            city = asset.exifInfo?.city?.normalizedPlace(),
            country = asset.exifInfo?.country?.normalizedPlace()
        )
    }
}

/**
 * Pairs portrait photos in list order: each remaining photo takes the matching remaining photo
 * closest in capture time (on a tie, the earlier one). Candidates come from a day bucket and a
 * city bucket, so no pass scans all remaining photos.
 */
private fun pairPortraits(portraits: List<PairKey>, pairing: PortraitPairing): List<List<Asset>> {
    val byDay = HashMap<Int, MutableList<Int>>()
    val byCity = HashMap<String, MutableList<Int>>()
    portraits.forEachIndexed { index, key ->
        key.day?.let { byDay.getOrPut(it) { ArrayList() }.add(index) }
        key.city?.let { byCity.getOrPut(it) { ArrayList() }.add(index) }
    }
    val removed = BooleanArray(portraits.size)
    val groups = ArrayList<List<Asset>>(portraits.size)
    for (index in portraits.indices) {
        if (removed[index]) continue
        removed[index] = true
        val first = portraits[index]
        var partner = -1
        first.day?.let { day ->
            byDay[day]?.let { partner = closestPartner(first, it, portraits, removed, pairing, partner) }
        }
        if (pairing == PortraitPairing.SAME_DAY_OR_PLACE) {
            first.city?.let { city ->
                byCity[city]?.let { partner = closestPartner(first, it, portraits, removed, pairing, partner) }
            }
        }
        if (partner >= 0) {
            removed[partner] = true
            groups.add(listOf(first.asset, portraits[partner].asset))
        } else {
            groups.add(listOf(first.asset))
        }
    }
    return groups
}

/**
 * Returns the index of the matching remaining portrait in [bucket] closest to [first], compared
 * with [current] (-1 for none) by distance and then by index. Removed entries are dropped from
 * [bucket] during the scan.
 */
private fun closestPartner(
    first: PairKey,
    bucket: MutableList<Int>,
    portraits: List<PairKey>,
    removed: BooleanArray,
    pairing: PortraitPairing,
    current: Int
): Int {
    var best = current
    var bestDistance = if (current >= 0) first.captureDistanceTo(portraits[current]) else Long.MAX_VALUE
    var kept = 0
    for (position in bucket.indices) {
        val candidate = bucket[position]
        if (removed[candidate]) continue
        bucket[kept++] = candidate
        val key = portraits[candidate]
        if (!first.canPairWith(key, pairing)) continue
        val distance = first.captureDistanceTo(key)
        if (best < 0 || distance < bestDistance || (distance == bestDistance && candidate < best)) {
            best = candidate
            bestDistance = distance
        }
    }
    bucket.subList(kept, bucket.size).clear()
    return best
}

/** Capture time: EXIF date taken, falling back to the file creation date. */
internal fun Asset.captureTime(): Date? = this.exifInfo?.dateTimeOriginal ?: this.fileCreatedAt

private fun String.normalizedPlace(): String? = this.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

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
        height = if (swap) exifW else exifH,
        durationMs = if (itemType == SliderItemType.VIDEO) parseImmichDurationMs(this.duration) else null,
        geoLocationProvider = AssetMetaDataMapping.geoLocationProviderFor(this),
        locationEstimateProvider = AssetMetaDataMapping.locationEstimateProviderFor(this)
    )
}

/** Parses Immich's "H:MM:SS.ffffff" duration into milliseconds; null when absent, malformed or zero. */
internal fun parseImmichDurationMs(duration: String?): Long? {
    val parts = duration?.trim()?.split(":") ?: return null
    if (parts.size != 3) return null
    val h = parts[0].toLongOrNull() ?: return null
    val m = parts[1].toLongOrNull() ?: return null
    val sec = parts[2].toDoubleOrNull() ?: return null
    val ms = h * 3_600_000L + m * 60_000L + Math.round(sec * 1000)
    return ms.takeIf { it > 0 }
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
