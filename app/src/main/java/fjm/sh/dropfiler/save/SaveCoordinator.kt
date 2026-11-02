package fjm.sh.dropfiler.save

import android.content.Context
import android.net.Uri
import android.os.Build
import fjm.sh.dropfiler.destination.DestinationKind
import fjm.sh.dropfiler.destination.SaveDestination
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Settings that affect saving (from [fjm.sh.dropfiler.util.PreferenceHelper]). */
data class SaveSettings(
	val policy: DuplicatePolicy,
	val lenientNames: Boolean,
	val followRedirects: Boolean,
	val forceSaving: Boolean,
	val registerMediaScanner: Boolean,
)

/** An item ready to be written: final name, MIME type and how to open its content. */
class PreparedItem(
	val name: String,
	val mime: String,
	val source: SourceOpener,
	val sourceUri: Uri? = null,
)

data class ItemResult(val requestedName: String, val outcome: WriteOutcome)

/** The outcome of saving all items of one share to one target. */
data class SaveReport(val results: List<ItemResult>) {
	val saved: List<WriteOutcome.Saved> get() = results.mapNotNull { it.outcome as? WriteOutcome.Saved }
	val failed: List<ItemResult> get() = results.filter { it.outcome is WriteOutcome.Failed }
	val allSaved: Boolean get() = results.isNotEmpty() && failed.isEmpty()
	val cancelled: Boolean
		get() = results.any { (it.outcome as? WriteOutcome.Failed)?.reason == FailReason.CANCELLED }

	/** The common failure reason, if every failed item failed for the same reason. */
	val failReason: FailReason?
		get() = failed.map { (it.outcome as WriteOutcome.Failed).reason }.distinct().singleOrNull()
}

/**
 * The single save engine used by Share, Share multiple, "Open with", Direct Share, text,
 * links and Save as. Activities only choose the target and show the result.
 *
 * All methods block and must run on a background thread.
 */
class SaveCoordinator(context: Context, private val settings: SaveSettings) {

	private val context = context.applicationContext

	sealed class Prepared {
		data class Ready(val items: List<PreparedItem>) : Prepared()
		/** Nothing in the share can be saved (unknown content, unsupported link type). */
		object Unsupported : Prepared()
	}

	/**
	 * Works out names and types. For links this asks the server for the content type, as
	 * before; [dryRun] only checks whether the share is supported.
	 */
	fun prepare(items: List<IncomingItem>, dryRun: Boolean = false): Prepared {
		if (items.isEmpty()) return Prepared.Unsupported
		val prepared = items.map { item ->
			when (item) {
				is IncomingItem.Stream -> prepareStream(item)
				is IncomingItem.Text -> prepareText(item.subject ?: item.text, item.text,
					Filenames.baseMime(item.type) ?: "text/plain")
				is IncomingItem.Link -> prepareLink(item, dryRun) ?: return Prepared.Unsupported
			}
		}
		return Prepared.Ready(prepared)
	}

	private fun prepareStream(item: IncomingItem.Stream): PreparedItem {
		val rawName = IncomingContentParser.displayName(context, item.uri) ?: Filenames.FALLBACK_NAME
		val mime = IncomingContentParser.mimeOf(context, item, rawName)
		val name = Filenames.sanitize(rawName, mime, settings.lenientNames).name
		return PreparedItem(name, Filenames.outputMime(name, mime), {
			context.contentResolver.openInputStream(item.uri) ?: throw IOException("No input stream")
		}, item.uri)
	}

	private fun prepareText(rawName: String, text: String, mime: String): PreparedItem {
		val name = Filenames.sanitize(rawName, mime, settings.lenientNames).name
		val bytes = text.toByteArray(Charsets.UTF_8)
		return PreparedItem(name, Filenames.outputMime(name, mime), { ByteArrayInputStream(bytes) })
	}

	/**
	 * Links keep their earlier behaviour: images, videos and audio are downloaded; for text
	 * types (web pages) the link itself is saved as text; anything else is downloaded only
	 * with "Force saving".
	 */
	private fun prepareLink(item: IncomingItem.Link, dryRun: Boolean): PreparedItem? {
		val probe = try {
			UrlDownloader.probe(item.url, settings.followRedirects)
		} catch (e: IOException) {
			return null
		}
		val type = Filenames.baseMime(probe.contentType) ?: return null
		val rawName = item.subject ?: Uri.parse(probe.url).lastPathSegment ?: Filenames.FALLBACK_NAME
		val download = type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/") ||
			(!type.startsWith("text/") && settings.forceSaving && !dryRun)
		return when {
			download -> {
				val name = Filenames.sanitize(rawName, type, settings.lenientNames).name
				PreparedItem(name, Filenames.outputMime(name, type), { UrlDownloader.open(probe.url) })
			}
			type.startsWith("text/") -> prepareText(rawName, probe.url, type)
			else -> null
		}
	}

	fun writerFor(destination: SaveDestination): DestinationWriter = when (destination.kind) {
		DestinationKind.MEDIASTORE_DOWNLOADS_DEFAULT ->
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreDownloadsWriter(context)
			else LegacyDownloadsWriter(context, scan = settings.registerMediaScanner)
		DestinationKind.SAF_TREE -> SafTreeWriter(context, Uri.parse(destination.treeUri), destination.id)
		DestinationKind.LEGACY_RECONNECT_REQUIRED -> object : DestinationWriter {
			// A path can never be written to without a new grant
			override fun write(request: WriteRequest) = WriteOutcome.Failed(FailReason.NO_PERMISSION)
		}
	}

	fun writerFor(document: Uri): DestinationWriter = SingleDocumentWriter(context, document)

	/** Saves the items one after another (bounded memory), stopping early when cancelled. */
	fun save(items: List<PreparedItem>, writer: DestinationWriter, cancelled: AtomicBoolean): SaveReport {
		val results = ArrayList<ItemResult>(items.size)
		for (item in items) {
			val outcome = if (cancelled.get()) WriteOutcome.Failed(FailReason.CANCELLED)
			else try {
				writer.write(WriteRequest(item.name, item.mime, settings.policy, item.source, item.sourceUri, cancelled))
			} catch (e: Exception) {
				failureFor(e)
			}
			results.add(ItemResult(item.name, outcome))
		}
		return SaveReport(results)
	}

}
