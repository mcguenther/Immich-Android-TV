package nl.giejay.android.tv.immich.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.leanback.preference.LeanbackSettingsFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import arrow.core.Either
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.listSummary
import nl.giejay.android.tv.immich.shared.prefs.EnumByTitlePref
import nl.giejay.android.tv.immich.shared.prefs.EnumPref
import nl.giejay.android.tv.immich.shared.prefs.EnumWithTitle
import nl.giejay.android.tv.immich.shared.prefs.IntListPref
import nl.giejay.android.tv.immich.shared.prefs.PrefScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager


abstract class SettingsScreenFragment : LeanbackSettingsFragmentCompat() {

    abstract fun getFragment(): SettingsInnerFragment

    override fun onPreferenceStartInitialScreen() {
        val fragment = getFragment()
        fragment.arguments = arguments
        startPreferenceFragment(fragment)
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val args = pref.extras
        val f = childFragmentManager.fragmentFactory.instantiate(
            requireActivity().classLoader, pref.fragment
        )
        f.arguments = args
        f.setTargetFragment(caller, 0)
        if (f is PreferenceFragmentCompat
            || f is PreferenceDialogFragmentCompat
        ) {
            startPreferenceFragment(f)
        } else {
            startImmersiveFragment(f)
        }
        return true
    }

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen
    ): Boolean {
        val fragment: Fragment = getFragment()
        val args = Bundle(1)
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
        fragment.arguments = args
        startPreferenceFragment(fragment)
        return true
    }

    abstract class SettingsInnerFragment : LeanbackPreferenceFragmentCompat() {
        abstract fun getLayout(): Either<Int, PrefScreen>

        // SharedPreferences only keeps weak references to listeners, so hold on to it
        private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshPreferences() }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            refreshPreferences()
        }

        override fun onStart() {
            super.onStart()
            PreferenceManager.sharedPreference.registerOnSharedPreferenceChangeListener(prefChangeListener)
            refreshPreferences()
        }

        override fun onStop() {
            PreferenceManager.sharedPreference.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
            super.onStop()
        }

        /**
         * Brings the shown preferences in line with the stored values: applies the screen's visibility rules and
         * shows the selected value of list preferences as their summary.
         */
        private fun refreshPreferences() {
            if (preferenceScreen == null) return
            getLayout().map { prefScreen ->
                prefScreen.children.flatMap { it.children }.forEach { pref ->
                    val preference = findPreference<Preference>(pref.key()) ?: return@forEach
                    prefScreen.visibility[pref]?.let { preference.isVisible = it(PreferenceManager.sharedPreference) }
                    when (pref) {
                        is EnumByTitlePref<*> -> {
                            val value = pref.getValue(PreferenceManager.sharedPreference) as EnumWithTitle
                            preference.summary = if (preference is ListPreference) listSummary(value.getTitle()) else value.getTitle()
                        }
                        is IntListPref, is EnumPref<*> -> (preference as? ListPreference)?.entry?.let { preference.summary = listSummary(it) }
                        else -> {}
                    }
                }
            }
        }

        abstract fun handlePreferenceClick(preference: Preference?): Boolean
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Load the preferences from an XML resource
            setPreferencesFromResource(R.xml.preferences_empty, rootKey)
            val layout = getLayout()
            layout.fold(
                { layoutId -> setPreferencesFromResource(layoutId, rootKey) },
                { prefScreen ->
                    preferenceScreen.title = prefScreen.title
                    prefScreen.children.forEach {
                        val preferenceCategory = PreferenceCategory(requireContext())
                        preferenceScreen.addPreference(preferenceCategory)
                        if(it.title.isNotBlank()){
                            preferenceCategory.title = it.title
                        }
                        it.children.forEach { child ->
                            preferenceCategory.addPreference(child.createPreference(requireContext()))
                        }
                    }
                })
        }


        override fun onPreferenceTreeClick(preference: Preference?): Boolean {
            val handled = handlePreferenceClick(preference!!)
            return if (handled) true else super.onPreferenceTreeClick(preference)
        }
    }
}