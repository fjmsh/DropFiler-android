package fjm.sh.dropfiler

import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.util.DirectShareHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Short labels of the launcher shortcuts and Direct Share targets keep the start of the name. */
@RunWith(AndroidJUnit4::class)
class ShortcutLabelTest {

	@Test
	fun shortNames_areUnchanged() {
		assertEquals("Work", DirectShareHelper.shortLabel("Work"))
		assertEquals("Exactly15chars!", DirectShareHelper.shortLabel("Exactly15chars!"))
		assertEquals("Work", DirectShareHelper.shortLabel("  Work "))
	}

	@Test
	fun longNames_keepTheStartAndCutAtAWord() {
		assertEquals("Another Neat…", DirectShareHelper.shortLabel("Another Neat Folder"))
		assertEquals("My Documents…", DirectShareHelper.shortLabel("My Documents Archive 2026"))
	}

	@Test
	fun longWords_areCutMidWord() {
		assertEquals("Supercalifragi…", DirectShareHelper.shortLabel("Supercalifragilistic"))
		// A space too early would leave too little of the name
		assertEquals("A Supercalifra…", DirectShareHelper.shortLabel("A Supercalifragilistic"))
		assertEquals(15, DirectShareHelper.shortLabel("Supercalifragilistic").length)
	}
}
