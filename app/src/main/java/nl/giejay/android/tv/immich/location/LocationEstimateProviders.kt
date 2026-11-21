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

/** Map position for an asset without GPS, estimated from photos with GPS before and after it. */
class EstimatedMapLocationProvider(private val asset: Asset) : MapLocationProvider {
    override suspend fun getMapLocation(): MapLocation? =
        LocationEstimateService.estimate(asset)?.toMapLocation()
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
