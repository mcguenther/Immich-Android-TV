package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import java.util.Locale

/** Language used for country names in the slideshow / screensaver metadata. */
enum class CountryNameLanguage : EnumWithTitle {
    /** Show the name exactly as Immich returns it (English). */
    ORIGINAL {
        override fun getTitle(): String = ImmichApplication.appContext!!.getString(R.string.country_name_language_original)
        override fun targetLocale(): Locale? = null
    },
    DEVICE {
        override fun getTitle(): String = ImmichApplication.appContext!!.getString(R.string.country_name_language_device)
        override fun targetLocale(): Locale? = Locale.getDefault()
    },
    GERMAN {
        override fun getTitle(): String = ImmichApplication.appContext!!.getString(R.string.country_name_language_german)
        override fun targetLocale(): Locale? = Locale.GERMAN
    };

    /** Locale to translate country names into, or null to keep the Immich value. */
    abstract fun targetLocale(): Locale?
}
