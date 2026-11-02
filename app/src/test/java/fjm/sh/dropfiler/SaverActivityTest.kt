package fjm.sh.dropfiler

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.MimeTypeMap
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.prefs
import fjm.sh.dropfiler.TestSupport.addFolders
import fjm.sh.dropfiler.TestSupport.folderFile
import fjm.sh.dropfiler.TestSupport.waitFor
import fjm.sh.dropfiler.activity.SaverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * Share target behaviour: the saving logic must be unchanged by the UI modernisation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 26, 28, 29, 30, 31, 33, 34, 35, 36])
class SaverActivityTest {

	@Before
	fun setUp() {
		TestSupport.reset()
		// These tests are about the user's own (SAF) destinations: hide the built-in Download
		// destination, which is then only used when no other destination works (DefaultFolderTest)
		TestSupport.hideDefault()
		// Robolectric's MimeTypeMap may be empty; register what the tests use
		shadowOf(MimeTypeMap.getSingleton()).apply {
			addExtensionMimeTypeMapping("txt", "text/plain")
			addExtensionMimeTypeMapping("jpg", "image/jpeg")
			addExtensionMimeTypeMapping("mp4", "video/mp4")
			addExtensionMimeTypeMapping("pdf", "application/pdf")
		}
	}

	private fun textIntent(text: String) = Intent(context, SaverActivity::class.java)
		.setAction(Intent.ACTION_SEND)
		.setType("text/plain")
		.putExtra(Intent.EXTRA_TEXT, text)

	private fun sourceFile(name: String, content: String): Uri {
		val file = File(context.cacheDir, name)
		file.writeText(content)
		return Uri.fromFile(file)
	}

	private fun streamIntent(type: String, uri: Uri) = Intent(context, SaverActivity::class.java)
		.setAction(Intent.ACTION_SEND)
		.setType(type)
		.putExtra(Intent.EXTRA_STREAM, uri)

	private fun launch(intent: Intent): ActivityController<SaverActivity> =
		Robolectric.buildActivity(SaverActivity::class.java, intent).setup()

	@Test
	fun singleDestination_savesTextAutomatically() {
		addFolders("Saved")
		val controller = launch(textIntent("hello world"))

		val saved = folderFile("Saved/helloworld.txt")
		assertTrue("text should be saved without asking", waitFor { saved.exists() && saved.length() > 0 })
		assertEquals("hello world", saved.readText())
		assertTrue(waitFor { controller.get().isFinishing })
	}

	private fun notifications() =
		shadowOf(context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
			.allNotifications

	private fun android.app.Notification.title() = extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
	private fun android.app.Notification.text() = extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()

	@Test
	fun successfulSave_postsResultNotification() {
		addFolders("Saved")
		launch(textIntent("hello world"))
		assertTrue(waitFor { notifications().isNotEmpty() })

		val notification = notifications().single()
		assertEquals(context.getString(R.string.notification_saved_title), notification.title())
		assertEquals("helloworld.txt saved to Saved", notification.text())
	}

	@Test
	fun failedSave_postsResultNotificationWithReason() {
		addFolders("Saved")
		folderFile("Saved/helloworld.txt").writeText("old")
		prefs.edit().putString("file_exists", "Nothing").commit()
		launch(textIntent("hello world"))
		assertTrue(waitFor { notifications().isNotEmpty() })

		val notification = notifications().single()
		assertEquals(context.getString(R.string.notification_failed_title), notification.title())
		assertEquals("helloworld.txt: " + context.getString(R.string.toast_save_file_exists_fail), notification.text())
	}

	@Test
	fun multipleFiles_postOneNotification() {
		addFolders("Batch")
		val intent = Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND_MULTIPLE)
			.setType("image/*")
			.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(
				sourceFile("a.jpg", "1"), sourceFile("b.jpg", "2")))
		launch(intent)
		assertTrue(waitFor { notifications().isNotEmpty() })
		assertEquals("2 items saved to Batch", notifications().single().text())
	}

	private fun rows(activity: SaverActivity) =
		(activity.findViewById<RecyclerView>(R.id.list).adapter as fjm.sh.dropfiler.ui.CardListAdapter)
			.items.filterIsInstance<fjm.sh.dropfiler.ui.CardItem.Row>()

	private fun clickRow(activity: SaverActivity, title: String) {
		val list = activity.findViewById<RecyclerView>(R.id.list)
		val adapter = list.adapter as fjm.sh.dropfiler.ui.CardListAdapter
		val position = adapter.items.indexOfFirst { it is fjm.sh.dropfiler.ui.CardItem.Row && it.title == title }
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
		list.layout(0, 0, 1080, list.measuredHeight)
		// The sheet list is height-limited; bring the row on screen like a user would
		list.scrollToPosition(position)
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
		list.layout(0, 0, 1080, list.measuredHeight)
		list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.row).performClick()
	}

	@Test
	fun shareSheet_followsTheOrderSetOnTheMainScreen() {
		addFolders("Zeta", "Alpha", "Mid")
		val activity = launch(textIntent("order")).get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		assertEquals(listOf("Zeta", "Alpha", "Mid"), rows(activity).map { it.title.toString() })
	}

	@Test
	fun shareSheet_hasDividersBetweenDestinations() {
		addFolders("A", "B", "C")
		val activity = launch(textIntent("dividers")).get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		val list = activity.findViewById<RecyclerView>(R.id.list)
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
		list.layout(0, 0, 1080, list.measuredHeight)
		val dividers = (0 until 3).map {
			list.findViewHolderForAdapterPosition(it)!!.itemView.findViewById<View>(R.id.row_divider).visibility
		}
		assertEquals(listOf(View.GONE, View.VISIBLE, View.VISIBLE), dividers)
	}

	@Test
	fun missingSingleDestination_asksForAnotherFolderInsteadOfCrashing() {
		addFolders("Gone")
		folderFile("Gone").deleteRecursively()
		// The built-in Download destination is offered again, as nothing else works
		val activity = launch(streamIntent("image/jpeg", sourceFile("photo.jpg", "x"))).get()

		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		assertFalse(activity.isFinishing)
		val rows = rows(activity)
		assertEquals(context.getString(R.string.saver_missing_title), rows[0].title)
		assertTrue(rows[0].danger)
		// The missing destination is shown but cannot be chosen
		assertFalse(rows.first { it.title == "Gone" }.enabled)
		assertTrue(rows.first { it.title == context.getString(R.string.default_destination_label) }.enabled)
		// The system picker is always offered
		assertTrue(rows.any { it.title == context.getString(R.string.saver_choose_other_folder) })
		assertFalse(folderFile("Gone").exists())
	}

	@Test
	fun missingDestination_anotherFolderCanBeChosen() {
		addFolders("Gone", "Ok")
		folderFile("Gone").deleteRecursively()
		val activity = launch(textIntent("other folder")).get()

		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		assertFalse(rows(activity).first { it.title == "Gone" }.clickable)
		clickRow(activity, "Ok")
		assertTrue(waitFor { folderFile("Ok/otherfolder.txt").length() > 0 })
	}

	@Test
	fun missingDirectShareFolder_asksForAnotherFolder() {
		val (gone, _) = addFolders("Gone", "Ok")
		folderFile("Gone").deleteRecursively()
		val activity = launch(textIntent("direct").putExtra(SaverActivity.DIRECT_SHARE_DESTINATION, gone.id)).get()

		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		assertEquals(context.getString(R.string.saver_missing_title), rows(activity)[0].title)
		clickRow(activity, "Ok")
		assertTrue(waitFor { folderFile("Ok/direct.txt").length() > 0 })
	}

	@Test
	@Suppress("DEPRECATION")
	fun missingDestination_systemPickerCanBeChosen() {
		addFolders("Gone")
		folderFile("Gone").deleteRecursively()
		val activity = launch(textIntent("picked")).get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })

		clickRow(activity, context.getString(R.string.saver_choose_other_folder))
		assertTrue(waitFor { shadowOf(activity).peekNextStartedActivityForResult() != null })
		assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(activity).nextStartedActivityForResult.intent.action)
	}

	@Test
	fun multipleDestinations_showsSelector_andSavesToChosenFolder() {
		addFolders("A", "B")
		val activity = launch(textIntent("pick me")).get()

		val list = activity.findViewById<RecyclerView>(R.id.list)
		assertTrue("destination list shown", waitFor { list.visibility == View.VISIBLE && list.adapter?.itemCount == 2 })
		assertEquals(View.GONE, activity.findViewById<View>(R.id.loading).visibility)

		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST))
		list.layout(0, 0, 1080, list.measuredHeight)
		list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.row).performClick()

		val saved = folderFile("B/pickme.txt")
		assertTrue(waitFor { saved.exists() && saved.length() > 0 })
		assertEquals("pick me", saved.readText())
		assertFalse(folderFile("A/pickme.txt").exists())
	}

	@Test
	fun sharedImageStream_isCopied() {
		addFolders("Pictures")
		launch(streamIntent("image/jpeg", sourceFile("photo.jpg", "jpeg-bytes")))

		val saved = folderFile("Pictures/photo.jpg")
		assertTrue(waitFor { saved.exists() && saved.length() > 0 })
		assertEquals("jpeg-bytes", saved.readText())
	}

	@Test
	fun sharedVideoAndDocumentStreams_areCopied() {
		addFolders("Media")
		launch(streamIntent("video/mp4", sourceFile("clip.mp4", "video")))
		assertTrue(waitFor { folderFile("Media/clip.mp4").length() > 0 })

		launch(streamIntent("application/pdf", sourceFile("report.pdf", "pdf")))
		assertTrue(waitFor { folderFile("Media/report.pdf").length() > 0 })
	}

	@Test
	fun sendMultiple_savesEveryStream() {
		addFolders("Batch")
		val intent = Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND_MULTIPLE)
			.setType("image/*")
			.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(
				sourceFile("one.jpg", "1"), sourceFile("two.jpg", "2")))
		launch(intent)

		assertTrue(waitFor { folderFile("Batch/one.jpg").length() > 0 && folderFile("Batch/two.jpg").length() > 0 })
		assertEquals("1", folderFile("Batch/one.jpg").readText())
		assertEquals("2", folderFile("Batch/two.jpg").readText())
	}

	@Test
	fun existingFile_overwriteStrategy_replacesFile() {
		addFolders("Saved")
		val existing = folderFile("Saved/helloworld.txt").apply { writeText("old") }
		prefs.edit().putString("file_exists", "Overwrite").commit()

		launch(textIntent("hello world"))
		assertTrue(waitFor { runCatching { existing.readText() }.getOrNull() == "hello world" })
	}

	@Test
	fun existingFile_doNotSaveStrategy_keepsFile() {
		addFolders("Saved")
		val existing = folderFile("Saved/helloworld.txt").apply { writeText("old") }
		prefs.edit().putString("file_exists", "Nothing").commit()

		val controller = launch(textIntent("hello world"))
		assertTrue(waitFor { controller.get().isFinishing })
		assertEquals("old", existing.readText())
		assertEquals(1, folderFile("Saved").listFiles()!!.size)
		assertEquals(context.getString(R.string.toast_save_file_exists_fail), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
	}

	@Test
	fun existingFile_postfixStrategy_appendsNumber() {
		addFolders("Saved")
		val existing = folderFile("Saved/helloworld.txt").apply { writeText("old") }
		prefs.edit().putString("file_exists", "Postfix").commit()

		launch(textIntent("hello world"))
		val numbered = folderFile("Saved/helloworld.1.txt")
		assertTrue(waitFor { numbered.exists() && numbered.length() > 0 })
		assertEquals("hello world", numbered.readText())
		assertEquals("old", existing.readText())
	}

	@Test
	fun directShareExtra_savesToTargetWithoutSelector() {
		val (_, b) = addFolders("A", "B")
		val intent = textIntent("direct").putExtra(SaverActivity.DIRECT_SHARE_DESTINATION, b.id)
		launch(intent)

		assertTrue(waitFor { folderFile("B/direct.txt").length() > 0 })
		assertFalse(folderFile("A/direct.txt").exists())
	}

	@Test
	fun sharingShortcut_savesToTargetWithoutSelector() {
		val (a, _) = addFolders("A", "B")
		val intent = textIntent("shortcut").putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "dest:" + a.id)
		launch(intent)

		assertTrue(waitFor { folderFile("A/shortcut.txt").length() > 0 })
		assertFalse(folderFile("B/shortcut.txt").exists())
	}

	@Test
	fun unsupportedContent_showsSupportInformation() {
		addFolders("A", "B")
		val intent = Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND)
			.setType("application/x-unknown")
		val activity = launch(intent).get()

		assertTrue(waitFor { activity.findViewById<View>(R.id.not_supported_wrapper).visibility == View.VISIBLE })
		assertEquals(View.GONE, activity.findViewById<View>(R.id.list).visibility)
	}

	@Test
	fun noDestinations_savesToDownload() {
		val controller = launch(textIntent("nowhere"))
		val saved = TestSupport.downloads().resolve("nowhere.txt")
		assertTrue(waitFor { saved.length() > 0 })
		assertEquals("nowhere", saved.readText())
		assertTrue(waitFor { controller.get().isFinishing })
	}

	@Test
	fun cancel_closesSheetWithoutSaving() {
		addFolders("A", "B")
		val activity = launch(textIntent("cancel me")).get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })

		activity.findViewById<View>(R.id.cancel).performClick()
		assertTrue(activity.isFinishing)
		assertFalse(folderFile("A/cancelme.txt").exists())
		assertFalse(folderFile("B/cancelme.txt").exists())
	}
}
