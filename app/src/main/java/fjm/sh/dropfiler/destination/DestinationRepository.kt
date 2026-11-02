package fjm.sh.dropfiler.destination

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import fjm.sh.dropfiler.R
import fjm.sh.dropfiler.util.DirectShareHelper
import java.util.UUID

/**
 * The single source of truth for save destinations: the built-in Download destination plus
 * the user's SAF folders (and migrated records that still need reconnecting).
 *
 * Methods that talk to a document provider ([checkState], [addTree], [reconnect]) may block
 * and must be called off the main thread.
 */
object DestinationRepository {

	/** Stable id of the built-in destination. */
	const val DEFAULT_ID = "system:downloads"

	/** Setting: hide the built-in destination while the user has other usable destinations. */
	const val PREF_HIDE_DEFAULT_FOLDER = "hide_default_folder"

	private const val TAG = "DestinationRepository"

	private val lock = Any()

	fun defaultDestination(context: Context) = SaveDestination(
		id = DEFAULT_ID,
		kind = DestinationKind.MEDIASTORE_DOWNLOADS_DEFAULT,
		label = context.getString(R.string.default_destination_label),
		description = context.getString(R.string.default_destination_path),
		legacyPath = DestinationStore.LEGACY_DEFAULT_PATH,
	)

	/** The user's destinations in their order (excluding the default). */
	fun custom(context: Context): List<SaveDestination> = DestinationStore.load(context)

	fun find(context: Context, id: String?): SaveDestination? = when (id) {
		null -> null
		DEFAULT_ID -> defaultDestination(context)
		else -> custom(context).firstOrNull { it.id == id }
	}

	/**
	 * Resolves a DropFiler 6.x path (old Direct Share targets and `folder:` shortcuts).
	 * `/DropFiler` was the old built-in folder and now means the built-in Download destination.
	 */
	fun findByLegacyPath(context: Context, path: String?): SaveDestination? = when (path) {
		null -> null
		DestinationStore.LEGACY_DEFAULT_PATH -> defaultDestination(context)
		else -> custom(context).firstOrNull { it.legacyPath == path }
	}

	private fun hideDefault(context: Context) =
		PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_HIDE_DEFAULT_FOLDER, false)

	/**
	 * The destinations offered for saving, in display order: the default first, then the
	 * user's destinations. The default is left out only when the user hid it *and* at least
	 * one other destination is [usable] — so there is always somewhere that works.
	 */
	fun offered(
		context: Context,
		customs: List<SaveDestination> = custom(context),
		usable: (SaveDestination) -> Boolean = { quickState(context, it) == DestinationState.READY },
	): List<SaveDestination> =
		if (hideDefault(context) && customs.any(usable)) customs else listOf(defaultDestination(context)) + customs

	// State ------------------------------------------------------------------------------------

	/** Whether saving to the default needs the legacy storage permission (Android 6 - 9). */
	fun defaultNeedsLegacyPermission(context: Context): Boolean =
		Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.Q &&
			ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
			PackageManager.PERMISSION_GRANTED

	/** A cheap state check without provider I/O (safe on the main thread). */
	fun quickState(context: Context, destination: SaveDestination): DestinationState = when (destination.kind) {
		DestinationKind.MEDIASTORE_DOWNLOADS_DEFAULT ->
			if (defaultNeedsLegacyPermission(context)) DestinationState.NEEDS_PERMISSION else DestinationState.READY
		DestinationKind.LEGACY_RECONNECT_REQUIRED -> DestinationState.RECONNECT_REQUIRED
		DestinationKind.SAF_TREE ->
			if (hasWriteGrant(context, destination.treeUri)) DestinationState.READY else DestinationState.NEEDS_PERMISSION
	}

	/** Full state check, including whether the folder still exists and accepts new files. */
	fun checkState(context: Context, destination: SaveDestination): DestinationState {
		val quick = quickState(context, destination)
		if (destination.kind != DestinationKind.SAF_TREE || quick != DestinationState.READY) {
			if (destination.isDefault && quick == DestinationState.READY && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
				Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
				return DestinationState.UNAVAILABLE
			}
			return quick
		}
		return probeTree(context, Uri.parse(destination.treeUri))
	}

	private fun probeTree(context: Context, tree: Uri): DestinationState = try {
		val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		context.contentResolver.query(doc, arrayOf(
			DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), null, null, null
		)?.use { c ->
			if (!c.moveToFirst()) return DestinationState.UNAVAILABLE
			if (c.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) return DestinationState.UNAVAILABLE
			if (c.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) DestinationState.READ_ONLY
			else DestinationState.READY
		} ?: DestinationState.UNAVAILABLE
	} catch (e: SecurityException) {
		DestinationState.NEEDS_PERMISSION
	} catch (e: Exception) {
		// FileNotFoundException (deleted), IllegalArgumentException / IllegalStateException
		// (unmounted storage, provider gone)
		Log.w(TAG, "Destination folder not available: ${e.javaClass.simpleName}")
		DestinationState.UNAVAILABLE
	}

	fun hasWriteGrant(context: Context, treeUri: String?): Boolean {
		if (treeUri == null) return false
		return try {
			context.contentResolver.persistedUriPermissions.any {
				it.uri.toString() == treeUri && it.isWritePermission
			}
		} catch (e: RuntimeException) {
			false
		}
	}

	// Changes ----------------------------------------------------------------------------------

	sealed class TreeResult {
		data class Added(val destination: SaveDestination, val previousLabel: String? = null) : TreeResult()
		object Cancelled : TreeResult()
		object Invalid : TreeResult()
		object NotGranted : TreeResult()
		object ReadOnly : TreeResult()
		object Unavailable : TreeResult()
		data class Duplicate(val existing: SaveDestination) : TreeResult()
	}

	/** Flags to put on the ACTION_OPEN_DOCUMENT_TREE intent. */
	const val TREE_REQUEST_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
		Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

	/** Adds a folder picked with ACTION_OPEN_DOCUMENT_TREE. Blocking. */
	fun addTree(context: Context, uri: Uri?, resultFlags: Int): TreeResult =
		authorize(context, uri, resultFlags, replacing = null)

	/**
	 * Re-authorizes an existing record with a newly picked folder, keeping its id and position.
	 * If a different folder was picked, [TreeResult.Added.previousLabel] tells the UI so.
	 */
	fun reconnect(context: Context, id: String, uri: Uri?, resultFlags: Int): TreeResult {
		val existing = custom(context).firstOrNull { it.id == id } ?: return TreeResult.Invalid
		return authorize(context, uri, resultFlags, replacing = existing)
	}

	private fun isTreeUri(uri: Uri): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) DocumentsContract.isTreeUri(uri)
		else uri.pathSegments.size >= 2 && uri.pathSegments[0] == "tree"

	private fun authorize(context: Context, uri: Uri?, resultFlags: Int, replacing: SaveDestination?): TreeResult {
		if (uri == null) return TreeResult.Cancelled
		if (uri.scheme != "content" || !isTreeUri(uri)) return TreeResult.Invalid
		val treeUri = uri.toString()

		synchronized(lock) {
			val customs = custom(context)
			customs.firstOrNull { it.treeUri == treeUri && it.id != replacing?.id }?.let {
				return TreeResult.Duplicate(it)
			}
		}

		val granted = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
		if (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) return TreeResult.NotGranted
		try {
			context.contentResolver.takePersistableUriPermission(uri, granted)
		} catch (e: SecurityException) {
			// Not persistable, or the per-app grant limit was reached
			Log.w(TAG, "Unable to persist folder access: ${e.message}")
			return TreeResult.NotGranted
		}

		when (probeTree(context, uri)) {
			DestinationState.READY -> Unit
			DestinationState.READ_ONLY -> { releaseIfUnused(context, treeUri, custom(context)); return TreeResult.ReadOnly }
			DestinationState.NEEDS_PERMISSION -> { releaseIfUnused(context, treeUri, custom(context)); return TreeResult.NotGranted }
			else -> { releaseIfUnused(context, treeUri, custom(context)); return TreeResult.Unavailable }
		}

		val label = treeDisplayName(context, uri) ?: context.getString(R.string.destination_unnamed_folder)
		val description = providerLabel(context, uri)

		synchronized(lock) {
			val customs = custom(context).toMutableList()
			customs.firstOrNull { it.treeUri == treeUri && it.id != replacing?.id }?.let { return TreeResult.Duplicate(it) }
			val result: SaveDestination
			var previousLabel: String? = null
			if (replacing != null) {
				val index = customs.indexOfFirst { it.id == replacing.id }
				if (index < 0) return TreeResult.Invalid
				val old = customs[index]
				result = old.copy(kind = DestinationKind.SAF_TREE, label = label, treeUri = treeUri, description = description)
				customs[index] = result
				if (old.label.isNotEmpty() && old.label != label) previousLabel = old.label
				if (!persist(context, customs)) return TreeResult.Unavailable
				if (old.treeUri != null && old.treeUri != treeUri) releaseIfUnused(context, old.treeUri, customs)
			} else {
				result = SaveDestination("saf:" + UUID.randomUUID(), DestinationKind.SAF_TREE, label, treeUri, description)
				customs.add(result)
				if (!persist(context, customs)) {
					releaseIfUnused(context, treeUri, customs - result)
					return TreeResult.Unavailable
				}
			}
			return TreeResult.Added(result, previousLabel)
		}
	}

	fun remove(context: Context, id: String): Boolean = synchronized(lock) {
		val customs = custom(context).toMutableList()
		val removed = customs.firstOrNull { it.id == id } ?: return false
		customs.remove(removed)
		if (!persist(context, customs)) return false
		removed.treeUri?.let { releaseIfUnused(context, it, customs) }
		true
	}

	/** Stores a new order. Unknown ids are ignored; destinations missing from [ids] keep their place at the end. */
	fun reorder(context: Context, ids: List<String>): Boolean = synchronized(lock) {
		val customs = custom(context)
		val byId = customs.associateBy { it.id }
		val ordered = ids.distinct().mapNotNull { byId[it] }
		val rest = customs.filterNot { it in ordered }
		val result = ordered + rest
		if (result == customs) return true
		persist(context, result)
	}

	private fun persist(context: Context, customs: List<SaveDestination>): Boolean {
		val ok = DestinationStore.write(context, customs)
		if (ok) DirectShareHelper.publish(context)
		return ok
	}

	private fun releaseIfUnused(context: Context, treeUri: String, remaining: List<SaveDestination>) {
		if (remaining.any { it.treeUri == treeUri }) return
		try {
			context.contentResolver.persistedUriPermissions.filter { it.uri.toString() == treeUri }.forEach {
				var flags = 0
				if (it.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
				if (it.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				context.contentResolver.releasePersistableUriPermission(it.uri, flags)
			}
		} catch (e: RuntimeException) {
			Log.w(TAG, "Unable to release folder access: ${e.message}")
		}
	}

	private fun treeDisplayName(context: Context, tree: Uri): String? = try {
		val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
			if (it.moveToFirst()) it.getString(0)?.takeIf { name -> name.isNotBlank() } else null
		}
	} catch (e: Exception) {
		null
	}

	/** The name of the app providing the folder, e.g. "External Storage" or a cloud app. */
	private fun providerLabel(context: Context, tree: Uri): String? = try {
		val authority = tree.authority ?: return null
		context.packageManager.resolveContentProvider(authority, 0)?.loadLabel(context.packageManager)?.toString()
			?.takeIf { it.isNotBlank() }
	} catch (e: RuntimeException) {
		null
	}
}
