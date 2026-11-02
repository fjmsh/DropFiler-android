package fjm.sh.dropfiler.activity

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.annotation.ArrayRes
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.hchen.himiuix.callback.OnChooseItemListener
import com.hchen.himiuix.preference.MiuixDropDownPreference
import com.hchen.himiuix.preference.MiuixSwitchPreference
import fjm.sh.dropfiler.R
import fjm.sh.dropfiler.util.ChangeLogHelper
import fjm.sh.dropfiler.util.DirectShareHelper
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.util.SettingsDataStore
import fjm.sh.dropfiler.util.ThemeHelper

/**
 * Application settings, presented as grouped Miuix preference cards.
 */
class SettingsActivity: BaseActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.settings_activity)
		setUpAppBar(getString(R.string.title_activity_settings), showUp = true)
	}

	/**
	 * Miuix XML requires its own PreferenceFragmentCompat subclass for MiuixAppBar linkage.
	 */
	class DropFilerPreferenceFragment: com.hchen.himiuix.fragment.PreferenceFragmentCompat(),
			SharedPreferences.OnSharedPreferenceChangeListener {

		private lateinit var sharedPreferences: SharedPreferences

		override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
			sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
			// Keep the stored value format of the old ListPreferences (see SettingsDataStore)
			preferenceManager.preferenceDataStore = SettingsDataStore(sharedPreferences, mapOf(
				"file_exists" to resources.getStringArray(R.array.pref_list_values_file_exists),
				"log_to_user" to resources.getStringArray(R.array.pref_list_values_log_to_user),
				ThemeHelper.PREF_THEME to resources.getStringArray(R.array.pref_list_values_theme),
			))
			setPreferencesFromResource(R.xml.preferences, rootKey)

			// Bind the summaries of list preferences to their value description.
			bindDescriptionSummary("file_exists", R.array.pref_list_description_file_exists)
			bindDescriptionSummary("log_to_user", R.array.pref_list_description_log_to_user)

			if (!ThemeHelper.isSystemDarkThemeAvailable) {
				findPreference<Preference>(ThemeHelper.PREF_THEME)
					?.setSummary(R.string.pref_description_theme_system_unsupported)
			}

			// Dynamic colors only exist on Android 12+
			findPreference<MiuixSwitchPreference>(ThemeHelper.PREF_DYNAMIC_COLORS)?.let {
				if (!ThemeHelper.isDynamicColorAvailable) {
					it.isEnabled = false
					it.setSummary(R.string.pref_description_dynamic_colors_unavailable)
				}
			}

			findPreference<Preference>("change_log")?.setOnPreferenceClickListener {
				ChangeLogHelper.show(requireActivity(), true)
				true
			}
			findPreference<Preference>("about")?.setOnPreferenceClickListener {
				startActivity(Intent(requireContext(), CreditsActivity::class.java))
				true
			}
		}

		override fun onResume() {
			super.onResume()
			sharedPreferences.registerOnSharedPreferenceChangeListener(this)
		}

		override fun onPause() {
			sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
			super.onPause()
		}

		override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
			when (key) {
				// Recreates all activities with the new night mode. Posted so the drop-down
				// closes first instead of being torn down with the recreated activity.
				ThemeHelper.PREF_THEME -> {
					val context = requireContext().applicationContext
					view?.post { ThemeHelper.applyNightMode(context) } ?: ThemeHelper.applyNightMode(context)
				}
				// Other activities recreate themselves on resume (BaseActivity)
				ThemeHelper.PREF_DYNAMIC_COLORS -> activity?.recreate()
				// Keep Direct Share targets in line with the destinations shown when sharing
				DestinationRepository.PREF_HIDE_DEFAULT_FOLDER -> context?.let { DirectShareHelper.publish(it) }
			}
		}

		/**
		 * Shows the description of the selected entry as the summary.
		 */
		private fun bindDescriptionSummary(key: String, @ArrayRes descriptionArray: Int) {
			val preference = findPreference<MiuixDropDownPreference>(key) ?: return
			val descriptions = resources.getStringArray(descriptionArray)
			preference.summary = preference.value?.toIntOrNull()?.let { descriptions.getOrNull(it) }
			preference.setOnChooseItemListener(object : OnChooseItemListener {
				override fun onChooseBefore(item: CharSequence?, which: Int): Boolean {
					preference.summary = descriptions.getOrNull(which)
					return true
				}
			})
		}
	}
}
