package nl.giejay.android.tv.immich.shared.util

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates the English country names Immich stores in `exifInfo.country` into another language,
 * fully offline via the platform's locale data.
 *
 * Immich takes these names from GeoNames' `countryInfo.txt` (older servers used
 * i18n-iso-countries), so the English name is mapped back to its ISO 3166 code and then rendered
 * with [Locale.getDisplayCountry]. Anything that cannot be mapped is returned unchanged, so the
 * result is never worse than the original value.
 *
 * Pure JVM on purpose: no Android, no PreferenceManager, no Robolectric.
 */
object CountryNameTranslator {

    /** Codes missing from [Locale.getISOCountries] that Immich can still return. */
    private val EXTRA_CODES = listOf("XK")

    /**
     * English spellings used by GeoNames / i18n-iso-countries that differ from the platform's
     * English display names even after [normalize]. Keys are already normalized.
     */
    private val ALIASES = mapOf(
        "bonaire saint eustatius and saba" to "BQ",
        "cabo verde" to "CV",
        "cape verde" to "CV",
        "czech republic" to "CZ",
        "democratic republic of congo" to "CD",
        "congo democratic republic of" to "CD",
        "republic of congo" to "CG",
        "east timor" to "TL",
        "heard island and mcdonald islands" to "HM",
        "ivory coast" to "CI",
        "kosovo" to "XK",
        "macau" to "MO",
        "macedonia" to "MK",
        "palestine" to "PS",
        "palestinian territory" to "PS",
        "palestine state of" to "PS",
        "pitcairn" to "PN",
        "swaziland" to "SZ",
        "turkey" to "TR",
        "turkiye" to "TR",
        "united states minor outlying islands" to "UM",
        "vatican" to "VA",
        "holy see" to "VA",
        // i18n-iso-countries official names (older Immich servers)
        "united states of america" to "US",
        "united kingdom of great britain and northern ireland" to "GB",
        "russian federation" to "RU",
        "korea republic of" to "KR",
        "republic of korea" to "KR",
        "korea democratic peoples republic of" to "KP",
        "viet nam" to "VN",
        "iran islamic republic of" to "IR",
        "taiwan province of china" to "TW",
        "bolivia plurinational state of" to "BO",
        "venezuela bolivarian republic of" to "VE",
        "tanzania united republic of" to "TZ",
        "lao peoples democratic republic" to "LA",
        "syrian arab republic" to "SY",
        "moldova republic of" to "MD",
        "micronesia federated states of" to "FM",
    )

    /**
     * Per-language replacements for platform names that are technically right but read badly
     * on a slideshow (e.g. "Sonderverwaltungsregion Hongkong").
     */
    private val DISPLAY_OVERRIDES: Map<String, Map<String, String>> = mapOf(
        "de" to mapOf(
            "HK" to "Hongkong",
            "MO" to "Macau",
            "PS" to "Palästina",
        )
    )

    private val codeByEnglishName: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        (Locale.getISOCountries().toList() + EXTRA_CODES).forEach { code ->
            val english = Locale("", code).getDisplayCountry(Locale.ENGLISH)
            if (english.isNotBlank() && english != code) {
                map.putIfAbsent(normalize(english), code)
            }
        }
        map.putAll(ALIASES)
        map
    }

    private val cache = ConcurrentHashMap<Pair<String, String>, String>()

    /** ISO 3166 alpha-2 code for an English country name, or null if unknown. */
    fun isoCodeOf(englishName: String): String? =
        codeByEnglishName[normalize(englishName)]

    /**
     * [countryName] rendered in [target]'s language, or [countryName] itself when the target is
     * English, the name is unknown, or the platform has no translation for it.
     */
    fun translate(countryName: String, target: Locale): String {
        if (countryName.isBlank() || target.language.isEmpty() || target.language == "en") {
            return countryName
        }
        return cache.getOrPut(countryName to target.toLanguageTag()) {
            val code = isoCodeOf(countryName) ?: return@getOrPut countryName
            DISPLAY_OVERRIDES[target.language]?.get(code)
                ?: Locale("", code).getDisplayCountry(target)
                    .takeIf { it.isNotBlank() && it != code }
                ?: countryName
        }
    }

    /**
     * Lowercase ASCII words only: drops accents, parenthesised parts ("Myanmar (Burma)"),
     * " SAR China" suffixes, "the", and unifies "&"/"St." with "and"/"Saint".
     */
    internal fun normalize(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("['’]".toRegex(), "")
        return ascii
            .replace("\\(.*?\\)".toRegex(), " ")
            .replace(" sar china", " ")
            .replace("&", " and ")
            .replace("\\bst\\.".toRegex(), "saint ")
            .replace("[^a-z]+".toRegex(), " ")
            .split(' ')
            .filter { it.isNotEmpty() && it != "the" }
            .joinToString(" ")
    }
}
