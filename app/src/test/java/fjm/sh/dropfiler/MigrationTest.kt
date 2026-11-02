package fjm.sh.dropfiler

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.prefs
import fjm.sh.dropfiler.TestSupport.waitFor
import fjm.sh.dropfiler.activity.FolderListActivity
import fjm.sh.dropfiler.activity.SaverActivity
import fjm.sh.dropfiler.destination.DestinationKind
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.DestinationState
import fjm.sh.dropfiler.destination.DestinationStore
import fjm.sh.dropfiler.ui.CardItem
import fjm.sh.dropfiler.ui.CardListAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * In-place upgrade from DropFiler 6.x (targetSdk 28, FOLDER_STORE = JSON list of paths,
 * implicit /DropFiler default) to the MediaStore + SAF model.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 28, 29, 34, 36])
class MigrationTest {

	private val legacyFile get() = File(context.filesDir, DestinationStore.LEGACY_FILE_NAME)
	private val newFile get() = File(context.filesDir, DestinationStore.FILE_NAME)

	@Before
	fun setUp() {
		TestSupport.reset()
	}

	private fun mainRows(activity: FolderListActivity) =
		(activity.findViewById<RecyclerView>(R.id.list).adapter as CardListAdapter).items

	@Test
	fun customPaths_becomeReconnectRecords_inOrder_withoutInventingGrants() {
		TestSupport.writeLegacyStore("/DCIM/Memes", "/DropFiler", "/Pictures", "", "/DCIM/Memes")
		val legacyBefore = legacyFile.readText()

		val migrated = DestinationRepository.custom(context)
		assertEquals("same order, implicit default and duplicates dropped",
			listOf("/DCIM/Memes", "/Pictures", ""), migrated.map { it.legacyPath })
		assertEquals(listOf("Memes", "Pictures", ""), migrated.map { it.label })
		assertTrue(migrated.all { it.kind == DestinationKind.LEGACY_RECONNECT_REQUIRED && it.treeUri == null })
		assertTrue(migrated.all { DestinationRepository.checkState(context, it) == DestinationState.RECONNECT_REQUIRED })
		assertTrue("no grants made up", context.contentResolver.persistedUriPermissions.isEmpty())
		// Old data is kept
		assertEquals(legacyBefore, legacyFile.readText())
		assertTrue(newFile.readText().contains("\"version\":2"))
	}

	@Test
	fun migration_isIdempotent_andRepeatsIdenticallyAfterAnInterruption() {
		TestSupport.writeLegacyStore("/A", "/B")
		val first = DestinationRepository.custom(context)
		val stored = newFile.readText()
		assertEquals(first, DestinationRepository.custom(context))
		assertEquals(stored, newFile.readText())

		// Crash before the new file was written: the next start migrates again, same ids
		newFile.delete()
		assertEquals(first.map { it.id }, DestinationRepository.custom(context).map { it.id })
		// A shortcut published in between still resolves
		assertEquals(first[1].id, DestinationRepository.findByLegacyPath(context, "/B")?.id)
	}

	@Test
	fun corruptStore_isKept_andMigratedAgain() {
		TestSupport.writeLegacyStore("/A")
		newFile.writeText("{not json")
		assertEquals(listOf("A"), DestinationRepository.custom(context).map { it.label })
		assertTrue(context.filesDir.list()!!.any { it.startsWith(DestinationStore.FILE_NAME + ".corrupt-") })
	}

	@Test
	fun downloadRootPath_isNotADuplicateDefault() {
		TestSupport.writeLegacyStore("/Download", "/A")
		val (download, a) = DestinationRepository.custom(context)
		assertTrue(download.duplicatesDefault)
		assertFalse(a.duplicatesDefault)
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		waitFor(300) { false }
		val row = mainRows(activity).filterIsInstance<CardItem.Row>().first { it.key == download.id }
		assertEquals(context.getString(R.string.destination_state_duplicates_default), row.summary)
		assertFalse("cannot be reconnected", row.clickable)
		assertTrue("can be removed", row.actionIcon != 0)
	}

	@Test
	fun upgrade_withoutCustomDestinations_switchesDefaultToDownload_andExplainsOnce() {
		newFile.delete() // An upgrade: the 6.x app had been opened, no FOLDER_STORE yet
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		val items = mainRows(activity)
		val notice = items.first() as CardItem.Message
		assertEquals(context.getString(R.string.upgrade_notice_title), notice.title)
		assertTrue(notice.body.toString().contains("Internal storage/Download"))
		assertEquals(listOf(DestinationRepository.DEFAULT_ID), items.filterIsInstance<CardItem.Row>().map { it.key })

		// Dismiss: gone for good
		activity.findViewById<RecyclerView>(R.id.list).let { list ->
			list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY))
			list.layout(0, 0, 1080, 4000)
			list.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.message_action).performClick()
		}
		assertFalse(mainRows(activity).any { it is CardItem.Message })
		assertFalse(mainRows(Robolectric.buildActivity(FolderListActivity::class.java).setup().get()).any { it is CardItem.Message })
	}

	@Test
	fun upgrade_sharing_savesToDownload_andLeavesOldDropFilerFilesAlone() {
		val oldFolder = File(TestSupport.root(), "DropFiler").apply { mkdirs() }
		val oldFile = File(oldFolder, "earlier.txt").apply { writeText("earlier") }
		TestSupport.writeLegacyStore()
		Robolectric.buildActivity(SaverActivity::class.java, Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "after")).setup()
		assertTrue(waitFor { File(TestSupport.downloads(), "after.txt").length() > 0 })
		assertEquals("earlier", oldFile.readText())
		assertEquals(listOf("earlier.txt"), oldFolder.list()!!.toList())
	}

	@Test
	fun upgrade_hiddenDefault_withOnlyLegacyFolders_stillOffersDownload() {
		TestSupport.hideDefault()
		TestSupport.writeLegacyStore("/A", "/B")
		val activity = Robolectric.buildActivity(SaverActivity::class.java, Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x")).setup().get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		val rows = (activity.findViewById<RecyclerView>(R.id.list).adapter as CardListAdapter).items.filterIsInstance<CardItem.Row>()
		assertTrue(rows.first { it.title == context.getString(R.string.default_destination_label) }.enabled)
		assertFalse(rows.first { it.title == "A" }.enabled)
		assertFalse(rows.first { it.title == "B" }.enabled)
		// The setting itself is unchanged
		assertTrue(prefs.getBoolean(DestinationRepository.PREF_HIDE_DEFAULT_FOLDER, false))
	}

	@Test
	fun upgrade_keepsOtherSettings() {
		prefs.edit().putString("file_exists", "Postfix").putBoolean("location_select", true)
			.putBoolean("register_file", true).putBoolean("force_saving", true).putBoolean("follow_redirects", false)
			.putString("theme_mode", "dark").putBoolean("dynamic_colors", true).commit()
		val before = prefs.all.toMap()
		TestSupport.writeLegacyStore("/A")
		Robolectric.buildActivity(FolderListActivity::class.java).setup()
		before.forEach { (key, value) -> assertEquals(key, value, prefs.all[key]) }
	}

	@Test
	fun legacyFolder_reconnect_keepsIdAndPosition() {
		TestSupport.writeLegacyStore("/A", "/Pictures")
		val (a, pictures) = DestinationRepository.custom(context)
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		val list = activity.findViewById<RecyclerView>(R.id.list)
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY))
		list.layout(0, 0, 1080, 4000)
		val rows = mainRows(activity)
		val position = rows.indexOfFirst { it is CardItem.Row && it.key == pictures.id }
		val row = rows[position] as CardItem.Row
		assertTrue(row.danger && row.clickable)
		assertEquals(context.getString(R.string.destination_state_reconnect, "/Pictures"), row.summary)

		list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.row).performClick()
		val started = shadowOf(activity).nextStartedActivityForResult
		assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.action)
		shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK,
			TestDocumentsProvider.pickerResult(TestDocumentsProvider.tree("Pictures")))
		assertTrue(waitFor { ShadowToast.getTextOfLatestToast() == context.getString(R.string.toast_destination_reconnected, "Pictures") })
		assertEquals(DestinationKind.SAF_TREE, DestinationRepository.custom(context)[1].kind)
		val after = DestinationRepository.custom(context)
		assertEquals(listOf(a.id, pictures.id), after.map { it.id })
		assertEquals("/Pictures", after[1].legacyPath)

		// Now usable: an old 6.x shortcut for that path saves there without asking
		Robolectric.buildActivity(SaverActivity::class.java, Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "via old shortcut")
			.putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "folder:/Pictures")).setup()
		assertTrue(waitFor { TestSupport.folderFile("Pictures/viaoldshortcut.txt").length() > 0 })
	}

	@Test
	fun staleLegacyShortcut_showsTheSheetInsteadOfSaving() {
		TestSupport.writeLegacyStore("/A")
		val activity = Robolectric.buildActivity(SaverActivity::class.java, Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x")
			.putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "folder:/A")).setup().get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		val rows = (activity.findViewById<RecyclerView>(R.id.list).adapter as CardListAdapter).items.filterIsInstance<CardItem.Row>()
		assertEquals(context.getString(R.string.saver_missing_title), rows[0].title)
		assertTrue(TestSupport.downloadedFiles().isEmpty())

		// And a shortcut for a destination removed since
		val gone = Robolectric.buildActivity(SaverActivity::class.java, Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x")
			.putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "dest:saf:removed")).setup().get()
		assertTrue(waitFor { gone.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		assertNotNull((gone.findViewById<RecyclerView>(R.id.list).adapter as CardListAdapter).items
			.filterIsInstance<CardItem.Row>().firstOrNull { it.title == context.getString(R.string.saver_missing_title) })
	}

	@Test
	@Config(sdk = [29, 34, 36])
	fun legacyFolders_areNotPublishedAsShortcuts() {
		TestSupport.writeLegacyStore("/A")
		Robolectric.buildActivity(FolderListActivity::class.java).setup()
		assertEquals(listOf("dest:" + DestinationRepository.DEFAULT_ID),
			ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }.map { it.id })
	}
}
