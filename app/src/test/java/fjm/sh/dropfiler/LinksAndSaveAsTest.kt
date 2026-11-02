package fjm.sh.dropfiler

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.webkit.MimeTypeMap
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.addFolders
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.downloads
import fjm.sh.dropfiler.TestSupport.folderFile
import fjm.sh.dropfiler.TestSupport.prefs
import fjm.sh.dropfiler.TestSupport.waitFor
import fjm.sh.dropfiler.activity.SaverActivity
import fjm.sh.dropfiler.ui.CardItem
import fjm.sh.dropfiler.ui.CardListAdapter
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Shared links and text (downloaded straight into the chosen destination instead of
 * DownloadManager), one-time Save as, cancelling, activity recreation and repeated taps.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 28, 29, 34, 36])
class LinksAndSaveAsTest {

	private lateinit var server: TinyHttpServer
	private val image = Random.nextBytes(512 * 1024 + 7)
	/** Released to let the slow response finish. */
	private var slowGate = CountDownLatch(1)

	@Before
	fun setUp() {
		TestSupport.reset()
		shadowOf(MimeTypeMap.getSingleton()).apply {
			addExtensionMimeTypeMapping("txt", "text/plain")
			addExtensionMimeTypeMapping("jpg", "image/jpeg")
			addExtensionMimeTypeMapping("html", "text/html")
			addExtensionMimeTypeMapping("bin", "application/octet-stream")
		}
		server = TinyHttpServer()
		fun respond(path: String, type: String, body: ByteArray, code: Int = 200) = server.route(path) {
			it.send(code, mapOf("Content-Type" to type), if (code == 200) body else ByteArray(0))
		}
		respond("/cat.jpg", "image/jpeg", image)
		respond("/page", "text/html; charset=utf-8", "<html></html>".toByteArray())
		respond("/blob", "application/x-custom", "custom".toByteArray())
		respond("/missing.jpg", "image/jpeg", ByteArray(0), code = 404)
		server.route("/moved") { it.send(302, mapOf("Location" to "/cat.jpg"), ByteArray(0)) }
		server.route("/slow.jpg") { r ->
			r.send(200, mapOf("Content-Type" to "image/jpeg"), null, length = 128L * 1024)
			r.out.write(ByteArray(64 * 1024)); r.out.flush()
			slowGate.await(20, TimeUnit.SECONDS)
			r.out.write(ByteArray(64 * 1024)); r.out.flush()
		}
		server.start()
	}

	@After
	fun tearDown() {
		slowGate.countDown()
		server.stop()
	}

	private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

	private fun share(text: String, subject: String? = null) = Robolectric.buildActivity(SaverActivity::class.java,
		Intent(context, SaverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
			.putExtra(Intent.EXTRA_TEXT, text).apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }).setup()

	private fun notifications() =
		shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

	private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
	private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

	private fun clickRow(activity: SaverActivity, title: String) {
		val list = activity.findViewById<RecyclerView>(R.id.list)
		val position = (list.adapter as CardListAdapter).items.indexOfFirst { it is CardItem.Row && it.title == title }
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST))
		list.layout(0, 0, 1080, list.measuredHeight)
		list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.row).performClick()
	}

	private fun finished(controller: ActivityController<SaverActivity>) = waitFor(20_000) { controller.get().isFinishing }

	// Links --------------------------------------------------------------------------------

	@Test
	fun imageLink_isDownloadedIntoDownload_completeBeforeTheNotification() {
		val controller = share(url("/cat.jpg"))
		assertTrue(finished(controller))
		val saved = File(downloads(), "cat.jpg")
		assertArrayEquals(image, saved.readBytes())
		assertEquals("cat.jpg saved to Internal storage/Download", notifications().single().text())
	}

	@Test
	fun imageLink_isDownloadedIntoAnSafFolder() {
		addFolders("Pics")
		TestSupport.hideDefault()
		assertTrue(finished(share(url("/cat.jpg"))))
		assertArrayEquals(image, folderFile("Pics/cat.jpg").readBytes())
		assertFalse(File(downloads(), "cat.jpg").exists())
		assertEquals("cat.jpg saved to Pics", notifications().single().text())
	}

	@Test
	fun redirectedLink_followsToTheImage() {
		assertTrue(finished(share(url("/moved"), subject = "Holiday")))
		assertArrayEquals(image, File(downloads(), "Holiday.jpg").readBytes())
	}

	@Test
	fun webPageLink_savesTheLinkAsText() {
		addFolders("Links")
		TestSupport.hideDefault()
		assertTrue(finished(share(url("/page"), subject = "page")))
		assertEquals(url("/page"), folderFile("Links/page.html").readText())
	}

	@Test
	fun unknownLinkType_isUnsupported_unlessForceSaving() {
		val activity = share(url("/blob")).get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.not_supported_wrapper).visibility == View.VISIBLE })
		assertTrue(TestSupport.downloadedFiles().isEmpty())

		prefs.edit().putBoolean("force_saving", true).commit()
		assertTrue(finished(share(url("/blob"), subject = "blob")))
		assertEquals("custom", TestSupport.downloadedFiles().single().readText())
	}

	@Test
	fun failedDownload_isReportedAsFailure_andLeavesNothing() {
		prefs.edit().putBoolean("follow_redirects", false).commit()
		// Type from the extension; the download itself fails with HTTP 404
		assertTrue(finished(share(url("/missing.jpg"))))
		assertTrue(TestSupport.downloadedFiles().isEmpty())
		val n = notifications().single()
		assertEquals(context.getString(R.string.notification_failed_title), n.title())
		assertEquals("missing.jpg: " + context.getString(R.string.toast_save_network), n.text())
		if (android.os.Build.VERSION.SDK_INT >= 29) assertTrue(FakeMediaStore.rows.isEmpty())
	}

	@Test
	fun cancellingADownload_removesThePartialFile_andPostsNoSuccess() {
		addFolders("Slow")
		TestSupport.hideDefault()
		val controller = share(url("/slow.jpg"))
		val activity = controller.get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.loading).visibility == View.VISIBLE &&
			TestSupport.folderFile("Slow").list()?.isNotEmpty() == true })
		// Repeated taps on Cancel while stopping are harmless
		activity.findViewById<View>(R.id.cancel).performClick()
		activity.findViewById<View>(R.id.cancel).performClick()
		slowGate.countDown()
		assertTrue(finished(controller))
		assertEquals(context.getString(R.string.toast_save_cancelled), ShadowToast.getTextOfLatestToast())
		assertTrue(folderFile("Slow").list()!!.isEmpty())
		assertTrue(notifications().isEmpty())
	}

	@Test
	fun recreatingTheSheetWhileSaving_savesOnce_andReportsOnce() {
		addFolders("Slow")
		TestSupport.hideDefault()
		val controller = share(url("/slow.jpg"))
		assertTrue(waitFor { folderFile("Slow").list()?.isNotEmpty() == true })
		controller.recreate()
		slowGate.countDown()
		assertTrue(finished(controller))
		assertEquals(listOf("slow.jpg"), folderFile("Slow").list()!!.toList())
		assertEquals(1, notifications().size)
	}

	@Test
	fun recreatingTheSheetWhileChoosing_doesNotSave() {
		addFolders("A", "B")
		val controller = share("choose")
		assertTrue(waitFor { controller.get().findViewById<View>(R.id.list).visibility == View.VISIBLE })
		controller.recreate()
		assertTrue(waitFor { controller.get().findViewById<View>(R.id.list).visibility == View.VISIBLE })
		clickRow(controller.get(), "B")
		clickRow(controller.get(), "B")
		assertTrue(finished(controller))
		assertEquals(listOf("choose.txt"), folderFile("B").list()!!.toList())
		assertTrue(folderFile("A").list()!!.isEmpty())
		assertTrue(TestSupport.downloadedFiles().isEmpty())
		assertEquals(1, notifications().size)
	}

	// Save as ------------------------------------------------------------------------------

	private fun answerCreateDocument(activity: SaverActivity, path: String?): Intent {
		assertTrue(waitFor { shadowOf(activity).peekNextStartedActivityForResult() != null })
		val started = shadowOf(activity).nextStartedActivityForResult
		assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
		if (path == null) {
			shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
		} else {
			val tree = TestDocumentsProvider.tree("Picked")
			folderFile("Picked/$path").createNewFile()
			shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK,
				Intent().setData(DocumentsContract.buildDocumentUriUsingTree(tree, "Picked/$path")))
		}
		return started.intent
	}

	@Test
	fun saveAs_writesIntoTheCreatedDocument_withoutAddingADestination() {
		prefs.edit().putBoolean("location_select", true).commit()
		val controller = share("save as me")
		val activity = controller.get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		clickRow(activity, context.getString(R.string.location_select_title))
		val picker = answerCreateDocument(activity, "chosen.txt")
		assertEquals("saveasme.txt", picker.getStringExtra(Intent.EXTRA_TITLE))
		assertEquals("text/plain", picker.type)
		assertTrue(finished(controller))
		assertEquals("save as me", folderFile("Picked/chosen.txt").readText())
		assertEquals(emptyList<Any>(), fjm.sh.dropfiler.destination.DestinationRepository.custom(context))
		assertEquals("chosen.txt saved to " + context.getString(R.string.notification_location_picker),
			notifications().single().text())
	}

	@Test
	fun saveAs_multipleFiles_asksOncePerFile_andCancelStopsTheRest() {
		prefs.edit().putBoolean("location_select", true).commit()
		fun source(name: String) = Uri.fromFile(File(context.cacheDir, name).apply { writeText(name) })
		val controller = Robolectric.buildActivity(SaverActivity::class.java, Intent(context, SaverActivity::class.java)
			.setAction(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
			.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(source("1.txt"), source("2.txt"), source("3.txt")))).setup()
		val activity = controller.get()
		assertTrue(waitFor { activity.findViewById<View>(R.id.list).visibility == View.VISIBLE })
		clickRow(activity, context.getString(R.string.location_select_title))
		answerCreateDocument(activity, "first.txt")
		answerCreateDocument(activity, null)
		assertTrue(finished(controller))
		assertEquals("1.txt", folderFile("Picked/first.txt").readText())
		assertEquals(context.getString(R.string.notification_partial_title), notifications().single().title())
	}

	@Test
	fun saveAs_cancelled_savesNothing() {
		prefs.edit().putBoolean("location_select", true).commit()
		val controller = share("nope")
		assertTrue(waitFor { controller.get().findViewById<View>(R.id.list).visibility == View.VISIBLE })
		clickRow(controller.get(), context.getString(R.string.location_select_title))
		answerCreateDocument(controller.get(), null)
		assertTrue(finished(controller))
		assertEquals(context.getString(R.string.toast_save_cancelled), ShadowToast.getTextOfLatestToast())
		assertTrue(notifications().isEmpty())
	}

	// Text ---------------------------------------------------------------------------------

	@Test
	fun unicodeText_withLenientNames_keepsTheName() {
		prefs.edit().putBoolean("lenient_regex", true).commit()
		assertTrue(finished(share("内容", subject = "メモ")))
		assertEquals("内容", File(downloads(), "メモ.txt").readText())
	}
}
