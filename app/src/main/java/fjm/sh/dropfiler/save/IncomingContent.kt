package fjm.sh.dropfiler.save

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import java.util.Locale

/** One thing to save, as received from Share or "Open with". */
sealed class IncomingItem {
	/** A file (content:// or, best effort, file://). Only ever opened for reading. */
	data class Stream(val uri: Uri, val type: String?) : IncomingItem()

	data class Text(val text: String, val subject: String?, val type: String?) : IncomingItem()

	/** Shared text that is a single http(s) link. */
	data class Link(val url: String, val subject: String?) : IncomingItem()
}

/**
 * Normalizes ACTION_SEND, ACTION_SEND_MULTIPLE and ACTION_VIEW intents. Malformed extras
 * from the sending app give an empty list instead of a crash.
 */
object IncomingContentParser {

	fun parse(intent: Intent?): List<IncomingItem> {
		intent ?: return emptyList()
		return try {
			when (intent.action) {
				Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf(::isSupportedScheme)?.let {
					IncomingItem.Stream(it, intent.type)
				})
				Intent.ACTION_SEND_MULTIPLE -> streams(intent)
				else -> single(intent)
			}
		} catch (e: RuntimeException) {
			// BadParcelableException, ClassCastException, …
			emptyList()
		}
	}

	fun isSupportedScheme(uri: Uri) =
		uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE

	private fun single(intent: Intent): List<IncomingItem> {
		IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
			return listOf(IncomingItem.Stream(it, intent.type))
		}
		intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let {
			return listOf(IncomingItem.Stream(it, intent.type))
		}
		val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return emptyList()
		val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
		return listOf(if (UrlDownloader.isHttpUrl(text)) IncomingItem.Link(text.trim(), subject)
			else IncomingItem.Text(text, subject, intent.type))
	}

	private fun streams(intent: Intent): List<IncomingItem> {
		val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
			?.filterNotNull()?.takeIf { it.isNotEmpty() }
			?: intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
			?: emptyList()
		return uris.map { IncomingItem.Stream(it, intent.type) }
	}

	/** Display name of a content URI, falling back to the last path segment. */
	fun displayName(context: Context, uri: Uri): String? {
		if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
			try {
				context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
					val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
					if (index >= 0 && it.moveToFirst() && !it.isNull(index)) {
						it.getString(index)?.takeIf { name -> name.isNotBlank() }?.let { name -> return name }
					}
				}
			} catch (e: RuntimeException) {
				// SecurityException / IllegalArgumentException from the provider; fall back below
			}
		}
		return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
	}

	/** MIME type: the sender's (if concrete), then the provider's, then the extension, else binary. */
	fun mimeOf(context: Context, item: IncomingItem.Stream, name: String?): String =
		Filenames.baseMime(item.type)?.takeIf { Filenames.isConcrete(it) }
			?: try { Filenames.baseMime(context.contentResolver.getType(item.uri)) } catch (e: RuntimeException) { null }
			?: name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.let {
				MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase(Locale.ROOT))
			}
			?: Filenames.FALLBACK_MIME
}
