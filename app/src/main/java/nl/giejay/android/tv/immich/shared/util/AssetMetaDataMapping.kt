package nl.giejay.android.tv.immich.shared.util

import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.location.EstimatedMapLocationProvider
import nl.giejay.android.tv.immich.location.EstimatedPlaceMetaDataProvider
import nl.giejay.android.tv.immich.shared.prefs.COUNTRY_NAME_LANGUAGE
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.GeoLocationProvider
import nl.giejay.mediaslider.model.MapLocationProvider
import nl.giejay.mediaslider.model.MetaDataProvider
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.StaticMetaDataProvider
import java.util.Locale

/**
 * Single place to map an [Asset] onto slider [MetaDataType] values / providers.
 * Add new metadata fields here — not in [Asset.toSliderItem] and not in
 * [AssetDetailMetaDataProvider].
 */
object AssetMetaDataMapping {

    private val defaultCountryNameLocale: () -> Locale? = {
        // never let an unreadable preference break the metadata overlay
        runCatching { PreferenceManager.get(COUNTRY_NAME_LANGUAGE).targetLocale() }.getOrNull()
    }

    /**
     * Target language for [MetaDataType.COUNTRY] (null = keep the Immich value).
     * Test seam — production reads [COUNTRY_NAME_LANGUAGE].
     */
    @Volatile
    var countryNameLocale: () -> Locale? = defaultCountryNameLocale

    /** [country] as Immich returns it (English), translated into the chosen language when one is set. */
    fun displayCountry(country: String): String =
        countryNameLocale()?.let { CountryNameTranslator.translate(country, it) } ?: country

    /** Test helper. */
    fun resetCountryNameLocale() {
        countryNameLocale = defaultCountryNameLocale
    }

    /**
     * Maps each supported [MetaDataType] to a lambda that extracts its string value
     * from an [Asset]. This defines both the available fields and their order.
     */
    private val MAPPINGS: Map<MetaDataType, (Asset) -> String?> = mapOf(
        MetaDataType.DATE to { asset ->
            val date = asset.exifInfo?.dateTimeOriginal
                ?: asset.fileCreatedAt
                ?: asset.fileModifiedAt
            date?.let { formatAssetDate(it) }
        },
        MetaDataType.CITY to { it.exifInfo?.city },
        MetaDataType.COUNTRY to { asset ->
            asset.exifInfo?.country?.let { country -> displayCountry(country) }
        },
        MetaDataType.DESCRIPTION to { it.exifInfo?.description },
        MetaDataType.FILENAME to { it.originalFileName },
        MetaDataType.PEOPLE to { asset ->
            asset.people
                ?.mapNotNull { it.name }
                ?.filter { it.isNotBlank() }
                ?.joinToString(", ")
                ?.ifBlank { null }
        },
        MetaDataType.FILEPATH to { it.originalPath },
        MetaDataType.CAMERA to { asset ->
            listOfNotNull(asset.exifInfo?.make, asset.exifInfo?.model)
                .joinToString(" ")
                .ifBlank { null }
        },
        MetaDataType.ALBUM_NAME to { it.albumName?.ifBlank { null } },
        MetaDataType.TIME to {asset ->
            val date = asset.exifInfo?.dateTimeOriginal
                ?: asset.fileCreatedAt
                ?: asset.fileModifiedAt
            date?.let { formatAssetTime(it) }
        },
        MetaDataType.CURRENT_DATE to { null },
    )

    /**
     * Project a loaded/partial [Asset] to a display string for [field], without network.
     * Returns null when the asset does not carry that field yet.
     */
    fun valueOf(asset: Asset, field: MetaDataType): String? =
        MAPPINGS[field]?.invoke(asset)

    /**
     * GPS coordinates from [Asset.exifInfo], or null when missing, out of range, or exactly 0/0
     * (some cameras write 0/0 when they have no fix).
     */
    fun geoLocationOf(asset: Asset): GeoLocation? {
        val latitude = asset.exifInfo?.latitude ?: return null
        val longitude = asset.exifInfo.longitude ?: return null
        if (latitude.isNaN() || longitude.isNaN()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return GeoLocation(latitude, longitude)
    }

    /**
     * Static when the asset already carries coordinates, otherwise a lazy detail fetch
     * (timeline/bucket assets have no EXIF). Only called when the location map is shown.
     */
    fun geoLocationProviderFor(asset: Asset): GeoLocationProvider =
        geoLocationOf(asset)?.let { location -> GeoLocationProvider { location } }
            ?: AssetDetailGeoLocationProvider(asset.id)

    /**
     * Estimated map position for assets without GPS (e.g. system camera photos), from photos with GPS taken
     * before and after. Only asked when the asset has no coordinates of its own and the map is shown.
     */
    fun locationEstimateProviderFor(asset: Asset): MapLocationProvider = EstimatedMapLocationProvider(asset)

    /**
     * Build providers for [Asset.toSliderItem]: static when [valueOf] is known, else lazy
     * detail fetch ([AssetDetailMetaDataProvider]) or album list lookup. A missing city or country
     * falls back to the location estimate ([EstimatedPlaceMetaDataProvider]).
     */
    fun providersFor(asset: Asset): Map<MetaDataType, MetaDataProvider> =
        MAPPINGS.keys.associateWith { field ->
            val inline = valueOf(asset, field)
            when {
                inline != null -> StaticMetaDataProvider(inline)
                field == MetaDataType.ALBUM_NAME -> AlbumMetaDataProvider(asset.id)
                field == MetaDataType.CURRENT_DATE -> DateMetaDataProvider()
                field == MetaDataType.CITY || field == MetaDataType.COUNTRY -> EstimatedPlaceMetaDataProvider(asset, field)
                else -> AssetDetailMetaDataProvider(asset.id, field)
            }
        }
}
