package fjm.sh.dropfiler.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import fjm.sh.dropfiler.activity.FolderListActivity
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.DestinationState
import fjm.sh.dropfiler.destination.SaveDestination

/**
 * Direct Share support.
 *
 * Android 6.0 - 9 query [fjm.sh.dropfiler.service.LocationChooserTargetService].
 * ChooserTargetService is deprecated and no longer queried by recent Android versions,
 * so on Android 10+ the usable destinations are published as sharing shortcuts
 * (res/xml/shortcuts.xml). Selecting one launches SaverActivity with
 * [ShortcutManagerCompat.EXTRA_SHORTCUT_ID], mapped back by [destinationForShortcut].
 *
 * Shortcut ids are `dest:<destination id>`. DropFiler 6.x used `folder:<path>`; those ids
 * are retired by [publish] (dynamic shortcuts are replaced, long-lived cached ones removed)
 * and still resolve through the migrated records' legacy path if one is used anyway.
 */
object DirectShareHelper {

	const val CATEGORY_SAVE_TO_FOLDER = "fjm.sh.dropfiler.category.SAVE_TO_FOLDER"
	private const val SHORTCUT_PREFIX = "dest:"
	private const val LEGACY_SHORTCUT_PREFIX = "folder:"
	private const val ICON_SIZE_DP = 108
	private const val SHORT_LABEL_LENGTH = 15

	fun shortcutId(destination: SaveDestination) = SHORTCUT_PREFIX + destination.id

	/**
	 * A destination name shortened for the launcher shortcut menu and the share sheet: the
	 * start of the name is kept ("Another Neat Folder" -> "Another Neat…"), cut at a word
	 * boundary when that keeps at least half of the allowed length, otherwise mid-word.
	 */
	fun shortLabel(label: String, maxLength: Int = SHORT_LABEL_LENGTH): String {
		val name = label.trim()
		if (name.length <= maxLength) return name
		val cut = name.take(maxLength - 1)
		val space = cut.lastIndexOf(' ')
		val kept = if (space >= maxLength / 2) cut.substring(0, space) else cut
		return kept.trimEnd() + "…"
	}

	/**
	 * @return The destination for a sharing shortcut id, or null if it is not one of ours or
	 * the destination was removed. Also reports the usage so the system can rank it.
	 */
	fun destinationForShortcut(context: Context, shortcutId: String?): SaveDestination? {
		val destination = when {
			shortcutId == null -> null
			shortcutId.startsWith(SHORTCUT_PREFIX) ->
				DestinationRepository.find(context, shortcutId.removePrefix(SHORTCUT_PREFIX))
			shortcutId.startsWith(LEGACY_SHORTCUT_PREFIX) ->
				DestinationRepository.findByLegacyPath(context, shortcutId.removePrefix(LEGACY_SHORTCUT_PREFIX))
			else -> null
		}
		if (destination != null && shortcutId!!.startsWith(SHORTCUT_PREFIX)) {
			ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
		}
		return destination
	}

	/** Whether a shortcut id is ours (current or legacy), even if its destination is gone. */
	fun isOurShortcut(shortcutId: String?) =
		shortcutId != null && (shortcutId.startsWith(SHORTCUT_PREFIX) || shortcutId.startsWith(LEGACY_SHORTCUT_PREFIX))

	/** The destinations offered as Direct Share targets: those offered for saving that are usable now. */
	fun targets(context: Context): List<SaveDestination> =
		DestinationRepository.offered(context).filter {
			DestinationRepository.quickState(context, it) == DestinationState.READY
		}

	/** Publishes (replaces) the sharing shortcuts. */
	fun publish(context: Context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			// Older versions use LocationChooserTargetService
			return
		}
		try {
			val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
			val density = context.resources.displayMetrics.density
			val shortcuts = targets(context).take(max).mapIndexed { rank, destination ->
				val label = destination.label
				ShortcutInfoCompat.Builder(context, shortcutId(destination))
					.setShortLabel(shortLabel(label))
					.setLongLabel(label)
					.setIcon(IconCompat.createWithBitmap(
						DirectShareIcon.generate(context, label, (ICON_SIZE_DP * density).toInt())))
					// Shortcuts need an intent; launched from the launcher it opens the app
					.setIntent(Intent(context, FolderListActivity::class.java).setAction(Intent.ACTION_MAIN))
					.setCategories(setOf(CATEGORY_SAVE_TO_FOLDER))
					.setLongLived(true)
					.setRank(rank)
					.build()
			}
			ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
			// Retire long-lived shortcuts of removed destinations and 6.x path-based ids
			val current = shortcuts.map { it.id }.toSet()
			val stale = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_CACHED)
				.map { it.id }.filter { isOurShortcut(it) && it !in current }
			if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
		} catch (e: RuntimeException) {
			// Rate limiting or launcher restrictions must never break saving
			android.util.Log.w(DirectShareHelper::class.java.simpleName, "Unable to publish sharing shortcuts", e)
		}
	}
}
