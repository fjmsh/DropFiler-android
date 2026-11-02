package fjm.sh.dropfiler

import android.content.Intent
import android.view.View
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hchen.himiuix.preference.MiuixPreferenceCategory
import com.hchen.himiuix.preference.MiuixSwitchPreference
import fjm.sh.dropfiler.TestSupport.addFolders
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.downloads
import fjm.sh.dropfiler.TestSupport.folderFile
import fjm.sh.dropfiler.TestSupport.hideDefault
import fjm.sh.dropfiler.TestSupport.prefs
import fjm.sh.dropfiler.TestSupport.waitFor
import fjm.sh.dropfiler.activity.FolderListActivity
import fjm.sh.dropfiler.activity.SaverActivity
import fjm.sh.dropfiler.activity.SettingsActivity
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.DestinationRepository.DEFAULT_ID
import fjm.sh.dropfiler.ui.CardItem
import fjm.sh.dropfiler.ui.CardListAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * The built-in, non-removable "Download" destination (Internal storage/Download, written
 * through MediaStore.Downloads on Android 10+) and the setting that hides it while other
 * usable destinations exist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 28, 29, 34, 36])
class DefaultFolderTest {

	private val download get() = context.getString(R.string.default_destination_label)

	@Before
	fun setUp() {
		TestSupport.reset()
	}

	private fun share(text: String) = Robolectric.buildActivity(SaverActivity::class.java,
		Intent(context, SaverActivity::class.java).setAction(Intent.ACTION_SEND)
			.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)).setup()

	private fun sheetRows(activity: SaverActivity) =
		(activity.findViewById<RecyclerView>(R.id.list).adapter as CardListAdapter).items.filterIsInstance<CardItem.Row>()

	private fun mainRows(activity: FolderListActivity) =
		(activity.findViewById<RecyclerView>(R.id.list).adapter as CardListAdapter).items.filterIsInstance<CardItem.Row>()

	private fun launchMain(): FolderListActivity =
		Robolectric.buildActivity(FolderListActivity::class.java).setup().get().also { waitFor(300) { false } }

	// Destination list ---------------------------------------------------------------------

	@Test
	fun destinations_defaultFirst_hiddenOnlyWhenOthersAreUsable() {
		fun ids() = DestinationRepository.offered(context).map { it.id }
		assertEquals(listOf(DEFAULT_ID), ids())
		val (a, b) = addFolders("A", "B")
		assertEquals(listOf(DEFAULT_ID, a.id, b.id), ids())
		hideDefault()
		assertEquals(listOf(a.id, b.id), ids())
		// Hidden, but the other destinations lost their access: Download is offered again
		TestSupport.revoke(a)
		TestSupport.revoke(b)
		assertEquals(listOf(DEFAULT_ID, a.id, b.id), ids())
		assertEquals("Internal storage/Download", context.getString(R.string.default_destination_path))
	}

	// Sharing ------------------------------------------------------------------------------

	@Test
	fun freshInstall_sharingSavesToDownload_withoutAnySetup() {
		val controller = share("first share")
		val saved = File(downloads(), "firstshare.txt")
		assertTrue("saved without asking", waitFor { saved.length() > 0 })
		assertEquals("first share", saved.readText())
		assertFalse("not in a DropFiler sub folder", File(downloads(), "DropFiler").exists())
		assertFalse("not in the old root folder", File(TestSupport.root(), "DropFiler").exists())
		assertTrue(waitFor { controller.get().isFinishing })
		assertEquals(context.getString(R.string.toast_save_successful), ShadowToast.getTextOfLatestToast())
		if (android.os.Build.VERSION.SDK_INT >= 29) {
			// Through MediaStore, not a file path; published after writing
			assertEquals(listOf("firstshare.txt"), FakeMediaStore.publishedNames())
			assertEquals(listOf(true), FakeMediaStore.pendingAtWrite)
		}
	}

	@Test
	fun withOtherDestinations_sheetOffersDownloadFirst() {
		addFolders("A")
		val activity = share("pick").get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		val rows = sheetRows(activity)
		assertEquals(listOf(download, "A"), rows.map { it.title.toString() })
		assertEquals("Internal storage/Download", rows[0].summary)
		assertTrue(rows[0].enabled)
	}

	@Test
	fun hidden_withOtherDestinations_sheetLeavesItOut() {
		hideDefault()
		addFolders("A", "B")
		val activity = share("pick").get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		assertEquals(listOf("A", "B"), sheetRows(activity).map { it.title.toString() })
	}

	@Test
	fun hidden_withoutOtherDestinations_stillSavesToDownload() {
		hideDefault()
		share("still here")
		assertTrue(waitFor { File(downloads(), "stillhere.txt").length() > 0 })
	}

	@Test
	fun hidden_whenAllOtherDestinationsBroke_offersDownloadAsRecovery() {
		hideDefault()
		val (a) = addFolders("A")
		TestSupport.revoke(a)
		val activity = share("rescue").get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		val rows = sheetRows(activity)
		assertEquals(context.getString(R.string.saver_missing_title), rows[0].title)
		assertTrue(rows.first { it.title == download }.enabled)
		assertFalse(rows.first { it.title == "A" }.enabled)
		val list = activity.findViewById<RecyclerView>(R.id.list)
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
		list.layout(0, 0, 1080, list.measuredHeight)
		val position = (list.adapter as CardListAdapter).items.indexOfFirst { it is CardItem.Row && it.title == download }
		list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.row).performClick()
		assertTrue(waitFor { File(downloads(), "rescue.txt").length() > 0 })
	}

	@Test
	fun openWith_withoutDestinations_savesToDownload() {
		val file = File(context.cacheDir, "doc.txt").apply { writeText("viewed") }
		Robolectric.buildActivity(SaverActivity::class.java, Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.fromFile(file), "text/plain")).setup()
		assertTrue(waitFor { TestSupport.downloadedFiles().any { it.name.endsWith("doc.txt") } })
	}

	// Main screen --------------------------------------------------------------------------

	@Test
	fun mainScreen_showsDownloadFirst_notRemovableOrMovable() {
		val (a, b) = addFolders("A", "B")
		val rows = mainRows(launchMain())
		assertEquals(listOf(DEFAULT_ID, a.id, b.id), rows.map { it.key })
		val default = rows[0]
		assertEquals(download, default.title)
		assertEquals(context.getString(R.string.folder_list_default_summary, "Internal storage/Download"), default.summary)
		assertEquals("no delete button", 0, default.actionIcon)
		assertFalse("no drag handle", default.draggable)
		assertTrue(rows.drop(1).all { it.draggable && it.actionIcon != 0 })
	}

	@Test
	fun mainScreen_hidden_onlyWhenOthersExist() {
		hideDefault()
		val (a) = addFolders("A")
		assertEquals(listOf(a.id), mainRows(launchMain()).map { it.key })
		DestinationRepository.remove(context, a.id)
		assertEquals(listOf(DEFAULT_ID), mainRows(launchMain()).map { it.key })
	}

	@Test
	fun mainScreen_followsTheSettingOnResume() {
		val (a) = addFolders("A")
		val controller = Robolectric.buildActivity(FolderListActivity::class.java).setup()
		assertEquals(listOf(DEFAULT_ID, a.id), mainRows(controller.get()).map { it.key })
		hideDefault()
		controller.pause().resume()
		TestSupport.idle()
		assertEquals(listOf(a.id), mainRows(controller.get()).map { it.key })
	}

	@Test
	fun removingTheLastDestination_leavesDownload_andIsNeverStored() {
		hideDefault()
		addFolders("A")
		val activity = launchMain()
		val list = activity.findViewById<RecyclerView>(R.id.list)
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY))
		list.layout(0, 0, 1080, 4000)
		list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.action).performClick()
		TestSupport.idle()
		val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
		fun find(view: View): View? = when {
			view is android.widget.TextView && view.text == context.getString(R.string.folder_list_delete_confirm) -> view
			view is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
			else -> null
		}
		find(dialog.window!!.decorView)!!.performClick()
		TestSupport.idle()

		assertEquals(emptyList<Any>(), DestinationRepository.custom(context))
		assertFalse(File(context.filesDir, fjm.sh.dropfiler.destination.DestinationStore.FILE_NAME).readText().contains(DEFAULT_ID))
		assertEquals(listOf(DEFAULT_ID), mainRows(activity).map { it.key })
		// The folder access was given up
		assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
	}

	// Direct Share -------------------------------------------------------------------------

	@Test
	@Config(sdk = [29, 34, 36])
	fun directShare_includesDownload_unlessHidden() {
		val (a) = addFolders("A")
		fun published() = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }.map { it.id }
		assertEquals(listOf("dest:$DEFAULT_ID", "dest:${a.id}"), published())
		hideDefault()
		DirectShareHelperAccess.publish()
		assertEquals(listOf("dest:${a.id}"), published())
	}

	@Test
	@Config(sdk = [29, 34, 36])
	fun directShare_toDownload_savesThere() {
		addFolders("A")
		Robolectric.buildActivity(SaverActivity::class.java,
			Intent(context, SaverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, "direct").putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "dest:$DEFAULT_ID")).setup()
		assertTrue(waitFor { File(downloads(), "direct.txt").length() > 0 })
		assertFalse(folderFile("A/direct.txt").exists())
	}

	@Test
	@Config(sdk = [29, 36])
	fun oldDropFilerShortcut_savesToDownload() {
		// DropFiler 6.x shortcut id for the old built-in folder
		Robolectric.buildActivity(SaverActivity::class.java,
			Intent(context, SaverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, "old").putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "folder:/DropFiler")).setup()
		assertTrue(waitFor { File(downloads(), "old.txt").length() > 0 })
		assertFalse(File(TestSupport.root(), "DropFiler").exists())
	}

	private object DirectShareHelperAccess {
		fun publish() = fjm.sh.dropfiler.util.DirectShareHelper.publish(context)
	}

	// Settings -----------------------------------------------------------------------------

	@Test
	fun settings_hasHideSwitchInSavingSection_offByDefault() {
		val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
		TestSupport.idle()
		val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PreferenceFragmentCompat>().single()
		val switch = fragment.findPreference<MiuixSwitchPreference>(DestinationRepository.PREF_HIDE_DEFAULT_FOLDER)!!
		assertEquals("Hide \"Download\" save location", switch.title.toString())
		assertFalse(switch.isChecked)
		val saving = fragment.findPreference<MiuixPreferenceCategory>("category_saving")!!
		assertTrue((0 until saving.preferenceCount).any { saving.getPreference(it) === switch })
		// Opening Settings writes nothing
		assertFalse(prefs.contains(DestinationRepository.PREF_HIDE_DEFAULT_FOLDER))
	}

	@Test
	@Config(sdk = [29, 34, 36])
	fun settings_toggle_updatesDirectShare() {
		val (a) = addFolders("A")
		val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
		TestSupport.idle()
		val fragment = activity.supportFragmentManager.fragments.filterIsInstance<PreferenceFragmentCompat>().single()
		val switch = fragment.findPreference<MiuixSwitchPreference>(DestinationRepository.PREF_HIDE_DEFAULT_FOLDER)!!
		(switch as com.hchen.himiuix.callback.OnStateChangeListener).onStateChange(true)
		TestSupport.idle()
		assertTrue(prefs.getBoolean(DestinationRepository.PREF_HIDE_DEFAULT_FOLDER, false))
		assertEquals(listOf("dest:${a.id}"), ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }.map { it.id })
	}
}
