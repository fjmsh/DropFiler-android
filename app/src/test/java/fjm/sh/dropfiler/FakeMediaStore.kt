package fjm.sh.dropfiler

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * Stands in for MediaProvider's Downloads collection (authority "media"), which Robolectric
 * does not have. It models the rules DropFiler relies on:
 * - items are files in [dir] (the Download root); RELATIVE_PATH must be "Download/";
 * - new items get a unique name on conflicts with *any* file ("a (1).txt");
 * - queries only see this app's rows, and not pending rows unless asked to (files of other
 *   apps are present on disk but invisible, as without storage permission);
 * - updates publish (IS_PENDING = 0) and rename (DISPLAY_NAME).
 *
 * It cannot prove real MediaProvider behaviour; that needs a device (see README).
 */
class FakeMediaStore : ContentProvider() {

	class Row(var name: String, val mime: String?, var pending: Boolean, val relativePath: String)

	companion object {
		lateinit var dir: File
		val rows = linkedMapOf<Long, Row>()
		private var nextId = 1L
		/** Whether each item was pending when content was written to it. */
		val pendingAtWrite = mutableListOf<Boolean>()
		var failInsert = false
		var failPublish = false

		fun reset(downloads: File) {
			dir = downloads.apply { mkdirs() }
			rows.clear()
			nextId = 1L
			pendingAtWrite.clear()
			failInsert = false
			failPublish = false
		}

		fun register() {
			Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
		}

		/** Visible (published) names, as DropFiler's own queries see them. */
		fun publishedNames() = rows.values.filter { !it.pending }.map { it.name }

		fun file(name: String) = File(dir, name)
	}

	override fun onCreate() = true

	private fun idOf(uri: Uri): Long? = uri.lastPathSegment?.toLongOrNull()

	private fun unique(name: String, except: Long? = null): String {
		fun taken(n: String) = File(dir, n).exists() && rows.entries.none { it.key == except && it.value.name == n }
		if (!taken(name)) return name
		val dot = name.lastIndexOf('.')
		val base = if (dot > 0) name.substring(0, dot) else name
		val ext = if (dot > 0) name.substring(dot) else ""
		var i = 1
		while (taken("$base ($i)$ext")) i++
		return "$base ($i)$ext"
	}

	override fun insert(uri: Uri, values: ContentValues?): Uri? {
		if (failInsert) return null
		values ?: return null
		val relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
		require(relativePath == "Download/") { "Downloads only accepts the Download folder, got $relativePath" }
		val name = unique(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
		File(dir, name).createNewFile()
		val id = nextId++
		rows[id] = Row(name, values.getAsString(MediaStore.MediaColumns.MIME_TYPE),
			values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1, relativePath)
		return ContentUris.withAppendedId(uri, id)
	}

	override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
		val includePending = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE ||
			uri.getQueryParameter("includePending") == "1"
		return select(uri, projection, queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
			queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), includePending)
	}

	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
					   sortOrder: String?): Cursor =
		select(uri, projection, selection, selectionArgs, uri.getQueryParameter("includePending") == "1")

	private fun select(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?,
					   includePending: Boolean): Cursor {
		val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
		val cursor = MatrixCursor(columns)
		val conditions = selection?.split(" AND ")?.mapIndexed { i, c -> c.substringBefore("=?").trim() to args!![i] }.orEmpty()
		val id = idOf(uri)
		rows.filter { (rowId, row) ->
			(id == null || rowId == id) && (includePending || !row.pending) && conditions.all { (column, value) ->
				when (column) {
					MediaStore.MediaColumns.RELATIVE_PATH -> row.relativePath == value
					MediaStore.MediaColumns.DISPLAY_NAME -> row.name == value
					else -> error("Unsupported selection column $column")
				}
			}
		}.forEach { (rowId, row) ->
			cursor.addRow(columns.map {
				when (it) {
					MediaStore.MediaColumns._ID -> rowId
					MediaStore.MediaColumns.DISPLAY_NAME -> row.name
					MediaStore.MediaColumns.IS_PENDING -> if (row.pending) 1 else 0
					else -> null
				}
			}.toTypedArray())
		}
		return cursor
	}

	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
		if (failPublish) return 0
		val id = idOf(uri) ?: return 0
		val row = rows[id] ?: return 0
		values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)?.let { wanted ->
			val newName = unique(wanted, except = id)
			File(dir, row.name).renameTo(File(dir, newName))
			row.name = newName
		}
		values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)?.let { row.pending = it == 1 }
		return 1
	}

	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
		val id = idOf(uri) ?: return 0
		val row = rows.remove(id) ?: return 0
		File(dir, row.name).delete()
		return 1
	}

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
		val row = idOf(uri)?.let { rows[it] } ?: throw FileNotFoundException(uri.toString())
		if (mode != "r") pendingAtWrite.add(row.pending)
		val file = File(dir, row.name)
		return ParcelFileDescriptor.open(file, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
			else ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_CREATE)
	}

	override fun getType(uri: Uri): String? = idOf(uri)?.let { rows[it]?.mime }
}
