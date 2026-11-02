package fjm.sh.dropfiler.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.hchen.himiuix.springback.SpringBackLayout
import fjm.sh.dropfiler.R
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.DestinationRepository.TreeResult
import fjm.sh.dropfiler.destination.DestinationState
import fjm.sh.dropfiler.destination.DestinationStore
import fjm.sh.dropfiler.destination.SaveDestination
import fjm.sh.dropfiler.ui.CardItem
import fjm.sh.dropfiler.ui.AppBarTitleIcon
import fjm.sh.dropfiler.ui.CardListAdapter
import fjm.sh.dropfiler.ui.MiuixListFragment
import fjm.sh.dropfiler.util.Background
import fjm.sh.dropfiler.util.ChangeLogHelper
import fjm.sh.dropfiler.util.DebugLogger
import fjm.sh.dropfiler.util.DialogHelper
import fjm.sh.dropfiler.util.DirectShareHelper
import fjm.sh.dropfiler.util.PreferenceHelper
import fjm.sh.dropfiler.util.SaveNotifier

/**
 * The entry point activity. Shows the save destinations: the built-in Download destination
 * (MediaStore, no setup) and the folders the user added with the system folder picker.
 */
class FolderListActivity : BaseActivity(), MiuixListFragment.Host {

	companion object {
		private const val KEY_LOCATION_SELECT = "location_select"
		private const val STATE_RECONNECT_ID = "reconnect_id"
		/** Set once the notification permission was requested (Android 13+). */
		const val PREF_ASKED_NOTIFICATIONS = "asked_notification_permission"
	}

	private lateinit var log: DebugLogger

	/** The user's destinations in display order. */
	private val destinations: MutableList<SaveDestination> = ArrayList()

	/** Result of the background availability check, by destination id. */
	private val states: MutableMap<String, DestinationState> = HashMap()

	private val preferenceHelper = PreferenceHelper(this)

	/** The destination being reconnected, while the folder picker is open. */
	private var reconnectId: String? = null

	private lateinit var folderPicker: ActivityResultLauncher<Intent>
	private lateinit var permissionRequest: ActivityResultLauncher<Array<String>>

	private val adapter: CardListAdapter = CardListAdapter(
		onRowClick = { row, _ -> onRowClick(row.key) },
		onRowAction = { row, _ -> confirmDelete(row.key) },
		onMessageAction = { dismissUpgradeNotice() },
		onStartDrag = { holder -> itemTouchHelper.startDrag(holder) },
		onMoveBy = { row, delta -> moveDestination(row.key, delta) },
		showDividers = true,
	)

	/**
	 * Drag and drop reordering of the destinations. The order is saved when the row is dropped
	 * and is the order used by the share sheet (and the Direct Share ranking).
	 */
	private val itemTouchHelper: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
		private var springBack: SpringBackLayout? = null

		private fun isDraggable(holder: RecyclerView.ViewHolder) =
			(adapter.items.getOrNull(holder.bindingAdapterPosition) as? CardItem.Row)?.draggable == true

		override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
			if (isDraggable(viewHolder)) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0

		// Drags are started from the handle or by long-pressing a row (CardListAdapter)
		override fun isLongPressDragEnabled() = false

		override fun isItemViewSwipeEnabled() = false

		override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder,
								 target: RecyclerView.ViewHolder) = isDraggable(target)

		override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
							target: RecyclerView.ViewHolder): Boolean {
			adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
			return true
		}

		override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

		override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
			super.onSelectedChanged(viewHolder, actionState)
			if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
				viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
				// Keep the Miuix over-scroll from taking over the drag gesture
				springBack = findSpringBack(viewHolder.itemView)
				springBack?.setSpringBackEnable(false)
			}
		}

		override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
			super.clearView(recyclerView, viewHolder)
			springBack?.setSpringBackEnable(true)
			springBack = null
			saveDisplayedOrder()
		}
	})

	private fun findSpringBack(view: View): SpringBackLayout? {
		var parent = view.parent
		while (parent != null && parent !is SpringBackLayout) parent = parent.parent
		return parent as? SpringBackLayout
	}

	/**
	 * Saves the order currently shown in the list (after a drag).
	 */
	private fun saveDisplayedOrder() {
		val ordered = adapter.items.filterIsInstance<CardItem.Row>().filter { it.draggable }.map { it.key }
		if (ordered != destinations.map { it.id }) {
			val byId = destinations.associateBy { it.id }
			val reordered = ordered.mapNotNull { byId[it] }
			destinations.clear()
			destinations.addAll(reordered)
			DestinationRepository.reorder(this, ordered)
		}
		// Rebind so the rounded group corners follow the new order
		listView?.post { adapter.refreshAfterDrag() }
	}

	/**
	 * Moves a destination one place up (-1) or down (+1), for accessibility services.
	 */
	private fun moveDestination(id: String, delta: Int) {
		val from = destinations.indexOfFirst { it.id == id }
		val to = from + delta
		if (from < 0 || to !in destinations.indices) return
		val moved = destinations.removeAt(from)
		destinations.add(to, moved)
		DestinationRepository.reorder(this, destinations.map { it.id })
		showFolderList()
		listView?.announceForAccessibility(getString(R.string.folder_list_moved_announcement,
			displayName(moved), to + 1, destinations.size))
	}

	private var listView: RecyclerView? = null

	private lateinit var fab: View

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.folder_list_activity)
		reconnectId = savedInstanceState?.getString(STATE_RECONNECT_ID)

		folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			val id = reconnectId
			reconnectId = null
			onFolderPicked(id, if (result.resultCode == RESULT_OK) result.data else null)
		}
		permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
			// Saving works either way; refresh the default destination's state
			refreshStates()
		}

		val appBar = setUpAppBar(getString(R.string.app_name), showUp = false)
		// [three-folder symbol] DropFiler, tinted with the current accent (default or Monet)
		AppBarTitleIcon.apply(appBar, R.drawable.ic_dropfiler_symbol)
		appBar.toolbar.inflateMenu(R.menu.folder_list_menu)
		appBar.toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }
		showOverflowMenuIcons(appBar.toolbar.menu)

		fab = findViewById(R.id.fab)
		fab.setOnClickListener { openFolderPicker(null) }
		applyBottomInset(fab, asMargin = true)

		log = DebugLogger(this)

		SaveNotifier.createChannel(this)

		preferenceHelper.loadPreferences()

		// Show the change log when there is something new
		ChangeLogHelper.show(this, false)

		if (savedInstanceState == null) requestPermissions()

		// Keep Direct Share targets in sync (also retires DropFiler 6.x shortcut ids)
		DirectShareHelper.publish(this)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString(STATE_RECONNECT_ID, reconnectId)
	}

	override fun onListViewCreated(recyclerView: RecyclerView) {
		listView = recyclerView
		recyclerView.adapter = adapter
		itemTouchHelper.attachToRecyclerView(recyclerView)
	}

	override fun onResume() {
		super.onResume()

		preferenceHelper.loadPreferences()

		// Init list items
		loadFolderList()
	}

	/**
	 * Runtime permissions, asked when the user opens the app rather than while sharing:
	 * - Android 6 - 9: legacy storage, needed only to save to the built-in Download folder
	 *   (Android 10+ uses MediaStore and needs nothing).
	 * - Android 13+: notifications, once. Saving works without it (toasts only).
	 */
	private fun requestPermissions() {
		val wanted = mutableListOf<String>()
		if (DestinationRepository.defaultNeedsLegacyPermission(this)) {
			wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
		}
		val prefs = PreferenceManager.getDefaultSharedPreferences(this)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false) &&
			ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			wanted.add(Manifest.permission.POST_NOTIFICATIONS)
			prefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply()
		}
		if (wanted.isNotEmpty()) {
			log.i("Requesting permission for " + wanted.joinToString())
			permissionRequest.launch(wanted.toTypedArray())
		}
	}

	/**
	 * Opens the system folder picker (ACTION_OPEN_DOCUMENT_TREE).
	 * @param reconnect The destination to re-authorize, or null to add a new one.
	 */
	private fun openFolderPicker(reconnect: SaveDestination?) {
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(DestinationRepository.TREE_REQUEST_FLAGS)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			reconnect?.treeUri?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
		}
		reconnectId = reconnect?.id
		try {
			folderPicker.launch(intent)
		} catch (e: ActivityNotFoundException) {
			reconnectId = null
			Toast.makeText(this, R.string.toast_no_folder_picker, Toast.LENGTH_LONG).show()
		}
	}

	private fun onFolderPicked(reconnect: String?, data: Intent?) {
		val uri = data?.data
		val flags = data?.flags ?: 0
		Background.run({
			if (reconnect != null) DestinationRepository.reconnect(this, reconnect, uri, flags)
			else DestinationRepository.addTree(this, uri, flags)
		}) { result ->
			if (isDestroyed) return@run
			val message = when (result) {
				is TreeResult.Added -> when {
					result.previousLabel != null ->
						getString(R.string.toast_destination_changed, result.destination.label, result.previousLabel)
					reconnect != null -> getString(R.string.toast_destination_reconnected, result.destination.label)
					else -> null
				}
				TreeResult.Cancelled -> null
				is TreeResult.Duplicate -> getString(R.string.toast_destination_duplicate, result.existing.label)
				TreeResult.Invalid, TreeResult.Unavailable -> getString(R.string.toast_destination_unavailable)
				TreeResult.NotGranted -> getString(R.string.toast_destination_not_granted)
				TreeResult.ReadOnly -> getString(R.string.toast_destination_read_only)
			}
			message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
			loadFolderList()
		}
	}

	private fun onRowClick(id: String) {
		val destination = destinations.firstOrNull { it.id == id } ?: return
		if (destination.duplicatesDefault) return
		if (stateOf(destination) != DestinationState.READY) openFolderPicker(destination)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean =
		when (item.itemId) {
			R.id.action_credits -> {
				startActivity(Intent(this, CreditsActivity::class.java))
				true
			}
			R.id.action_settings -> {
				startActivity(Intent(this, SettingsActivity::class.java))
				true
			}
			R.id.action_github -> {
				openGitHub()
				true
			}
			R.id.action_change_log -> {
				ChangeLogHelper.show(this, true)
				true
			}
			else ->
				super.onOptionsItemSelected(item)
		}

	/**
	 * Overflow menus hide item icons by default; show the rounded-square item icons.
	 */
	@SuppressLint("RestrictedApi")
	private fun showOverflowMenuIcons(menu: Menu) {
		(menu as? MenuBuilder)?.setOptionalIconsVisible(true)
		menu.findItem(R.id.action_github)?.let {
			MenuItemCompat.setContentDescription(it, getString(R.string.action_github_description))
		}
	}

	/**
	 * Opens the fjmsh GitHub profile in the browser.
	 */
	private fun openGitHub() {
		val url = getString(R.string.github_url)
		try {
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
		} catch (e: ActivityNotFoundException) {
			Toast.makeText(this, getString(R.string.toast_no_browser, url), Toast.LENGTH_LONG).show()
		}
	}

	/**
	 * Loads the destinations, shows them straight away with the cheap state, then checks
	 * the folders in the background (deleted, unmounted, read-only).
	 */
	private fun loadFolderList() {
		destinations.clear()
		destinations.addAll(DestinationRepository.custom(this))
		states.keys.retainAll(destinations.map { it.id }.toSet())
		showFolderList()
		refreshStates()
	}

	private fun refreshStates() {
		val snapshot = destinations.toList()
		Background.run({ snapshot.associate { it.id to DestinationRepository.checkState(this, it) } }) { result ->
			if (isDestroyed) return@run
			if (result != states.filterKeys { it in result }) {
				states.putAll(result)
				showFolderList()
			}
		}
	}

	private fun stateOf(destination: SaveDestination): DestinationState =
		states[destination.id] ?: DestinationRepository.quickState(this, destination)

	private fun upgradeNoticePending() = PreferenceManager.getDefaultSharedPreferences(this)
		.getBoolean(DestinationStore.PREF_UPGRADE_NOTICE, false)

	private fun dismissUpgradeNotice() {
		PreferenceManager.getDefaultSharedPreferences(this).edit()
			.putBoolean(DestinationStore.PREF_UPGRADE_NOTICE, false).apply()
		showFolderList()
	}

	/**
	 * Shows the destinations: Location Select (if enabled), the built-in Download destination
	 * (unless hidden) and the user's destinations. There is always at least one destination.
	 */
	private fun showFolderList() {
		val items = mutableListOf<CardItem>()
		if (upgradeNoticePending()) {
			items.add(CardItem.Message(
				icon = R.drawable.fs_ic_folder,
				title = getString(R.string.upgrade_notice_title),
				body = getString(R.string.upgrade_notice_body, getString(R.string.default_destination_path)),
				actionText = getString(R.string.dialog_positive),
			))
		}
		items.add(CardItem.Header(getString(R.string.folder_list_section_destinations)))
		if (PreferenceHelper.locationSelectEnabled) {
			items.add(CardItem.Row(
				key = KEY_LOCATION_SELECT,
				title = getString(R.string.location_select_title),
				summary = getString(R.string.location_select_summary),
				icon = R.drawable.fs_ic_document,
				clickable = false,
			))
		}
		val offered = DestinationRepository.offered(this, destinations) { stateOf(it) == DestinationState.READY }
		offered.firstOrNull { it.isDefault }?.let { default ->
			// Built in and cannot be removed or moved, so no delete button or drag handle
			items.add(CardItem.Row(
				key = default.id,
				title = default.label,
				summary = getString(R.string.folder_list_default_summary, getString(R.string.default_destination_path)),
				clickable = false,
			))
		}
		destinations.forEach { destination ->
			val name = displayName(destination)
			val state = stateOf(destination)
			val ready = state == DestinationState.READY
			val canReconnect = !ready && !destination.duplicatesDefault
			items.add(CardItem.Row(
				key = destination.id,
				title = name,
				summary = summaryFor(destination, state),
				clickable = canReconnect,
				showChevron = canReconnect,
				clickDescription = if (canReconnect) getString(R.string.destination_reconnect_description, name) else null,
				danger = !ready,
				icon = if (ready) R.drawable.fs_ic_folder else R.drawable.fs_ic_error,
				actionIcon = R.drawable.fs_ic_delete,
				actionDescription = getString(R.string.folder_list_delete_description, name),
				draggable = true,
			))
		}
		adapter.items = items
	}

	private fun summaryFor(destination: SaveDestination, state: DestinationState): String = when {
		destination.duplicatesDefault -> getString(R.string.destination_state_duplicates_default)
		state == DestinationState.READY -> destination.description ?: getString(R.string.destination_saf_summary)
		state == DestinationState.RECONNECT_REQUIRED -> getString(R.string.destination_state_reconnect,
			destination.legacyPath?.ifBlank { "/" } ?: destination.label)
		state == DestinationState.READ_ONLY -> getString(R.string.destination_state_read_only)
		state == DestinationState.UNAVAILABLE -> getString(R.string.destination_state_unavailable)
		else -> getString(R.string.destination_state_needs_permission)
	}

	private fun displayName(destination: SaveDestination): String =
		destination.label.ifBlank { getString(R.string.folder_list_root_folder) }

	/**
	 * Asks for confirmation, then removes a destination (and releases its folder access).
	 */
	private fun confirmDelete(id: String) {
		val destination = destinations.firstOrNull { it.id == id } ?: return
		DialogHelper.showConfirmDialog(
			this,
			title = getString(R.string.folder_list_delete_title),
			message = getString(R.string.folder_list_delete_message, displayName(destination)),
			confirmText = getString(R.string.folder_list_delete_confirm),
		) {
			if (DestinationRepository.remove(this, id)) {
				loadFolderList()
			}
		}
	}
}
