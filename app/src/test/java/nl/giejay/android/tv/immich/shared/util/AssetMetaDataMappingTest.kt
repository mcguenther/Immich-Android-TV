package nl.giejay.android.tv.immich.shared.util

import arrow.core.right
import kotlinx.coroutines.runBlocking
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.android.tv.immich.location.EstimatedPlaceMetaDataProvider
import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.StaticMetaDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.Locale

class AssetMetaDataMappingTest {

    @After
    fun tearDown() {
        AssetDetailCache.clear()
        AssetMetaDataMapping.resetCountryNameLocale()
    }

    private fun assetWithCoordinates(id: String, latitude: Double?, longitude: Double?) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = AssetExifInfo(
            description = null,
            orientation = 1,
            exifImageWidth = null,
            exifImageHeight = null,
            city = null,
            country = null,
            dateTimeOriginal = null,
            make = null,
            model = null,
            latitude = latitude,
            longitude = longitude
        ),
        fileCreatedAt = null,
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null,
        isFavorite = false
    )

    @Test
    fun `geoLocationOf reads exif coordinates and rejects missing or bogus ones`() {
        assertEquals(GeoLocation(67.93, 13.03), AssetMetaDataMapping.geoLocationOf(assetWithCoordinates("1", 67.93, 13.03)))
        assertNull(AssetMetaDataMapping.geoLocationOf(assetWithCoordinates("2", null, 13.03)))
        assertNull(AssetMetaDataMapping.geoLocationOf(assetWithCoordinates("3", 0.0, 0.0)))
        assertNull(AssetMetaDataMapping.geoLocationOf(assetWithCoordinates("4", 95.0, 10.0)))
        assertNull(AssetMetaDataMapping.geoLocationOf(assetWithCoordinates("5", Double.NaN, 10.0)))
    }

    @Test
    fun `valueOf projects exif city and skips blank camera`() {
        val asset = Asset(
            id = "1",
            type = "IMAGE",
            deviceAssetId = null,
            exifInfo = AssetExifInfo(
                description = "desc",
                orientation = 1,
                exifImageWidth = 100,
                exifImageHeight = 200,
                city = "Oslo",
                country = "Norway",
                dateTimeOriginal = null,
                make = null,
                model = null
            ),
            fileCreatedAt = null,
            fileModifiedAt = null,
            albumName = "Trip",
            people = null,
            tags = null,
            originalPath = "/a",
            originalFileName = "a.jpg",
            isFavorite = false
        )

        assertEquals("Oslo", AssetMetaDataMapping.valueOf(asset, MetaDataType.CITY))
        assertEquals("Trip", AssetMetaDataMapping.valueOf(asset, MetaDataType.ALBUM_NAME))
        assertNull(AssetMetaDataMapping.valueOf(asset, MetaDataType.CAMERA))
    }

    @Test
    fun `providersFor uses static when inline value exists, estimate fallback for places, else detail provider`() {
        val asset = Asset(
            id = "2",
            type = "IMAGE",
            deviceAssetId = null,
            exifInfo = AssetExifInfo(
                description = null,
                orientation = 1,
                exifImageWidth = null,
                exifImageHeight = null,
                city = "Bergen",
                country = null,
                dateTimeOriginal = null,
                make = null,
                model = null
            ),
            fileCreatedAt = null,
            fileModifiedAt = null,
            albumName = null,
            people = null,
            tags = null,
            originalPath = null,
            originalFileName = null,
            isFavorite = false
        )

        val providers = AssetMetaDataMapping.providersFor(asset)
        assertTrue(providers[MetaDataType.CITY] is StaticMetaDataProvider)
        assertTrue(providers[MetaDataType.COUNTRY] is EstimatedPlaceMetaDataProvider)
        assertTrue(providers[MetaDataType.DESCRIPTION] is AssetDetailMetaDataProvider)
        assertTrue(providers[MetaDataType.ALBUM_NAME] is AlbumMetaDataProvider)
    }

    private fun assetInCountry(country: String?) = Asset(
        id = "c",
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = AssetExifInfo(
            description = null,
            orientation = 1,
            exifImageWidth = null,
            exifImageHeight = null,
            city = "Oslo",
            country = country,
            dateTimeOriginal = null,
            make = null,
            model = null
        ),
        fileCreatedAt = null,
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null,
        isFavorite = false
    )

    @Test
    fun `country is translated to the configured language`() {
        AssetMetaDataMapping.countryNameLocale = { Locale.GERMAN }
        assertEquals("Norwegen", AssetMetaDataMapping.valueOf(assetInCountry("Norway"), MetaDataType.COUNTRY))
        // city stays as Immich provides it
        assertEquals("Oslo", AssetMetaDataMapping.valueOf(assetInCountry("Norway"), MetaDataType.CITY))
        assertNull(AssetMetaDataMapping.valueOf(assetInCountry(null), MetaDataType.COUNTRY))
    }

    @Test
    fun `country keeps the Immich value when translation is off or unavailable`() {
        AssetMetaDataMapping.countryNameLocale = { null }
        assertEquals("Norway", AssetMetaDataMapping.valueOf(assetInCountry("Norway"), MetaDataType.COUNTRY))

        // default reads PreferenceManager, which is not initialised in plain JVM tests
        AssetMetaDataMapping.resetCountryNameLocale()
        assertEquals("Norway", AssetMetaDataMapping.valueOf(assetInCountry("Norway"), MetaDataType.COUNTRY))
    }
}
