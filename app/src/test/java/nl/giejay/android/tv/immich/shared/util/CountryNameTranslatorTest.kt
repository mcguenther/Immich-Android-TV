package nl.giejay.android.tv.immich.shared.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CountryNameTranslatorTest {

    /** Retired ISO codes GeoNames still lists; the platform has no names for them. */
    private val obsoleteCodes = setOf("AN", "CS")

    private fun geoNamesCountries(): List<Pair<String, String>> =
        javaClass.classLoader!!.getResourceAsStream("geonames-country-names.tsv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { it[0] to it[1] } }

    @Test
    fun `every GeoNames country name Immich can return maps back to its ISO code`() {
        val countries = geoNamesCountries()
        assertTrue(countries.size > 240)

        val unmapped = countries
            .filter { (code, _) -> code !in obsoleteCodes }
            .filter { (code, name) -> CountryNameTranslator.isoCodeOf(name) != code }

        assertEquals("Unmapped: $unmapped", emptyList<Pair<String, String>>(), unmapped)
    }

    @Test
    fun `every GeoNames country gets a German name`() {
        val untranslated = geoNamesCountries()
            .filter { (code, _) -> code !in obsoleteCodes }
            .filter { (_, name) -> CountryNameTranslator.translate(name, Locale.GERMAN).isBlank() }
        assertEquals(emptyList<Pair<String, String>>(), untranslated)
    }

    @Test
    fun `translates common countries to German`() {
        assertEquals("Deutschland", CountryNameTranslator.translate("Germany", Locale.GERMAN))
        assertEquals("Österreich", CountryNameTranslator.translate("Austria", Locale.GERMAN))
        assertEquals("Norwegen", CountryNameTranslator.translate("Norway", Locale.GERMAN))
        assertEquals("Vereinigte Staaten", CountryNameTranslator.translate("United States", Locale.GERMAN))
        assertEquals("Spanien", CountryNameTranslator.translate("Spain", Locale.GERMANY))
    }

    @Test
    fun `GeoNames spellings that differ from the platform still translate`() {
        assertEquals("Türkei", CountryNameTranslator.translate("Turkey", Locale.GERMAN))
        assertEquals("Côte d’Ivoire", CountryNameTranslator.translate("Ivory Coast", Locale.GERMAN))
        assertEquals("Kosovo", CountryNameTranslator.translate("Kosovo", Locale.GERMAN))
        assertEquals("Bosnien und Herzegowina", CountryNameTranslator.translate("Bosnia and Herzegovina", Locale.GERMAN))
        assertEquals("St. Lucia", CountryNameTranslator.translate("Saint Lucia", Locale.GERMAN))
    }

    @Test
    fun `older Immich i18n-iso-countries names translate too`() {
        assertEquals("Vereinigte Staaten", CountryNameTranslator.translate("United States of America", Locale.GERMAN))
        assertEquals("Russland", CountryNameTranslator.translate("Russian Federation", Locale.GERMAN))
        assertEquals("Südkorea", CountryNameTranslator.translate("Korea, Republic of", Locale.GERMAN))
    }

    @Test
    fun `German overrides replace long official names`() {
        assertEquals("Hongkong", CountryNameTranslator.translate("Hong Kong", Locale.GERMAN))
        assertEquals("Macau", CountryNameTranslator.translate("Macao", Locale.GERMAN))
    }

    @Test
    fun `unknown, blank and English targets keep the original name`() {
        assertEquals("Atlantis", CountryNameTranslator.translate("Atlantis", Locale.GERMAN))
        assertEquals("", CountryNameTranslator.translate("", Locale.GERMAN))
        assertEquals("Germany", CountryNameTranslator.translate("Germany", Locale.ENGLISH))
        assertEquals("Germany", CountryNameTranslator.translate("Germany", Locale.US))
        assertEquals("Serbia and Montenegro", CountryNameTranslator.translate("Serbia and Montenegro", Locale.GERMAN))
    }

    @Test
    fun `other target languages work as well`() {
        assertEquals("Allemagne", CountryNameTranslator.translate("Germany", Locale.FRENCH))
        assertNotEquals("Germany", CountryNameTranslator.translate("Germany", Locale.forLanguageTag("ru")))
    }
}
