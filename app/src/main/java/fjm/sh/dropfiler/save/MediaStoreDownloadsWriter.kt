package fjm.sh.dropfiler.save

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Saves new files into `Internal storage/Download` (the root of the standard Downloads
 * folder, not a sub folder) through MediaStore.Downloads on the primary shared storage
 * volume. Needs no permission on Android 10+.
 *
 * Each file is inserted as pending (IS_PENDING = 1), streamed, then published. On failure
 * the new row is deleted, so no partial file is left behind.
 *
 * Duplicate handling. Without storage permissions an app only sees the Download items it
 * created itself, so:
 * - files DropFiler saved earlier are detected and handled by the policy (replace, skip,
 *   number);
 * - files from other apps are invisible. MediaStore then gives the new file a unique name
 *   such as "a (1).txt". With "Do not save" that renamed file is removed again and the save
 *   is reported as skipped; with "Overwrite" the other app's file is never replaced and the
 *   new name is reported; with "Append number" the provider's name is accepted.
 * The name reported is always the one MediaStore actually used.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreDownloadsWriter(context: Context) : DestinationWriter {

	companion object {
		private const val TAG = "MediaStoreDownloads"
		/** RELATIVE_PATH of the Download root, as MediaStore stores it. */
		val RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/"
		private val lock = Any()

		fun collection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
	}

	private val resolver: ContentResolver = context.contentResolver

	override fun write(request: WriteRequest): WriteOutcome {
		val collection = collection()
		val existing: Uri?
		val target: Uri
		val name: String
		try {
			synchronized(lock) {
				existing = findOwn(collection, request.name)
				name = when {
					existing == null -> request.name
					request.policy == DuplicatePolicy.SKIP -> return WriteOutcome.Failed(FailReason.EXISTS)
					request.policy == DuplicatePolicy.POSTFIX ->
						Filenames.firstFree(request.name) { findOwn(collection, it) != null }
							?: return WriteOutcome.Failed(FailReason.TOO_MANY_DUPLICATES)
					else -> {
						if (sameItem(existing, request.sourceUri)) return WriteOutcome.Failed(FailReason.SELF_OVERWRITE)
						// Staged next to the original; renamed over it once complete
						request.name
					}
				}
				target = resolver.insert(collection, ContentValues().apply {
					put(MediaStore.MediaColumns.DISPLAY_NAME, name)
					put(MediaStore.MediaColumns.MIME_TYPE, request.mime)
					put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
					put(MediaStore.MediaColumns.IS_PENDING, 1)
				}) ?: return WriteOutcome.Failed(FailReason.DESTINATION_UNAVAILABLE)

				if (request.policy == DuplicatePolicy.SKIP && existing == null) {
					val actual = displayName(target)
					if (actual != null && actual != name) {
						// A file of another app has this name; MediaStore renamed ours
						deleteQuietly(target)
						return WriteOutcome.Failed(FailReason.EXISTS)
					}
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "Unable to create download item: ${e.javaClass.simpleName}")
			return failureFor(e)
		}

		try {
			request.openSource().use { input ->
				(resolver.openOutputStream(target, "w") ?: throw java.io.FileNotFoundException("No output stream"))
					.use { output -> copyStream(input, output, request.cancelled) }
			}
		} catch (e: Exception) {
			deleteQuietly(target)
			return failureFor(e)
		}

		var replaced = false
		try {
			val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
			if (request.policy == DuplicatePolicy.OVERWRITE && existing != null) {
				// The new copy is complete; only now remove the old one (ours, so allowed)
				replaced = resolver.delete(existing, null, null) > 0
				if (replaced) publish.put(MediaStore.MediaColumns.DISPLAY_NAME, request.name)
			}
			if (resolver.update(target, publish, null, null) < 1) {
				deleteQuietly(target)
				return WriteOutcome.Failed(FailReason.IO)
			}
		} catch (e: Exception) {
			deleteQuietly(target)
			return failureFor(e)
		}
		return WriteOutcome.Saved(displayName(target) ?: name, target, replaced)
	}

	private fun sameItem(existing: Uri, source: Uri?): Boolean =
		source != null && source.authority == existing.authority &&
			source.lastPathSegment != null && source.lastPathSegment == existing.lastPathSegment

	/** The id of a published Download item of this app with exactly this name, if any. */
	private fun findOwn(collection: Uri, name: String): Uri? =
		query(collection, arrayOf(MediaStore.MediaColumns._ID),
			"${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
			arrayOf(RELATIVE_PATH, name), includePending = false)?.use {
			if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null
		}

	private fun displayName(item: Uri): String? = try {
		query(item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, includePending = true)?.use {
			if (it.moveToFirst()) it.getString(0) else null
		}
	} catch (e: RuntimeException) {
		null
	}

	private fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?,
					  includePending: Boolean): Cursor? =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			resolver.query(uri, projection, Bundle().apply {
				selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
				args?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
				if (includePending) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
			}, null)
		} else {
			@Suppress("DEPRECATION")
			resolver.query(if (includePending) MediaStore.setIncludePending(uri) else uri, projection, selection, args, null)
		}

	private fun deleteQuietly(uri: Uri) {
		try {
			resolver.delete(uri, null, null)
		} catch (e: RuntimeException) {
			Log.w(TAG, "Unable to clean up incomplete download item")
		}
	}
}
