package fjm.sh.dropfiler.save

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * One-time "Save as" (Choose location when saving): writes into the single document the
 * user just created with ACTION_CREATE_DOCUMENT. This is a document URI with a temporary
 * grant, not a persistent destination tree. The system picker already handled name
 * conflicts with the user, so the duplicate policy does not apply.
 */
class SingleDocumentWriter(context: Context, private val document: Uri) : DestinationWriter {

	private val resolver = context.contentResolver

	override fun write(request: WriteRequest): WriteOutcome {
		if (request.sourceUri != null && request.sourceUri == document) return WriteOutcome.Failed(FailReason.SELF_OVERWRITE)
		return try {
			request.openSource().use { input ->
				openTruncating().use { output -> copyStream(input, output, request.cancelled) }
			}
			WriteOutcome.Saved(displayName() ?: request.name, document)
		} catch (e: Exception) {
			failureFor(e)
		}
	}

	/**
	 * "wt" truncates an existing document the user chose to replace. Some providers only
	 * accept "w"; the document was just created or confirmed by the user, so that is used
	 * as a fallback.
	 */
	private fun openTruncating(): OutputStream = try {
		resolver.openOutputStream(document, "wt")
	} catch (e: IllegalArgumentException) {
		null
	} catch (e: FileNotFoundException) {
		null
	} catch (e: UnsupportedOperationException) {
		null
	} ?: resolver.openOutputStream(document, "w") ?: throw FileNotFoundException("No output stream")

	private fun displayName(): String? = try {
		resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
			if (it.moveToFirst()) it.getString(0) else null
		}
	} catch (e: Exception) {
		null
	}
}
