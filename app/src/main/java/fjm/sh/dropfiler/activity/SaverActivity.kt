package fjm.sh.dropfiler.activity

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fjm.sh.dropfiler.R
import fjm.sh.dropfiler.destination.DestinationPaths
import fjm.sh.dropfiler.destination.DestinationRepository
import fjm.sh.dropfiler.destination.DestinationState
import fjm.sh.dropfiler.destination.SaveDestination
import fjm.sh.dropfiler.save.IncomingContentParser
import fjm.sh.dropfiler.save.IncomingItem
import fjm.sh.dropfiler.save.SaveCoordinator
import fjm.sh.dropfiler.save.UrlDownloader
import fjm.sh.dropfiler.ui.CardItem
import fjm.sh.dropfiler.ui.CardListAdapter
import fjm.sh.dropfiler.util.Background
import fjm.sh.dropfiler.util.DebugLogger
import fjm.sh.dropfiler.util.DirectShareHelper
import fjm.sh.dropfiler.util.PreferenceHelper
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The Share / "Open with" target: a bottom sheet listing the destinations. All saving goes
 * through [SaveCoordinator]; this activity only picks the target and shows progress.
 * https://developer.android.com/training/sharing/receive.html
 */
class SaverActivity : BaseActivity() {

	companion object {
		/** Direct Share (Android 6 - 9 chooser targets): the destination id. */
		const val DIRECT_SHARE_DESTINATION = "DIRECT_SHARE_DESTINATION"
		/** DropFiler 6.x chooser target extra (a folder path). Still resolved for old targets. */
		const val DIRECT_SHARE_FOLDER = "DIRECT_SHARE_FOLDER"

		private const val KEY_DESTINATION_PREFIX = "dest:"
		private const val KEY_SAVE_AS = "save_as"
		private const val KEY_PICKER = "picker"
		private const val KEY_MISSING = "missing"
	}

	private lateinit var log: DebugLogger
	private val preferenceHelper = PreferenceHelper(this)
	private lateinit var session: SaveSession

	private lateinit var documentPicker: ActivityResultLauncher<Intent>
	private lateinit var storagePermission: ActivityResultLauncher<String>

	/** Whether this activity was started with "Open with" (ACTION_VIEW) rather than Share. */
	private val isOpenWith: Boolean
		get() = Intent.ACTION_VIEW == intent?.action

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.saver_activity)
		log = DebugLogger(this)
		preferenceHelper.loadPreferences()
		session = ViewModelProvider(this)[SaveSession::class.java]
		session.onFinished = { exitApplication() }

		documentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			val item = session.documentQueue.removeFirstOrNull() ?: return@registerForActivityResult
			val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
			showProgress(R.string.saver_saving_title, R.string.saver_saving_body)
			session.saveDocument(item, uri) { nextDocument() }
		}
		storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			val destination = DestinationRepository.find(this, session.pendingDestinationId)
			session.pendingDestinationId = null
			if (granted && destination != null) save(destination)
			else session.fail(getString(R.string.toast_save_no_permission))
		}
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() = cancel()
		})

		setUpSheet()

		if (session.finishPending) {
			exitApplication()
			return
		}
		when (session.phase) {
			SaveSession.Phase.NEW, SaveSession.Phase.CHECKING -> start()
			SaveSession.Phase.CHOOSING -> chooseDestination()
			SaveSession.Phase.SAVING, SaveSession.Phase.PICKING_DOCUMENT ->
				showProgress(R.string.saver_saving_title, R.string.saver_saving_body)
			SaveSession.Phase.DONE -> exitApplication()
		}
	}

	override fun onDestroy() {
		// A save in progress keeps running in the session and still reports its result
		session.onFinished = null
		super.onDestroy()
	}

	/**
	 * Cancel / back / tap outside: closes without saving, or stops a save in progress (the
	 * partly written file is removed and the sheet closes once that is done).
	 */
	private fun cancel() {
		when (session.phase) {
			SaveSession.Phase.SAVING -> session.cancelled.set(true)
			SaveSession.Phase.DONE -> exitApplication()
			else -> {
				session.phase = SaveSession.Phase.DONE
				finish()
			}
		}
	}

	// Start ----------------------------------------------------------------------------------

	private fun start() {
		if (session.phase == SaveSession.Phase.CHECKING) return
		session.phase = SaveSession.Phase.CHECKING
		session.coordinator = SaveCoordinator(this, PreferenceHelper.saveSettings())
		val items = IncomingContentParser.parse(intent)
		log.i("Action: ${intent?.action}, type: ${intent?.type}, items: ${items.size}")

		// Reading content, asking a server for a link's type and checking folders may be slow
		Background.run({
			if (isOpenWith && !canReadOpenWithSource(items)) {
				null
			} else try {
				session.coordinator.prepare(items)
			} catch (e: RuntimeException) {
				// A misbehaving provider or malformed link must not crash the share
				log.w("Unable to prepare the shared content: ${e.javaClass.simpleName}")
				SaveCoordinator.Prepared.Unsupported
			}
		}) { prepared ->
			if (isFinishing || isDestroyed || session.phase != SaveSession.Phase.CHECKING) return@run
			when (prepared) {
				null -> session.fail(getString(R.string.toast_open_with_unreadable))
				is SaveCoordinator.Prepared.Unsupported ->
					if (PreferenceHelper.forceSaving || items.isEmpty() && intent?.type == null) {
						session.fail(getString(R.string.toast_save_failed))
					} else showNotSupported()
				is SaveCoordinator.Prepared.Ready -> {
					session.prepared = prepared.items
					session.phase = SaveSession.Phase.CHOOSING
					chooseDestination()
				}
			}
		}
	}

	/**
	 * Whether the file given to "Open with" can be read. Fails for a missing URI, an
	 * unsupported scheme, a missing or revoked read grant, directories and providers that
	 * refuse access. The stream is only opened and closed again; nothing is read or changed.
	 */
	private fun canReadOpenWithSource(items: List<IncomingItem>): Boolean {
		val uri = (items.singleOrNull() as? IncomingItem.Stream)?.uri ?: return false.also {
			log.w("Open with: no supported URI")
		}
		return try {
			contentResolver.openInputStream(uri)?.use { true } ?: false
		} catch (e: FileNotFoundException) {
			log.w("Open with: cannot open the file: ${e.message}")
			false
		} catch (e: IOException) {
			log.w("Open with: cannot open the file: ${e.message}")
			false
		} catch (e: SecurityException) {
			log.w("Open with: no permission to read the file")
			false
		} catch (e: RuntimeException) {
			// e.g. IllegalArgumentException / IllegalStateException from a misbehaving provider
			log.w("Open with: provider failed: ${e.javaClass.simpleName}")
			false
		}
	}

	/** The destination requested by Direct Share or a sharing shortcut, if any. */
	private sealed class Requested {
		object None : Requested()
		data class Found(val destination: SaveDestination) : Requested()
		/** A stale target (destination removed since). */
		object Gone : Requested()
	}

	private fun requestedDestination(): Requested {
		val extras = try { intent?.extras } catch (e: RuntimeException) { null } ?: return Requested.None
		extras.getString(DIRECT_SHARE_DESTINATION)?.let { id ->
			return DestinationRepository.find(this, id)?.let { Requested.Found(it) } ?: Requested.Gone
		}
		extras.getString(DIRECT_SHARE_FOLDER)?.let { path ->
			return DestinationRepository.findByLegacyPath(this, path)?.let { Requested.Found(it) } ?: Requested.Gone
		}
		val shortcut = extras.getString(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
		if (DirectShareHelper.isOurShortcut(shortcut)) {
			return DirectShareHelper.destinationForShortcut(this, shortcut)?.let { Requested.Found(it) } ?: Requested.Gone
		}
		return Requested.None
	}

	// Choosing ----------------------------------------------------------------------------------

	/**
	 * Uses the Direct Share destination or the only destination, or shows the list.
	 */
	private fun chooseDestination() {
		val requested = requestedDestination()
		Background.run({
			val customs = DestinationRepository.custom(this)
			val all = listOf(DestinationRepository.defaultDestination(this)) + customs
			val states = (all + listOfNotNull((requested as? Requested.Found)?.destination)).distinctBy { it.id }
				.associate { it.id to DestinationRepository.checkState(this, it) }
			Triple(customs, states, requested)
		}) { (customs, states, requested) ->
			if (isFinishing || isDestroyed || session.phase != SaveSession.Phase.CHOOSING) return@run
			fun ready(d: SaveDestination) = states[d.id] == DestinationState.READY ||
				// Android 6 - 9: the default asks for the storage permission when chosen
				(d.isDefault && states[d.id] == DestinationState.NEEDS_PERMISSION)
			val offered = DestinationRepository.offered(this, customs) { states[it.id] == DestinationState.READY }

			when (requested) {
				is Requested.Found ->
					if (ready(requested.destination)) return@run save(requested.destination)
					else return@run showDestinations(offered, states, missing = requested.destination.label)
				Requested.Gone -> return@run showDestinations(offered, states, missing = getString(R.string.saver_missing_removed))
				Requested.None -> Unit
			}
			if (PreferenceHelper.locationSelectEnabled || offered.size > 1) {
				// The single destination the user expected is not available: explain it
				val hide = PreferenceManager.getDefaultSharedPreferences(this)
					.getBoolean(DestinationRepository.PREF_HIDE_DEFAULT_FOLDER, false)
				val expected = if (hide && customs.size == 1) customs.single() else null
				showDestinations(offered, states, missing = expected?.takeUnless { ready(it) }?.label)
				return@run
			}
			// Only one destination: just use it
			val only = offered.single()
			if (ready(only)) save(only) else showDestinations(offered, states, missing = only.label)
		}
	}

	private fun destinationRow(destination: SaveDestination, state: DestinationState?): CardItem.Row {
		val usable = state == DestinationState.READY || (destination.isDefault && state == DestinationState.NEEDS_PERMISSION)
		val name = destination.label.ifBlank { getString(R.string.folder_list_root_folder) }
		val summary = DestinationPaths.pathOf(this, destination) ?: getString(R.string.destination_saf_summary)
		return CardItem.Row(
			key = KEY_DESTINATION_PREFIX + destination.id,
			title = name,
			// Destinations that need reconnecting or are gone cannot be chosen
			summary = if (usable) summary else getString(R.string.saver_folder_unusable, name),
			clickable = usable,
			enabled = usable,
		)
	}

	/**
	 * Shows the destination list so the user can pick one.
	 * @param missing A destination that should have been used but is not available, explained at the top.
	 */
	private fun showDestinations(offered: List<SaveDestination>, states: Map<String, DestinationState>, missing: String? = null) {
		findViewById<View>(R.id.loading).visibility = View.GONE
		val listView = findViewById<RecyclerView>(R.id.list)
		listView.visibility = View.VISIBLE
		listView.layoutManager = LinearLayoutManager(this)

		val items = mutableListOf<CardItem>()
		missing?.let {
			items.add(CardItem.Row(
				key = KEY_MISSING,
				title = getString(R.string.saver_missing_title),
				summary = getString(R.string.saver_missing_body, it),
				icon = R.drawable.fs_ic_error,
				clickable = false,
				danger = true,
			))
			items.add(CardItem.Header(getString(R.string.saver_missing_choose)))
		}
		if (PreferenceHelper.locationSelectEnabled) {
			items.add(saveAsRow(KEY_SAVE_AS, R.string.location_select_title))
		}
		offered.forEach { items.add(destinationRow(it, states[it.id])) }
		if (missing != null && !PreferenceHelper.locationSelectEnabled) {
			// Always offer the system picker, even if no other destination is available
			items.add(saveAsRow(KEY_PICKER, R.string.saver_choose_other_folder))
		}

		val byKey = offered.associateBy { KEY_DESTINATION_PREFIX + it.id }
		var selected = false
		listView.adapter = CardListAdapter(onRowClick = { row, _ ->
			// Ignore repeated taps while the first selection is being saved
			if (selected || session.phase != SaveSession.Phase.CHOOSING) return@CardListAdapter
			when (row.key) {
				KEY_SAVE_AS, KEY_PICKER -> { selected = true; saveAs() }
				else -> byKey[row.key]?.let { selected = true; save(it) }
			}
		}, showDividers = true).apply {
			this.items = items
		}
		listView.scrollToPosition(0)
	}

	private fun saveAsRow(key: String, title: Int) = CardItem.Row(
		key = key,
		title = getString(title),
		summary = getString(R.string.location_select_summary),
		icon = R.drawable.fs_ic_document,
		showChevron = true,
	)

	// Saving -------------------------------------------------------------------------------

	private fun save(destination: SaveDestination) {
		if (destination.isDefault && DestinationRepository.defaultNeedsLegacyPermission(this)) {
			// Android 6 - 9 only: the Download folder is written with the legacy permission
			session.pendingDestinationId = destination.id
			storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
			return
		}
		showProgress(R.string.saver_saving_title, R.string.saver_saving_body)
		session.save(session.coordinator.writerFor(destination), destination.displayLocation(this), isOpenWith)
	}

	/** Save as: one system "create document" picker per item, one after another. */
	private fun saveAs() {
		session.documentQueue.clear()
		session.documentQueue.addAll(session.prepared)
		session.documentResults.clear()
		nextDocument()
	}

	private fun nextDocument() {
		val item = session.documentQueue.firstOrNull()
		if (item == null) {
			session.report(fjm.sh.dropfiler.save.SaveReport(session.documentResults.toList()), null, isOpenWith)
			return
		}
		session.phase = SaveSession.Phase.PICKING_DOCUMENT
		val picker = Intent(Intent.ACTION_CREATE_DOCUMENT)
			.addCategory(Intent.CATEGORY_OPENABLE)
			.setType(item.mime)
			.putExtra(Intent.EXTRA_TITLE, item.name)
		try {
			documentPicker.launch(picker)
		} catch (e: ActivityNotFoundException) {
			session.fail(getString(R.string.toast_no_folder_picker))
		}
	}

	private fun showProgress(title: Int, body: Int) {
		findViewById<View>(R.id.list).visibility = View.GONE
		findViewById<View>(R.id.not_supported_wrapper).visibility = View.GONE
		findViewById<View>(R.id.loading).visibility = View.VISIBLE
		findViewById<TextView>(R.id.loading_title).setText(title)
		findViewById<TextView>(R.id.loading_body).setText(body)
	}

	// Sheet --------------------------------------------------------------------------------

	/**
	 * Prepare the bottom sheet presentation. Tapping outside the sheet or Cancel closes it
	 * without saving, exactly like pressing back.
	 */
	private fun setUpSheet() {
		val sheet = findViewById<View>(R.id.sheet)
		applyBottomInset(sheet, asMargin = false)

		// Limit the width on tablets and in landscape
		val maxWidth = resources.getDimensionPixelSize(R.dimen.fs_sheet_max_width)
		if (resources.displayMetrics.widthPixels > maxWidth) {
			sheet.layoutParams = sheet.layoutParams.apply { width = maxWidth }
		}

		findViewById<View>(R.id.scrim).setOnClickListener { cancel() }
		findViewById<View>(R.id.cancel).setOnClickListener { cancel() }
		findViewById<TextView>(R.id.sheet_subtitle).text = describeSharedContent()

		// Slide in. Uses the animator duration scale, so it is instant when animations are off.
		sheet.post {
			sheet.translationY = sheet.height.toFloat()
			sheet.animate().translationY(0f)
				.setDuration(resources.getInteger(android.R.integer.config_mediumAnimTime).toLong())
				.setInterpolator(android.view.animation.DecelerateInterpolator(2f))
				.start()
		}
	}

	/**
	 * A short description of what is being shared, e.g. "3 items" or "Link".
	 */
	private fun describeSharedContent(): CharSequence? = try {
		when {
			isOpenWith -> intent.data?.takeIf { it.scheme != ContentResolver.SCHEME_CONTENT }?.lastPathSegment
				?: resources.getQuantityString(R.plurals.saver_subtitle_files, 1, 1)
			Intent.ACTION_SEND_MULTIPLE == intent.action ->
				IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.size?.let {
					resources.getQuantityString(R.plurals.saver_subtitle_files, it, it)
				}
			IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) != null ->
				IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.lastPathSegment
					?.substringAfterLast('/') ?: resources.getQuantityString(R.plurals.saver_subtitle_files, 1, 1)
			intent.getCharSequenceExtra(Intent.EXTRA_TEXT) != null -> {
				val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT).toString()
				if (UrlDownloader.isHttpUrl(text)) getString(R.string.saver_subtitle_link) else getString(R.string.saver_subtitle_text)
			}
			else -> intent.type
		}
	} catch (e: RuntimeException) {
		// Malformed extras from the sharing app; the description is only cosmetic
		null
	}.also { subtitle ->
		// The display name of an "Open with" file needs a provider query; fill it in later
		if (isOpenWith) intent?.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }?.let { uri ->
			Background.run({ IncomingContentParser.displayName(this, uri) }) { name ->
				if (name != null && !isDestroyed) findViewById<TextView>(R.id.sheet_subtitle).text = name
			}
		}
		subtitle
	}

	/**
	 * Show the not supported information.
	 */
	private fun showNotSupported() {
		session.phase = SaveSession.Phase.DONE
		findViewById<View>(R.id.loading).visibility = View.GONE
		findViewById<View>(R.id.list).visibility = View.GONE
		// Generate issue text here as should always be English and does not need to be in strings.xml
		val bobTitle = StringBuilder()
		bobTitle.append("Support Request - ")
		bobTitle.append(intent.type)
		val bobBody = StringBuilder()
		bobBody.append("Support request. Generated by DropFiler.%0D%0A")
		bobBody.append("%0D%0AIntent type: ")
		bobBody.append(intent.type)
		bobBody.append("%0D%0AIntent action: ")
		bobBody.append(intent.action)
		intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
			bobBody.append("%0D%0AText: ")
			bobBody.append(it)
		}
		intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let {
			bobBody.append("%0D%0ASubject: ")
			bobBody.append(it)
		}
		intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let {
			bobBody.append("%0D%0AHTML Text: ")
			bobBody.append(it)
		}
		// Version
		try {
			val versionName = packageManager.getPackageInfo(packageName, 0).versionName
			bobBody.append("%0D%0AApplication Version: ")
			bobBody.append(versionName)
		} catch (e: PackageManager.NameNotFoundException) {
			log.e("Unable to get package version", e)
		}
		bobBody.append("%0D%0A%0D%0AMore information: TYPE_ADDITIONAL_INFORMATION_HERE")
		bobBody.append("%0D%0A%0D%0AThank you")
		val issueLink = getString(R.string.issues_new_url) + "?title=" +
				bobTitle.toString().replace(" ", "%20") +
				"&body=" +
				bobBody.toString().replace(" ", "%20").replace("=", "%3D")

		// Build and show unsupported message
		val supportView = findViewById<TextView>(R.id.not_supported)
		@Suppress("DEPRECATION")
		supportView.text = Html.fromHtml(resources.getString(R.string.not_supported, issueLink))
		supportView.movementMethod = LinkMovementMethod.getInstance()
		findViewById<View>(R.id.not_supported_wrapper).visibility = View.VISIBLE
	}

	/**
	 * Closes the sheet and removes it from recents.
	 */
	private fun exitApplication() {
		if (!isFinishing) finishAndRemoveTask()
	}
}
