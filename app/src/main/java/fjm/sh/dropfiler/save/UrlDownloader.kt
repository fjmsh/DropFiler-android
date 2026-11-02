package fjm.sh.dropfiler.save

import android.webkit.MimeTypeMap
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A link could not be downloaded (bad status, too many redirects, no connection). */
class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Downloads shared links by streaming the HTTP response straight into the chosen
 * destination (MediaStore, SAF tree or Save-as document). Replaces DownloadManager, which
 * can only write to filesystem paths and cannot target a document tree.
 *
 * Redirects are followed manually, including http ↔ https, which HttpURLConnection does
 * not follow by itself.
 */
object UrlDownloader {

	const val MAX_REDIRECTS = 10
	var connectTimeoutMs = 20_000
	var readTimeoutMs = 60_000

	data class Probe(val url: String, val contentType: String?)

	fun isHttpUrl(text: String): Boolean {
		val trimmed = text.trim()
		if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
		return try {
			val url = URL(trimmed)
			(url.protocol == "http" || url.protocol == "https") && !url.host.isNullOrEmpty()
		} catch (e: IOException) {
			false
		}
	}

	/**
	 * Finds the content type of a link, as before: from the response when redirects are
	 * followed, else from the URL extension, and only then by asking the server.
	 */
	@Throws(IOException::class)
	fun probe(url: String, followRedirects: Boolean): Probe {
		if (!followRedirects) {
			val ext = MimeTypeMap.getFileExtensionFromUrl(url)
			MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))?.let { return Probe(url, it) }
		}
		val conn = connect(url)
		try {
			val type = conn.contentType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
				MimeTypeMap.getFileExtensionFromUrl(conn.url.toString()).lowercase(Locale.ROOT))
			return Probe(if (followRedirects) conn.url.toString() else url, type)
		} finally {
			conn.disconnect()
		}
	}

	/**
	 * Opens the response body, following redirects (as DownloadManager did before; the
	 * setting only affects how the content type is found). The stream disconnects when closed.
	 */
	@Throws(IOException::class)
	fun open(url: String): InputStream {
		val conn = connect(url)
		val input = try {
			conn.inputStream
		} catch (e: IOException) {
			conn.disconnect()
			throw NetworkException("Unable to read response", e)
		}
		return object : FilterInputStream(input) {
			override fun read(): Int = wrap { super.read() }
			override fun read(b: ByteArray, off: Int, len: Int): Int = wrap { super.read(b, off, len) }
			override fun close() {
				try { super.close() } finally { conn.disconnect() }
			}
			private inline fun wrap(block: () -> Int): Int = try {
				block()
			} catch (e: IOException) {
				throw NetworkException("Download interrupted", e)
			}
		}
	}

	@Throws(IOException::class)
	private fun connect(start: String): HttpURLConnection {
		var current = URL(start)
		var redirects = 0
		while (true) {
			val conn = try {
				(current.openConnection() as? HttpURLConnection ?: throw NetworkException("Not an HTTP link")).apply {
					instanceFollowRedirects = false
					connectTimeout = connectTimeoutMs
					readTimeout = readTimeoutMs
					setRequestProperty("Accept-Encoding", "identity")
					connect()
				}
			} catch (e: NetworkException) {
				throw e
			} catch (e: IOException) {
				throw NetworkException("Unable to connect", e)
			}
			val code = try { conn.responseCode } catch (e: IOException) {
				conn.disconnect()
				throw NetworkException("No response", e)
			}
			if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
				val location = conn.getHeaderField("Location")
				conn.disconnect()
				if (location == null || ++redirects > MAX_REDIRECTS) throw NetworkException("Too many redirects")
				val next = URL(current, location)
				if (next.protocol != "http" && next.protocol != "https") throw NetworkException("Unsupported redirect")
				current = next
				continue
			}
			if (code !in 200..299) {
				conn.disconnect()
				throw NetworkException("HTTP $code")
			}
			return conn
		}
	}
}
