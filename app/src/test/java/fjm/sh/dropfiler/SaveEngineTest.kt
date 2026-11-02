package fjm.sh.dropfiler

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.save.DestinationWriter
import fjm.sh.dropfiler.save.DuplicatePolicy
import fjm.sh.dropfiler.save.FailReason
import fjm.sh.dropfiler.save.Filenames
import fjm.sh.dropfiler.save.LegacyDownloadsWriter
import fjm.sh.dropfiler.save.MediaStoreDownloadsWriter
import fjm.sh.dropfiler.save.SafTreeWriter
import fjm.sh.dropfiler.save.SingleDocumentWriter
import fjm.sh.dropfiler.save.WriteOutcome
import fjm.sh.dropfiler.save.WriteRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The target-specific writers: MediaStore Downloads (API 29+), the legacy Download folder
 * (API 21 - 28), SAF trees and single Save-as documents, with every duplicate policy.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 29, 30, 33, 34, 36])
class SaveEngineTest {

	@Before
	fun setUp() {
		TestSupport.reset()
		shadowOf(MimeTypeMap.getSingleton()).apply {
			addExtensionMimeTypeMapping("txt", "text/plain")
			addExtensionMimeTypeMapping("pdf", "application/pdf")
		}
	}

	private fun request(name: String, bytes: ByteArray, policy: DuplicatePolicy = DuplicatePolicy.OVERWRITE,
						sourceUri: Uri? = null, cancelled: AtomicBoolean = AtomicBoolean(false)) =
		WriteRequest(name, Filenames.outputMime(name, null), policy, { ByteArrayInputStream(bytes) }, sourceUri, cancelled)

	private fun safWriter(path: String = "Docs"): SafTreeWriter {
		val destination = TestSupport.addFolders(path).single()
		return SafTreeWriter(context, Uri.parse(destination.treeUri), destination.id)
	}

	private fun defaultWriter(): DestinationWriter =
		if (android.os.Build.VERSION.SDK_INT >= 29) MediaStoreDownloadsWriter(context) else LegacyDownloadsWriter(context)

	private fun saved(outcome: WriteOutcome) = outcome as? WriteOutcome.Saved
		?: throw AssertionError("expected Saved, got $outcome")

	private fun failed(outcome: WriteOutcome) = (outcome as? WriteOutcome.Failed)?.reason
		?: throw AssertionError("expected Failed, got $outcome")

	// Filenames ----------------------------------------------------------------------------

	@Test
	@Config(sdk = [36])
	fun filenames_postfixAndFallbacks() {
		assertEquals("a.1.txt", Filenames.postfix("a.txt", 1))
		assertEquals("README.2", Filenames.postfix("README", 2))
		assertEquals(".hidden.1", Filenames.postfix(".hidden", 1))
		// Non-Latin text with strict validation no longer becomes a hidden ".txt"
		assertEquals("default.txt", Filenames.sanitize("日本語", "text/plain", lenient = false).name)
		assertEquals("日本語.txt", Filenames.sanitize("日本語", "text/plain", lenient = true).name)
		assertEquals("helloworld.txt", Filenames.sanitize("hello world", "text/plain; charset=utf-8", false).name)
		// Byte limit for long Unicode names
		val long = Filenames.sanitize("😀".repeat(100), null, lenient = true).name
		assertTrue(long.toByteArray().size <= 200)
		assertEquals(DuplicatePolicy.POSTFIX, DuplicatePolicy.fromIndex(-1))
		assertEquals(DuplicatePolicy.POSTFIX, DuplicatePolicy.fromIndex(3))
		assertEquals("application/pdf", Filenames.outputMime("x.pdf", "image/*"))
		assertEquals("application/octet-stream", Filenames.outputMime("x.weird", "image/*"))
	}

	// SAF ----------------------------------------------------------------------------------

	@Test
	fun saf_newFile_isWrittenThroughTheProvider() {
		val bytes = Random.nextBytes(300_000)
		val outcome = saved(safWriter().write(request("report.pdf", bytes)))
		assertEquals("report.pdf", outcome.name)
		assertArrayEquals(bytes, TestSupport.folderFile("Docs/report.pdf").readBytes())
	}

	@Test
	fun saf_overwrite_replacesOnlyAfterTheNewCopyIsComplete() {
		val writer = safWriter()
		TestSupport.folderFile("Docs/a.txt").writeText("old old old old")
		val outcome = saved(writer.write(request("a.txt", "new".toByteArray())))
		assertTrue(outcome.replaced)
		assertEquals("a.txt", outcome.name)
		assertEquals("new", TestSupport.folderFile("Docs/a.txt").readText())
		assertEquals(listOf("a.txt"), TestSupport.folderFile("Docs").list()!!.toList())
	}

	@Test
	fun saf_overwrite_withoutDelete_truncatesTheOldDocument() {
		val writer = safWriter()
		TestSupport.folderFile("Docs/a.txt").writeText("old old old old")
		TestDocumentsProvider.supportsDelete = false
		val outcome = saved(writer.write(request("a.txt", "new".toByteArray())))
		assertTrue(outcome.replaced)
		// "wt" truncates: no stale tail of the longer old file
		assertEquals("new", TestSupport.folderFile("Docs/a.txt").readText())
	}

	@Test
	fun saf_overwrite_withoutDeleteOrTruncate_keepsBothFiles() {
		val writer = safWriter()
		TestSupport.folderFile("Docs/a.txt").writeText("old")
		TestDocumentsProvider.supportsDelete = false
		TestDocumentsProvider.supportsTruncate = false
		val outcome = saved(writer.write(request("a.txt", "new".toByteArray())))
		assertFalse(outcome.replaced)
		assertEquals("a (1).txt", outcome.name)
		assertEquals("old", TestSupport.folderFile("Docs/a.txt").readText())
		assertEquals("new", TestSupport.folderFile("Docs/a (1).txt").readText())
	}

	@Test
	fun saf_skip_and_postfix() {
		val writer = safWriter()
		TestSupport.folderFile("Docs/a.txt").writeText("old")
		assertEquals(FailReason.EXISTS, failed(writer.write(request("a.txt", "x".toByteArray(), DuplicatePolicy.SKIP))))
		assertEquals("old", TestSupport.folderFile("Docs/a.txt").readText())

		assertEquals("a.1.txt", saved(writer.write(request("a.txt", "1".toByteArray(), DuplicatePolicy.POSTFIX))).name)
		assertEquals("a.2.txt", saved(writer.write(request("a.txt", "2".toByteArray(), DuplicatePolicy.POSTFIX))).name)
		assertEquals("old", TestSupport.folderFile("Docs/a.txt").readText())
		assertEquals("2", TestSupport.folderFile("Docs/a.2.txt").readText())
	}

	@Test
	fun saf_failedWrite_removesOnlyTheNewDocument() {
		val writer = safWriter()
		TestSupport.folderFile("Docs/keep.txt").writeText("keep")
		TestDocumentsProvider.failWrites = true
		failed(writer.write(request("new.txt", Random.nextBytes(10_000))))
		assertEquals(listOf("keep.txt"), TestSupport.folderFile("Docs").list()!!.toList())
		// An overwrite that fails leaves the existing file untouched
		failed(writer.write(request("keep.txt", Random.nextBytes(10_000))))
		assertEquals("keep", TestSupport.folderFile("Docs/keep.txt").readText())
	}

	@Test
	fun saf_unreadableSource_isReportedAndCleanedUp() {
		val writer = safWriter()
		val outcome = writer.write(WriteRequest("x.txt", "text/plain", DuplicatePolicy.OVERWRITE,
			{ throw IOException("gone") }))
		assertEquals(FailReason.SOURCE_UNREADABLE, failed(outcome))
		assertTrue(TestSupport.folderFile("Docs").list()!!.isEmpty())
	}

	@Test
	fun saf_cancelled_removesPartialFile() {
		val writer = safWriter()
		val cancelled = AtomicBoolean(false)
		val slow = object : InputStream() {
			var n = 0
			override fun read(): Int = if (n++ < 200_000) 1 else -1
			override fun read(b: ByteArray, off: Int, len: Int): Int {
				if (n > 100_000) cancelled.set(true)
				return super.read(b, off, minOf(len, 1000))
			}
		}
		val outcome = writer.write(WriteRequest("big.bin", "application/octet-stream", DuplicatePolicy.OVERWRITE,
			{ slow }, null, cancelled))
		assertEquals(FailReason.CANCELLED, failed(outcome))
		assertTrue(TestSupport.folderFile("Docs").list()!!.isEmpty())
	}

	@Test
	fun saf_concurrentSaves_withPostfix_neverShareAName() {
		val writer = safWriter()
		val pool = Executors.newFixedThreadPool(4)
		val start = CountDownLatch(1)
		val results = (0 until 8).map { i ->
			pool.submit<WriteOutcome> {
				start.await()
				writer.write(request("same.txt", "v$i".toByteArray(), DuplicatePolicy.POSTFIX))
			}
		}
		start.countDown()
		val names = results.map { saved(it.get(30, TimeUnit.SECONDS)).name }
		pool.shutdown()
		assertEquals(8, names.toSet().size)
		assertEquals(8, TestSupport.folderFile("Docs").list()!!.size)
	}

	@Test
	fun saf_revokedGrant_isReportedAsNoPermission() {
		val destination = TestSupport.addFolders("Docs").single()
		TestSupport.revoke(destination)
		assertEquals(fjm.sh.dropfiler.destination.DestinationState.NEEDS_PERMISSION,
			DestinationRepository.checkState(context, destination))
	}

	// Built-in Download ---------------------------------------------------------------------

	@Test
	fun download_newFile_isSavedToTheDownloadRoot() {
		val bytes = Random.nextBytes(100_000)
		val outcome = saved(defaultWriter().write(request("app.apk", bytes)))
		assertEquals("app.apk", outcome.name)
		assertArrayEquals(bytes, TestSupport.downloads().resolve("app.apk").readBytes())
		assertFalse("no Download/DropFiler folder", TestSupport.downloads().resolve("DropFiler").exists())
		if (android.os.Build.VERSION.SDK_INT >= 29) {
			assertEquals("written while pending", listOf(true), FakeMediaStore.pendingAtWrite)
			assertEquals(listOf("app.apk"), FakeMediaStore.publishedNames())
		}
	}

	@Test
	fun download_allPolicies_forOwnFiles() {
		val writer = defaultWriter()
		saved(writer.write(request("a.txt", "old".toByteArray())))
		assertEquals(FailReason.EXISTS, failed(writer.write(request("a.txt", "x".toByteArray(), DuplicatePolicy.SKIP))))
		assertEquals("a.1.txt", saved(writer.write(request("a.txt", "p".toByteArray(), DuplicatePolicy.POSTFIX))).name)
		val replaced = saved(writer.write(request("a.txt", "new".toByteArray(), DuplicatePolicy.OVERWRITE)))
		assertEquals("a.txt", replaced.name)
		assertTrue(replaced.replaced)
		assertEquals("new", TestSupport.downloads().resolve("a.txt").readText())
		assertEquals("p", TestSupport.downloads().resolve("a.1.txt").readText())
		assertEquals(listOf("a.1.txt", "a.txt"), TestSupport.downloadedFiles().map { it.name })
	}

	@Test
	@Config(sdk = [29, 30, 33, 36])
	fun mediaStore_fileOfAnotherApp_isNeverReplaced() {
		// Not created through MediaStore by DropFiler, so invisible to it
		TestSupport.downloads().resolve("theirs.txt").writeText("theirs")
		val writer = MediaStoreDownloadsWriter(context)

		val overwrite = saved(writer.write(request("theirs.txt", "mine".toByteArray(), DuplicatePolicy.OVERWRITE)))
		assertEquals("theirs (1).txt", overwrite.name)
		assertFalse(overwrite.replaced)
		assertEquals("theirs", TestSupport.downloads().resolve("theirs.txt").readText())

		assertEquals(FailReason.EXISTS, failed(writer.write(request("theirs.txt", "x".toByteArray(), DuplicatePolicy.SKIP))))
		assertEquals("provider-renamed copy is removed again for Do not save",
			listOf("theirs (1).txt", "theirs.txt"), TestSupport.downloadedFiles().map { it.name })

		assertEquals("theirs (2).txt", saved(writer.write(request("theirs.txt", "y".toByteArray(), DuplicatePolicy.POSTFIX))).name)
	}

	@Test
	@Config(sdk = [29, 36])
	fun mediaStore_failures_leaveNoRow() {
		val writer = MediaStoreDownloadsWriter(context)
		FakeMediaStore.failInsert = true
		assertEquals(FailReason.DESTINATION_UNAVAILABLE, failed(writer.write(request("a.txt", "x".toByteArray()))))
		FakeMediaStore.failInsert = false
		FakeMediaStore.failPublish = true
		failed(writer.write(request("b.txt", "x".toByteArray())))
		FakeMediaStore.failPublish = false
		failed(writer.write(WriteRequest("c.txt", "text/plain", DuplicatePolicy.OVERWRITE, { throw IOException("x") })))
		assertTrue(FakeMediaStore.rows.isEmpty())
		assertTrue(TestSupport.downloadedFiles().isEmpty())
	}

	@Test
	@Config(sdk = [28])
	fun legacyDownload_withoutPermission_failsHonestly() {
		shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
		assertEquals(FailReason.NO_PERMISSION, failed(LegacyDownloadsWriter(context).write(request("a.txt", "x".toByteArray()))))
		assertTrue(TestSupport.downloadedFiles().isEmpty())
	}

	// Save as ------------------------------------------------------------------------------

	@Test
	fun singleDocument_isTruncated() {
		val tree = TestDocumentsProvider.tree("Pick")
		TestSupport.folderFile("Pick/chosen.txt").writeText("a much longer old content")
		val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "Pick/chosen.txt")
		val outcome = saved(SingleDocumentWriter(context, doc).write(request("ignored.txt", "short".toByteArray())))
		assertEquals("chosen.txt", outcome.name)
		assertEquals("short", TestSupport.folderFile("Pick/chosen.txt").readText())
	}
}
