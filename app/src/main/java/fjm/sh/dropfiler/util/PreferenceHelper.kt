package fjm.sh.dropfiler.util

import android.content.Context
import android.preference.PreferenceManager
import fjm.sh.dropfiler.R
import fjm.sh.dropfiler.save.DuplicatePolicy
import fjm.sh.dropfiler.save.SaveSettings

/**
 * A helper class for managing preferences.
 */
class PreferenceHelper(private val context: Context) {

	companion object {
		var saveStrategy = 0
		var locationSelectEnabled = false
		var registerMediaServer = false
		var useLenientRegex = false
		var followRedirects = false
		var forceSaving = false
		var logLevel = 0

		/** The current settings for the save engine (call [loadPreferences] first). */
		fun saveSettings() = SaveSettings(
			policy = DuplicatePolicy.fromIndex(saveStrategy),
			lenientNames = useLenientRegex,
			followRedirects = followRedirects,
			forceSaving = forceSaving,
			registerMediaScanner = registerMediaServer,
		)
	}

	/**
	 * Force loading of the preferences
	 */
	fun loadPreferences() {
		val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

		forceSaving = sharedPrefs.getBoolean("force_saving", false)
		followRedirects = sharedPrefs.getBoolean("follow_redirects", true) // Defaults to true
		registerMediaServer = sharedPrefs.getBoolean("register_file", false)
		useLenientRegex = sharedPrefs.getBoolean("lenient_regex", false)
		locationSelectEnabled = sharedPrefs.getBoolean("location_select", false)
		saveStrategy = context.resources.getStringArray(R.array.pref_list_values_file_exists).indexOf(
				PreferenceManager.getDefaultSharedPreferences(context).getString(
						"file_exists", context.resources.getString(R.string.pref_default_value_file_exists)))
		logLevel = context.resources.getStringArray(R.array.pref_list_values_log_to_user).indexOf(
				PreferenceManager.getDefaultSharedPreferences(context).getString(
						"log_to_user", context.resources.getString(R.string.pref_default_value_log_to_user)))
	}
}
