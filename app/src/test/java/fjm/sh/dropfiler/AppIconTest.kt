package fjm.sh.dropfiler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hchen.himiuix.MiuixAppBar
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.prefs
import fjm.sh.dropfiler.activity.CreditsActivity
import fjm.sh.dropfiler.activity.FolderListActivity
import fjm.sh.dropfiler.ui.AppBarTitleIcon
import fjm.sh.dropfiler.util.ThemeHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconTest {

	@Before
	fun setUp() {
		prefs.edit().clear().commit()
		de.cketti.library.changelog.ChangeLog(context).skipLogDialog()
	}

	private fun largeTitle(activity: FolderListActivity): TextView =
		AppBarTitleIcon.findLargeTitle(activity.findViewById<MiuixAppBar>(R.id.appbar))!!

	private fun titleIcon(activity: FolderListActivity): Drawable =
		TextViewCompat.getCompoundDrawablesRelative(largeTitle(activity))[0]!!

	/** The colour of the drawn strokes (any opaque pixel; the glyph is a single colour). */
	private fun drawnColor(drawable: Drawable): Int {
		val bounds = drawable.copyBounds()
		val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
		drawable.draw(canvas)
		for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
			val pixel = bitmap.getPixel(x, y)
			if (Color.alpha(pixel) == 255) return pixel
		}
		throw AssertionError("Nothing drawn")
	}

	@Test
	@Config(sdk = [23, 28, 36])
	fun mainTitle_showsTintedSymbolBeforeText() {
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		val title = largeTitle(activity)
		// The heading stays plain, accessible text; the icon is a decorative start drawable
		assertEquals(context.getString(R.string.app_name), title.text.toString())
		assertNull(title.contentDescription)
		val icon = titleIcon(activity)
		val density = context.resources.displayMetrics.density
		assertEquals((28 * density).toInt(), icon.bounds.width())
		assertEquals((10 * density).toInt(), title.compoundDrawablePadding)
		// Robolectric can only rasterise with native graphics from API 26
		if (Build.VERSION.SDK_INT >= 26) assertEquals(activity.getColor(R.color.fs_on_accent_container), drawnColor(icon))
	}

	@Test
	@Config(sdk = [28, 34])
	fun mainTitle_iconGrowsWithLargerFonts() {
		org.robolectric.RuntimeEnvironment.setFontScale(2f)
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		val title = largeTitle(activity)
		val icon = titleIcon(activity)
		val density = context.resources.displayMetrics.density
		assertTrue(icon.bounds.width() > (28 * density).toInt())
		assertTrue(icon.bounds.height() <= title.layoutParams.height)
	}

	@Test
	@Config(sdk = [31, 34, 36])
	fun mainTitle_followsDynamicColors() {
		prefs.edit().putBoolean(ThemeHelper.PREF_DYNAMIC_COLORS, true).commit()
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		assertEquals(activity.getColor(android.R.color.system_accent1_700), drawnColor(titleIcon(activity)))
	}

	@Test
	@Config(sdk = [31, 36], qualifiers = "night")
	fun mainTitle_dynamicColorsInDarkThemeMatchFolderIcons() {
		prefs.edit().putBoolean(ThemeHelper.PREF_DYNAMIC_COLORS, true).commit()
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		assertEquals(activity.getColor(android.R.color.system_accent1_100), drawnColor(titleIcon(activity)))
	}

	@Test
	@Config(sdk = [28, 36], qualifiers = "night")
	fun mainTitle_defaultDarkThemeMatchesFolderIcons() {
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		assertEquals(0xFFA9C6FF.toInt(), drawnColor(titleIcon(activity)))
	}

	@Test
	@Config(sdk = [31, 36])
	fun mainTitle_updatesWhenAppearanceChanges() {
		val controller = Robolectric.buildActivity(FolderListActivity::class.java).setup()
		assertEquals(controller.get().getColor(R.color.fs_on_accent_container), drawnColor(titleIcon(controller.get())))
		// Settings change dynamic colours; BaseActivity recreates the screen when it resumes
		prefs.edit().putBoolean(ThemeHelper.PREF_DYNAMIC_COLORS, true).commit()
		controller.recreate()
		assertEquals(controller.get().getColor(android.R.color.system_accent1_700),
			drawnColor(titleIcon(controller.get())))
	}

	@Test
	@Config(sdk = [26, 33, 36])
	fun launcherIcon_isAdaptiveWithFolderIconColors() {
		for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
			val icon = context.getDrawable(id) as AdaptiveIconDrawable
			assertEquals(0xFF1E2A43.toInt(), (icon.background as ColorDrawable).color)
			assertNotNull(icon.foreground)
			icon.foreground.setBounds(0, 0, 216, 216)
			assertEquals(0xFFA9C6FF.toInt(), drawnColor(icon.foreground))
			if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon.monochrome)
		}
	}

	@Test
	@Config(sdk = [23, 25])
	fun launcherIcon_usesLegacyBitmapsBeforeAndroid8() {
		assertTrue(context.getDrawable(R.mipmap.ic_launcher) is android.graphics.drawable.BitmapDrawable)
		assertTrue(context.getDrawable(R.mipmap.ic_launcher_round) is android.graphics.drawable.BitmapDrawable)
	}

	@Test
	@Config(sdk = [28, 36])
	fun aboutLogo_usesIconTileCornerRatio() {
		val activity = Robolectric.buildActivity(CreditsActivity::class.java).setup().get()
		val logo = activity.findViewById<android.widget.ImageView>(R.id.credits_logo)
		val tile = logo.background as GradientDrawable
		val res = context.resources
		val logoRatio = tile.cornerRadius / res.getDimension(R.dimen.fs_logo_size)
		val tileRatio = res.getDimension(R.dimen.fs_icon_tile_radius) / res.getDimension(R.dimen.fs_icon_tile)
		assertEquals(tileRatio, logoRatio, 0.001f)
		assertEquals(0xFF1E2A43.toInt(), tile.color!!.defaultColor)
		assertNotNull(logo.drawable)
	}
}
