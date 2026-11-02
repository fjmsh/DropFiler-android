package fjm.sh.dropfiler

import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.content.ContentProvider
import android.content.ContentValues
import android.os.Bundle
import android.webkit.MimeTypeMap
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * A document provider backed by a directory, standing in for DocumentsUI providers
 * (ExternalStorageProvider, SD cards, cloud apps) in Robolectric tests. It speaks the
 * DocumentsContract protocol (tree/document/children URIs and the android:createDocument,
 * android:deleteDocument and android:renameDocument calls), and like a real provider it
 * refuses documents outside the granted tree. Document ids are paths relative to [root].
 * Like ExternalStorageProvider it renames new documents on name conflicts ("a (1).txt").
 *
 * It is a plain ContentProvider because Robolectric's ContentResolver uses the query form
 * that the framework DocumentsProvider rejects.
 *
 * Failure modes can be switched on per test: read-only folders, no delete support,
 * unsupported "wt" mode, failing writes, and "unmounted" storage.
 */
class TestDocumentsProvider : ContentProvider() {

	companion object {
		const val AUTHORITY = "fjm.sh.dropfiler.test.documents"
		lateinit var root: File

		var readOnly = mutableSetOf<String>()
		var supportsDelete = true
		var supportsTruncate = true
		var unmounted = false
		/** Writing content fails (simulates a full or failing card). */
		var failWrites = false
		/** Calls to openDocument by mode, to verify that sources are never opened for writing. */
		val opened = mutableListOf<String>()

		fun reset(dir: File) {
			root = dir.apply { deleteRecursively(); mkdirs() }
			readOnly = mutableSetOf()
			supportsDelete = true
			supportsTruncate = true
			unmounted = false
			failWrites = false
			opened.clear()
		}

		fun register() {
			Robolectric.setupContentProvider(TestDocumentsProvider::class.java, AUTHORITY)
		}

		/** Creates a folder and returns its tree URI, as ACTION_OPEN_DOCUMENT_TREE would. */
		fun tree(path: String): Uri {
			File(root, path).mkdirs()
			return DocumentsContract.buildTreeDocumentUri(AUTHORITY, path)
		}

		/** The picker result for [tree], with the grant flags DocumentsUI adds. */
		fun pickerResult(tree: Uri): Intent = Intent().setData(tree).addFlags(
			Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
				Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

		fun file(path: String) = File(root, path)
	}

	private val docColumns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)

	override fun onCreate() = true

	/** The document id, after checking that it lies inside the URI's tree (if any). */
	private fun documentId(uri: Uri): String {
		val id = DocumentsContract.getDocumentId(uri)
		if (isTree(uri)) {
			val tree = DocumentsContract.getTreeDocumentId(uri)
			if (id != tree && !isChildDocument(tree, id)) throw SecurityException("Outside of the granted tree")
		}
		return id
	}

	private fun isTree(uri: Uri) = uri.pathSegments.firstOrNull() == "tree"

	private fun buildUri(like: Uri, id: String): Uri =
		if (isTree(like)) DocumentsContract.buildDocumentUriUsingTree(like, id)
		else DocumentsContract.buildDocumentUri(AUTHORITY, id)

	override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
					   selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
		val segments = uri.pathSegments
		return if (segments.lastOrNull() == "children") {
			queryChildDocuments(documentId(uri), projection)
		} else {
			queryDocument(documentId(uri), projection)
		}
	}

	override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
		val uri = extras?.let { androidx.core.os.BundleCompat.getParcelable(it, "uri", Uri::class.java) }
			?: return super.call(method, arg, extras)
		val id = documentId(uri)
		val out = Bundle()
		when (method) {
			"android:createDocument" -> out.putParcelable("uri", buildUri(uri, createDocument(id,
				extras.getString(Document.COLUMN_MIME_TYPE)!!, extras.getString(Document.COLUMN_DISPLAY_NAME)!!)))
			"android:deleteDocument" -> deleteDocument(id)
			"android:renameDocument" -> out.putParcelable("uri", buildUri(uri,
				renameDocument(id, extras.getString(Document.COLUMN_DISPLAY_NAME)!!)))
			else -> throw UnsupportedOperationException(method)
		}
		return out
	}

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openDocument(documentId(uri), mode, null)

	override fun getType(uri: Uri): String? = try {
		val f = fileFor(documentId(uri))
		if (f.isDirectory) Document.MIME_TYPE_DIR else mimeOf(f.name)
	} catch (e: Exception) {
		null
	}

	override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
		throw UnsupportedOperationException()
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
		throw UnsupportedOperationException()

	private fun fileFor(id: String): File {
		if (unmounted) throw IllegalStateException("Storage unmounted")
		val f = File(root, id)
		if (!f.exists()) throw FileNotFoundException("Missing $id")
		return f
	}

	private fun idFor(file: File) = file.relativeTo(root).path

	private fun row(cursor: MatrixCursor, file: File, projection: Array<out String>?) {
		val id = idFor(file)
		val dir = file.isDirectory
		var flags = 0
		if (dir && id !in readOnly) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
		if (!dir && file.parentFile?.let { idFor(it) } !in readOnly) {
			flags = flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME
			if (supportsDelete) flags = flags or Document.FLAG_SUPPORTS_DELETE
		}
		val values = mapOf(
			Document.COLUMN_DOCUMENT_ID to id,
			Document.COLUMN_DISPLAY_NAME to file.name,
			Document.COLUMN_MIME_TYPE to if (dir) Document.MIME_TYPE_DIR else mimeOf(file.name),
			Document.COLUMN_FLAGS to flags,
			Document.COLUMN_SIZE to file.length(),
		)
		cursor.addRow((projection ?: docColumns).map { values[it] }.toTypedArray())
	}

	private fun mimeOf(name: String) = MimeTypeMap.getSingleton()
		.getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "application/octet-stream"

	fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
		MatrixCursor(projection ?: docColumns).apply { row(this, fileFor(documentId), projection) }

	fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?): Cursor =
		MatrixCursor(projection ?: docColumns).apply {
			fileFor(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { row(this, it, projection) }
		}

	fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
		documentId.startsWith("$parentDocumentId/")

	fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
		val parent = fileFor(parentDocumentId)
		if (parentDocumentId in readOnly) throw SecurityException("Read-only")
		var target = File(parent, displayName)
		var i = 1
		val base = displayName.substringBeforeLast('.')
		val ext = displayName.substringAfterLast('.', "").let { if (it.isEmpty() || !displayName.contains('.')) "" else ".$it" }
		while (target.exists()) target = File(parent, "$base (${i++})$ext")
		if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
		return idFor(target)
	}

	fun deleteDocument(documentId: String) {
		if (!supportsDelete) throw UnsupportedOperationException("Delete not supported")
		if (!fileFor(documentId).deleteRecursively()) throw IllegalStateException("Unable to delete")
	}

	fun renameDocument(documentId: String, displayName: String): String {
		val file = fileFor(documentId)
		val target = File(file.parentFile, displayName)
		if (target.exists()) throw IllegalStateException("Exists")
		file.renameTo(target)
		return idFor(target)
	}

	fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
		opened.add(mode)
		val file = fileFor(documentId)
		if (mode == "wt" && !supportsTruncate) throw IllegalArgumentException("Unsupported mode wt")
		if (mode != "r" && file.parentFile?.let { idFor(it) } in readOnly) throw SecurityException("Read-only")
		val pfdMode = when (mode) {
			"r" -> ParcelFileDescriptor.MODE_READ_ONLY
			"wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
			// Like many providers, "w" does not truncate
			else -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
		}
		if (mode != "r" && failWrites) {
			// Writes to a read-only descriptor fail with an IOException, like a full card
			return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
		}
		return ParcelFileDescriptor.open(file, pfdMode)
	}
}
