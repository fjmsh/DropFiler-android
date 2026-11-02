package fjm.sh.dropfiler.save

import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Opens the content to save. Called once, on a background thread. */
fun interface SourceOpener {
	@Throws(IOException::class)
	fun open(): InputStream
}

/** Thrown by [copyStream] when the user cancelled. */
class SaveCancelledException : IOException("Cancelled")

/** Thrown when the source cannot be read (as opposed to the destination failing). */
class SourceUnreadableException(cause: Throwable) : IOException(cause.message, cause)

class WriteRequest(
	/** The sanitized filename to save as. */
	val name: String,
	/** Concrete MIME type for the output. */
	val mime: String,
	val policy: DuplicatePolicy,
	val source: SourceOpener,
	/** The source content URI, to refuse saving a file over itself. */
	val sourceUri: Uri? = null,
	val cancelled: AtomicBoolean = AtomicBoolean(false),
)

enum class FailReason {
	/** The destination already has a file with this name and the policy is [DuplicatePolicy.SKIP]. */
	EXISTS,
	/** Overwriting would replace the source with itself. */
	SELF_OVERWRITE,
	SOURCE_UNREADABLE,
	/** The folder is gone, read-only or its storage is unmounted. */
	DESTINATION_UNAVAILABLE,
	/** Access to the destination was revoked, or the legacy storage permission is missing. */
	NO_PERMISSION,
	NO_SPACE,
	/** No free numbered name was found. */
	TOO_MANY_DUPLICATES,
	CANCELLED,
	/** Network download failed (HTTP error, timeout, no connection). */
	NETWORK,
	IO,
}

sealed class WriteOutcome {
	/**
	 * @param name The name actually used. Providers may change it (e.g. "a (1).txt").
	 * @param replaced An existing file was replaced.
	 */
	data class Saved(val name: String, val uri: Uri?, val replaced: Boolean = false) : WriteOutcome()

	data class Failed(val reason: FailReason, val cause: Throwable? = null) : WriteOutcome()
}

/** Writes one file into one destination, applying the duplicate policy. */
interface DestinationWriter {
	/** Blocking; call on a background thread. Never throws. */
	fun write(request: WriteRequest): WriteOutcome
}

private const val BUFFER_SIZE = 64 * 1024

/**
 * Copies with a bounded buffer, checking for cancellation between chunks. Read errors are
 * reported as [SourceUnreadableException] so they can be told apart from write errors.
 */
@Throws(IOException::class)
fun copyStream(input: InputStream, output: OutputStream, cancelled: AtomicBoolean): Long {
	val buffer = ByteArray(BUFFER_SIZE)
	var total = 0L
	while (true) {
		if (cancelled.get()) throw SaveCancelledException()
		val read = try {
			input.read(buffer)
		} catch (e: IOException) {
			throw SourceUnreadableException(e)
		}
		if (read < 0) break
		output.write(buffer, 0, read)
		total += read
	}
	output.flush()
	return total
}

/** Classifies an exception thrown while saving. */
fun failureFor(e: Throwable): WriteOutcome.Failed = WriteOutcome.Failed(when {
	e is SaveCancelledException -> FailReason.CANCELLED
	e is SourceUnreadableException -> if (e.cause is NetworkException) FailReason.NETWORK else FailReason.SOURCE_UNREADABLE
	e is NetworkException -> FailReason.NETWORK
	e is SecurityException -> FailReason.NO_PERMISSION
	e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true -> FailReason.NO_SPACE
	e is java.io.FileNotFoundException -> FailReason.DESTINATION_UNAVAILABLE
	else -> FailReason.IO
}, e)

/** Opens the source, mapping open failures to [SourceUnreadableException]. */
@Throws(IOException::class)
fun WriteRequest.openSource(): InputStream = try {
	source.open()
} catch (e: NetworkException) {
	throw e
} catch (e: IOException) {
	throw SourceUnreadableException(e)
} catch (e: SecurityException) {
	throw SourceUnreadableException(e)
} catch (e: RuntimeException) {
	throw SourceUnreadableException(e)
}
