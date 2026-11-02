package fjm.sh.dropfiler

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.DestinationStore
import fjm.sh.dropfiler.destination.SaveDestination
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/**
 * Shared helpers for the Robolectric tests.
 *
 * Storage model under test:
 * - the built-in Download destination is [FakeMediaStore] on API 29+ and the public
 *   Download directory ([downloads]) on API 28 and older; both are the same folder here;
 * - user destinations are folders of [TestDocumentsProvider] with persisted grants.
 */
object TestSupport {

	val context: Context
		get() = ApplicationProvider.getApplicationContext()

	val prefs: SharedPreferences
		get() = PreferenceManager.getDefaultSharedPreferences(context)

	/** Robolectric's external storage root (the old `/storage/emulated/0`). */
	fun root(): File = Environment.getExternalStorageDirectory().apply { mkdirs() }

	/** `Internal storage/Download`. */
	fun downloads(): File = File(root(), Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }

	/** Fresh storage, settings and providers. Call from every @Before. */
	fun reset() {
		root().listFiles()?.forEach { it.deleteRecursively() }
		prefs.edit().clear().commit()
		File(context.filesDir, DestinationStore.FILE_NAME).delete()
		File(context.filesDir, DestinationStore.FILE_NAME + ".bak").delete()
		File(context.filesDir, DestinationStore.LEGACY_FILE_NAME).delete()
		ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
		TestDocumentsProvider.reset(File(context.cacheDir, "documents"))
		TestDocumentsProvider.register()
		FakeMediaStore.reset(downloads())
		FakeMediaStore.register()
		// Android 6 - 9: the legacy permission for the Download folder is granted, as after
		// the user allowed it on the main screen
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			shadowOf(context as Application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
		}
		context.contentResolver.persistedUriPermissions.forEach {
			context.contentResolver.releasePersistableUriPermission(it.uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
		}
		// Notifications allowed, so the main screen does not ask (see FolderListActivityTest)
		if (Build.VERSION.SDK_INT >= 33) {
			shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
		}
		de.cketti.library.changelog.ChangeLog(context).skipLogDialog()
		// A current install: nothing to migrate (MigrationTest covers upgrades)
		DestinationStore.write(context, emptyList())
	}

	fun hideDefault(hidden: Boolean = true) =
		prefs.edit().putBoolean(DestinationRepository.PREF_HIDE_DEFAULT_FOLDER, hidden).commit()

	/**
	 * Adds SAF destinations the way the app does after the folder picker returns: the grant
	 * is persisted and the tree is validated by [DestinationRepository.addTree].
	 * @return The destinations, in order.
	 */
	fun addFolders(vararg paths: String): List<SaveDestination> = paths.map { path ->
		val tree = TestDocumentsProvider.tree(path)
		val result = DestinationRepository.addTree(context, tree, TestDocumentsProvider.pickerResult(tree).flags)
		assertTrue("added $path: $result", result is DestinationRepository.TreeResult.Added)
		(result as DestinationRepository.TreeResult.Added).destination
	}

	/** A file inside a SAF destination folder. */
	fun folderFile(path: String) = TestDocumentsProvider.file(path)

	/** Revokes the persisted grant of a destination (e.g. the user cleared the app's access). */
	fun revoke(destination: SaveDestination) {
		context.contentResolver.releasePersistableUriPermission(Uri.parse(destination.treeUri),
			Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
	}

	/** Writes a DropFiler 6.x FOLDER_STORE (JSON list of paths) and removes the new store. */
	fun writeLegacyStore(vararg paths: String) {
		File(context.filesDir, DestinationStore.FILE_NAME).delete()
		File(context.filesDir, DestinationStore.LEGACY_FILE_NAME).writeText(
			paths.joinToString(",", "[", "]") { "\"" + it.replace("\"", "\\\"") + "\"" })
	}

	/** Runs main looper tasks until [condition] is true or the timeout expires. */
	fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
		val end = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < end) {
			shadowOf(Looper.getMainLooper()).idle()
			if (condition()) return true
			Thread.sleep(20)
		}
		shadowOf(Looper.getMainLooper()).idle()
		return condition()
	}

	fun idle() = shadowOf(Looper.getMainLooper()).idle()

	/** Files in the Download root, ignoring DropFiler's hidden staging files. */
	fun downloadedFiles(): List<File> = downloads().listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
		?.sortedBy { it.name }.orEmpty()
}
