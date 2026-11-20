package nl.giejay.android.tv.immich.api.model

import java.util.Date

data class AssetExifInfo(
    val description: String?,
    val orientation: Int?,
    val exifImageWidth: Int?,
    val exifImageHeight: Int?,
    val city: String?,
    val country: String?,
    val dateTimeOriginal: Date?,
    val make: String?,
    val model: String?,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class Tag(
    val color: String?,
    val createdAt: Date,
    val name: String,
    val value: String
)

data class Asset(
    val id: String,
    val type: String,
    val deviceAssetId: String?,
    val exifInfo: AssetExifInfo?,
    val fileCreatedAt: Date? = null,
    val fileModifiedAt: Date?,
    val albumName: String?,
    val people: List<Person>?,
    val tags: List<Tag>?,
    val originalPath: String?,
    val originalFileName: String?,
    val isFavorite: Boolean = false,
    /** Video length as Immich reports it ("0:00:12.345000"), null for photos or unknown. */
    val duration: String? = null
)
