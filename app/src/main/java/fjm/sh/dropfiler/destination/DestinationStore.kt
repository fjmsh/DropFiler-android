package fjm.sh.dropfiler.destination

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * Persists the user's destinations (not the built-in default) in their display order.
 *
 * Format (version 2), written atomically with [AtomicFile]:
 * ```
 * {"version":2,"destinations":[{"id":"saf:…","kind":"SAF_TREE","label":"Memes","treeUri":"content://…"}]}
 * ```
 *
 * Migration from DropFiler 6.x: the old `FOLDER_STORE` file holds a JSON list of folder
 * paths relative to the storage root (the built-in `/DropFiler` folder was implicit and is
 * never stored). The first [load] without a version 2 file converts every path into a
 * [DestinationKind.LEGACY_RECONNECT_REQUIRED] record (same order and label, deterministic
 * id), writes the new file, and leaves `FOLDER_STORE` untouched. Because the new file is
 * only replaced atomically, a crash during migration simply repeats it on the next start
 * and produces the same records.
 */
object DestinationStore {

	const val VERSION = 2
	const val FILE_NAME = "DESTINATIONS.json"
	/** The DropFiler 6.x store. Read for migration only, never written or deleted. */
	const val LEGACY_FILE_NAME = "FOLDER_STORE"
	/** The 6.x built-in folder. Implicit, so normally absent from FOLDER_STORE. */
	const val LEGACY_DEFAULT_PATH = "/DropFiler"

	/**
	 * Set when an existing DropFiler 6.x install is migrated, so the main screen explains
	 * once that new files now go to Download and earlier files stay where they are.
	 */
	const val PREF_UPGRADE_NOTICE = "storage_upgrade_notice"
	/** Written by ckChangeLog once a change log was seen, i.e. the app was used before. */
	private const val CHANGELOG_VERSION_KEY = "ckChangeLog_last_version_code"

	private const val TAG = "DestinationStore"

	private class Record(
		val id: String? = null,
		val kind: String? = null,
		val label: String? = null,
		val treeUri: String? = null,
		val description: String? = null,
		val legacyPath: String? = null,
	)

	private class Document(val version: Int = 0, val destinations: List<Record>? = null)

	private val gson = Gson()

	private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE_NAME))

	/** Whether the version 2 file exists (i.e. migration has completed). */
	fun isMigrated(context: Context): Boolean = File(context.filesDir, FILE_NAME).exists()

	@Synchronized
	fun load(context: Context): List<SaveDestination> {
		readCurrent(context)?.let { return it }
		val migrated = migrateLegacy(context)
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val upgrading = File(context.filesDir, LEGACY_FILE_NAME).exists() || prefs.contains(CHANGELOG_VERSION_KEY)
		if (write(context, migrated)) {
			if (upgrading) prefs.edit().putBoolean(PREF_UPGRADE_NOTICE, true).apply()
		} else {
			Log.e(TAG, "Unable to write migrated destinations; will retry on next start")
		}
		return migrated
	}

	@Synchronized
	fun write(context: Context, destinations: List<SaveDestination>): Boolean {
		val doc = Document(VERSION, destinations.filterNot { it.isDefault }.map {
			Record(it.id, it.kind.name, it.label, it.treeUri, it.description, it.legacyPath)
		})
		val atomic = file(context)
		val out = try {
			atomic.startWrite()
		} catch (e: IOException) {
			Log.e(TAG, "Unable to open destination store", e)
			return false
		}
		return try {
			out.write(gson.toJson(doc).toByteArray(Charsets.UTF_8))
			atomic.finishWrite(out)
			true
		} catch (e: IOException) {
			atomic.failWrite(out)
			Log.e(TAG, "Unable to write destination store", e)
			false
		}
	}

	/** @return The stored destinations, or null if there is no (readable) version 2 file. */
	private fun readCurrent(context: Context): List<SaveDestination>? {
		val atomic = file(context)
		val json = try {
			String(atomic.readFully(), Charsets.UTF_8)
		} catch (e: FileNotFoundException) {
			return null
		} catch (e: IOException) {
			Log.e(TAG, "Unable to read destination store", e)
			return null
		}
		val doc = try {
			gson.fromJson(json, Document::class.java)
		} catch (e: JsonParseException) {
			null
		}
		if (doc == null || doc.version < 1 || doc.destinations == null) {
			// Keep the unreadable file for diagnosis instead of overwriting it silently
			val base = File(context.filesDir, FILE_NAME)
			base.renameTo(File(context.filesDir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}"))
			Log.e(TAG, "Destination store is corrupt; migrating again from $LEGACY_FILE_NAME")
			return null
		}
		val seen = HashSet<String>()
		return doc.destinations.mapNotNull { r ->
			val kind = r.kind?.let { k -> DestinationKind.values().firstOrNull { it.name == k } }
			if (r.id == null || kind == null || kind == DestinationKind.MEDIASTORE_DOWNLOADS_DEFAULT || !seen.add(r.id)) {
				null
			} else if (kind == DestinationKind.SAF_TREE && r.treeUri == null) {
				null
			} else {
				SaveDestination(r.id, kind, r.label ?: "", r.treeUri, r.description, r.legacyPath)
			}
		}
	}

	/** Reads FOLDER_STORE (6.x) and converts it. Missing or unreadable → empty. */
	fun migrateLegacy(context: Context): List<SaveDestination> {
		val file = File(context.filesDir, LEGACY_FILE_NAME)
		if (!file.exists()) return emptyList()
		val paths: List<String?> = try {
			gson.fromJson(file.readText(), object : TypeToken<List<String?>>() {}.type) ?: emptyList()
		} catch (e: Exception) {
			Log.e(TAG, "Unable to read $LEGACY_FILE_NAME", e)
			emptyList()
		}
		return paths.filterNotNull()
			.filterNot { it == LEGACY_DEFAULT_PATH }
			.distinct()
			.map { legacyRecord(it) }
	}

	/** Deterministic, so repeating an interrupted migration gives the same ids. */
	fun legacyId(path: String): String =
		"legacy:" + UUID.nameUUIDFromBytes(path.toByteArray(Charsets.UTF_8))

	private fun legacyRecord(path: String) = SaveDestination(
		id = legacyId(path),
		kind = DestinationKind.LEGACY_RECONNECT_REQUIRED,
		label = path.trimEnd('/').substringAfterLast('/'),
		legacyPath = path,
	)
}
