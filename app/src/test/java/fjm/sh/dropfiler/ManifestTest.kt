package fjm.sh.dropfiler

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.activity.FolderListActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Package, target SDK, permissions and exported components after the scoped storage migration.
 * (The built APK is also checked with aapt2 in CI.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 33, 36])
class ManifestTest {

	@Before
	fun setUp() = TestSupport.reset()

	private val info get() = context.packageManager.getPackageInfo(context.packageName,
		PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)

	@Test
	fun identityAndTarget() {
		assertEquals("fjm.sh.dropfiler", context.packageName)
		assertEquals(36, context.applicationInfo.targetSdkVersion)
	}

	@Test
	fun noBroadStorageAccess() {
		val requested = info.requestedPermissions!!.toList()
		assertFalse(requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
		assertFalse(requested.contains("android.permission.READ_MEDIA_IMAGES"))
		assertTrue(requested.contains(Manifest.permission.INTERNET))
		assertTrue(requested.contains(Manifest.permission.POST_NOTIFICATIONS))
		assertFalse("requestLegacyExternalStorage is not used",
			context.applicationInfo.flags and 0x20000000 != 0 && android.os.Build.VERSION.SDK_INT == 29)
	}

	@Test
	fun componentsAndExportedState() {
		val activities = info.activities!!.associateBy { it.name.substringAfterLast('.') }
		assertTrue(activities.getValue("SaverActivity").exported)
		assertTrue(activities.getValue("FolderListActivity").exported)
		assertFalse(activities.getValue("SettingsActivity").exported)
		assertFalse("legacy folder browser removed", activities.containsKey("FolderSelectActivity"))
		assertTrue("DownloadManager receiver removed",
			info.receivers.orEmpty().none { it.name.startsWith("fjm.sh.dropfiler") })
		assertEquals(Manifest.permission.BIND_CHOOSER_TARGET_SERVICE, info.services!!.single().permission)
	}

	@Test
	@Config(sdk = [33, 36])
	fun notificationPermission_isRequestedOnceFromTheMainScreen() {
		shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		val request = shadowOf(activity).nextStartedActivityForResult
		assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", request.intent.action)
		assertTrue(request.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES")!!
			.contains(Manifest.permission.POST_NOTIFICATIONS))
		assertTrue(PreferenceManager.getDefaultSharedPreferences(context).getBoolean(FolderListActivity.PREF_ASKED_NOTIFICATIONS, false))
		val again = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		assertEquals(null, shadowOf(again).nextStartedActivityForResult)
		assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
	}

	@Test
	@Config(sdk = [28])
	fun android9_requestsLegacyStorageOnlyForTheDownloadFolder() {
		shadowOf(context as Application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		val request = shadowOf(activity).nextStartedActivityForResult
		assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
			request.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES")!!.toList())
	}

	@Test
	@Config(sdk = [36])
	fun android10Plus_neverRequestsStorage() {
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		assertEquals(null, shadowOf(activity).nextStartedActivityForResult)
	}
}
