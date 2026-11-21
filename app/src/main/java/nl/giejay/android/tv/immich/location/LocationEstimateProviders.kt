package nl.giejay.android.tv.immich.location

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.ESTIMATE_CAMERA_LOCATIONS
import nl.giejay.android.tv.immich.shared.prefs.ESTIMATE_LOCATIONS_WITH_PARTNERS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.util.AssetMetaDataMapping
import nl.giejay.android.tv.immich.shared.util.fetchAssetDetailDetached
import nl.giejay.mediaslider.model.MapLocation
import nl.giejay.mediaslider.model.MapLocationProvider
import nl.giejay.mediaslider.model.MetaDataProvider
import nl.giejay.mediaslider.model.MetaDataType
import timber.log.Timber

/** Reads the location estimate settings (shared by the viewer slideshow and the screensaver). */
object LocationEstimatePrefs {
    fun current(): LocationEstimateSettings = LocationEstimateSettings(
        enabled = PreferenceManager.get(ESTIMATE_CAMERA_LOCATIONS),
        includePartners = PreferenceManager.get(ESTIMATE_LOCATIONS_WITH_PARTNERS)
    )
}

/**
 * The one location source of a slide: the measured GPS position when the asset has one (inline, or in its
 * loaded detail for timeline/bucket assets without EXIF), otherwise an estimate from photos with GPS taken
 * before and after it. Only asked when the location map is shown.
 */
class AssetMapLocationProvider(private val asset: Asset) : MapLocationProvider {
    override suspend fun getMapLocation(): MapLocation? {
        // Fast path: coordinates already on the asset, no network call.
        AssetMetaDataMapping.geoLocationOf(asset)?.let { return MapLocation(it) }
        // Detached: the map cancels this lookup on every slide change, which must not fail other waiters.
        fetchAssetDetailDetached(asset.id).fold(
            { error -> Timber.w("Failed to load asset ${asset.id} for location: $error") },
            { detail -> AssetMetaDataMapping.geoLocationOf(detail)?.let { return MapLocation(it) } }
        )
        return LocationEstimateService.estimate(asset)?.toMapLocation()
    }
}

/**
 * City or country for the metadata block. Uses the asset's own value when it has one (loading details for
 * partial assets); for assets without GPS it falls back to the estimate: "ca. Innsbruck",
 * "zwischen Garmisch-Partenkirchen und Innsbruck" or "Raum Innsbruck".
 */
class EstimatedPlaceMetaDataProvider(
    private val asset: Asset,
    private val field: MetaDataType
) : MetaDataProvider {

    override suspend fun getValue(): String? {
        val detail = fetchAssetDetailDetached(asset.id).fold(
            { error ->
                Timber.w("Failed to load asset ${asset.id} for field $field: $error")
                null
            },
            { it }
        )
        detail?.let { AssetMetaDataMapping.valueOf(it, field) }?.let { return it }
        // An asset with its own GPS but no place name keeps showing nothing rather than a guess.
        if (detail != null && AssetMetaDataMapping.geoLocationOf(detail) != null) return null
        val estimate = LocationEstimateService.estimate(detail ?: asset) ?: return null
        return when (field) {
            MetaDataType.CITY -> {
                val context = ImmichApplication.appContext ?: return null
                estimate.placeLabel(
                    near = { context.getString(R.string.estimated_place_near, it) },
                    between = { from, to -> context.getString(R.string.estimated_place_between, from, to) },
                    area = { context.getString(R.string.estimated_place_area, it) },
                    countryName = { AssetMetaDataMapping.displayCountry(it) }
                )
            }
            MetaDataType.COUNTRY -> estimate.country?.let { AssetMetaDataMapping.displayCountry(it) }
            else -> null
        }
    }
}
