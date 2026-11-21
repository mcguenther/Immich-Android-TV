package nl.giejay.android.tv.immich.screensaver

import androidx.annotation.StringRes
import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetResponse
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.EXCLUDE_ASSETS_IN_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.SlideshowSettings
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ALBUMS
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_INCLUDE_VIDEOS
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_PEOPLE
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_PLAY_SOUND
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_SHOW_DATE_TOP_LEFT
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_TYPE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_DPAD_SEEK_IN_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.util.Utils.pmap
import nl.giejay.android.tv.immich.shared.util.toSliderPages
import nl.giejay.android.tv.immich.slider.FavoriteService
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.util.LoadMore
import nl.giejay.mediaslider.util.LoadMoreResult
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

// internal so app/src/test can call it directly without instantiating ScreenSaverService
internal fun <T> Either<String, T>.getOrElseLogged(logContext: String, default: T): T =
    this.onLeft { error -> Timber.w("Failed to load assets for %s: %s", logContext, error) }
        .getOrElse { default }

internal fun filterExcludedAssets(assets: List<Asset>, excludedAssetIds: Set<String>): List<Asset> =
    assets.filter { asset -> asset.tags?.none { it.name == "exclude_immich_tv" } ?: true }
        .filterNot { excludedAssetIds.contains(it.id) }

/** Single construction site of the screensaver's ApiClientConfig, shared by both hosts. */
internal fun screenSaverApiClientConfig(): ApiClientConfig =
    ApiClientConfig(
        PreferenceManager.hostName,
        PreferenceManager.get(API_KEY),
        PreferenceManager.get(DISABLE_SSL_VERIFICATION),
        PreferenceManager.get(DEBUG_MODE)
    )

/**
 * THE single construction site of the screensaver MediaSliderConfiguration. Having exactly one
 * literal is what guarantees the in-app preview and the real DreamService stay identical.
 */
internal fun buildScreenSaverConfiguration(
    pages: List<SliderItemViewHolder>,
    loadMore: LoadMore?,
    scope: CoroutineScope,
    favoriteService: FavoriteService,
    /** Slides shown from the history while the first batch loads: the slideshow then waits on the last of them. */
    holdOnLastPageUntilLoaded: Boolean = false,
    onPageSelected: (SliderItemViewHolder) -> Unit = {}
): MediaSliderConfiguration {
    val enabledPlugins = PreferenceManager.createEnabledSliderPlugins(scope, favoriteService)
    return MediaSliderConfiguration(
        0,
        PreferenceManager.get(SCREENSAVER_INTERVAL),
        PreferenceManager.get(SCREENSAVER_PLAY_SOUND),
        pages,
        loadMore,
        onAssetSelected = onPageSelected,
        gradiantOverlay = true,
        metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.SCREENSAVER),
        options = SlideshowSettings.sliderOptions(MetaDataScreen.SCREENSAVER),
        useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
        dpadSeeksInVideo = PreferenceManager.get(SLIDER_DPAD_SEEK_IN_VIDEO),
        showDateTopLeft = PreferenceManager.get(SCREENSAVER_SHOW_DATE_TOP_LEFT),
        controllerPlugins = enabledPlugins.controllerPlugins,
        viewPlugins = enabledPlugins.viewPlugins,
        keyEventPlugins = enabledPlugins.keyEventPlugins,
        // Black until the photos of the first slide are all there, then a fade-in instead of photos popping in.
        revealWhenLoaded = true,
        holdOnLastPageUntilLoaded = holdOnLastPageUntilLoaded
    )
}

/**
 * Loads the assets for the Immich screensaver and builds its MediaSliderConfiguration.
 *
 * Context-agnostic on purpose: the host owns the view and all UI side effects, so the exact same
 * loading pipeline backs both [ScreenSaverService] and the in-app [ScreenSaverPreviewFragment].
 * Every [Host] callback is invoked on the main thread.
 */
class ScreenSaverAssetLoader(
    private val scope: CoroutineScope,
    private val apiClient: ApiClient,
    private val host: Host,
    private val favoriteService: FavoriteService = FavoriteService(),
    private val recentPages: RecentScreenSaverPages = defaultRecentPages()
) {
    interface Host {
        /** Host resolves [messageRes] against its own Context and shows a toast. */
        fun showScreenSaverMessage(@StringRes messageRes: Int, longDuration: Boolean = false)

        /** DreamService.finish() for the dream, NavController.popBackStack() for the preview. */
        fun exitScreenSaver()

        /** Host calls loadMediaSliderView(configuration) and starts the slideshow. */
        fun onScreenSaverConfigurationReady(configuration: MediaSliderConfiguration)

        /**
         * True for the preview: leave the screen instead of sitting on black when there is
         * nothing to show. The real dream keeps its existing stay-on-black behaviour.
         */
        val exitWhenNothingToShow: Boolean
            get() = false
    }

    private var currentPage = 0
    private var excludedAssetIds: Set<String> = emptySet()
    private val filteredAssetLoader = FilteredAssetLoader(
        filterAssets = { filterExcludedAssets(it, excludedAssetIds) }
    )
    private val loadedAssetIds = mutableSetOf<String>()
    private var partnerFinder = partnerFinder()

    /** Every asset that went into a slide, so the slide that is shown can be stored as history. */
    private val assetsById = ConcurrentHashMap<String, Asset>()

    /** The first fresh batch, when the slideshow already started with the stored history (see [start]). */
    private var freshBatch: CompletableDeferred<FreshBatch>? = null

    private class FreshBatch(val pages: List<SliderItemViewHolder>, val loadMore: LoadMore?)

    private fun partnerFinder(albums: List<String> = emptyList(), people: List<UUID> = emptyList()) =
        PortraitPartnerFinder(
            source = ServerPartnerCandidateSource(apiClient, albums, people),
            isAllowed = { filterExcludedAssets(listOf(it), excludedAssetIds).isNotEmpty() }
        )

    /** Slider pages for one batch, with partners looked up for portrait photos. */
    private suspend fun toPages(assets: List<Asset>): List<SliderItemViewHolder> {
        val groups = partnerFinder.group(assets, SlideshowSettings.sliderItems())
        groups.forEach { group -> group.forEach { assetsById[it.id] = it } }
        return groups.toSliderPages()
    }

    /** Identifies the source of the slides, so the history of another one is never shown. */
    private fun historySignature(): String = RecentScreenSaverPages.signature(
        PreferenceManager.hostName,
        PreferenceManager.get(SCREENSAVER_TYPE),
        PreferenceManager.get(SCREENSAVER_ALBUMS).sorted(),
        PreferenceManager.get(SCREENSAVER_PEOPLE).sorted(),
        PreferenceManager.get(EXCLUDE_ASSETS_IN_ALBUM).sorted(),
        PreferenceManager.get(SCREENSAVER_INCLUDE_VIDEOS)
    )

    private fun recordShown(page: SliderItemViewHolder) {
        val assets = page.ids().mapNotNull { assetsById[it] }
        if (assets.size == page.ids().size) recentPages.record(historySignature(), assets)
    }

    /**
     * The slides of the previous run, newest first, to start with while the first batch loads. Only what the
     * server may still show: assets tagged for exclusion are dropped (the excluded albums are not known yet).
     */
    private suspend fun recentSlides(): List<SliderItemViewHolder> {
        val groups = recentPages.load(historySignature())
            .map { group -> filterExcludedAssets(group, emptySet()) }
            .filter { it.isNotEmpty() }
        groups.forEach { group -> group.forEach { assetsById[it.id] = it } }
        return groups.toSliderPages()
    }

    fun start(): Job = scope.launch(Dispatchers.IO) {
        // Start with the slides of the previous run at once; the first batch then follows as "load more".
        val recent = recentSlides()
        if (recent.isNotEmpty()) {
            val fresh = CompletableDeferred<FreshBatch>().also { freshBatch = it }
            withContext(Dispatchers.Main) {
                host.onScreenSaverConfigurationReady(
                    buildScreenSaverConfiguration(
                        recent, loadMoreAfterRecent(fresh), scope, favoriteService,
                        holdOnLastPageUntilLoaded = true, onPageSelected = ::recordShown
                    )
                )
            }
        }
        try {
            loadAssets()
        } finally {
            // Whatever happened, the slideshow must not keep waiting on the last stored slide for a batch that will not come.
            freshBatch?.complete(FreshBatch(emptyList(), null))
        }
    }

    private suspend fun loadAssets() {
        val excludedAlbums = PreferenceManager.get(EXCLUDE_ASSETS_IN_ALBUM)
        if (excludedAlbums.isNotEmpty()) {
            excludedAssetIds = apiClient.listAssetsFromAlbum(excludedAlbums.toList(), pageCount = MAX_SEARCH_PAGE_SIZE)
                .getOrElseLogged("excluded albums $excludedAlbums", AssetResponse(emptyList(), false))
                .assets
                .map { it.id }
                .toSet()
        }

        if (ScreenSaverType.ALBUMS == PreferenceManager.get(SCREENSAVER_TYPE)) {
            loadImagesFrom(albums = PreferenceManager.get(SCREENSAVER_ALBUMS).toList())
        }
        else if (ScreenSaverType.PEOPLE == PreferenceManager.get(SCREENSAVER_TYPE)) {
            loadImagesFrom(people = PreferenceManager.get(SCREENSAVER_PEOPLE).toList())
        }
        else {
            val screenSaverType = PreferenceManager.get(SCREENSAVER_TYPE)
            filteredAssetLoader.load({ loadNextAssets(screenSaverType) }).fold(
                ifLeft = { error ->
                    Timber.w("Failed to load assets for screensaver type %s: %s", screenSaverType, error)
                    if (host.exitWhenNothingToShow) {
                        failAndExit(R.string.could_not_load_assets)
                    }
                },
                ifRight = { response ->
                    setInitialAssets(
                        response.assets,
                        if (response.canLoadMore) createLoadMore { loadNextAssets(screenSaverType) } else null
                    )
                }
            )
        }
    }

    private suspend fun loadNextAssets(screenSaverType: ScreenSaverType): Either<String, AssetResponse> {
        val contentType = if (PreferenceManager.get(SCREENSAVER_INCLUDE_VIDEOS)) ContentType.ALL else ContentType.IMAGE
        val page = currentPage
        currentPage += 1
        return when (screenSaverType) {
            ScreenSaverType.RECENT -> apiClient.recentAssets(page, BATCH_SIZE, contentType = contentType)
            ScreenSaverType.SIMILAR_TIME_PERIOD -> apiClient.similarAssets(page, BATCH_SIZE, contentType = contentType)
            else -> apiClient.listAssets(page, BATCH_SIZE, true, contentType = contentType)
        }
    }

    /**
     * Load-more for a slideshow that started with stored slides: its first request delivers the first
     * fresh batch (waiting for it to be loaded), later requests go to that batch's own load-more.
     */
    private fun loadMoreAfterRecent(fresh: CompletableDeferred<FreshBatch>): LoadMore {
        var consumed = false
        var followUp: LoadMore? = null
        return suspend {
            if (!consumed) {
                val batch = fresh.await()
                consumed = true
                followUp = batch.loadMore
                LoadMoreResult(batch.pages, batch.loadMore != null)
            } else {
                followUp?.invoke() ?: LoadMoreResult(emptyList(), false)
            }
        }
    }

    private fun createLoadMore(fetch: suspend () -> Either<String, AssetResponse>): LoadMore = suspend {
        filteredAssetLoader.load(fetch).fold(
            { error ->
                Timber.w("Failed to load more screensaver assets: %s", error)
                LoadMoreResult(emptyList(), false)
            },
            { response ->
                LoadMoreResult(toPages(response.assets), response.canLoadMore)
            }
        )
    }

    private suspend fun loadImagesFrom(albums: List<String>? = null, people: List<String>? = null) {
        try {
            if(albums != null && albums.isEmpty()){
                failAndExit(R.string.set_albums_screensaver_error)
            }
            else if(people != null &&  people.isEmpty()){
                failAndExit(R.string.set_people_screensaver_error)
            }

            val albumsList = albums ?: emptyList()
            val peopleList = people ?: emptyList()
            partnerFinder = partnerFinder(albumsList, peopleList.map { UUID.fromString(it) })

            val response = filteredAssetLoader.load(fetch =
                {
                    loadNextAssetsFrom(albumsList, peopleList, random = true).fold(
                        {
                            Timber.w("Could not load random assets for albums $albums, falling back to all album assets")
                            loadNextAssetsFrom(albumsList, peopleList, random = false)
                        },
                        {
                            Either.Right(it)
                        }
                    )
                }, retry = {
                // load assets not randomly. This can happen because the logged in User/API key does not have any own photo's, only shared access
                // shared photos are not returned yet for the Random endpoint (ongoing feature request).
                Timber.w("Random asset search for album was empty for albums $albums, now using the regular search assets in album endpoint")
                loadNextAssetsFrom(albumsList, peopleList, random = false)
            }).getOrElse { AssetResponse(emptyList(), false) }

            setInitialAssets(
                response.assets.shuffled(),
                if (response.canLoadMore) {
                    createLoadMore { loadNextAssetsFrom(albumsList, peopleList, random = false) }
                } else null
            )

        } catch (e: Exception) {
            Timber.e(e, "Could not fetch assets from Immich for Screensaver")
            failAndExit(R.string.could_not_load_assets)
        }
    }

    private suspend fun loadNextAssetsFrom(albums: List<String>, people: List<String>, random: Boolean = false): Either<String, AssetResponse> {
        val contentType =
            if (PreferenceManager.get(SCREENSAVER_INCLUDE_VIDEOS)) ContentType.ALL else ContentType.IMAGE
        val response = if (random) {
            val pageCount = (BATCH_SIZE / max(albums.size, people.size).coerceAtLeast(1)).coerceAtLeast(1)
            val responses = if (albums.isNotEmpty()) {
                albums.pmap { albumId ->
                    apiClient.listAssets(
                        page = 1,
                        pageCount = pageCount,
                        random = true,
                        contentType = contentType,
                        albumIds = listOf(albumId)
                    )
                }
            } else {
                people.pmap { personId ->
                    apiClient.listAssets(
                        page = 1,
                        pageCount = pageCount,
                        random = true,
                        contentType = contentType,
                        personIds = listOf(UUID.fromString(personId))
                    )
                }
            }
            responses.firstOrNull { it.isLeft() }?.let { return it }
            Either.Right(
                AssetResponse(
                    responses.flatMap { it.getOrElse { AssetResponse(emptyList(), true) }.assets }
                        .shuffled(),
                    true
                )
            )
        } else {
            if (albums.isNotEmpty()) {
                apiClient.listAssetsFromAlbum(albums, contentType, pageCount = MAX_SEARCH_PAGE_SIZE)
                    .map { AssetResponse(it.assets.shuffled(), it.canLoadMore) }
            } else {
                val personIds = people.map { UUID.fromString(it) }.toMutableList()
                apiClient.listAssets(
                    page = 1,
                    pageCount = MAX_SEARCH_PAGE_SIZE,
                    random = false,
                    contentType = contentType,
                    personIds = personIds
                ).map { AssetResponse(it.assets.shuffled(), it.canLoadMore) }
            }
        }
        return response.map { it.copy(assets = it.assets.filter { asset -> loadedAssetIds.add(asset.id) }) }
    }

    private suspend fun failAndExit(@StringRes messageRes: Int) = withContext(Dispatchers.Main) {
        host.showScreenSaverMessage(messageRes)
        host.exitScreenSaver()
    }

    private suspend fun setInitialAssets(assets: List<Asset>, loadMore: (suspend () -> LoadMoreResult)?) {
        // The slider drops repeats of later batches; the first one must not hold an asset twice either.
        val pages = toPages(assets.distinctBy { it.id })
        val waiting = freshBatch
        if (waiting != null) {
            // Already showing the stored slides: the slider appends these when it asks for more.
            waiting.complete(FreshBatch(pages, loadMore))
        } else {
            withContext(Dispatchers.Main) { showInitialPages(pages, loadMore) }
        }
    }

    private fun showInitialPages(pages: List<SliderItemViewHolder>, loadMore: (suspend () -> LoadMoreResult)?) {
        if (pages.isEmpty()) {
            host.showScreenSaverMessage(R.string.no_assets_for_screensaver, longDuration = true)
            if (host.exitWhenNothingToShow) {
                host.exitScreenSaver()
            }
        } else {
            host.onScreenSaverConfigurationReady(
                buildScreenSaverConfiguration(pages, loadMore, scope, favoriteService, onPageSelected = ::recordShown)
            )
        }
    }

    companion object {
        private fun defaultRecentPages() = RecentScreenSaverPages(
            read = { PreferenceManager.getString(RECENT_PAGES_KEY, "").ifEmpty { null } },
            write = { PreferenceManager.sharedPreference.edit().putString(RECENT_PAGES_KEY, it).apply() }
        )

        private const val RECENT_PAGES_KEY = "screensaver_recent_pages"

        /**
         * Assets per request. Portrait partners are looked up per photo, so a batch no longer has to be
         * large to hold two photos of the same day; small batches start the screensaver sooner.
         */
        internal const val BATCH_SIZE = 30
    }
}
