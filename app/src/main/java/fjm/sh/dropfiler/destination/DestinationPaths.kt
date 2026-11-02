package fjm.sh.dropfiler.destination

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.annotation.VisibleForTesting
import fjm.sh.dropfiler.R

/**
 * The human-readable path of a destination folder, e.g. `Internal storage/Documents/Work`,
 * shown under its name in the destination lists.
 *
 * Folders on the device's own storage (the system "Local storage" / "External Storage"
 * provider, and the Downloads provider) have their path in the document id, so it is shown in
 * full. Other providers (cloud apps, ...) use opaque ids, so their path is the provider's name
 * and the folder's name, e.g. `Google Drive/Work`.
 *
 * It is worked out from the stored tree URI each time, without provider I/O, so it is safe on
 * the main thread and also covers destinations added by earlier versions.
 */
object DestinationPaths {

	@VisibleForTesting const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
	@VisibleForTesting const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

	private const val ROOT_PRIMARY = "primary"
	/** ExternalStorageProvider's "Documents" root (the primary storage's Documents folder). */
	private const val ROOT_HOME = "home"
	private const val DOWNLOADS_ROOT_ID = "downloads"
	private const val RAW_PREFIX = "raw:"
	private const val PRIMARY_STORAGE_PATH = "/storage/emulated/0"

	/**
	 * The path of [destination], or null when there is nothing better than a generic summary
	 * (no tree URI, or an unknown provider without a name).
	 */
	fun pathOf(context: Context, destination: SaveDestination): String? {
		if (destination.isDefault) return context.getString(R.string.default_destination_path)
		val tree = destination.treeUri?.let(Uri::parse)
		val docId = tree?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
		return format(
			authority = tree?.authority,
			treeDocumentId = docId,
			label = destination.label,
			providerName = destination.description,
			internalStorage = context.getString(R.string.storage_internal),
			sdCard = context.getString(R.string.storage_sd_card),
			volumeName = { volumeDescription(context, it) },
		)
	}

	@VisibleForTesting
	fun format(
		authority: String?,
		treeDocumentId: String?,
		label: String,
		providerName: String?,
		internalStorage: String,
		sdCard: String,
		volumeName: (uuid: String) -> String? = { null },
	): String? {
		if (treeDocumentId != null) when (authority) {
			EXTERNAL_STORAGE_AUTHORITY -> {
				val root = treeDocumentId.substringBefore(':')
				val relative = treeDocumentId.substringAfter(':', "").trim('/')
				val rootName = when (root) {
					ROOT_PRIMARY -> internalStorage
					ROOT_HOME -> "$internalStorage/Documents"
					else -> volumeName(root)?.takeIf { it.isNotBlank() } ?: sdCard
				}
				return join(rootName, relative)
			}
			DOWNLOADS_AUTHORITY -> when {
				treeDocumentId == DOWNLOADS_ROOT_ID -> return "$internalStorage/Download"
				treeDocumentId.startsWith(RAW_PREFIX) -> {
					val path = treeDocumentId.removePrefix(RAW_PREFIX).trimEnd('/')
					if (path == PRIMARY_STORAGE_PATH || path.startsWith("$PRIMARY_STORAGE_PATH/")) {
						return join(internalStorage, path.removePrefix(PRIMARY_STORAGE_PATH).trim('/'))
					}
				}
			}
		}
		val provider = providerName?.takeIf { it.isNotBlank() } ?: return null
		return if (label.isBlank()) provider else "$provider/$label"
	}

	private fun join(root: String, relative: String) = if (relative.isEmpty()) root else "$root/$relative"

	/** The name of the removable volume with this file system UUID, e.g. "SanDisk SD card". */
	private fun volumeDescription(context: Context, uuid: String): String? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
		return try {
			context.getSystemService(StorageManager::class.java)?.storageVolumes
				?.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.getDescription(context)
		} catch (e: RuntimeException) {
			null
		}
	}
}
