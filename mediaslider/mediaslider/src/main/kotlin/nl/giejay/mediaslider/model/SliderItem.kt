package nl.giejay.mediaslider.model

import java.util.Objects

interface MetaDataProvider {
    suspend fun getValue(): String?
}

class StaticMetaDataProvider(private val value: String?) : MetaDataProvider {
    override suspend fun getValue(): String? = value
}

class SliderItem(
    var id: String,
    val url: String?,
    val type: SliderItemType,
    val orientation: Int,
    private val metaData: Map<MetaDataType, MetaDataProvider>,
    val thumbnailUrl: String?,
    val isPanorama: Boolean,
    var isFavorite: Boolean = false,
    /** Display width in pixels (after EXIF rotation) when known, so the slider can lay out before loading. */
    val width: Int? = null,
    /** Display height in pixels (after EXIF rotation) when known. */
    val height: Int? = null,
    /** Video length in milliseconds when known ahead of playback (null for photos or unknown). */
    val durationMs: Long? = null
) {
    suspend fun get(metaDataType: MetaDataType): String? {
        return this.metaData[metaDataType]?.getValue()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as SliderItem
        return url == that.url
    }

    override fun hashCode(): Int {
        return Objects.hash(url)
    }
}
