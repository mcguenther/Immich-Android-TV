package nl.giejay.android.tv.immich.screensaver

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import nl.giejay.android.tv.immich.api.model.Asset
import timber.log.Timber
import java.lang.reflect.Type
import java.util.Date

/**
 * Where the screensaver continues next time: the slide that was shown last, followed by the slides that were
 * queued behind it but not shown yet. The next run starts with exactly these instead of a black screen while
 * the first batch is still loading. The queued slides were preloaded into Glide's cache while the slideshow
 * ran, so they appear at once.
 *
 * A slide is the list of its assets (one, or two portrait photos). The queue belongs to one
 * [signature] (screensaver source and server); a different one is ignored, so a changed setup never shows
 * photos from the old one.
 *
 * @param read the stored JSON, null when there is none
 * @param write stores the JSON
 */
class RecentScreenSaverPages(
    private val read: () -> String?,
    private val write: (String) -> Unit
) {
    internal data class Stored(val signature: String = "", val pages: List<List<Asset>> = emptyList())

    /** The slides in the order to show them; empty without a queue, for another [signature] or when it cannot be read. */
    fun load(signature: String): List<List<Asset>> = try {
        read()?.let { gson.fromJson(it, Stored::class.java) }
            ?.takeIf { it.signature == signature }
            ?.pages
            ?.filter { page -> page.isNotEmpty() && page.size <= 2 }
            ?.take(MAX_PAGES)
            .orEmpty()
    } catch (e: Exception) {
        Timber.w(e, "Ignoring unreadable screensaver queue")
        emptyList()
    }

    /** Replaces the stored queue of [signature] with [pages] (the shown slide first, then the queued ones). */
    fun save(signature: String, pages: List<List<Asset>>) {
        if (pages.isEmpty()) return
        try {
            write(gson.toJson(Stored(signature, pages.take(MAX_PAGES)), Stored::class.java))
        } catch (e: Exception) {
            Timber.w(e, "Could not store screensaver queue")
        }
    }

    companion object {
        /** Slides that are queued behind the current one (what the prefetcher preloads by default). */
        const val UPCOMING = 3

        /** The slide shown last plus [UPCOMING] queued ones. */
        const val MAX_PAGES = 1 + UPCOMING

        // Dates as epoch milliseconds: the default text format depends on the locale.
        private val gson: Gson = GsonBuilder()
            .registerTypeAdapter(Date::class.java, object : JsonSerializer<Date>, JsonDeserializer<Date> {
                override fun serialize(src: Date, typeOfSrc: Type, context: JsonSerializationContext) = JsonPrimitive(src.time)
                override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext) = Date(json.asLong)
            })
            .create()

        /** Identifies the setup the queue belongs to. */
        fun signature(vararg parts: Any?): String = parts.joinToString("|") { it?.toString().orEmpty() }
    }
}
