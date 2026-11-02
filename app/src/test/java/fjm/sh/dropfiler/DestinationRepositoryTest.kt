package fjm.sh.dropfiler

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.addFolders
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.folderFile
import fjm.sh.dropfiler.TestSupport.waitFor
import fjm.sh.dropfiler.activity.SaverActivity
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.DestinationRepository.TreeResult
import fjm.sh.dropfiler.destination.DestinationState
import fjm.sh.dropfiler.destination.DestinationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File

/**
 * Several independent SAF destinations: persisted grants, order and state over restarts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 28, 29, 33, 36])
class DestinationRepositoryTest {

	@Before
	fun setUp() = TestSupport.reset()

	private fun shareTo(id: String, text: String) = Robolectric.buildActivity(SaverActivity::class.java,
		Intent(context, SaverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
			.putExtra(Intent.EXTRA_TEXT, text).putExtra(SaverActivity.DIRECT_SHARE_DESTINATION, id)).setup()

	@Test
	fun fiveFolders_eachSavedTo_survivingARestart() {
		val paths = listOf("One", "Two/Nested", "Three", "Four", "Five")
		val added = addFolders(*paths.toTypedArray())
		assertEquals(5, added.map { it.id }.toSet().size)
		assertEquals(5, context.contentResolver.persistedUriPermissions.size)

		// "Restart": nothing cached in memory; everything comes from the store and grants
		val reloaded = DestinationRepository.custom(context)
		assertEquals(added, reloaded)
		assertTrue(reloaded.all { DestinationRepository.checkState(context, it) == DestinationState.READY })

		reloaded.forEachIndexed { i, d ->
			val controller = shareTo(d.id, "file $i")
			assertTrue(waitFor { controller.get().isFinishing })
		}
		paths.forEachIndexed { i, p -> assertEquals("file $i", folderFile("$p/file$i.txt").readText()) }

		// Reorder and remove, then reload again
		DestinationRepository.reorder(context, listOf(added[4].id, added[0].id))
		assertEquals(listOf("Five", "One", "Nested", "Three", "Four"), DestinationRepository.custom(context).map { it.label })
		DestinationRepository.remove(context, added[2].id)
		assertEquals(listOf("Five", "One", "Nested", "Four"), DestinationRepository.custom(context).map { it.label })
		assertEquals("only the removed folder's grant is released", 4, context.contentResolver.persistedUriPermissions.size)
		assertTrue("files are never deleted", folderFile("Three/file2.txt").exists())
	}

	@Test
	fun states_forUnmountedReadOnlyDeletedAndRevoked() {
		val (a, b, c, d) = addFolders("A", "B", "C", "D")
		TestDocumentsProvider.readOnly.add("B")
		folderFile("C").deleteRecursively()
		TestSupport.revoke(d)
		assertEquals(DestinationState.READY, DestinationRepository.checkState(context, a))
		assertEquals(DestinationState.READ_ONLY, DestinationRepository.checkState(context, b))
		assertEquals(DestinationState.UNAVAILABLE, DestinationRepository.checkState(context, c))
		assertEquals(DestinationState.NEEDS_PERMISSION, DestinationRepository.checkState(context, d))
		TestDocumentsProvider.unmounted = true
		assertEquals(DestinationState.UNAVAILABLE, DestinationRepository.checkState(context, a))
		// Records are kept, so they can be reconnected later
		assertEquals(4, DestinationRepository.custom(context).size)
	}

	@Test
	fun unmountedDestination_viaDirectShare_offersOthers_andSavesNothingThere() {
		val (sd, _) = addFolders("SD", "Phone")
		TestDocumentsProvider.unmounted = true
		val activity = shareTo(sd.id, "later").get()
		assertTrue(waitFor { activity.findViewById<android.view.View>(R.id.list).visibility == android.view.View.VISIBLE })
		assertFalse(activity.isFinishing)
		TestDocumentsProvider.unmounted = false
		assertFalse(folderFile("SD/later.txt").exists())
	}

	@Test
	fun duplicateTree_isRejected_andKeepsItsGrant() {
		val (a) = addFolders("A")
		val tree = TestDocumentsProvider.tree("A")
		val result = DestinationRepository.addTree(context, tree, TestDocumentsProvider.pickerResult(tree).flags)
		assertEquals(TreeResult.Duplicate(a), result)
		assertEquals(1, DestinationRepository.custom(context).size)
		assertTrue(DestinationRepository.hasWriteGrant(context, a.treeUri))
	}

	@Test
	fun cancelledOrInvalidPicks_storeNothing() {
		assertEquals(TreeResult.Cancelled, DestinationRepository.addTree(context, null, 0))
		assertEquals(TreeResult.Invalid, DestinationRepository.addTree(context,
			android.net.Uri.fromFile(File("/sdcard/Download")), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
		assertEquals(emptyList<Any>(), DestinationRepository.custom(context))
		assertTrue(File(context.filesDir, DestinationStore.FILE_NAME).readText().contains("\"destinations\":[]"))
	}
}
