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
 * The last slides the screensaver showed (newest first), kept across runs: the next run starts with them
 * instead of a black screen while the first batch is still loading. Photos shown before are in Glide's disk
 * cache, so they appear at once.
 *
 * A slide is the list of its assets (one, or two portrait photos). The history belongs to one
 * [signature] (screensaver source and server); a different one is ignored, so a changed setup never shows
 * photos from the old one.
 *
 * @param read the stored JSON, null when there is none
 * @param write stores the JSON
 */
class RecentScreenSaverPages(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val maxPages: Int = MAX_PAGES
) {
    internal data class Stored(val signature: String = "", val pages: List<List<Asset>> = emptyList())

    /** Newest first; empty without history, with a history of another [signature] or when it cannot be read. */
    fun load(signature: String): List<List<Asset>> = try {
        read()?.let { gson.fromJson(it, Stored::class.java) }
            ?.takeIf { it.signature == signature }
            ?.pages
            ?.filter { page -> page.isNotEmpty() && page.size <= 2 }
            ?.take(maxPages)
            .orEmpty()
    } catch (e: Exception) {
        Timber.w(e, "Ignoring unreadable screensaver history")
        emptyList()
    }

    /** [page] just became the current slide: it moves to the front of the history of [signature]. */
    fun record(signature: String, page: List<Asset>) {
        if (page.isEmpty()) return
        try {
            val updated = updated(load(signature), page, maxPages)
            write(gson.toJson(Stored(signature, updated), Stored::class.java))
        } catch (e: Exception) {
            Timber.w(e, "Could not store screensaver history")
        }
    }

    companion object {
        /** The last slide and two before it. */
        const val MAX_PAGES = 3

        // Dates as epoch milliseconds: the default text format depends on the locale.
        private val gson: Gson = GsonBuilder()
            .registerTypeAdapter(Date::class.java, object : JsonSerializer<Date>, JsonDeserializer<Date> {
                override fun serialize(src: Date, typeOfSrc: Type, context: JsonSerializationContext) = JsonPrimitive(src.time)
                override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext) = Date(json.asLong)
            })
            .create()

        /** [history] with [page] in front (an older entry of the same assets is dropped), at most [max] entries. */
        fun updated(history: List<List<Asset>>, page: List<Asset>, max: Int): List<List<Asset>> {
            val ids = page.map { it.id }
            return (listOf(page) + history.filter { entry -> entry.map { it.id } != ids }).take(max)
        }

        /** Identifies the setup the history belongs to. */
        fun signature(vararg parts: Any?): String = parts.joinToString("|") { it?.toString().orEmpty() }
    }
}
