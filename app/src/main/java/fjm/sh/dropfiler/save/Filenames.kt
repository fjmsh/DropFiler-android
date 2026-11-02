package fjm.sh.dropfiler.save

import android.webkit.MimeTypeMap
import java.util.Locale

/** What to do when a file with the same name already exists in the destination. */
enum class DuplicatePolicy {
	/** Replace the existing file (only files DropFiler is allowed to replace). */
	OVERWRITE,
	/** Do not save the new file. */
	SKIP,
	/** Add a number before the extension so both files are kept. */
	POSTFIX;

	companion object {
		/**
		 * Maps the stored `file_exists` index (see `pref_list_values_file_exists`). The old,
		 * never-implemented "Request" value and anything unknown keep both files, so no
		 * setting can cause data loss or a crash.
		 */
		fun fromIndex(index: Int): DuplicatePolicy = when (index) {
			0 -> OVERWRITE
			1 -> SKIP
			else -> POSTFIX
		}
	}
}

/**
 * Filename generation, unchanged from earlier versions except for the fixes noted below.
 */
object Filenames {

	const val FILENAME_REGEX = "[^-_.A-Za-z0-9]"
	const val FILENAME_LENIENT_REGEX = "[\\p{Cntrl}]"
	const val FILENAME_LENGTH_LIMIT = 100
	/** Most filesystems limit a name to 255 bytes; stay well below for providers that add " (1)". */
	private const val FILENAME_BYTE_LIMIT = 200
	const val FILENAME_EXT_MATCH_LIMIT = 1000
	const val FALLBACK_NAME = "default"
	const val FALLBACK_MIME = "application/octet-stream"

	data class Name(val name: String, val extension: String)

	/** `text/plain; charset=utf-8` → `text/plain`. */
	fun baseMime(mime: String?): String? =
		mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

	fun isConcrete(mime: String?): Boolean = mime != null && '*' !in mime && '/' in mime

	/**
	 * Builds the filename to save as: the part after the last slash, invalid characters
	 * removed, at most [FILENAME_LENGTH_LIMIT] characters, and an extension from the MIME
	 * type when the name has no known extension.
	 *
	 * Fixes: a name that is empty after cleaning (e.g. non-Latin text with strict
	 * validation) becomes [FALLBACK_NAME] instead of a hidden ".txt" file, and long
	 * Unicode names are also limited in bytes.
	 */
	fun sanitize(raw: String, mime: String?, lenient: Boolean): Name {
		var result = raw.substringAfterLast('/')
			.replace(Regex(if (lenient) FILENAME_LENIENT_REGEX else FILENAME_REGEX), "")
			.trim()
		if (result.length > FILENAME_LENGTH_LIMIT) result = result.substring(0, FILENAME_LENGTH_LIMIT)
		while (result.toByteArray(Charsets.UTF_8).size > FILENAME_BYTE_LIMIT) result = result.dropLast(1)
		if (result.isEmpty() || result.all { it == '.' }) result = FALLBACK_NAME

		val map = MimeTypeMap.getSingleton()
		var ext = result.substringAfterLast('.', "")
		if (ext.isEmpty() || !map.hasExtension(ext.lowercase(Locale.ROOT))) {
			val fromMime = baseMime(mime)?.let { map.getExtensionFromMimeType(it) }
			// An unknown extension without a better one from the MIME type is kept ("blob.weird")
			if (fromMime != null) {
				result += ".$fromMime"
				ext = fromMime
			}
		}
		return Name(result, ext)
	}

	/** `name.ext` → `name.<i>.ext`; `README` → `README.<i>` (previously `.<i>`). */
	fun postfix(name: String, i: Int): String {
		val dot = name.lastIndexOf('.')
		return if (dot > 0) name.substring(0, dot) + ".$i" + name.substring(dot) else "$name.$i"
	}

	/** First free name: [name] itself, then [postfix] 1, 2, … up to [FILENAME_EXT_MATCH_LIMIT]. */
	fun firstFree(name: String, exists: (String) -> Boolean): String? {
		if (!exists(name)) return name
		for (i in 1..FILENAME_EXT_MATCH_LIMIT) {
			val candidate = postfix(name, i)
			if (!exists(candidate)) return candidate
		}
		return null
	}

	/**
	 * A concrete MIME type for the output: from the name's extension when known (so providers
	 * do not append a second extension), else the given type when concrete, else binary.
	 */
	fun outputMime(name: String, hint: String?): String {
		val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
		val fromExt = ext.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
		return fromExt ?: baseMime(hint)?.takeIf { isConcrete(it) } ?: FALLBACK_MIME
	}
}
