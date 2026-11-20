package nl.giejay.android.tv.immich.shared.util

import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.GeoLocationProvider
import nl.giejay.mediaslider.model.MetaDataProvider
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.StaticMetaDataProvider

/**
 * Single place to map an [Asset] onto slider [MetaDataType] values / providers.
 * Add new metadata fields here — not in [Asset.toSliderItem] and not in
 * [AssetDetailMetaDataProvider].
 */
object AssetMetaDataMapping {

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
        MetaDataType.COUNTRY to { it.exifInfo?.country },
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
     * Build providers for [Asset.toSliderItem]: static when [valueOf] is known, else lazy
     * detail fetch ([AssetDetailMetaDataProvider]) or album list lookup.
     */
    fun providersFor(asset: Asset): Map<MetaDataType, MetaDataProvider> =
        MAPPINGS.keys.associateWith { field ->
            val inline = valueOf(asset, field)
            when {
                inline != null -> StaticMetaDataProvider(inline)
                field == MetaDataType.ALBUM_NAME -> AlbumMetaDataProvider(asset.id)
                field == MetaDataType.CURRENT_DATE -> DateMetaDataProvider()
                else -> AssetDetailMetaDataProvider(asset.id, field)
            }
        }
}
