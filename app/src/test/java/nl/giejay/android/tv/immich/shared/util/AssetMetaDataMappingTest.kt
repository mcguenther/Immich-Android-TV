package nl.giejay.android.tv.immich.shared.util

import arrow.core.right
import kotlinx.coroutines.runBlocking
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.StaticMetaDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class AssetMetaDataMappingTest {

    @After
    fun tearDown() {
        AssetDetailCache.clear()
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
    fun `geo location is static when inline and fetched lazily otherwise`() = runBlocking {
        var fetches = 0
        AssetDetailCache.fetchAsset = { id ->
            fetches++
            assetWithCoordinates(id, 39.61, 3.28).right()
        }

        val inline = AssetMetaDataMapping.geoLocationProviderFor(assetWithCoordinates("inline", 47.56, 13.65))
        assertEquals(GeoLocation(47.56, 13.65), inline.getLocation())
        assertEquals(0, fetches)

        val lazy = AssetMetaDataMapping.geoLocationProviderFor(assetWithCoordinates("lazy", null, null))
        assertTrue(lazy is AssetDetailGeoLocationProvider)
        assertEquals(GeoLocation(39.61, 3.28), lazy.getLocation())
        assertEquals(1, fetches)
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
    fun `providersFor uses static when inline value exists else detail provider`() {
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
        assertTrue(providers[MetaDataType.COUNTRY] is AssetDetailMetaDataProvider)
        assertTrue(providers[MetaDataType.ALBUM_NAME] is AlbumMetaDataProvider)
    }
}
