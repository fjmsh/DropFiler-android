package fjm.sh.dropfiler.destination

import android.content.Context
import fjm.sh.dropfiler.R

/** How a destination is written to. */
enum class DestinationKind {
	/**
	 * The built-in, non-removable `Internal storage/Download` folder. Written through
	 * MediaStore.Downloads on Android 10+ (no permission needed) and through the legacy
	 * public Download directory on Android 5 - 9.
	 */
	MEDIASTORE_DOWNLOADS_DEFAULT,

	/** A folder the user chose with ACTION_OPEN_DOCUMENT_TREE, with a persisted grant. */
	SAF_TREE,

	/**
	 * A folder path stored by DropFiler 6.x (before SAF). A path cannot be turned into a
	 * document tree grant, so the user has to choose the folder again ("Reconnect").
	 */
	LEGACY_RECONNECT_REQUIRED,
}

/** Whether a destination can be saved to right now. */
enum class DestinationState {
	READY,
	/** The persisted tree grant is missing or was revoked; reconnect to fix. */
	NEEDS_PERMISSION,
	/** The folder was deleted, its storage is unmounted, or the provider fails. */
	UNAVAILABLE,
	/** The provider does not allow creating files in the folder. */
	READ_ONLY,
	/** A migrated path record that was never authorized with the system picker. */
	RECONNECT_REQUIRED,
}

/**
 * A save destination. The built-in default is [DestinationRepository.defaultDestination];
 * all others are stored by [DestinationStore] in the user's order.
 *
 * @param id Stable identity used by the share sheet, Direct Share and shortcuts. Never a
 *   list position and never a filesystem path.
 * @param treeUri The persisted `content://` tree URI for [DestinationKind.SAF_TREE].
 * @param description Friendly secondary text (e.g. the storage provider's name).
 * @param legacyPath The DropFiler 6.x path this record was migrated from, kept so that old
 *   Direct Share targets / shortcuts (`folder:<path>`) still resolve to the same record.
 */
data class SaveDestination(
	val id: String,
	val kind: DestinationKind,
	val label: String,
	val treeUri: String? = null,
	val description: String? = null,
	val legacyPath: String? = null,
) {
	val isDefault: Boolean get() = kind == DestinationKind.MEDIASTORE_DOWNLOADS_DEFAULT

	/**
	 * A migrated record pointing at the root of Download. The built-in destination already
	 * covers it, so it is not reconnected; the user is told to remove it.
	 */
	val duplicatesDefault: Boolean
		get() = kind == DestinationKind.LEGACY_RECONNECT_REQUIRED &&
			legacyPath?.trimEnd('/')?.equals("/Download", ignoreCase = true) == true

	/** Where the files went, for result notifications. */
	fun displayLocation(context: Context): String =
		if (isDefault) context.getString(R.string.default_destination_path) else label
}
