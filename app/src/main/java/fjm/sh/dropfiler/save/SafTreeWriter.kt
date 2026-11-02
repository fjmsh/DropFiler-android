package fjm.sh.dropfiler.save

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.io.FileNotFoundException

/**
 * Saves into a user-authorized document tree (ACTION_OPEN_DOCUMENT_TREE with a persisted
 * grant), using only DocumentsContract and ContentResolver — never filesystem paths.
 *
 * - New files are created with createDocument; the provider may adjust the name (for
 *   example "a (1).txt" or a cleaned-up name), and the actual name is reported.
 * - Overwrite is staged: the new copy is written to a new document first. Only when it is
 *   complete is the old document deleted and the new one renamed to the original name.
 *   If the provider cannot delete the old document, its content is replaced from the
 *   complete copy with mode "wt" (truncate) and the copy is removed; if "wt" is not
 *   supported either, the old file is kept and the new copy keeps its own name.
 * - If writing fails, only the document created by this save is deleted.
 */
class SafTreeWriter(context: Context, private val tree: Uri, private val lockKey: String) : DestinationWriter {

	companion object {
		private const val TAG = "SafTreeWriter"
		private val locks = HashMap<String, Any>()
		private fun lockFor(key: String) = synchronized(locks) { locks.getOrPut(key) { Any() } }
	}

	private val resolver: ContentResolver = context.contentResolver

	private class Child(val uri: Uri, val flags: Int)

	override fun write(request: WriteRequest): WriteOutcome {
		val existing: Child?
		val created: Uri
		val createdName: String
		try {
			val parentId = DocumentsContract.getTreeDocumentId(tree)
			val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
			synchronized(lockFor(lockKey)) {
				val children = children(parentId)
				existing = children[request.name]
				val name = when {
					existing == null -> request.name
					request.policy == DuplicatePolicy.SKIP -> return WriteOutcome.Failed(FailReason.EXISTS)
					request.policy == DuplicatePolicy.POSTFIX ->
						Filenames.firstFree(request.name) { it in children } ?: return WriteOutcome.Failed(FailReason.TOO_MANY_DUPLICATES)
					else -> {
						if (sameDocument(existing.uri, request.sourceUri)) return WriteOutcome.Failed(FailReason.SELF_OVERWRITE)
						request.name
					}
				}
				created = DocumentsContract.createDocument(resolver, parent, request.mime, name)
					?: return WriteOutcome.Failed(FailReason.DESTINATION_UNAVAILABLE)
				createdName = displayName(created) ?: name
				if (request.policy == DuplicatePolicy.SKIP && createdName != name) {
					// Something with this name appeared, or the provider uses another name
					deleteQuietly(created)
					return WriteOutcome.Failed(FailReason.EXISTS)
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "Unable to create document: ${e.javaClass.simpleName}")
			return failureFor(e)
		}

		try {
			request.openSource().use { input ->
				(resolver.openOutputStream(created, "w") ?: throw FileNotFoundException("No output stream"))
					.use { output -> copyStream(input, output, request.cancelled) }
			}
		} catch (e: Exception) {
			deleteQuietly(created)
			return failureFor(e)
		}

		if (request.policy != DuplicatePolicy.OVERWRITE || existing == null) {
			return WriteOutcome.Saved(createdName, created)
		}
		return replace(existing, created, createdName, request)
	}

	/** The new copy at [created] is complete; put it in place of [old]. */
	private fun replace(old: Child, created: Uri, createdName: String, request: WriteRequest): WriteOutcome {
		if (old.flags and Document.FLAG_SUPPORTS_DELETE != 0 && deleteQuietly(old.uri)) {
			if (createdName == request.name) return WriteOutcome.Saved(createdName, created, replaced = true)
			val renamed = try {
				DocumentsContract.renameDocument(resolver, created, request.name)
			} catch (e: Exception) {
				null
			}
			val uri = renamed ?: created
			return WriteOutcome.Saved(displayName(uri) ?: createdName, uri, replaced = true)
		}
		// Cannot delete the old file: replace its content from the complete copy
		val ok = try {
			resolver.openInputStream(created).use { input ->
				(resolver.openOutputStream(old.uri, "wt") ?: throw FileNotFoundException()).use { output ->
					copyStream(input ?: throw FileNotFoundException(), output, request.cancelled)
				}
			}
			true
		} catch (e: Exception) {
			Log.w(TAG, "Provider does not support replacing content: ${e.javaClass.simpleName}")
			false
		}
		return if (ok) {
			deleteQuietly(created)
			WriteOutcome.Saved(request.name, old.uri, replaced = true)
		} else {
			// Keep both rather than risk the existing file
			WriteOutcome.Saved(createdName, created)
		}
	}

	private fun children(parentId: String): Map<String, Child> {
		val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
		val result = HashMap<String, Child>()
		resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_FLAGS),
			null, null, null)?.use { c ->
			while (c.moveToNext()) {
				val id = c.getString(0) ?: continue
				val name = c.getString(1) ?: continue
				result[name] = Child(DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getInt(2))
			}
		} ?: throw FileNotFoundException("Folder cannot be listed")
		return result
	}

	private fun displayName(doc: Uri): String? = try {
		resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
			if (it.moveToFirst()) it.getString(0) else null
		}
	} catch (e: Exception) {
		null
	}

	private fun sameDocument(doc: Uri, source: Uri?): Boolean {
		if (source == null || source.authority != doc.authority) return false
		return try {
			DocumentsContract.isDocumentUri(null, source) &&
				DocumentsContract.getDocumentId(source) == DocumentsContract.getDocumentId(doc)
		} catch (e: Exception) {
			false
		}
	}

	private fun deleteQuietly(doc: Uri): Boolean = try {
		DocumentsContract.deleteDocument(resolver, doc)
	} catch (e: Exception) {
		Log.w(TAG, "Unable to delete document: ${e.javaClass.simpleName}")
		false
	}
}
