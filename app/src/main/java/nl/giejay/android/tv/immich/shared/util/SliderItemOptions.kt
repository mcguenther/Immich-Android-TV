package nl.giejay.android.tv.immich.shared.util

import nl.giejay.android.tv.immich.shared.prefs.PortraitPairing

/** How a list of assets is turned into slider pages; see [toSliderItems]. */
data class SliderItemOptions(
    val pairing: PortraitPairing,
    /** When false, portrait videos are left out (except an asset the user explicitly opened). */
    val showPortraitVideos: Boolean = true
)
