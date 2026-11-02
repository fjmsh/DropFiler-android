package fjm.sh.dropfiler.save

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * The built-in Download destination on Android 5 - 9, where MediaStore.Downloads does not
 * exist. Writes to the public Download directory with the legacy storage permission
 * (declared with maxSdkVersion 28, so it is never used or requested on Android 10+).
 *
 * The content is written to a hidden staging file in the same folder and renamed into
 * place when complete, so an existing file is only replaced by a complete copy.
 */
class LegacyDownloadsWriter(
	private val context: Context,
	@Suppress("DEPRECATION")
	private val directory: File = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS),
	private val scan: Boolean = false,
) : DestinationWriter {

	companion object {
		private val lock = Any()
	}

	override fun write(request: WriteRequest): WriteOutcome {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ContextCompat.checkSelfPermission(context,
				Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			return WriteOutcome.Failed(FailReason.NO_PERMISSION)
		}
		@Suppress("DEPRECATION")
		if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
			return WriteOutcome.Failed(FailReason.DESTINATION_UNAVAILABLE)
		}
		if (!directory.isDirectory && !directory.mkdirs()) return WriteOutcome.Failed(FailReason.DESTINATION_UNAVAILABLE)

		val target: File
		var reserved = false
		val existed: Boolean
		synchronized(lock) {
			val requested = File(directory, request.name)
			existed = requested.exists()
			target = when {
				!existed -> requested
				request.policy == DuplicatePolicy.SKIP -> return WriteOutcome.Failed(FailReason.EXISTS)
				request.policy == DuplicatePolicy.POSTFIX -> Filenames.firstFree(request.name) { File(directory, it).exists() }
					?.let { File(directory, it) } ?: return WriteOutcome.Failed(FailReason.TOO_MANY_DUPLICATES)
				else -> {
					if (isSameFile(requested, request.sourceUri)) return WriteOutcome.Failed(FailReason.SELF_OVERWRITE)
					requested
				}
			}
			if (!target.exists()) {
				// Reserve the name so a concurrent save cannot pick it too
				reserved = try { target.createNewFile() } catch (e: IOException) { false }
				if (!reserved) return WriteOutcome.Failed(FailReason.DESTINATION_UNAVAILABLE)
			}
		}

		val staging = File(directory, ".${target.name}.${UUID.randomUUID()}.dropfiler-part")
		try {
			request.openSource().use { input ->
				FileOutputStream(staging).use { output ->
					copyStream(input, output, request.cancelled)
					output.fd.sync()
				}
			}
			if (!staging.renameTo(target)) throw IOException("Unable to move the saved file into place")
		} catch (e: Exception) {
			staging.delete()
			if (reserved) target.delete()
			return failureFor(e)
		}
		if (scan) MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
		return WriteOutcome.Saved(target.name, Uri.fromFile(target), replaced = existed && target.name == request.name)
	}

	private fun isSameFile(file: File, source: Uri?): Boolean {
		if (source?.scheme != ContentResolver.SCHEME_FILE) return false
		val path = source.path ?: return false
		return try {
			File(path).canonicalPath == file.canonicalPath
		} catch (e: IOException) {
			false
		}
	}
}
