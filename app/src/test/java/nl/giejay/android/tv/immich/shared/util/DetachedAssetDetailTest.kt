package nl.giejay.android.tv.immich.shared.util

import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.giejay.android.tv.immich.api.model.Asset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DetachedAssetDetailTest {

    @After
    fun tearDown() {
        AssetDetailCache.clear()
    }

    @Test
    fun `cancelling the caller that started a lookup does not fail a concurrent caller`() = runBlocking {
        val fetches = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        AssetDetailCache.fetchAsset = { id ->
            fetches.incrementAndGet()
            started.complete(Unit)
            gate.await()
            sampleAsset(id).right()
        }

        // The first caller owns the shared request (like the map of a slide that is skipped right away).
        val first = launch(Dispatchers.IO) { fetchAssetDetailDetached("a1") }
        started.await()
        // The second caller waits for the same request (like the place name of the same slide).
        val second = async(Dispatchers.IO) { fetchAssetDetailDetached("a1") }
        first.cancelAndJoin()
        gate.complete(Unit)

        val result = withTimeout(5_000) { second.await() }
        assertTrue("concurrent caller got $result", result.isRight())
        assertEquals("a1", result.getOrNull()!!.id)
        assertEquals(1, fetches.get())
    }

    @Test
    fun `the request of a cancelled caller still fills the cache`() = runBlocking {
        val fetches = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        AssetDetailCache.fetchAsset = { id ->
            fetches.incrementAndGet()
            started.complete(Unit)
            gate.await()
            sampleAsset(id).right()
        }

        val caller = launch(Dispatchers.IO) { fetchAssetDetailDetached("b1") }
        started.await()
        caller.cancelAndJoin()
        gate.complete(Unit)

        // Waits for (or reuses) the request that kept running; no second fetch.
        val result = withTimeout(5_000) { fetchAssetDetailDetached("b1") }
        assertEquals("b1", result.getOrNull()!!.id)
        assertEquals(1, fetches.get())
    }

    private fun sampleAsset(id: String) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = null,
        fileCreatedAt = null,
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null,
        isFavorite = false
    )
}
