package fjm.sh.dropfiler

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.prefs
import fjm.sh.dropfiler.activity.CreditsActivity
import fjm.sh.dropfiler.activity.FolderListActivity
import fjm.sh.dropfiler.activity.SaverActivity
import fjm.sh.dropfiler.activity.SettingsActivity
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens to PNG files for visual review (light and dark).
 * Only runs when -Pscreenshots is passed:
 *   ./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots -ProbolectricSdks=33
 * Output: app/build/screenshots/
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

	private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

	@Before
	fun setUp() {
		assumeTrue(System.getProperty("screenshotDir") != null)
		outDir.mkdirs()
		TestSupport.reset()
	}

	private fun capture(activity: Activity, name: String) {
		TestSupport.idle()
		val view = activity.window.decorView
		view.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY))
		view.layout(0, 0, 1233, 2673)
		TestSupport.idle()
		val bitmap = Bitmap.createBitmap(1233, 2673, Bitmap.Config.ARGB_8888)
		view.draw(android.graphics.Canvas(bitmap))
		File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
	}

	private fun captureView(view: View, name: String) {
		TestSupport.idle()
		val width = view.width.takeIf { it > 0 } ?: 1233
		val height = view.height.takeIf { it > 0 } ?: 2673
		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		bitmap.eraseColor(0xFF888888.toInt())
		view.draw(android.graphics.Canvas(bitmap))
		File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
	}

	/** Captures the most recently added window (popups / drop-downs). */
	private fun captureLatestWindow(name: String) {
		val global = Class.forName("android.view.WindowManagerGlobal")
		val instance = global.getMethod("getInstance").invoke(null)
		val field = global.getDeclaredField("mViews").apply { isAccessible = true }
		@Suppress("UNCHECKED_CAST")
		val views = field.get(instance) as List<View>
		captureView(views.last(), name)
	}

	private fun all(suffix: String) {
		capture(Robolectric.buildActivity(FolderListActivity::class.java).setup().get(), "01-empty$suffix")

		val folders = TestSupport.addFolders("DCIM/Memes", "Pictures/Reaction images", "Documents/Receipts")
		val memes = folders.first()
		// A folder added by DropFiler 6.x, waiting to be reconnected
		fjm.sh.dropfiler.destination.DestinationStore.write(context, folders +
			fjm.sh.dropfiler.destination.SaveDestination(fjm.sh.dropfiler.destination.DestinationStore.legacyId("/Movies"),
				fjm.sh.dropfiler.destination.DestinationKind.LEGACY_RECONNECT_REQUIRED, "Movies", legacyPath = "/Movies"))
		capture(Robolectric.buildActivity(FolderListActivity::class.java).setup().get(), "02-destinations$suffix")

		capture(Robolectric.buildActivity(SettingsActivity::class.java).setup().get(), "03-settings$suffix")

		// Overflow menu (GitHub item)
		val menuActivity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		capture(menuActivity, "02b-menu-base$suffix")
		menuActivity.findViewById<com.hchen.himiuix.MiuixAppBar>(R.id.appbar).toolbar.showOverflowMenu()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
		captureLatestWindow("02c-overflow-menu$suffix")

		val share = Intent(context, SaverActivity::class.java).setAction(Intent.ACTION_SEND)
			.setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Some shared text")
		val saver = Robolectric.buildActivity(SaverActivity::class.java, share).setup().get()
		TestSupport.waitFor { saver.findViewById<View>(R.id.list).visibility == View.VISIBLE }
		// Let the slide-in animation finish
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
		capture(saver, "05-share-sheet$suffix")

		val list = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		fjm.sh.dropfiler.util.DialogHelper.showConfirmDialog(list,
			context.getString(R.string.folder_list_delete_title),
			context.getString(R.string.folder_list_delete_message, "/DCIM/Memes"),
			context.getString(R.string.folder_list_delete_confirm)) {}
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
		val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
		captureView(dialog.window!!.decorView, "07-confirm-dialog$suffix")

		capture(Robolectric.buildActivity(CreditsActivity::class.java).setup().get(), "06-credits$suffix")

		// Share sheet when the destination was deleted
		TestSupport.folderFile("DCIM/Memes").deleteRecursively()
		val missing = Robolectric.buildActivity(SaverActivity::class.java, Intent(share)
			.putExtra(SaverActivity.DIRECT_SHARE_DESTINATION, memes.id)).setup().get()
		TestSupport.waitFor { missing.findViewById<View>(R.id.list).visibility == View.VISIBLE }
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
		capture(missing, "09-missing-folder$suffix")

		capture(Robolectric.buildActivity(FolderListActivity::class.java).setup().get(), "10-destinations-reconnect$suffix")
		prefs.edit().putBoolean("dynamic_colors", true).commit()
		capture(Robolectric.buildActivity(SettingsActivity::class.java).setup().get(), "11-settings-dynamic$suffix")
		capture(Robolectric.buildActivity(FolderListActivity::class.java).setup().get(), "12-destinations-dynamic$suffix")
		prefs.edit().putBoolean("dynamic_colors", false).commit()
	}

	@Test
	fun light() = all("")

	@Test
	@Config(qualifiers = "+night")
	fun dark() = all("-dark")
}
