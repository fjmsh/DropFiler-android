package fjm.sh.dropfiler

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import fjm.sh.dropfiler.TestSupport.context
import fjm.sh.dropfiler.TestSupport.prefs
import fjm.sh.dropfiler.TestSupport.addFolders
import fjm.sh.dropfiler.TestSupport.waitFor
import fjm.sh.dropfiler.activity.FolderListActivity
import fjm.sh.dropfiler.destination.DestinationRepository
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import fjm.sh.dropfiler.ui.CardItem
import fjm.sh.dropfiler.ui.CardListAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 26, 28, 29, 30, 31, 33, 34, 35, 36])
class FolderListActivityTest {

	@Before
	fun setUp() {
		TestSupport.reset()
		// These tests are about the user's own destinations: hide the built-in Download
		// destination, which is then only used when no other destination works (DefaultFolderTest)
		TestSupport.hideDefault()
	}

	private fun launch(): FolderListActivity {
		val activity = Robolectric.buildActivity(FolderListActivity::class.java).setup().get()
		TestSupport.idle()
		return activity
	}

	/** The stored destinations' labels, in order. */
	private fun stored() = DestinationRepository.custom(context).map { it.label }

	private fun titles(activity: FolderListActivity) =
		items(activity).filterIsInstance<CardItem.Row>().map { it.title.toString() }

	private fun items(activity: FolderListActivity): List<CardItem> =
		(activity.findViewById<RecyclerView>(R.id.list).adapter as CardListAdapter).items

	@Test
	fun noDestinations_showsBuiltInDownload() {
		// Hidden in setUp, but there is no other destination, so it is still shown
		val activity = launch()
		val rows = items(activity).filterIsInstance<CardItem.Row>()
		assertEquals(listOf(DestinationRepository.DEFAULT_ID), rows.map { it.key })
		assertEquals(0, rows[0].actionIcon)
		assertFalse(rows[0].draggable)
		assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.fab).visibility)
	}

	@Test
	fun existingDestinations_areShownInStoredOrder() {
		addFolders("DCIM/Memes", "Pictures", "Documents/Receipts")
		val activity = launch()

		val rows = items(activity).filterIsInstance<CardItem.Row>()
		assertEquals(listOf("Memes", "Pictures", "Receipts"), rows.map { it.title.toString() })
		assertEquals(DestinationRepository.custom(context).map { it.id }, rows.map { it.key })
		// Ready folders: normal style, removable, not a "reconnect" row
		assertTrue(rows.all { !it.danger && !it.clickable && it.actionIcon != 0 })
		assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.fab).visibility)
	}

	@Test
	fun locationSelect_addsNonDeletableFirstRow() {
		addFolders("A")
		prefs.edit().putBoolean("location_select", true).commit()
		val rows = items(launch()).filterIsInstance<CardItem.Row>()
		assertEquals(2, rows.size)
		assertEquals(0, rows[0].actionIcon)
		assertTrue(rows[1].actionIcon != 0)
	}

	private fun pickFolder(activity: FolderListActivity, path: String) {
		activity.findViewById<View>(R.id.fab).performClick()
		val started = shadowOf(activity).nextStartedActivityForResult
		assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.action)
		shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK,
			TestDocumentsProvider.pickerResult(TestDocumentsProvider.tree(path)))
	}

	@Test
	fun folderPicker_appendsWithoutDuplicates_keepingCustomOrder() {
		addFolders("d", "b")
		val activity = launch()

		pickFolder(activity, "C")
		assertTrue(waitFor { stored() == listOf("d", "b", "C") && titles(activity) == listOf("d", "b", "C") })
		// The grant is persisted, so sharing never asks again
		assertTrue(DestinationRepository.hasWriteGrant(context, DestinationRepository.custom(context)[2].treeUri))

		pickFolder(activity, "C")
		assertTrue(waitFor { ShadowToast.getTextOfLatestToast() == context.getString(R.string.toast_destination_duplicate, "C") })
		assertEquals(listOf("d", "b", "C"), stored())
	}

	@Test
	fun folderPicker_cancelled_addsNothing() {
		val activity = launch()
		activity.findViewById<View>(R.id.fab).performClick()
		val started = shadowOf(activity).nextStartedActivityForResult
		shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
		waitFor(300) { false }
		assertEquals(emptyList<String>(), stored())
	}

	@Test
	fun folderPicker_readOnlyOrUngranted_isRejected() {
		val activity = launch()
		TestDocumentsProvider.tree("RO")
		TestDocumentsProvider.readOnly.add("RO")
		pickFolder(activity, "RO")
		assertTrue(waitFor { ShadowToast.getTextOfLatestToast() == context.getString(R.string.toast_destination_read_only) })
		assertEquals(emptyList<String>(), stored())
		assertTrue("grant released again", context.contentResolver.persistedUriPermissions.isEmpty())

		// A picker result without write access
		activity.findViewById<View>(R.id.fab).performClick()
		val started = shadowOf(activity).nextStartedActivityForResult
		shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent()
			.setData(TestDocumentsProvider.tree("NoWrite")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
		assertTrue(waitFor { ShadowToast.getTextOfLatestToast() == context.getString(R.string.toast_destination_not_granted) })
		assertEquals(emptyList<String>(), stored())

		// Not a tree at all
		activity.findViewById<View>(R.id.fab).performClick()
		val again = shadowOf(activity).nextStartedActivityForResult
		shadowOf(activity).receiveResult(again.intent, Activity.RESULT_OK,
			TestDocumentsProvider.pickerResult(android.net.Uri.parse("content://${TestDocumentsProvider.AUTHORITY}/document/x")))
		assertTrue(waitFor { ShadowToast.getTextOfLatestToast() == context.getString(R.string.toast_destination_unavailable) })
		assertEquals(emptyList<String>(), stored())
	}

	@Test
	fun brokenDestinations_showReconnect_andKeepIdAndOrder() {
		val (a, b, c) = addFolders("A", "B", "C")
		TestSupport.revoke(b)
		TestSupport.folderFile("C").deleteRecursively()
		val activity = launch()
		assertTrue(waitFor { items(activity).filterIsInstance<CardItem.Row>().count { it.danger } == 2 })
		val rows = items(activity).filterIsInstance<CardItem.Row>()
		// Download stays hidden: "A" still works
		assertEquals(listOf(a.id, b.id, c.id), rows.map { it.key })
		assertEquals(context.getString(R.string.destination_state_needs_permission), rows[1].summary)
		assertEquals(context.getString(R.string.destination_state_unavailable), rows[2].summary)
		assertTrue(rows[1].clickable && rows[1].showChevron && rows[1].actionIcon != 0 && rows[1].draggable)

		// Tap "B" → picker (starting at the old folder) → same folder → reconnected in place
		val list = laidOutList(activity)
		list.findViewHolderForAdapterPosition(2)!!.itemView.findViewById<View>(R.id.row).performClick()
		val started = shadowOf(activity).nextStartedActivityForResult
		assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.action)
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			assertEquals(b.treeUri, started.intent.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI).toString())
		}
		shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK,
			TestDocumentsProvider.pickerResult(android.net.Uri.parse(b.treeUri)))
		assertTrue(waitFor { ShadowToast.getTextOfLatestToast() == context.getString(R.string.toast_destination_reconnected, "B") })
		assertEquals(listOf(a.id, b.id, c.id), DestinationRepository.custom(context).map { it.id })
		assertTrue(DestinationRepository.hasWriteGrant(context, b.treeUri))

		// Reconnect "C" by picking a different folder: said explicitly, id and position kept
		laidOutList(activity).findViewHolderForAdapterPosition(3)!!.itemView.findViewById<View>(R.id.row).performClick()
		val next = shadowOf(activity).nextStartedActivityForResult
		shadowOf(activity).receiveResult(next.intent, Activity.RESULT_OK,
			TestDocumentsProvider.pickerResult(TestDocumentsProvider.tree("Elsewhere")))
		assertTrue(waitFor { ShadowToast.getTextOfLatestToast() == context.getString(R.string.toast_destination_changed, "Elsewhere", "C") })
		assertEquals(listOf("A", "B", "Elsewhere"), stored())
		assertEquals(c.id, DestinationRepository.custom(context)[2].id)
	}

	private fun laidOutList(activity: FolderListActivity): RecyclerView {
		val list = activity.findViewById<RecyclerView>(R.id.list)
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY))
		list.layout(0, 0, 1080, 4000)
		return list
	}

	private fun touch(view: View, action: Int, x: Float, y: Float, downTime: Long) {
		val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
		view.dispatchTouchEvent(event)
		event.recycle()
	}

	private fun itemTouchHelper(activity: FolderListActivity): androidx.recyclerview.widget.ItemTouchHelper =
		FolderListActivity::class.java.getDeclaredField("itemTouchHelper").apply { isAccessible = true }
			.get(activity) as androidx.recyclerview.widget.ItemTouchHelper

	private fun helperField(helper: androidx.recyclerview.widget.ItemTouchHelper, name: String): Any? =
		androidx.recyclerview.widget.ItemTouchHelper::class.java.getDeclaredField(name)
			.apply { isAccessible = true }.get(helper)

	@Test
	@Config(qualifiers = "w411dp-h1600dp")
	fun dragHandle_startsDrag() {
		addFolders("A", "B")
		val activity = launch()
		val list = activity.findViewById<RecyclerView>(R.id.list)
		val holder = list.findViewHolderForAdapterPosition(1)!!
		val handle = holder.itemView.findViewById<View>(R.id.drag_handle)
		assertEquals(View.VISIBLE, handle.visibility)

		val handleLocation = IntArray(2).also { handle.getLocationInWindow(it) }
		val listLocation = IntArray(2).also { list.getLocationInWindow(it) }
		val x = (handleLocation[0] - listLocation[0] + handle.width / 2).toFloat()
		val y = (handleLocation[1] - listLocation[1] + handle.height / 2).toFloat()
		val down = android.os.SystemClock.uptimeMillis()
		touch(list, android.view.MotionEvent.ACTION_DOWN, x, y, down)
		val helper = itemTouchHelper(activity)
		assertEquals("touching the handle starts dragging that row", holder, helperField(helper, "mSelected"))

		// Cancel before running the looper: Robolectric does not pace ItemTouchHelper's frames
		touch(list, android.view.MotionEvent.ACTION_CANCEL, x, y, down)
		assertEquals(null, helperField(helper, "mSelected"))
	}

	@Test
	@Config(qualifiers = "w411dp-h1600dp")
	fun drag_reordersDestinations_andSavesOrderOnDrop() {
		addFolders("A", "B", "C")
		prefs.edit().putBoolean("location_select", true).commit()
		val activity = launch()
		val list = activity.findViewById<RecyclerView>(R.id.list)
		val callback = helperField(itemTouchHelper(activity), "mCallback")
			as androidx.recyclerview.widget.ItemTouchHelper.Callback
		fun holder(position: Int) = list.findViewHolderForAdapterPosition(position)!!

		// 0 header, 1 location select, 2..4 destinations
		assertEquals(0, callback.getMovementFlags(list, holder(0)))
		assertEquals("location select row stays first", 0, callback.getMovementFlags(list, holder(1)))
		assertTrue(callback.getMovementFlags(list, holder(2)) != 0)
		assertFalse("cannot drop above location select", callback.canDropOver(list, holder(2), holder(1)))

		// Drag "A" down two places, then drop
		assertTrue(callback.onMove(list, holder(2), holder(3)))
		TestSupport.idle()
		assertTrue(callback.onMove(list, holder(3), holder(4)))
		TestSupport.idle()
		assertEquals("not saved before the drop", listOf("A", "B", "C"), stored())
		callback.clearView(list, holder(4))
		TestSupport.idle()

		assertEquals(listOf("B", "C", "A"), stored())
		assertEquals(listOf(context.getString(R.string.location_select_title), "B", "C", "A"), titles(activity))
	}

	@Test
	fun accessibilityActions_moveDestinationsUpAndDown() {
		addFolders("A", "B", "C")
		val activity = launch()
		val list = laidOutList(activity)

		fun actionsOf(position: Int): List<android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction> {
			val row = list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.row)
			val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
			row.onInitializeAccessibilityNodeInfo(info)
			return info.actionList
		}
		fun perform(position: Int, label: Int) {
			val row = list.findViewHolderForAdapterPosition(position)!!.itemView.findViewById<View>(R.id.row)
			val action = actionsOf(position).first { it.label == context.getString(label) }
			assertTrue(row.performAccessibilityAction(action.id, null))
			TestSupport.idle()
			laidOutList(activity)
		}

		val labels = { position: Int -> actionsOf(position).mapNotNull { it.label?.toString() } }
		// First destination can only move down, last only up
		assertFalse(labels(1).contains(context.getString(R.string.folder_list_move_up)))
		assertTrue(labels(1).contains(context.getString(R.string.folder_list_move_down)))
		assertFalse(labels(3).contains(context.getString(R.string.folder_list_move_down)))

		perform(1, R.string.folder_list_move_down)
		assertEquals(listOf("B", "A", "C"), stored())
		perform(3, R.string.folder_list_move_up)
		assertEquals(listOf("B", "C", "A"), stored())
	}

	@Test
	fun locationSelectRow_isNotReorderable() {
		addFolders("A", "B")
		prefs.edit().putBoolean("location_select", true).commit()
		val rows = items(launch()).filterIsInstance<CardItem.Row>()
		assertFalse(rows[0].draggable)
		assertTrue(rows.drop(1).all { it.draggable })
	}

	@Test
	fun delete_asksForConfirmation_thenRemovesDestination() {
		addFolders("A", "B")
		val activity = launch()
		val list = activity.findViewById<RecyclerView>(R.id.list)
		list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST))
		list.layout(0, 0, 1080, list.measuredHeight)

		// Position 0 is the section header
		list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.action).performClick()
		TestSupport.idle()
		assertEquals("nothing removed before confirming", listOf("A", "B"), stored())

		val dialog = ShadowDialog.getLatestDialog()
		assertNotNull(dialog)
		val confirm = findViewWithText(dialog.window!!.decorView, context.getString(R.string.folder_list_delete_confirm))
		assertNotNull("confirm button", confirm)
		confirm!!.performClick()
		TestSupport.idle()

		assertEquals(listOf("B"), stored())
		assertEquals(listOf("B"), titles(activity))
	}

	@Test
	fun launch_showsNoSupportPopup() {
		launch()
		assertEquals(null, ShadowDialog.getLatestDialog())
	}

	@Test
	fun githubMenuItem_opensProfile() {
		val activity = launch()
		val toolbar = activity.findViewById<com.hchen.himiuix.MiuixAppBar>(R.id.appbar).toolbar
		assertEquals(null, toolbar.menu.findItem(context.resources.getIdentifier("action_support", "id", context.packageName)))
		val item = toolbar.menu.findItem(R.id.action_github)
		assertEquals("fjmsh", item.title.toString())
		assertNotNull(item.icon)

		activity.onOptionsItemSelected(item)
		val started = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
		assertEquals(Intent.ACTION_VIEW, started.action)
		assertEquals("https://github.com/fjmsh", started.dataString)
	}

	@Test
	fun destinations_haveDividersBetweenRows() {
		addFolders("A", "B", "C")
		val list = laidOutList(launch())
		val dividers = (0 until list.adapter!!.itemCount).mapNotNull { position ->
			list.findViewHolderForAdapterPosition(position)?.itemView?.findViewById<View>(R.id.row_divider)?.visibility
		}
		// The section header has no divider view; no line above the first row of the card
		assertEquals(listOf(View.GONE, View.VISIBLE, View.VISIBLE), dividers)
	}

	@Test
	fun overflowMenu_titlesAndRoundedSquareIcons() {
		val activity = launch()
		val menu = activity.findViewById<com.hchen.himiuix.MiuixAppBar>(R.id.appbar).toolbar.menu
		assertEquals(listOf("Settings", "fjmsh", "Changelog", "About"),
			(0 until menu.size()).map { menu.getItem(it).title.toString() })
		val tile = activity.resources.getDimensionPixelSize(R.dimen.fs_menu_icon_tile)
		(0 until menu.size()).map { menu.getItem(it) }.forEach {
			val icon = it.icon
			assertNotNull("${it.title} icon", icon)
			assertTrue("${it.title} uses an icon tile", icon is android.graphics.drawable.LayerDrawable)
			assertEquals(tile, icon!!.intrinsicWidth)
			assertEquals(tile, icon.intrinsicHeight)
		}
		// The popup minimum width is 20% below AppCompat's 196dp
		assertEquals((157 * activity.resources.displayMetrics.density).toInt(),
			activity.resources.getDimensionPixelSize(R.dimen.fs_popup_min_width))
	}

	@Test
	fun addButton_opensSystemFolderPicker() {
		addFolders("A")
		val activity = launch()
		activity.findViewById<View>(R.id.fab).performClick()
		val next = shadowOf(activity).nextStartedActivityForResult
		assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, next.intent.action)
		assertEquals(null, next.intent.component)
		val flags = next.intent.flags
		assertTrue(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
		assertTrue(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
	}

	@Test
	@Config(qualifiers = "night")
	fun darkTheme_inflates() {
		addFolders("A")
		val activity = launch()
		assertTrue(activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
				android.content.res.Configuration.UI_MODE_NIGHT_YES)
		assertEquals(2, items(activity).size)
		// Dark backgrounds are #131313, not pure black
		assertEquals(0xFF131313.toInt(), androidx.core.content.ContextCompat.getColor(activity, R.color.fs_window_background))
	}

	private fun findViewWithText(view: View, text: String): View? {
		if (view is TextView && view.text.toString() == text) return view
		if (view is ViewGroup) {
			for (i in 0 until view.childCount) {
				findViewWithText(view.getChildAt(i), text)?.let { return it }
			}
		}
		return null
	}
}
