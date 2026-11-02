package fjm.sh.dropfiler

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.destination.DestinationKind
import fjm.sh.dropfiler.destination.DestinationPaths
import fjm.sh.dropfiler.destination.DestinationPaths.DOWNLOADS_AUTHORITY
import fjm.sh.dropfiler.destination.DestinationPaths.EXTERNAL_STORAGE_AUTHORITY
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.SaveDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The folder path shown under each destination instead of the provider's name. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 36])
class DestinationPathsTest {

	private fun format(authority: String?, docId: String?, label: String = "Work", provider: String? = "Local storage",
		volume: String? = null) =
		DestinationPaths.format(authority, docId, label, provider, "Internal storage", "SD card") { volume }

	@Test
	fun internalStorage_showsFullPath() {
		assertEquals("Internal storage/Folder", format(EXTERNAL_STORAGE_AUTHORITY, "primary:Folder"))
		assertEquals("Internal storage/Documents/Work", format(EXTERNAL_STORAGE_AUTHORITY, "primary:Documents/Work"))
		assertEquals("Internal storage", format(EXTERNAL_STORAGE_AUTHORITY, "primary:"))
		assertEquals("Internal storage/Documents", format(EXTERNAL_STORAGE_AUTHORITY, "home:"))
		assertEquals("Internal storage/Documents/Work", format(EXTERNAL_STORAGE_AUTHORITY, "home:Work"))
	}

	@Test
	fun removableStorage_usesVolumeName() {
		assertEquals("SanDisk SD card/Photos",
			format(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:Photos", volume = "SanDisk SD card"))
		assertEquals("SD card/Photos", format(EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:Photos"))
	}

	@Test
	fun downloadsProvider_showsPathWhenKnown() {
		assertEquals("Internal storage/Download", format(DOWNLOADS_AUTHORITY, "downloads"))
		assertEquals("Internal storage/Download/Work",
			format(DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/Work"))
		assertEquals("Downloads/Work", format(DOWNLOADS_AUTHORITY, "msf:42", provider = "Downloads"))
	}

	@Test
	fun otherProviders_showProviderAndFolderName() {
		assertEquals("Google Drive/Work", format("com.google.android.apps.docs.storage", "acc=1;doc=x", provider = "Google Drive"))
		assertNull(format("some.provider", "x", provider = null))
	}

	@Test
	fun pathOf_readsTheStoredTreeUri() {
		val tree = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Folder")
		val destination = SaveDestination("saf:1", DestinationKind.SAF_TREE, "Folder", tree.toString(), "Local storage")
		assertEquals("Internal storage/Folder", DestinationPaths.pathOf(context, destination))
		assertEquals(context.getString(R.string.default_destination_path),
			DestinationPaths.pathOf(context, DestinationRepository.defaultDestination(context)))
	}
}
