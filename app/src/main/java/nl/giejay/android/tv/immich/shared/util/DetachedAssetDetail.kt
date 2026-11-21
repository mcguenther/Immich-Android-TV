package nl.giejay.android.tv.immich.shared.util

import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import nl.giejay.android.tv.immich.api.model.Asset

/** Process-wide scope for [fetchAssetDetailDetached]; nothing a slide does can cancel it. */
private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * [AssetDetailCache.get], started in a scope the caller cannot cancel.
 *
 * Concurrent lookups of the same asset share the request of the first caller. If that caller is cancelled (the
 * location map or the place name of a slide that was skipped quickly), the cache completes the shared request
 * with an error and every other caller waiting for the same asset gets that error too. Started here, the request
 * always runs to its end and fills the cache. The caller still stops waiting at once when it is cancelled itself.
 */
suspend fun fetchAssetDetailDetached(assetId: String): Either<String, Asset> =
    detachedScope.async { AssetDetailCache.get(assetId) }.await()
